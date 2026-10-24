package com.instagrab.app.data.parser

import com.instagrab.app.data.auth.SessionManager
import com.instagrab.app.data.model.ExtractionResult
import com.instagrab.app.data.model.MediaItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

class InstagramExtractor(private val sessionManager: SessionManager) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    companion object {
        private const val USER_AGENT =
            "Mozilla/5.0 (iPhone; CPU iPhone OS 16_6 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/16.6 Mobile/15E148 Safari/604.1"
        private const val IG_APP_ID = "936619743392459"

        private val POST_REEL_PATTERN = Pattern.compile("instagram\\.com/(?:p|reel|reels|tv)/([^/?#&]+)")
        private val STORY_HIGHLIGHT_PATTERN = Pattern.compile("instagram\\.com/stories/highlights/([0-9]+)")
        private val STORY_PATTERN = Pattern.compile("instagram\\.com/stories/([^/?#&]+)(?:/([0-9]+))?")
    }

    /**
     * Converts Instagram Base64 shortcode (e.g., CxOWiQNP2MO) to numeric mediaId
     */
    fun shortcodeToMediaId(code: String): String {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
        var id: Long = 0
        for (c in code) {
            val idx = alphabet.indexOf(c)
            if (idx >= 0) {
                id = id * 64 + idx
            }
        }
        return id.toString()
    }

    /**
     * Extracts media items from an Instagram link (Post, Reel, Stories, Highlights).
     */
    suspend fun extract(rawUrl: String): ExtractionResult = withContext(Dispatchers.IO) {
        val cleanUrl = rawUrl.trim()
        if (cleanUrl.isEmpty()) {
            throw IllegalArgumentException("請輸入 Instagram 網址")
        }

        // 1. Check if Story Highlight
        val highlightMatcher = STORY_HIGHLIGHT_PATTERN.matcher(cleanUrl)
        if (highlightMatcher.find()) {
            val highlightId = highlightMatcher.group(1) ?: throw IllegalArgumentException("無效的限動精選網址")
            return@withContext extractStoryReel("highlight:$highlightId", null, cleanUrl)
        }

        // 2. Check if Story
        val storyMatcher = STORY_PATTERN.matcher(cleanUrl)
        if (storyMatcher.find()) {
            val username = storyMatcher.group(1) ?: throw IllegalArgumentException("無效的限時動態網址")
            val targetMediaId = if (storyMatcher.groupCount() >= 2) storyMatcher.group(2) else null
            return@withContext extractUserStory(username, targetMediaId, cleanUrl)
        }

        // 3. Check if Post or Reel
        val postMatcher = POST_REEL_PATTERN.matcher(cleanUrl)
        if (postMatcher.find()) {
            val shortcode = postMatcher.group(1) ?: throw IllegalArgumentException("無法解析貼文短代碼")
            return@withContext extractPostOrReel(shortcode, cleanUrl)
        }

        throw IllegalArgumentException("無法識別此 Instagram 網址格式，請確認是否為貼文、Reels 或限時動態連結")
    }

    /**
     * Extracts Post or Reel using official media info API and uncropped HTML JSON
     */
    private fun extractPostOrReel(shortcode: String, sourceUrl: String): ExtractionResult {
        val mediaId = shortcodeToMediaId(shortcode)
        val cookie = sessionManager.getCookieHeader()

        // Attempt 1: Official Instagram Web API (www.instagram.com/api/v1/media/{id}/info/)
        try {
            val apiUrl = "https://www.instagram.com/api/v1/media/$mediaId/info/"
            val result = queryMediaInfoApi(apiUrl, shortcode, sourceUrl, cookie)
            if (result != null) return result
        } catch (_: Exception) {}

        // Attempt 2: Instagram Mobile Internal API (i.instagram.com/api/v1/media/{id}/info/)
        try {
            val apiUrl = "https://i.instagram.com/api/v1/media/$mediaId/info/"
            val result = queryMediaInfoApi(apiUrl, shortcode, sourceUrl, cookie)
            if (result != null) return result
        } catch (_: Exception) {}

        // Attempt 3: Try extracting uncropped embedded media JSON from the web page
        try {
            val result = extractFromHtmlJson(shortcode, sourceUrl)
            if (result != null) return result
        } catch (_: Exception) {}

        // If all attempts failed:
        if (!sessionManager.isLoggedIn()) {
            throw IllegalStateException(
                "⚠️ Instagram 限制：此內容需要登入 IG 帳號以獲取【原始未裁切相片】與【完整 MP4 影片】。\n" +
                "請點擊右上角【登入 IG】（官方頁面安全登入），登入後即可正常下載！"
            )
        } else {
            throw IOException("無法獲取此貼文的媒體資料，可能該內容已遭刪除、設為私密帳號，或登入 Session 已過期。")
        }
    }

    fun queryMediaInfoApi(
        apiUrl: String,
        shortcode: String,
        sourceUrl: String,
        cookie: String
    ): ExtractionResult? {
        val requestBuilder = Request.Builder()
            .url(apiUrl)
            .header("User-Agent", USER_AGENT)
            .header("X-IG-App-ID", IG_APP_ID)
            .header("X-ASBD-ID", "129477")
            .header("X-IG-WWW-Claim", "0")
            .header("Origin", "https://www.instagram.com")
            .header("Referer", "https://www.instagram.com/p/$shortcode/")
            .header("Accept", "*/*")

        if (cookie.isNotBlank()) {
            requestBuilder.header("Cookie", cookie)
            val csrfToken = extractCookieValue(cookie, "csrftoken")
            if (!csrfToken.isNullOrBlank()) {
                requestBuilder.header("X-CSRFToken", csrfToken)
            }
        }

        val response = client.newCall(requestBuilder.build()).execute()
        val responseBody = response.body?.string().orEmpty()

        if (!response.isSuccessful || responseBody.isBlank()) {
            return null
        }

        return try {
            val json = JSONObject(responseBody)
            if (json.optString("status") == "fail" || json.optString("message") == "login_required") {
                null
            } else {
                parsePostJson(json, shortcode, sourceUrl)
            }
        } catch (e: Exception) {
            null
        }
    }

    fun parsePostJson(json: JSONObject, shortcode: String, sourceUrl: String): ExtractionResult? {
        // Format A: { items: [ ... ] }
        if (json.has("items")) {
            val itemsArray = json.getJSONArray("items")
            if (itemsArray.length() > 0) {
                val postItem = itemsArray.getJSONObject(0)
                val author = postItem.optJSONObject("user")?.optString("username", "") ?: ""
                val caption = postItem.optJSONObject("caption")?.optString("text", "")

                val mediaItems = mutableListOf<MediaItem>()

                // Check for Carousel (Multiple images/videos)
                if (postItem.has("carousel_media")) {
                    val carousel = postItem.getJSONArray("carousel_media")
                    val total = carousel.length()
                    for (i in 0 until total) {
                        val cItem = carousel.getJSONObject(i)
                        val id = cItem.optString("id", "${shortcode}_$i")
                        val isVideo = cItem.optInt("media_type") == 2 || cItem.has("video_versions")

                        val downloadUrl: String
                        val previewUrl: String

                        if (isVideo && cItem.has("video_versions")) {
                            val videos = cItem.getJSONArray("video_versions")
                            // video_versions[0] is the highest bitrate MP4 video
                            downloadUrl = videos.getJSONObject(0).getString("url")
                            previewUrl = cItem.optJSONObject("image_versions2")
                                ?.optJSONArray("candidates")
                                ?.optJSONObject(0)?.optString("url") ?: downloadUrl
                        } else {
                            // candidates[0] is the highest original uncropped photo
                            val images = cItem.getJSONObject("image_versions2").getJSONArray("candidates")
                            downloadUrl = images.getJSONObject(0).getString("url")
                            previewUrl = downloadUrl
                        }

                        mediaItems.add(
                            MediaItem(
                                id = id,
                                downloadUrl = downloadUrl,
                                previewThumbnailUrl = previewUrl,
                                isVideo = isVideo,
                                author = author,
                                index = i + 1,
                                totalCount = total
                            )
                        )
                    }
                } else {
                    // Single item
                    val isVideo = postItem.optInt("media_type") == 2 || postItem.has("video_versions")
                    val downloadUrl: String
                    val previewUrl: String

                    if (isVideo && postItem.has("video_versions")) {
                        val videos = postItem.getJSONArray("video_versions")
                        // Highest quality real MP4 video
                        downloadUrl = videos.getJSONObject(0).getString("url")
                        previewUrl = postItem.optJSONObject("image_versions2")
                            ?.optJSONArray("candidates")
                            ?.optJSONObject(0)?.optString("url") ?: downloadUrl
                    } else {
                        // Highest quality uncropped original photo
                        val images = postItem.getJSONObject("image_versions2").getJSONArray("candidates")
                        downloadUrl = images.getJSONObject(0).getString("url")
                        previewUrl = downloadUrl
                    }

                    mediaItems.add(
                        MediaItem(
                            id = shortcode,
                            downloadUrl = downloadUrl,
                            previewThumbnailUrl = previewUrl,
                            isVideo = isVideo,
                            author = author,
                            index = 1,
                            totalCount = 1
                        )
                    )
                }

                if (mediaItems.isNotEmpty()) {
                    return ExtractionResult(author, caption, mediaItems, sourceUrl)
                }
            }
        }

        // Format B: { graphql: { shortcode_media: { ... } } }
        if (json.has("graphql")) {
            val media = json.getJSONObject("graphql").getJSONObject("shortcode_media")
            val author = media.optJSONObject("owner")?.optString("username", "") ?: ""
            val caption = media.optJSONObject("edge_media_to_caption")
                ?.optJSONArray("edges")
                ?.optJSONObject(0)?.optJSONObject("node")?.optString("text")

            val mediaItems = mutableListOf<MediaItem>()

            if (media.has("edge_sidecar_to_children")) {
                val edges = media.getJSONObject("edge_sidecar_to_children").getJSONArray("edges")
                val total = edges.length()
                for (i in 0 until total) {
                    val node = edges.getJSONObject(i).getJSONObject("node")
                    val isVideo = node.optBoolean("is_video", false)
                    val downloadUrl = if (isVideo) node.getString("video_url") else node.getString("display_url")
                    val previewUrl = node.optString("display_url", downloadUrl)

                    mediaItems.add(
                        MediaItem(
                            id = node.optString("id", "${shortcode}_$i"),
                            downloadUrl = downloadUrl,
                            previewThumbnailUrl = previewUrl,
                            isVideo = isVideo,
                            author = author,
                            index = i + 1,
                            totalCount = total
                        )
                    )
                }
            } else {
                val isVideo = media.optBoolean("is_video", false)
                val downloadUrl = if (isVideo) media.getString("video_url") else media.getString("display_url")
                val previewUrl = media.optString("display_url", downloadUrl)

                mediaItems.add(
                    MediaItem(
                        id = shortcode,
                        downloadUrl = downloadUrl,
                        previewThumbnailUrl = previewUrl,
                        isVideo = isVideo,
                        author = author,
                        index = 1,
                        totalCount = 1
                    )
                )
            }

            if (mediaItems.isNotEmpty()) {
                return ExtractionResult(author, caption, mediaItems, sourceUrl)
            }
        }

        return null
    }

    /**
     * Extracts uncropped full photo or video from embedded script JSON inside page HTML
     */
    private fun extractFromHtmlJson(shortcode: String, sourceUrl: String): ExtractionResult? {
        val request = Request.Builder()
            .url("https://www.instagram.com/p/$shortcode/")
            .header("User-Agent", USER_AGENT)
            .header("X-IG-App-ID", IG_APP_ID)
            .build()

        val response = client.newCall(request).execute()
        val html = response.body?.string().orEmpty()

        // 1. Look for video_url in page scripts (actual .mp4 file)
        val videoUrlPattern = Pattern.compile("\"video_url\"\\s*:\\s*\"(https:[^\"]+)\"")
        val videoMatcher = videoUrlPattern.matcher(html)
        if (videoMatcher.find()) {
            val videoUrl = videoMatcher.group(1)!!
                .replace("\\u0026", "&")
                .replace("\\/", "/")
                .replace("&amp;", "&")

            val displayPattern = Pattern.compile("\"display_url\"\\s*:\\s*\"(https:[^\"]+)\"")
            val displayMatcher = displayPattern.matcher(html)
            val previewUrl = if (displayMatcher.find()) {
                displayMatcher.group(1)!!.replace("\\u0026", "&").replace("\\/", "/").replace("&amp;", "&")
            } else {
                videoUrl
            }

            return ExtractionResult(
                author = "",
                caption = null,
                items = listOf(
                    MediaItem(
                        id = shortcode,
                        downloadUrl = videoUrl,
                        previewThumbnailUrl = previewUrl,
                        isVideo = true,
                        index = 1,
                        totalCount = 1
                    )
                ),
                sourceUrl = sourceUrl
            )
        }

        // 2. Look for display_url in page scripts (uncropped full-size image, NOT og:image)
        val displayUrlPattern = Pattern.compile("\"display_url\"\\s*:\\s*\"(https:[^\"]+)\"")
        val displayMatcher = displayUrlPattern.matcher(html)
        if (displayMatcher.find()) {
            val imageUrl = displayMatcher.group(1)!!
                .replace("\\u0026", "&")
                .replace("\\/", "/")
                .replace("&amp;", "&")

            return ExtractionResult(
                author = "",
                caption = null,
                items = listOf(
                    MediaItem(
                        id = shortcode,
                        downloadUrl = imageUrl,
                        previewThumbnailUrl = imageUrl,
                        isVideo = false,
                        index = 1,
                        totalCount = 1
                    )
                ),
                sourceUrl = sourceUrl
            )
        }

        return null
    }

    /**
     * Extracts user stories. Requires IG login session.
     */
    private fun extractUserStory(username: String, targetMediaId: String?, sourceUrl: String): ExtractionResult {
        if (!sessionManager.isLoggedIn()) {
            throw IllegalStateException(
                "⚠️ 下載『限時動態』需先登入 Instagram 帳號（因 Meta 官方政策規定限動僅對登入帳號開放）。\n" +
                "請點擊右上角【登入 IG】完成安全登入後即可下載！"
            )
        }

        val cleanUsername = username.trim().removePrefix("@").trimEnd('/')
        val cookie = sessionManager.getCookieHeader()

        // 1. Direct Story Media ID lookup:
        // When users copy a specific story link (/stories/username/1234567890/),
        // the targetMediaId is the actual media ID. We can query it directly!
        if (!targetMediaId.isNullOrBlank()) {
            try {
                val mediaResult = queryMediaInfoApi(
                    "https://www.instagram.com/api/v1/media/$targetMediaId/info/",
                    targetMediaId,
                    sourceUrl,
                    cookie
                )
                if (mediaResult != null && mediaResult.items.isNotEmpty()) {
                    return mediaResult
                }
            } catch (_: Exception) {}

            try {
                val mediaResult = queryMediaInfoApi(
                    "https://i.instagram.com/api/v1/media/$targetMediaId/info/",
                    targetMediaId,
                    sourceUrl,
                    cookie
                )
                if (mediaResult != null && mediaResult.items.isNotEmpty()) {
                    return mediaResult
                }
            } catch (_: Exception) {}
        }

        // 2. Resolve username to numeric user ID
        val userId = getUserIdFromUsername(cleanUsername)
            ?: throw IOException("找不到 Instagram 使用者: @$cleanUsername（可能該帳號為私密帳號或暫時受 IG 頻率限制）")

        // 3. Fetch reels media for that user ID
        return extractStoryReel(userId, targetMediaId, sourceUrl, cleanUsername)
    }

    private fun getUserIdFromUsername(username: String): String? {
        val cookie = sessionManager.getCookieHeader()
        val csrfToken = extractCookieValue(cookie, "csrftoken") ?: ""

        // Strategy A: web_profile_info with full browser headers
        try {
            val url = "https://www.instagram.com/api/v1/users/web_profile_info/?username=$username"
            val requestBuilder = Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .header("X-IG-App-ID", IG_APP_ID)
                .header("X-ASBD-ID", "129477")
                .header("X-IG-WWW-Claim", "0")
                .header("Referer", "https://www.instagram.com/$username/")
                .header("Origin", "https://www.instagram.com")
                .header("Accept", "*/*")

            if (csrfToken.isNotEmpty()) {
                requestBuilder.header("X-CSRFToken", csrfToken)
            }
            if (cookie.isNotBlank()) {
                requestBuilder.header("Cookie", cookie)
            }

            val response = client.newCall(requestBuilder.build()).execute()
            if (response.isSuccessful) {
                val body = response.body?.string().orEmpty()
                val json = JSONObject(body)
                val id = json.optJSONObject("data")?.optJSONObject("user")?.optString("id")
                if (!id.isNullOrBlank()) return id
            }
        } catch (_: Exception) {}

        // Strategy B: topsearch API
        try {
            val searchUrl = "https://www.instagram.com/web/search/topsearch/?context=blended&query=$username"
            val requestBuilder = Request.Builder()
                .url(searchUrl)
                .header("User-Agent", USER_AGENT)
                .header("X-IG-App-ID", IG_APP_ID)
                .header("Referer", "https://www.instagram.com/")
                .header("Accept", "*/*")

            if (cookie.isNotBlank()) {
                requestBuilder.header("Cookie", cookie)
            }

            val response = client.newCall(requestBuilder.build()).execute()
            if (response.isSuccessful) {
                val body = response.body?.string().orEmpty()
                val json = JSONObject(body)
                val users = json.optJSONArray("users")
                if (users != null) {
                    for (i in 0 until users.length()) {
                        val u = users.getJSONObject(i).optJSONObject("user")
                        if (u != null) {
                            val uName = u.optString("username", "")
                            if (uName.equals(username, ignoreCase = true)) {
                                val pk = u.optString("pk", "")
                                if (pk.isNotBlank()) return pk
                            }
                        }
                    }
                }
            }
        } catch (_: Exception) {}

        // Strategy C: HTML profile page extraction
        try {
            val profileUrl = "https://www.instagram.com/$username/"
            val request = Request.Builder()
                .url(profileUrl)
                .header("User-Agent", USER_AGENT)
                .header("X-IG-App-ID", IG_APP_ID)
                .header("Cookie", cookie)
                .build()

            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                val html = response.body?.string().orEmpty()
                val idPattern = Pattern.compile("\"(?:user_id|target_id|profile_id)\"\\s*:\\s*\"?([0-9]+)\"?")
                val matcher = idPattern.matcher(html)
                if (matcher.find()) {
                    return matcher.group(1)
                }
            }
        } catch (_: Exception) {}

        return null
    }

    private fun extractCookieValue(cookies: String, key: String): String? {
        val parts = cookies.split(";")
        for (part in parts) {
            val trimmed = part.trim()
            if (trimmed.startsWith("$key=")) {
                return trimmed.substringAfter("$key=")
            }
        }
        return null
    }

    private fun extractStoryReel(
        reelId: String,
        targetMediaId: String?,
        sourceUrl: String,
        fallbackAuthor: String = ""
    ): ExtractionResult {
        val url = "https://www.instagram.com/api/v1/feed/reels_media/?reel_ids=$reelId"
        val cookie = sessionManager.getCookieHeader()
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("X-IG-App-ID", IG_APP_ID)
            .header("Cookie", cookie)
            .build()

        val response = client.newCall(request).execute()
        if (!response.isSuccessful) {
            throw IOException("無法獲取限時動態資料（HTTP ${response.code}），可能無有效限動或登入已過期，請重新登入。")
        }

        val body = response.body?.string().orEmpty()
        val json = JSONObject(body)

        val reelsObj = json.optJSONObject("reels")
        val reelData = if (reelsObj != null && reelsObj.has(reelId)) {
            reelsObj.getJSONObject(reelId)
        } else {
            val reelsMedia = json.optJSONArray("reels_media")
            if (reelsMedia != null && reelsMedia.length() > 0) {
                reelsMedia.getJSONObject(0)
            } else {
                null
            }
        } ?: throw IOException("該用戶目前沒有有效的限時動態，或限時動態已過期。")

        val author = reelData.optJSONObject("user")?.optString("username", fallbackAuthor) ?: fallbackAuthor
        val rawItems = reelData.optJSONArray("items") ?: JSONArray()

        if (rawItems.length() == 0) {
            throw IOException("該用戶目前沒有任何限時動態。")
        }

        val mediaItems = mutableListOf<MediaItem>()
        var matchedSpecificStory = false

        for (i in 0 until rawItems.length()) {
            val item = rawItems.getJSONObject(i)
            val itemId = item.optString("id", "")
            val pk = item.optString("pk", "")

            if (targetMediaId != null && (itemId.contains(targetMediaId) || pk == targetMediaId)) {
                matchedSpecificStory = true
            }

            val isVideo = item.optInt("media_type") == 2 || item.has("video_versions")
            val downloadUrl: String
            val previewUrl: String

            if (isVideo && item.has("video_versions")) {
                val videos = item.getJSONArray("video_versions")
                // True MP4 video stream
                downloadUrl = videos.getJSONObject(0).getString("url")
                previewUrl = item.optJSONObject("image_versions2")
                    ?.optJSONArray("candidates")
                    ?.optJSONObject(0)?.optString("url") ?: downloadUrl
            } else {
                // True uncropped original image
                val images = item.getJSONObject("image_versions2").getJSONArray("candidates")
                downloadUrl = images.getJSONObject(0).getString("url")
                previewUrl = downloadUrl
            }

            mediaItems.add(
                MediaItem(
                    id = if (pk.isNotEmpty()) pk else itemId,
                    downloadUrl = downloadUrl,
                    previewThumbnailUrl = previewUrl,
                    isVideo = isVideo,
                    author = author,
                    index = i + 1,
                    totalCount = rawItems.length()
                )
            )
        }

        val resultList = if (targetMediaId != null && matchedSpecificStory) {
            mediaItems.filter { it.id.contains(targetMediaId) }
        } else {
            mediaItems
        }

        return ExtractionResult(
            author = author,
            caption = "限時動態 (${resultList.size} 項)",
            items = resultList,
            sourceUrl = sourceUrl
        )
    }
}
