package com.instagrab.app.data.parser

import com.instagrab.app.data.model.ExtractionResult
import com.instagrab.app.data.model.MediaItem
import com.instagrab.app.data.model.Platform
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

class TikTokExtractor(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()
) {
    companion object {
        private val VIDEO_ID_PATTERN = Pattern.compile("(?:video|v)/([0-9]+)")
        private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"
    }

    suspend fun extract(rawUrl: String): ExtractionResult = withContext(Dispatchers.IO) {
        val cleanUrl = rawUrl.trim()
        if (cleanUrl.isEmpty()) {
            throw IllegalArgumentException("請輸入 TikTok 影片網址")
        }

        // 1. Resolve shortlink if needed (vt.tiktok.com / vm.tiktok.com)
        val canonicalUrl = resolveCanonicalUrl(cleanUrl)

        // 2. Try TikWM Public API (Extracts 100% unwatermarked high-definition MP4)
        try {
            val apiResult = extractFromTikWm(canonicalUrl, cleanUrl)
            if (apiResult != null) return@withContext apiResult
        } catch (e: Exception) {
            // Fall through to next strategy
        }

        // 3. Try Direct Web Scraping
        try {
            val webResult = extractFromWeb(canonicalUrl, cleanUrl)
            if (webResult != null) return@withContext webResult
        } catch (e: Exception) {
            // Fall through
        }

        throw IllegalArgumentException("無法取得該 TikTok 影片，請確認該影片是否為公開且有效")
    }

    private fun resolveCanonicalUrl(url: String): String {
        return try {
            if (url.contains("vt.tiktok.com") || url.contains("vm.tiktok.com")) {
                val req = Request.Builder()
                    .url(url)
                    .header("User-Agent", USER_AGENT)
                    .build()
                client.newCall(req).execute().use { resp ->
                    resp.request.url.toString()
                }
            } else {
                url
            }
        } catch (e: Exception) {
            url
        }
    }

    private fun extractFromTikWm(targetUrl: String, sourceUrl: String): ExtractionResult? {
        val encodedUrl = URLEncoder.encode(targetUrl, "UTF-8")
        val apiUrl = "https://www.tikwm.com/api/?url=$encodedUrl"
        val request = Request.Builder()
            .url(apiUrl)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/json")
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            val body = response.body?.string() ?: return null
            if (!body.startsWith("{")) return null

            val json = JSONObject(body)
            if (json.optInt("code", -1) != 0) return null

            val data = json.optJSONObject("data") ?: return null

            // Prioritize HD play if available, fallback to unwatermarked play
            var videoUrl = data.optString("hdplay")
            if (videoUrl.isNullOrBlank()) {
                videoUrl = data.optString("play")
            }
            if (videoUrl.isNullOrBlank()) return null

            // If relative URL returned
            if (videoUrl.startsWith("/")) {
                videoUrl = "https://www.tikwm.com$videoUrl"
            }

            val authorObj = data.optJSONObject("author")
            val author = authorObj?.optString("unique_id").orEmpty().ifBlank {
                authorObj?.optString("nickname").orEmpty()
            }
            val title = data.optString("title", "")
            val cover = data.optString("cover", "")
            val id = data.optString("id", System.currentTimeMillis().toString())

            val mediaItem = MediaItem(
                id = id,
                downloadUrl = videoUrl,
                previewThumbnailUrl = cover.ifBlank { videoUrl },
                isVideo = true,
                author = author,
                index = 1,
                totalCount = 1,
                platform = Platform.TIKTOK
            )

            return ExtractionResult(
                author = author,
                caption = title,
                items = listOf(mediaItem),
                sourceUrl = sourceUrl,
                platform = Platform.TIKTOK
            )
        }
    }

    private fun extractFromWeb(canonicalUrl: String, sourceUrl: String): ExtractionResult? {
        val request = Request.Builder()
            .url(canonicalUrl)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .header("Accept-Language", "en-US,en;q=0.9")
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            val html = response.body?.string() ?: return null

            val matcher = VIDEO_ID_PATTERN.matcher(canonicalUrl)
            val videoId = if (matcher.find()) matcher.group(1) ?: "" else ""

            // 1. Look for playAddr in Universal Data
            val playAddrPattern = Pattern.compile("\"playAddr\":\\s*\"([^\"]+)\"")
            val pMatcher = playAddrPattern.matcher(html)
            var videoUrl: String? = null
            if (pMatcher.find()) {
                videoUrl = pMatcher.group(1)?.replace("\\u0026", "&")?.replace("\\/", "/")
            }

            // 2. Look for downloadAddr
            if (videoUrl.isNullOrBlank()) {
                val dlPattern = Pattern.compile("\"downloadAddr\":\\s*\"([^\"]+)\"")
                val dMatcher = dlPattern.matcher(html)
                if (dMatcher.find()) {
                    videoUrl = dMatcher.group(1)?.replace("\\u0026", "&")?.replace("\\/", "/")
                }
            }

            if (videoUrl.isNullOrBlank()) return null

            // Author & Title
            val authorPattern = Pattern.compile("\"uniqueId\":\\s*\"([^\"]+)\"")
            val aMatcher = authorPattern.matcher(html)
            val author = if (aMatcher.find()) aMatcher.group(1) ?: "" else ""

            val titlePattern = Pattern.compile("\"desc\":\\s*\"([^\"]+)\"")
            val tMatcher = titlePattern.matcher(html)
            val title = if (tMatcher.find()) tMatcher.group(1) ?: "" else ""

            val coverPattern = Pattern.compile("\"cover\":\\s*\"([^\"]+)\"")
            val cMatcher = coverPattern.matcher(html)
            val cover = if (cMatcher.find()) {
                cMatcher.group(1)?.replace("\\u0026", "&")?.replace("\\/", "/") ?: ""
            } else ""

            val mediaItem = MediaItem(
                id = videoId.ifBlank { System.currentTimeMillis().toString() },
                downloadUrl = videoUrl,
                previewThumbnailUrl = cover.ifBlank { videoUrl },
                isVideo = true,
                author = author,
                index = 1,
                totalCount = 1,
                platform = Platform.TIKTOK
            )

            return ExtractionResult(
                author = author,
                caption = title,
                items = listOf(mediaItem),
                sourceUrl = sourceUrl,
                platform = Platform.TIKTOK
            )
        }
    }
}
