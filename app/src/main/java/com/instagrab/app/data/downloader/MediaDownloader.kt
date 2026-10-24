package com.instagrab.app.data.downloader

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.instagrab.app.data.model.MediaItem
import com.instagrab.app.data.model.Platform
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

class MediaDownloader(private val context: Context) {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val mainHandler = Handler(Looper.getMainLooper())
    private val idGenerator = AtomicLong(System.currentTimeMillis())
    private val reservedFilenames = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    companion object {
        private const val CHANNEL_ID = "snsgrab_download_channel"
        private const val CHANNEL_NAME = "下載通知"
        const val ALBUM_NAME = "SNSGrab"
    }

    init {
        createNotificationChannel()
        syncExistingDownloadsToGallery()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "SNSGrab 影片與圖片下載通知"
            }
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            manager?.createNotificationChannel(channel)
        }
    }

    /**
     * Downloads a single media item directly.
     * Patches MP4 atoms (mvhd/tkhd/mdhd) to current time so Gallery displays it under "Today",
     * and inserts the media item into Android's MediaStore.
     */
    fun download(item: MediaItem): Long {
        val downloadId = idGenerator.incrementAndGet()

        // CRITICAL: NEVER strip query parameters from signed URLs (Instagram, TikTok, Threads, etc.)
        val targetUrl = item.downloadUrl

        val baseFilename = sanitizeFilename(item.suggestedFilename)
        val finalFilename = getUniqueFilename(baseFilename, item.isVideo)

        mainHandler.post {
            Toast.makeText(context, "開始下載：$finalFilename...", Toast.LENGTH_SHORT).show()
        }

        scope.launch {
            val notificationId = (downloadId % Int.MAX_VALUE).toInt()
            showProgressNotification(notificationId, finalFilename)

            var tempFile: File? = null
            try {
                // 1. Fetch from network
                val referer = when (item.platform) {
                    Platform.TWITTER -> "https://x.com/"
                    Platform.TIKTOK -> "https://www.tiktok.com/"
                    Platform.BLUESKY -> "https://bsky.app/"
                    Platform.THREADS -> "https://www.threads.net/"
                    Platform.FACEBOOK -> "https://www.facebook.com/"
                    Platform.DOUYIN -> "https://www.douyin.com/"
                    else -> "https://www.instagram.com/"
                }

                val downloadUa = if (item.platform == Platform.DOUYIN) {
                    "com.ss.android.ugc.aweme/100401 (Linux; U; Android 10; zh_CN; Pixel 4; Build/QQ3A.200805.001; Cronet/58.0.2991.0)"
                } else {
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"
                }

                val request = Request.Builder()
                    .url(targetUrl)
                    .header("User-Agent", downloadUa)
                    .header("Referer", referer)
                    .build()

                tempFile = File(context.cacheDir, "dl_${System.currentTimeMillis()}_$finalFilename")
                httpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        throw Exception("伺服器回應錯誤 (HTTP ${response.code}: ${response.message})")
                    }
                    val body = response.body ?: throw Exception("伺服器無回應內容")
                    FileOutputStream(tempFile).use { fos ->
                        body.byteStream().use { input ->
                            input.copyTo(fos)
                        }
                    }
                }

                if (!tempFile.exists() || tempFile.length() == 0L) {
                    throw Exception("下載檔案長度為 0")
                }

                // 2. Patch MP4 internal timestamp so all Gallery apps sort it under TODAY
                val nowMs = System.currentTimeMillis()
                if (item.isVideo) {
                    Mp4Patcher.patchTimestamps(tempFile, nowMs)
                }

                // 3. Save to MediaStore (Movies/SNSGrab for videos, Pictures/SNSGrab for images)
                val mimeType = if (item.isVideo) "video/mp4" else "image/jpeg"
                val savedUri = saveToStorage(tempFile, finalFilename, mimeType, item.isVideo, nowMs)

                // 4. Update UI & Notification
                cancelNotification(notificationId)
                showCompletedNotification(notificationId, finalFilename, savedUri, mimeType)

                mainHandler.post {
                    val locationHint = if (item.isVideo) "相簿 (Movies/SNSGrab)" else "相簿 (Pictures/SNSGrab)"
                    Toast.makeText(context, "🎉 $finalFilename 下載完成！\n已保存至 $locationHint", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                cancelNotification(notificationId)
                val errMsg = e.localizedMessage ?: e.message ?: e.javaClass.simpleName
                mainHandler.post {
                    Toast.makeText(context, "下載失敗：$errMsg", Toast.LENGTH_LONG).show()
                }
            } finally {
                tempFile?.delete()
            }
        }

        return downloadId
    }

    /**
     * Downloads all media items in a batch.
     */
    fun downloadAll(items: List<MediaItem>): List<Long> {
        val ids = mutableListOf<Long>()
        items.forEach { item ->
            ids.add(download(item))
        }
        if (items.size > 1) {
            Toast.makeText(context, "已將全部 ${items.size} 個檔案加入下載佇列", Toast.LENGTH_SHORT).show()
        }
        return ids
    }

    private fun saveToStorage(
        tempFile: File,
        filename: String,
        mimeType: String,
        isVideo: Boolean,
        timestampMs: Long
    ): Uri {
        val nowSec = timestampMs / 1000

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Android 10+ (API 29+): Scoped Storage requires Movies or DCIM for videos, Pictures for images
            val relativePath = if (isVideo) {
                Environment.DIRECTORY_MOVIES + "/" + ALBUM_NAME
            } else {
                Environment.DIRECTORY_PICTURES + "/" + ALBUM_NAME
            }

            val collection = if (isVideo) {
                MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            } else {
                MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            }

            val contentValues = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
                put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
                if (isVideo) {
                    put(MediaStore.Video.Media.DATE_TAKEN, timestampMs)
                } else {
                    put(MediaStore.Images.Media.DATE_TAKEN, timestampMs)
                }
                put(MediaStore.MediaColumns.DATE_ADDED, nowSec)
                put(MediaStore.MediaColumns.DATE_MODIFIED, nowSec)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }

            val uri = context.contentResolver.insert(collection, contentValues)
                ?: throw IllegalStateException("建立媒體記錄失敗")

            try {
                context.contentResolver.openOutputStream(uri)?.use { out ->
                    tempFile.inputStream().use { input ->
                        input.copyTo(out)
                    }
                } ?: throw IllegalStateException("無法開啟媒體儲存串流")

                contentValues.clear()
                contentValues.put(MediaStore.MediaColumns.IS_PENDING, 0)
                context.contentResolver.update(uri, contentValues, null, null)
            } catch (writeErr: Exception) {
                try {
                    context.contentResolver.delete(uri, null, null)
                } catch (delErr: Exception) {
                    // ignore
                }
                throw writeErr
            }

            // Also trigger MediaScanner on the physical file if accessible
            try {
                val baseDir = if (isVideo) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_PICTURES
                val pubDir = File(Environment.getExternalStoragePublicDirectory(baseDir), ALBUM_NAME)
                val publicFile = File(pubDir, filename)
                if (publicFile.exists()) {
                    publicFile.setLastModified(timestampMs)
                    MediaScannerConnection.scanFile(context, arrayOf(publicFile.absolutePath), arrayOf(mimeType), null)
                }
            } catch (e: Exception) {
                // ignore
            }

            return uri
        } else {
            // Android 9 or lower
            val baseDir = if (isVideo) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_PICTURES
            val pubDir = File(Environment.getExternalStoragePublicDirectory(baseDir), ALBUM_NAME)
            if (!pubDir.exists()) pubDir.mkdirs()
            val destFile = File(pubDir, filename)
            tempFile.copyTo(destFile, overwrite = true)
            destFile.setLastModified(timestampMs)

            var scannedUri: Uri? = null
            MediaScannerConnection.scanFile(
                context,
                arrayOf(destFile.absolutePath),
                arrayOf(mimeType)
            ) { _, uri ->
                scannedUri = uri
            }
            return scannedUri ?: Uri.fromFile(destFile)
        }
    }

    private fun isFilenameTaken(filename: String, isVideo: Boolean): Boolean {
        if (reservedFilenames.contains(filename)) return true

        // 1. Check physical filesystem
        try {
            val baseDir = if (isVideo) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_PICTURES
            val pubDir = File(Environment.getExternalStoragePublicDirectory(baseDir), ALBUM_NAME)
            if (File(pubDir, filename).exists()) return true

            val dlDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            if (File(dlDir, filename).exists()) return true
        } catch (e: Exception) {
            // ignore
        }

        // 2. Check MediaStore (Videos & Images)
        try {
            val projection = arrayOf(MediaStore.MediaColumns._ID)
            val selection = "${MediaStore.MediaColumns.DISPLAY_NAME} = ?"
            val selectionArgs = arrayOf(filename)

            val targets = listOf(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            )

            for (collectionUri in targets) {
                context.contentResolver.query(collectionUri, projection, selection, selectionArgs, null)?.use { cursor ->
                    if (cursor.count > 0) return true
                }
            }
        } catch (e: Exception) {
            // ignore
        }

        return false
    }

    private fun getUniqueFilename(originalName: String, isVideo: Boolean): String {
        return synchronized(this) {
            val dotIdx = originalName.lastIndexOf('.')
            val name = if (dotIdx > 0) originalName.substring(0, dotIdx) else originalName
            val ext = if (dotIdx > 0) originalName.substring(dotIdx) else ""

            var candidate = originalName
            var counter = 1

            while (isFilenameTaken(candidate, isVideo) && counter < 1000) {
                candidate = "${name}_${counter}${ext}"
                counter++
            }

            reservedFilenames.add(candidate)
            candidate
        }
    }

    private fun showProgressNotification(notificationId: Int, filename: String) {
        try {
            val builder = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle("正在下載 $filename")
                .setContentText("下載後將自動校正時間戳並置頂至相簿...")
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setProgress(0, 0, true)
                .setOngoing(true)

            NotificationManagerCompat.from(context).notify(notificationId, builder.build())
        } catch (e: SecurityException) {
            // Notification permission might not be granted on Android 13+
        }
    }

    private fun showCompletedNotification(notificationId: Int, filename: String, uri: Uri?, mimeType: String) {
        try {
            val viewIntent = if (uri != null) {
                Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, mimeType)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            } else null

            val pendingIntent = viewIntent?.let {
                PendingIntent.getActivity(
                    context,
                    notificationId,
                    it,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            }

            val builder = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle("下載完成！已同步至相簿")
                .setContentText(filename)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setAutoCancel(true)
                .apply {
                    if (pendingIntent != null) {
                        setContentIntent(pendingIntent)
                    }
                }

            NotificationManagerCompat.from(context).notify(notificationId, builder.build())
        } catch (e: SecurityException) {
            // ignore
        }
    }

    private fun cancelNotification(notificationId: Int) {
        try {
            NotificationManagerCompat.from(context).cancel(notificationId)
        } catch (e: Exception) {
            // ignore
        }
    }

    /**
     * Scans existing downloaded files in Downloads directory and attempts to patch timestamps.
     */
    fun syncExistingDownloadsToGallery() {
        scope.launch {
            try {
                val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                if (!downloadsDir.exists()) return@launch

                val files = downloadsDir.listFiles { file ->
                    val name = file.name
                    (name.startsWith("X_") || name.startsWith("Twitter_") || name.startsWith("TikTok_") ||
                            name.startsWith("Bsky_") || name.startsWith("Threads_") || name.startsWith("IG_") ||
                            name.startsWith("FB_") || name.startsWith("DY_")) &&
                            (name.endsWith(".mp4", ignoreCase = true) || name.endsWith(".jpg", ignoreCase = true))
                } ?: return@launch

                for (file in files) {
                    val isVideo = file.name.endsWith(".mp4", ignoreCase = true)
                    val mimeType = if (isVideo) "video/mp4" else "image/jpeg"

                    if (isVideo && file.canWrite()) {
                        Mp4Patcher.patchTimestamps(file)
                    }

                    try {
                        file.setLastModified(System.currentTimeMillis())
                    } catch (e: Exception) {
                        // ignore
                    }

                    MediaScannerConnection.scanFile(
                        context.applicationContext,
                        arrayOf(file.absolutePath),
                        arrayOf(mimeType),
                        null
                    )
                }
            } catch (e: Exception) {
                // ignore
            }
        }
    }

    private fun sanitizeFilename(filename: String): String {
        return filename.replace("[\\\\/:*?\"<>|]".toRegex(), "_")
    }
}
