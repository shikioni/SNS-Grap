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

class TwitterExtractor(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()
) {
    companion object {
        private val TWEET_ID_PATTERN = Pattern.compile("(?:status|statuses)/([0-9]+)")
        private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"
    }

    suspend fun extract(rawUrl: String): ExtractionResult = withContext(Dispatchers.IO) {
        val cleanUrl = rawUrl.trim()
        val matcher = TWEET_ID_PATTERN.matcher(cleanUrl)
        if (!matcher.find()) {
            throw IllegalArgumentException("無法識別推文 ID，請確認是否為有效的 X (Twitter) 推文網址")
        }
        val tweetId = matcher.group(1) ?: throw IllegalArgumentException("無法解析推文 ID")

        // 1. Try Twitter Syndication API (Official & Fast)
        try {
            val syndicationResult = extractFromSyndication(tweetId, cleanUrl)
            if (syndicationResult != null) return@withContext syndicationResult
        } catch (e: Exception) {
            // Fall through to next strategy
        }

        // 2. Try vxTwitter API
        try {
            val vxResult = extractFromVxTwitter(tweetId, cleanUrl)
            if (vxResult != null) return@withContext vxResult
        } catch (e: Exception) {
            // Fall through to next strategy
        }

        // 3. Try fxTwitter API
        try {
            val fxResult = extractFromFxTwitter(tweetId, cleanUrl)
            if (fxResult != null) return@withContext fxResult
        } catch (e: Exception) {
            // Fall through
        }

        throw IllegalArgumentException("無法取得該推文的影片，請確認該推文是否為公開且包含影片")
    }

    private fun extractFromSyndication(tweetId: String, sourceUrl: String): ExtractionResult? {
        val tokensToTry = listOf(calculateSyndicationToken(tweetId), "1", "5")
        for (token in tokensToTry) {
            val url = "https://cdn.syndication.twimg.com/tweet-result?id=$tweetId&token=$token"
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .header("Accept", "application/json")
                .build()

            try {
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@use
                    val body = response.body?.string() ?: return@use
                    if (!body.startsWith("{")) return@use

                    val json = JSONObject(body)
                    val author = json.optJSONObject("user")?.optString("screen_name") ?: ""
                    val caption = json.optString("text", "")

                    // 1. Check mediaDetails
                    val mediaDetails = json.optJSONArray("mediaDetails")
                    if (mediaDetails != null && mediaDetails.length() > 0) {
                        val items = mutableListOf<MediaItem>()
                        for (i in 0 until mediaDetails.length()) {
                            val mediaObj = mediaDetails.getJSONObject(i)
                            val type = mediaObj.optString("type")
                            if (type == "video" || type == "animated_gif") {
                                val videoInfo = mediaObj.optJSONObject("video_info")
                                val variants = videoInfo?.optJSONArray("variants")
                                val bestVideoUrl = getBestMp4Variant(variants)
                                if (bestVideoUrl != null) {
                                    val thumb = mediaObj.optString("media_url_https")
                                    items.add(
                                        MediaItem(
                                            id = "${tweetId}_${i + 1}",
                                            downloadUrl = bestVideoUrl,
                                            previewThumbnailUrl = thumb,
                                            isVideo = true,
                                            author = author,
                                            index = i + 1,
                                            totalCount = mediaDetails.length(),
                                            platform = Platform.TWITTER
                                        )
                                    )
                                }
                            }
                        }
                        if (items.isNotEmpty()) {
                            return ExtractionResult(
                                author = author,
                                caption = caption,
                                items = items,
                                sourceUrl = sourceUrl,
                                platform = Platform.TWITTER
                            )
                        }
                    }

                    // 2. Check video object
                    val videoObj = json.optJSONObject("video")
                    if (videoObj != null) {
                        val variants = videoObj.optJSONArray("variants")
                        val bestMp4 = getBestMp4Variant(variants)
                        if (bestMp4 != null) {
                            val poster = videoObj.optString("poster")
                            return ExtractionResult(
                                author = author,
                                caption = caption,
                                items = listOf(
                                    MediaItem(
                                        id = tweetId,
                                        downloadUrl = bestMp4,
                                        previewThumbnailUrl = poster,
                                        isVideo = true,
                                        author = author,
                                        platform = Platform.TWITTER
                                    )
                                ),
                                sourceUrl = sourceUrl,
                                platform = Platform.TWITTER
                            )
                        }
                    }
                }
            } catch (e: Exception) {
                // Try next token
            }
        }
        return null
    }

    private fun extractFromVxTwitter(tweetId: String, sourceUrl: String): ExtractionResult? {
        val url = "https://api.vxtwitter.com/i/status/$tweetId"
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/json")
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            val body = response.body?.string() ?: return null
            if (!body.startsWith("{")) return null

            val json = JSONObject(body)
            val author = json.optString("user_screen_name", "")
            val caption = json.optString("text", "")
            val mediaExtended = json.optJSONArray("media_extended") ?: return null

            val items = mutableListOf<MediaItem>()
            for (i in 0 until mediaExtended.length()) {
                val mediaObj = mediaExtended.getJSONObject(i)
                val type = mediaObj.optString("type")
                if (type == "video" || type == "gif") {
                    val rawVideoUrl = mediaObj.optString("url")
                    val videoUrl = cleanMp4Url(rawVideoUrl)
                    val thumb = mediaObj.optString("thumbnail_url").ifEmpty {
                        mediaObj.optString("image_url")
                    }
                    if (videoUrl.isNotBlank() && !videoUrl.contains(".m3u8")) {
                        items.add(
                            MediaItem(
                                id = "${tweetId}_${i + 1}",
                                downloadUrl = videoUrl,
                                previewThumbnailUrl = thumb,
                                isVideo = true,
                                author = author,
                                index = i + 1,
                                totalCount = mediaExtended.length(),
                                platform = Platform.TWITTER
                            )
                        )
                    }
                }
            }

            if (items.isNotEmpty()) {
                return ExtractionResult(
                    author = author,
                    caption = caption,
                    items = items,
                    sourceUrl = sourceUrl,
                    platform = Platform.TWITTER
                )
            }
        }
        return null
    }

    private fun extractFromFxTwitter(tweetId: String, sourceUrl: String): ExtractionResult? {
        val url = "https://api.fxtwitter.com/status/$tweetId"
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/json")
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            val body = response.body?.string() ?: return null
            if (!body.startsWith("{")) return null

            val json = JSONObject(body)
            val tweetObj = json.optJSONObject("tweet") ?: return null
            val author = tweetObj.optJSONObject("author")?.optString("screen_name") ?: ""
            val caption = tweetObj.optString("text", "")

            val mediaObj = tweetObj.optJSONObject("media")
            val videos = mediaObj?.optJSONArray("videos") ?: return null

            val items = mutableListOf<MediaItem>()
            for (i in 0 until videos.length()) {
                val v = videos.getJSONObject(i)
                val rawVideoUrl = v.optString("url")
                val videoUrl = cleanMp4Url(rawVideoUrl)
                val thumb = v.optString("thumbnail_url")
                if (videoUrl.isNotBlank() && !videoUrl.contains(".m3u8")) {
                    items.add(
                        MediaItem(
                            id = "${tweetId}_${i + 1}",
                            downloadUrl = videoUrl,
                            previewThumbnailUrl = thumb,
                            isVideo = true,
                            author = author,
                            index = i + 1,
                            totalCount = videos.length(),
                            platform = Platform.TWITTER
                        )
                    )
                }
            }

            if (items.isNotEmpty()) {
                return ExtractionResult(
                    author = author,
                    caption = caption,
                    items = items,
                    sourceUrl = sourceUrl,
                    platform = Platform.TWITTER
                )
            }
        }
        return null
    }

    /**
     * Finds the highest bitrate MP4 file from the variants array.
     * Guaranteed to return a clean MP4 link, never an M3U8 streaming manifest.
     */
    private fun getBestMp4Variant(variants: org.json.JSONArray?): String? {
        if (variants == null || variants.length() == 0) return null
        var maxBitrate = -1
        var bestMp4Url: String? = null

        for (i in 0 until variants.length()) {
            val v = variants.getJSONObject(i)
            val contentType = v.optString("content_type", "").ifEmpty { v.optString("type", "") }
            val rawUrl = v.optString("url", "").ifEmpty { v.optString("src", "") }
            val bitrate = v.optInt("bitrate", 0)

            // Strictly filter out .m3u8
            if (rawUrl.contains(".m3u8", ignoreCase = true) || contentType.contains("mpegURL", ignoreCase = true)) {
                continue
            }

            val isMp4 = rawUrl.contains(".mp4", ignoreCase = true) || contentType.contains("mp4", ignoreCase = true)

            if (isMp4 && rawUrl.isNotBlank()) {
                if (bitrate >= maxBitrate || bestMp4Url == null) {
                    maxBitrate = bitrate
                    bestMp4Url = rawUrl
                }
            }
        }

        return bestMp4Url?.let { cleanMp4Url(it) }
    }

    private fun cleanMp4Url(url: String): String {
        return if (url.contains(".mp4?", ignoreCase = true)) {
            url.substringBefore("?")
        } else {
            url
        }
    }

    private fun calculateSyndicationToken(idStr: String): String {
        return try {
            val id = idStr.toDoubleOrNull() ?: return "1"
            val num = (id / 1e15) * Math.PI
            val intPart = Math.floor(num).toLong()
            var fracPart = num - Math.floor(num)
            val sb = StringBuilder()
            sb.append(java.lang.Long.toString(intPart, 36))
            if (fracPart > 0) {
                for (i in 0 until 10) {
                    fracPart *= 36
                    val d = Math.floor(fracPart).toInt()
                    sb.append(Character.forDigit(d, 36))
                    fracPart -= d
                }
            }
            sb.toString().replace(Regex("[0.]"), "")
        } catch (e: Exception) {
            "1"
        }
    }
}
