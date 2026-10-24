package com.instagrab.app.data.parser

import com.instagrab.app.data.model.ExtractionResult
import com.instagrab.app.data.model.MediaItem
import com.instagrab.app.data.model.Platform
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

class FacebookExtractor(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()
) {
    companion object {
        private val FB_VIDEO_ID_PATTERN = Pattern.compile("(?:videos/|reel/|watch/\\?v=|watch/|share/r/|share/v/|v=)([0-9]+)")
        private const val USER_AGENT_DESKTOP = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"
        private const val USER_AGENT_MOBILE = "Mozilla/5.0 (iPhone; CPU iPhone OS 16_5 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/16.5 Mobile/15E148 Safari/604.1"

        private val HD_URL_PATTERN = Pattern.compile("\"(?:browser_native_hd_url|playable_url_quality_hd|hd_src)\":\\s*\"(https:[^\"]+)\"")
        private val SD_URL_PATTERN = Pattern.compile("\"(?:browser_native_sd_url|playable_url|sd_src)\":\\s*\"(https:[^\"]+)\"")
        private val OG_VIDEO_PATTERN = Pattern.compile("<meta\\s+(?:property|name)=[\"']og:video(?::secure_url)?[\"']\\s+content=[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE)
        private val OG_TITLE_PATTERN = Pattern.compile("<meta\\s+(?:property|name)=[\"']og:title[\"']\\s+content=[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE)
        private val OG_DESC_PATTERN = Pattern.compile("<meta\\s+(?:property|name)=[\"']og:description[\"']\\s+content=[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE)
        private val OG_IMAGE_PATTERN = Pattern.compile("<meta\\s+(?:property|name)=[\"']og:image[\"']\\s+content=[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE)
    }

    suspend fun extract(rawUrl: String): ExtractionResult = withContext(Dispatchers.IO) {
        val cleanUrl = rawUrl.trim()
        val formattedUrl = when {
            cleanUrl.startsWith("http://") || cleanUrl.startsWith("https://") -> cleanUrl
            else -> "https://$cleanUrl"
        }

        // 1. Resolve redirect if it's a short link (fb.watch / share link)
        val canonicalUrl = resolveCanonicalUrl(formattedUrl)

        var videoId = extractVideoId(canonicalUrl) ?: extractVideoId(formattedUrl) ?: "fb_${System.currentTimeMillis()}"

        // 2. Strategy A: Direct Web Scraping (Desktop / Mobile User-Agents)
        try {
            val webResult = extractFromWeb(canonicalUrl, videoId, formattedUrl)
            if (webResult != null) return@withContext webResult
        } catch (e: Exception) {
            // Fall through to next strategy
        }

        // 3. Strategy B: SnapSave Parser Fallback
        try {
            val snapResult = extractFromSnapSave(formattedUrl, videoId)
            if (snapResult != null) return@withContext snapResult
        } catch (e: Exception) {
            // Fall through
        }

        throw IllegalArgumentException("無法取得該 Facebook 影片，請確認該貼文/Reels 是否為公開內容且包含影片")
    }

    private fun resolveCanonicalUrl(url: String): String {
        return try {
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT_MOBILE)
                .build()
            client.newCall(req).execute().use { resp ->
                resp.request.url.toString()
            }
        } catch (e: Exception) {
            url
        }
    }

    private fun extractVideoId(url: String): String? {
        val matcher = FB_VIDEO_ID_PATTERN.matcher(url)
        return if (matcher.find()) matcher.group(1) else null
    }

    private fun extractFromWeb(url: String, videoId: String, originalUrl: String): ExtractionResult? {
        val uas = listOf(USER_AGENT_DESKTOP, USER_AGENT_MOBILE)

        for (ua in uas) {
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", ua)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "zh-TW,zh;q=0.9,en-US;q=0.8,en;q=0.7")
                .build()

            val html = try {
                client.newCall(req).execute().use { resp ->
                    if (resp.isSuccessful) resp.body?.string() else null
                }
            } catch (e: Exception) {
                null
            } ?: continue

            // 1. Try HD URL
            var bestVideoUrl: String? = null
            val hdMatch = HD_URL_PATTERN.matcher(html)
            if (hdMatch.find()) {
                bestVideoUrl = cleanUrlString(hdMatch.group(1))
            }

            // 2. Try SD URL
            if (bestVideoUrl.isNullOrBlank()) {
                val sdMatch = SD_URL_PATTERN.matcher(html)
                if (sdMatch.find()) {
                    bestVideoUrl = cleanUrlString(sdMatch.group(1))
                }
            }

            // 3. Try OG Video
            if (bestVideoUrl.isNullOrBlank()) {
                val ogMatch = OG_VIDEO_PATTERN.matcher(html)
                if (ogMatch.find()) {
                    bestVideoUrl = cleanUrlString(ogMatch.group(1))
                }
            }

            if (!bestVideoUrl.isNullOrBlank()) {
                var title = ""
                val tMatch = OG_TITLE_PATTERN.matcher(html)
                if (tMatch.find()) {
                    title = cleanHtmlEntities(tMatch.group(1).orEmpty())
                }

                var desc: String? = null
                val dMatch = OG_DESC_PATTERN.matcher(html)
                if (dMatch.find()) {
                    desc = cleanHtmlEntities(dMatch.group(1).orEmpty())
                }

                var thumb: String? = null
                val imgMatch = OG_IMAGE_PATTERN.matcher(html)
                if (imgMatch.find()) {
                    thumb = cleanUrlString(imgMatch.group(1))
                }

                val author = extractAuthorFromTitle(title)

                return ExtractionResult(
                    platform = Platform.FACEBOOK,
                    author = author,
                    caption = desc ?: title,
                    items = listOf(
                        MediaItem(
                            id = videoId,
                            downloadUrl = bestVideoUrl,
                            previewThumbnailUrl = thumb ?: bestVideoUrl,
                            isVideo = true,
                            author = author,
                            platform = Platform.FACEBOOK
                        )
                    ),
                    sourceUrl = originalUrl
                )
            }
        }
        return null
    }

    private fun extractFromSnapSave(targetUrl: String, videoId: String): ExtractionResult? {
        val form = FormBody.Builder()
            .add("url", targetUrl)
            .build()

        val req = Request.Builder()
            .url("https://snapsave.app/action.php?lang=en")
            .header("User-Agent", USER_AGENT_DESKTOP)
            .header("Referer", "https://snapsave.app/")
            .header("Origin", "https://snapsave.app")
            .post(form)
            .build()

        return try {
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val script = resp.body?.string() ?: return null

                // Look for unpacked download link in decoded script or direct regex
                val urlMatch = Pattern.compile("href=\\\\\"(https:[^\\\\\"]+rapidcdn[^\\\\\"]+|https:[^\\\\\"]+fbcdn[^\\\\\"]+\\.mp4[^\\\\\"]*)\\\\\"").matcher(script)
                var downloadUrl: String? = null
                if (urlMatch.find()) {
                    downloadUrl = cleanUrlString(urlMatch.group(1))
                }

                // If obfuscated, unpack
                if (downloadUrl.isNullOrBlank()) {
                    val unpackedHtml = unpackSnapSaveJs(script)
                    if (unpackedHtml != null) {
                        val dlMatch = Pattern.compile("href=\"([^\"]+)\"[^>]*>\\s*(?:Download|Download HD|Download SD)").matcher(unpackedHtml)
                        if (dlMatch.find()) {
                            downloadUrl = dlMatch.group(1)
                        } else {
                            val anyDl = Pattern.compile("href=\"(https:[^\"]+)\"").matcher(unpackedHtml)
                            if (anyDl.find()) {
                                downloadUrl = anyDl.group(1)
                            }
                        }
                    }
                }

                if (!downloadUrl.isNullOrBlank()) {
                    ExtractionResult(
                        platform = Platform.FACEBOOK,
                        author = "Facebook",
                        caption = "Facebook 影片",
                        items = listOf(
                            MediaItem(
                                id = videoId,
                                downloadUrl = downloadUrl,
                                previewThumbnailUrl = downloadUrl,
                                isVideo = true,
                                author = "Facebook",
                                platform = Platform.FACEBOOK
                            )
                        ),
                        sourceUrl = targetUrl
                    )
                } else null
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun unpackSnapSaveJs(script: String): String? {
        return try {
            val evalPattern = Pattern.compile("eval\\(function\\(h,u,n,t,e,r\\)\\{.*?\\}\\(\"(.*?)\",(\\d+),\"(.*?)\",(\\d+),(\\d+),(\\d+)\\)\\)")
            val m = evalPattern.matcher(script)
            if (!m.find()) return null

            val h = m.group(1).orEmpty()
            val n = m.group(3).orEmpty()
            val t = m.group(4)?.toIntOrNull() ?: return null
            val e = m.group(5)?.toIntOrNull() ?: return null

            val sb = java.lang.StringBuilder()
            var i = 0
            val len = h.length
            val separator = n[e]

            while (i < len) {
                val s = java.lang.StringBuilder()
                while (i < len && h[i] != separator) {
                    s.append(h[i])
                    i++
                }
                i++ // skip separator

                var strVal = s.toString()
                for (j in n.indices) {
                    strVal = strVal.replace(n[j].toString(), j.toString())
                }

                try {
                    val parsed = java.lang.Long.parseLong(strVal, e)
                    val charCode = (parsed - t).toInt()
                    sb.append(charCode.toChar())
                } catch (numEx: Exception) {
                    // ignore
                }
            }

            sb.toString()
        } catch (e: Exception) {
            null
        }
    }

    private fun extractAuthorFromTitle(title: String): String {
        return when {
            title.contains("| Facebook") -> title.substringBefore("| Facebook").trim().take(30)
            title.contains(" - ") -> title.substringBefore(" - ").trim().take(30)
            title.isNotBlank() -> title.take(25)
            else -> "Facebook"
        }
    }

    private fun cleanUrlString(str: String?): String {
        if (str == null) return ""
        return str.replace("\\/", "/")
            .replace("\\u0026", "&")
            .replace("&amp;", "&")
    }

    private fun cleanHtmlEntities(str: String): String {
        return str.replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#xb7;", "·")
            .replace("&#xa0;", " ")
    }
}
