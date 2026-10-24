package com.instagrab.app.data.parser

import com.instagrab.app.data.model.ExtractionResult
import com.instagrab.app.data.model.MediaItem
import com.instagrab.app.data.model.Platform
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

class DouyinExtractor(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()
) {
    companion object {
        private const val MOBILE_AWEME_UA = "com.ss.android.ugc.aweme/100401 (Linux; U; Android 10; zh_CN; Pixel 4; Build/QQ3A.200805.001; Cronet/58.0.2991.0)"
        private const val BROWSER_UA = "Mozilla/5.0 (iPhone; CPU iPhone OS 16_6 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/16.6 Mobile/15E148 Safari/604.1"
        private val VIDEO_ID_PATTERN = Pattern.compile("(?:video|note)/([0-9]+)")
        private val DIGITS_PATTERN = Pattern.compile("([0-9]{18,21})")
    }

    suspend fun extract(rawText: String): ExtractionResult = withContext(Dispatchers.IO) {
        // 1. Extract clean URL from any shared text (skipping promotional text & hashtags)
        val cleanUrl = Platform.extractUrl(rawText)
        if (cleanUrl.isBlank() || (!cleanUrl.contains("douyin.com") && !cleanUrl.contains("iesdouyin.com"))) {
            throw IllegalArgumentException("請輸入有效的抖音 (陸抖) 影片連結")
        }

        // 2. Resolve 302 shortlink (e.g. v.douyin.com/xxxx/ -> www.iesdouyin.com/share/video/ID/...)
        val canonicalUrl = resolveCanonicalUrl(cleanUrl)

        // 3. Extract numeric Aweme ID
        var videoId: String? = null
        val idMatcher = VIDEO_ID_PATTERN.matcher(canonicalUrl)
        if (idMatcher.find()) {
            videoId = idMatcher.group(1)
        } else {
            val digitMatcher = DIGITS_PATTERN.matcher(canonicalUrl)
            if (digitMatcher.find()) {
                videoId = digitMatcher.group(1)
            }
        }

        if (videoId.isNullOrBlank()) {
            throw IllegalArgumentException("無法識別抖音影片 ID，請確認分享連結是否正確")
        }

        // 4. Query Douyin mobile API (extracts 100% unwatermarked original HD MP4)
        val endpoints = listOf(
            "https://api.amemv.com/aweme/v1/feed/?aweme_id=$videoId",
            "https://aweme.snssdk.com/aweme/v1/feed/?aweme_id=$videoId"
        )

        for (endpoint in endpoints) {
            try {
                val result = fetchAwemeFromApi(endpoint, videoId, cleanUrl)
                if (result != null) return@withContext result
            } catch (e: Exception) {
                // Try fallback endpoint
            }
        }

        throw IllegalArgumentException("無法取得該抖音影片，該影片可能已被刪除、設為私密或受到存取限制")
    }

    private fun resolveCanonicalUrl(url: String): String {
        return try {
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", BROWSER_UA)
                .build()
            client.newCall(req).execute().use { resp ->
                resp.request.url.toString()
            }
        } catch (e: Exception) {
            url
        }
    }

    private fun fetchAwemeFromApi(apiUrl: String, videoId: String, sourceUrl: String): ExtractionResult? {
        val req = Request.Builder()
            .url(apiUrl)
            .header("User-Agent", MOBILE_AWEME_UA)
            .build()

        return client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return null
            val body = resp.body?.string().orEmpty()
            if (!body.startsWith("{")) return null

            val json = JSONObject(body)
            val awemeList = json.optJSONArray("aweme_list") ?: return null
            if (awemeList.length() == 0) return null

            var targetItem: JSONObject? = null
            for (i in 0 until awemeList.length()) {
                val item = awemeList.getJSONObject(i)
                if (item.optString("aweme_id") == videoId) {
                    targetItem = item
                    break
                }
            }
            val target = targetItem ?: awemeList.getJSONObject(0)

            val author = target.optJSONObject("author")?.optString("nickname")?.ifBlank { "抖音用戶" } ?: "抖音用戶"
            val caption = target.optString("desc").ifBlank { "抖音影片" }

            // Check if it is a photo gallery / note (圖集)
            val imagesArr = target.optJSONArray("images")
            if (imagesArr != null && imagesArr.length() > 0) {
                val mediaItems = mutableListOf<MediaItem>()
                for (i in 0 until imagesArr.length()) {
                    val imgObj = imagesArr.getJSONObject(i)
                    val urlList = imgObj.optJSONArray("url_list")
                    val imgUrl = urlList?.optString(0)
                    if (!imgUrl.isNullOrBlank()) {
                        mediaItems.add(
                            MediaItem(
                                id = "${videoId}_$i",
                                downloadUrl = imgUrl,
                                previewThumbnailUrl = imgUrl,
                                isVideo = false,
                                author = author,
                                index = i + 1,
                                totalCount = imagesArr.length(),
                                platform = Platform.DOUYIN
                            )
                        )
                    }
                }
                if (mediaItems.isNotEmpty()) {
                    return ExtractionResult(
                        platform = Platform.DOUYIN,
                        author = author,
                        caption = caption,
                        items = mediaItems,
                        sourceUrl = sourceUrl
                    )
                }
            }

            // Standard video
            val videoObj = target.optJSONObject("video") ?: return null
            val coverUrl = videoObj.optJSONObject("cover")?.optJSONArray("url_list")?.optString(0).orEmpty()
            val playAddr = videoObj.optJSONObject("play_addr")
            val urlList = playAddr?.optJSONArray("url_list") ?: return null

            var directVideoUrl: String? = null
            for (i in 0 until urlList.length()) {
                val u = urlList.optString(i)
                if (u.isNotBlank()) {
                    // Replace playwm with play to guarantee watermark removal if applicable
                    directVideoUrl = u.replace("playwm", "play")
                    break
                }
            }

            if (directVideoUrl.isNullOrBlank()) return null

            ExtractionResult(
                platform = Platform.DOUYIN,
                author = author,
                caption = caption,
                items = listOf(
                    MediaItem(
                        id = videoId,
                        downloadUrl = directVideoUrl,
                        previewThumbnailUrl = coverUrl,
                        isVideo = true,
                        author = author,
                        platform = Platform.DOUYIN
                    )
                ),
                sourceUrl = sourceUrl
            )
        }
    }
}
