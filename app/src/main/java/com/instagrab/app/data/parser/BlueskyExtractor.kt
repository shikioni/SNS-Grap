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

class BlueskyExtractor(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()
) {
    companion object {
        private val BSKY_POST_PATTERN = Pattern.compile("profile/([^/]+)/post/([^/?#]+)")
        private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"
    }

    suspend fun extract(rawUrl: String): ExtractionResult = withContext(Dispatchers.IO) {
        val cleanUrl = rawUrl.trim()
        val matcher = BSKY_POST_PATTERN.matcher(cleanUrl)
        if (!matcher.find()) {
            throw IllegalArgumentException("無法識別 Bluesky 貼文網址，格式應為：https://bsky.app/profile/<使用者>/post/<ID>")
        }

        val handleOrDid = matcher.group(1) ?: throw IllegalArgumentException("無法解析 Bluesky 使用者資訊")
        val rkey = matcher.group(2) ?: throw IllegalArgumentException("無法解析 Bluesky 貼文 ID")

        // 1. Resolve handle to DID if needed
        val did = if (handleOrDid.startsWith("did:")) {
            handleOrDid
        } else {
            resolveHandle(handleOrDid)
        }

        // 2. Fetch Post Thread via Public XRPC
        val threadUrl = "https://public.api.bsky.app/xrpc/app.bsky.feed.getPostThread?uri=at://$did/app.bsky.feed.post/$rkey&depth=0"
        val request = Request.Builder()
            .url(threadUrl)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/json")
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IllegalArgumentException("無法取得 Bluesky 貼文資訊 (HTTP ${response.code})")
            }
            val body = response.body?.string() ?: throw IllegalArgumentException("貼文回應內容為空")
            val json = JSONObject(body)
            val thread = json.optJSONObject("thread") ?: throw IllegalArgumentException("找不到該 Bluesky 貼文")
            val post = thread.optJSONObject("post") ?: throw IllegalArgumentException("找不到貼文內容或貼文為私密")

            val author = post.optJSONObject("author")?.optString("handle") ?: handleOrDid
            val record = post.optJSONObject("record")
            val caption = record?.optString("text", "")

            // Extract video info
            val (cid, thumbUrl, width, height) = parseVideoDetails(post, record, did)

            if (cid.isNullOrBlank()) {
                throw IllegalArgumentException("此 Bluesky 貼文不包含影片（目前僅支援影片下載）")
            }

            // Direct blob download URL from Bluesky PDS / CDN
            val directDownloadUrl = "https://bsky.social/xrpc/com.atproto.sync.getBlob?did=$did&cid=$cid"
            val previewUrl = thumbUrl ?: "https://video.bsky.app/watch/$did/$cid/thumbnail.jpg"

            val mediaItem = MediaItem(
                id = rkey,
                downloadUrl = directDownloadUrl,
                previewThumbnailUrl = previewUrl,
                isVideo = true,
                width = width,
                height = height,
                author = author,
                index = 1,
                totalCount = 1,
                platform = Platform.BLUESKY
            )

            return@withContext ExtractionResult(
                platform = Platform.BLUESKY,
                author = author,
                caption = caption,
                items = listOf(mediaItem),
                sourceUrl = cleanUrl
            )
        }
    }

    private fun resolveHandle(handle: String): String {
        val url = "https://public.api.bsky.app/xrpc/com.atproto.identity.resolveHandle?handle=$handle"
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/json")
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IllegalArgumentException("無法解析 Bluesky 帳號：$handle")
            }
            val body = response.body?.string() ?: throw IllegalArgumentException("解析帳號回應為空")
            val json = JSONObject(body)
            val did = json.optString("did")
            if (did.isBlank()) {
                throw IllegalArgumentException("無法取得該帳號的 DID")
            }
            return did
        }
    }

    private data class VideoDetails(
        val cid: String?,
        val thumbUrl: String?,
        val width: Int,
        val height: Int
    )

    private fun parseVideoDetails(post: JSONObject, record: JSONObject?, did: String): VideoDetails {
        var cid: String? = null
        var thumbUrl: String? = null
        var width = 0
        var height = 0

        // Check embed inside record (app.bsky.embed.video)
        val recordEmbed = record?.optJSONObject("embed")
        if (recordEmbed != null && recordEmbed.optString("\$type") == "app.bsky.embed.video") {
            val videoBlob = recordEmbed.optJSONObject("video")
            val refObj = videoBlob?.optJSONObject("ref")
            cid = refObj?.optString("\$link")

            val aspect = recordEmbed.optJSONObject("aspectRatio")
            if (aspect != null) {
                width = aspect.optInt("width", 0)
                height = aspect.optInt("height", 0)
            }
        }

        // Check embed inside post view (app.bsky.embed.video#view)
        val postEmbed = post.optJSONObject("embed")
        if (postEmbed != null) {
            val type = postEmbed.optString("\$type")
            if (type.contains("video")) {
                if (cid.isNullOrBlank()) {
                    cid = postEmbed.optString("cid")
                }
                thumbUrl = postEmbed.optString("thumbnail")
                val aspect = postEmbed.optJSONObject("aspectRatio")
                if (aspect != null && width == 0) {
                    width = aspect.optInt("width", 0)
                    height = aspect.optInt("height", 0)
                }
            }
        }

        return VideoDetails(cid, thumbUrl, width, height)
    }
}
