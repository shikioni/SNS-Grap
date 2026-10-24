package com.instagrab.app.data.parser

import com.instagrab.app.data.auth.SessionManager
import com.instagrab.app.data.model.ExtractionResult
import com.instagrab.app.data.model.MediaItem
import com.instagrab.app.data.model.Platform
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import java.util.regex.Matcher
import java.util.regex.Pattern

class ThreadsExtractor(
    private val sessionManager: SessionManager? = null,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()
) {
    companion object {
        // Direct post pattern: /post/ID, /t/ID
        private val POST_CODE_PATTERN = Pattern.compile("(?:post|t)/([A-Za-z0-9_-]+)")
        // Share token pattern: /share/ID, /s/ID
        private val SHARE_TOKEN_PATTERN = Pattern.compile("(?:share|s)/([A-Za-z0-9_-]+)")
        private val USERNAME_PATTERN = Pattern.compile("@([A-Za-z0-9_.]+)")
        private val BARCELONA_PATTERN = Pattern.compile("barcelona://media\\?shortcode=([A-Za-z0-9_-]+)")
        private val AL_ANDROID_PATTERN = Pattern.compile("<meta\\s+(?:property|name)=[\"']al:android:url[\"']\\s+content=[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE)
        private val OG_VIDEO_PATTERN = Pattern.compile("<meta\\s+(?:property|name)=[\"']og:video(?::secure_url)?[\"']\\s+content=[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE)
        private val OG_IMAGE_PATTERN = Pattern.compile("<meta\\s+(?:property|name)=[\"']og:image[\"']\\s+content=[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE)
        private val OG_TITLE_PATTERN = Pattern.compile("<meta\\s+(?:property|name)=[\"']og:title[\"']\\s+content=[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE)
        private val OG_DESC_PATTERN = Pattern.compile("<meta\\s+(?:property|name)=[\"']og:description[\"']\\s+content=[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE)
        private val VIDEO_VERSIONS_PATTERN = Pattern.compile("\"video_versions\":\\s*(\\[[^\\]]+\\])")
        private val VIDEO_URL_PATTERN = Pattern.compile("\"video_url\":\\s*\"(https:[^\"]+)\"")
        private val MP4_CDN_PATTERN = Pattern.compile("https://[a-zA-Z0-9.-]+\\.(?:cdninstagram|fbcdn)\\.com/[^\\s\"'<>]+\\.mp4[^\\s\"'<>]*")
        private val THUMB_CANDIDATE_PATTERN = Pattern.compile("\"image_versions2\":\\{\"candidates\":\\[\\{\"url\":\"(https:[^\"]+)\"")

        // Threads requires Mobile Safari or Facebook Crawler UA to return 302 redirect & SSR metadata
        private const val MOBILE_USER_AGENT = "Mozilla/5.0 (iPhone; CPU iPhone OS 16_6 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/16.6 Mobile/15E148 Safari/604.1"
        private const val CRAWLER_USER_AGENT = "facebookexternalhit/1.1 (+http://www.facebook.com/externalhit_uatext.php)"
    }

    suspend fun extract(rawUrl: String): ExtractionResult = withContext(Dispatchers.IO) {
        val cleanUrl = rawUrl.trim()
        val formattedUrl = when {
            cleanUrl.startsWith("http://") || cleanUrl.startsWith("https://") -> cleanUrl
            else -> "https://$cleanUrl"
        }

        // Strategy 1: Fetch formattedUrl directly with Mobile Safari UA (OkHttp follows 302 redirects automatically)
        try {
            val directResult = fetchAndExtract(formattedUrl, MOBILE_USER_AGENT, formattedUrl)
            if (directResult != null) return@withContext directResult
        } catch (e: Exception) {
            // Fall through
        }

        // Strategy 2: Fetch with Facebook Crawler UA (guarantees SSR for Meta share tokens)
        try {
            val crawlerResult = fetchAndExtract(formattedUrl, CRAWLER_USER_AGENT, formattedUrl)
            if (crawlerResult != null) return@withContext crawlerResult
        } catch (e: Exception) {
            // Fall through
        }

        // Strategy 3: Try resolving canonical post shortcode and fetch canonical URLs
        val shortcode = resolveShortcode(formattedUrl)
        if (shortcode.isNotBlank()) {
            val candidateUrls = listOf(
                "https://www.threads.net/t/$shortcode",
                "https://www.threads.com/t/$shortcode"
            )
            for (candidateUrl in candidateUrls) {
                try {
                    val result = fetchAndExtract(candidateUrl, MOBILE_USER_AGENT, formattedUrl)
                        ?: fetchAndExtract(candidateUrl, CRAWLER_USER_AGENT, formattedUrl)
                    if (result != null) return@withContext result
                } catch (e: Exception) {
                    // Try next candidate
                }
            }
        }

        throw IllegalArgumentException("無法取得該 Threads 貼文的影片，請確認該貼文是否為公開且包含影片")
    }

    private fun resolveShortcode(url: String): String {
        // Direct pattern check
        var matcher = POST_CODE_PATTERN.matcher(url)
        if (matcher.find()) {
            return matcher.group(1) ?: ""
        }

        // Resolve redirect to get final URL
        for (ua in listOf(MOBILE_USER_AGENT, CRAWLER_USER_AGENT)) {
            try {
                val req = Request.Builder()
                    .url(url)
                    .header("User-Agent", ua)
                    .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                    .header("Accept-Language", "en-US,en;q=0.9")
                    .build()

                client.newCall(req).execute().use { response ->
                    val finalUrl = response.request.url.toString()
                    matcher = POST_CODE_PATTERN.matcher(finalUrl)
                    if (matcher.find()) {
                        val code = matcher.group(1)
                        if (!code.isNullOrBlank()) return code
                    }

                    val html = response.body?.string().orEmpty()
                    val barcMatcher = BARCELONA_PATTERN.matcher(html)
                    if (barcMatcher.find()) {
                        val code = barcMatcher.group(1)
                        if (!code.isNullOrBlank()) return code
                    }

                    val alMatcher = AL_ANDROID_PATTERN.matcher(html)
                    if (alMatcher.find()) {
                        val alUrl = cleanHtmlEntities(alMatcher.group(1).orEmpty())
                        val m = POST_CODE_PATTERN.matcher(alUrl)
                        if (m.find()) {
                            val code = m.group(1)
                            if (!code.isNullOrBlank()) return code
                        }
                    }
                }
            } catch (e: Exception) {
                // Try next ua
            }
        }

        // Fallback to share token if nothing else matches
        val shareMatcher = SHARE_TOKEN_PATTERN.matcher(url)
        return if (shareMatcher.find()) shareMatcher.group(1) ?: "" else ""
    }

    private fun fetchAndExtract(
        targetUrl: String,
        userAgent: String,
        sourceUrl: String
    ): ExtractionResult? {
        val request = Request.Builder()
            .url(targetUrl)
            .header("User-Agent", userAgent)
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
            .header("Accept-Language", "en-US,en;q=0.9")
            .header("Sec-Fetch-Site", "none")
            .header("Sec-Fetch-Mode", "navigate")
            // NOTE: Do NOT send Instagram cookies here! Instagram session cookies cause Threads
            // to issue a 302 redirect loop back to the homepage (https://www.threads.com/).
            .build()

        return client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            val finalUrl = response.request.url.toString()
            val html = response.body?.string() ?: return null

            parseHtmlPayload(html, finalUrl, sourceUrl)
        }
    }

    private fun parseHtmlPayload(
        html: String,
        finalUrl: String,
        sourceUrl: String
    ): ExtractionResult? {
        // 1. Extract Video URL
        var videoUrl: String? = null
        var thumbUrl: String? = null
        var width = 0
        var height = 0

        // 1-A. Check video_versions in embedded JSON
        val vMatcher = VIDEO_VERSIONS_PATTERN.matcher(html)
        if (vMatcher.find()) {
            val jsonArrayStr = vMatcher.group(1)
            if (!jsonArrayStr.isNullOrBlank()) {
                try {
                    val rawArray = unescapeJsonString(jsonArrayStr)
                    val array = org.json.JSONArray(rawArray)
                    for (i in 0 until array.length()) {
                        val obj = array.getJSONObject(i)
                        val u = obj.optString("url")
                        val w = obj.optInt("width", 0)
                        val h = obj.optInt("height", 0)
                        if (u.isNotBlank()) {
                            videoUrl = u
                            width = w
                            height = h
                            if (w > 0 && h > 0) break
                        }
                    }
                } catch (e: Exception) {
                    // ignore
                }
            }
        }

        // 1-B. Check direct video_url pattern
        if (videoUrl == null) {
            val directMatcher = VIDEO_URL_PATTERN.matcher(html)
            if (directMatcher.find()) {
                videoUrl = unescapeJsonString(directMatcher.group(1).orEmpty())
            }
        }

        // 1-C. Check progressive .mp4 streams in unescaped HTML
        if (videoUrl == null) {
            val unescapedHtml = unescapeJsonString(html)
            val mp4Matcher = MP4_CDN_PATTERN.matcher(unescapedHtml)
            while (mp4Matcher.find()) {
                val candidate = mp4Matcher.group(0)
                if (!candidate.contains("rsrc.php")) {
                    videoUrl = candidate
                    break
                }
            }
        }

        // 1-D. Check OpenGraph meta tags
        if (videoUrl == null) {
            val ogMatcher = OG_VIDEO_PATTERN.matcher(html)
            if (ogMatcher.find()) {
                videoUrl = cleanHtmlEntities(ogMatcher.group(1).orEmpty())
            }
        }

        if (videoUrl.isNullOrBlank()) return null

        // 2. Extract Thumbnail
        val thumbMatcher = THUMB_CANDIDATE_PATTERN.matcher(html)
        if (thumbMatcher.find()) {
            thumbUrl = unescapeJsonString(thumbMatcher.group(1).orEmpty())
        }
        if (thumbUrl.isNullOrBlank()) {
            val imgMatcher = OG_IMAGE_PATTERN.matcher(html)
            if (imgMatcher.find()) {
                thumbUrl = cleanHtmlEntities(imgMatcher.group(1).orEmpty())
            }
        }

        // 3. Extract Author
        var author = ""
        val userMatch = USERNAME_PATTERN.matcher(finalUrl)
        if (userMatch.find()) {
            author = userMatch.group(1) ?: ""
        }
        if (author.isBlank()) {
            val titleMatcher = OG_TITLE_PATTERN.matcher(html)
            if (titleMatcher.find()) {
                val title = cleanHtmlEntities(titleMatcher.group(1).orEmpty())
                val uMatch = USERNAME_PATTERN.matcher(title)
                if (uMatch.find()) {
                    author = uMatch.group(1) ?: ""
                }
            }
        }

        // 4. Extract Caption
        var caption: String? = null
        val descMatcher = OG_DESC_PATTERN.matcher(html)
        if (descMatcher.find()) {
            caption = cleanHtmlEntities(descMatcher.group(1).orEmpty())
        }

        // 5. Extract Shortcode
        var shortcode = ""
        val codeMatch = POST_CODE_PATTERN.matcher(finalUrl)
        if (codeMatch.find()) {
            shortcode = codeMatch.group(1) ?: ""
        }
        if (shortcode.isBlank()) {
            val barcMatcher = BARCELONA_PATTERN.matcher(html)
            if (barcMatcher.find()) {
                shortcode = barcMatcher.group(1) ?: ""
            }
        }
        if (shortcode.isBlank()) {
            val shareMatch = SHARE_TOKEN_PATTERN.matcher(finalUrl)
            if (shareMatch.find()) {
                shortcode = shareMatch.group(1) ?: ""
            }
        }
        if (shortcode.isBlank()) {
            shortcode = "threads_post"
        }

        return ExtractionResult(
            platform = Platform.THREADS,
            author = author,
            caption = caption,
            items = listOf(
                MediaItem(
                    id = shortcode,
                    downloadUrl = videoUrl,
                    previewThumbnailUrl = thumbUrl ?: videoUrl,
                    isVideo = true,
                    width = width,
                    height = height,
                    author = author,
                    platform = Platform.THREADS
                )
            ),
            sourceUrl = sourceUrl
        )
    }

    private fun unescapeJsonString(str: String): String {
        return str.replace("\\/", "/")
            .replace("\\u0026", "&")
            .replace("\\\"", "\"")
    }

    private fun cleanHtmlEntities(str: String): String {
        var result = str.replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
            .replace("&#064;", "@")
            .replace("&#x2022;", "•")

        try {
            // Hexadecimal entities &#xHHHH;
            val hexPattern = Pattern.compile("&#x([0-9a-fA-F]+);")
            val hexMatcher = hexPattern.matcher(result)
            val sbHex = StringBuffer()
            while (hexMatcher.find()) {
                val codePoint = hexMatcher.group(1).toInt(16)
                hexMatcher.appendReplacement(sbHex, Matcher.quoteReplacement(String(Character.toChars(codePoint))))
            }
            hexMatcher.appendTail(sbHex)
            result = sbHex.toString()

            // Decimal entities &#DDDD;
            val decPattern = Pattern.compile("&#([0-9]+);")
            val decMatcher = decPattern.matcher(result)
            val sbDec = StringBuffer()
            while (decMatcher.find()) {
                val codePoint = decMatcher.group(1).toInt(10)
                decMatcher.appendReplacement(sbDec, Matcher.quoteReplacement(String(Character.toChars(codePoint))))
            }
            decMatcher.appendTail(sbDec)
            result = sbDec.toString()
        } catch (e: Exception) {
            // ignore
        }

        return result
    }
}
