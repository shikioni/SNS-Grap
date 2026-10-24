package com.instagrab.app.data.model

enum class MediaType {
    PHOTO,
    VIDEO
}

enum class Platform(
    val id: String,
    val displayName: String,
    val iconEmoji: String,
    val prefix: String
) {
    INSTAGRAM("instagram", "Instagram", "📷", "IG"),
    TWITTER("twitter", "X (Twitter)", "🐦", "X"),
    TIKTOK("tiktok", "TikTok", "🎵", "TikTok"),
    BLUESKY("bluesky", "Bluesky", "🦋", "Bsky"),
    THREADS("threads", "Threads", "🧵", "Threads"),
    FACEBOOK("facebook", "Facebook", "📘", "FB"),
    DOUYIN("douyin", "抖音 (陸抖)", "🪕", "DY"),
    UNKNOWN("unknown", "未知平台", "🔗", "SNS");

    companion object {
        private val URL_REGEX = java.util.regex.Pattern.compile("https?://[^\\s<>\"']+")

        fun extractUrl(text: String): String {
            val matcher = URL_REGEX.matcher(text)
            return if (matcher.find()) {
                matcher.group(0)?.trim() ?: text.trim()
            } else {
                text.trim()
            }
        }

        fun detect(rawText: String): Platform {
            val cleanUrl = extractUrl(rawText).lowercase()
            return when {
                cleanUrl.contains("douyin.com") || cleanUrl.contains("iesdouyin.com") -> DOUYIN
                cleanUrl.contains("instagram.com") || cleanUrl.contains("instagr.am") -> INSTAGRAM
                cleanUrl.contains("twitter.com") || cleanUrl.contains("x.com") || cleanUrl.contains("t.co") -> TWITTER
                cleanUrl.contains("tiktok.com") -> TIKTOK
                cleanUrl.contains("bsky.app") || cleanUrl.contains("bsky.social") -> BLUESKY
                cleanUrl.contains("threads.net") || cleanUrl.contains("threads.com") -> THREADS
                cleanUrl.contains("facebook.com") || cleanUrl.contains("fb.watch") || cleanUrl.contains("fb.com") -> FACEBOOK
                else -> UNKNOWN
            }
        }
    }
}

data class MediaItem(
    val id: String,
    val downloadUrl: String,
    val previewThumbnailUrl: String,
    val isVideo: Boolean,
    val width: Int = 0,
    val height: Int = 0,
    val author: String = "",
    val index: Int = 1,
    val totalCount: Int = 1,
    val platform: Platform = Platform.INSTAGRAM
) {
    val suggestedFilename: String
        get() {
            val ext = if (isVideo) "mp4" else "jpg"
            val safeAuthor = if (author.isNotBlank()) "${author}_" else ""
            val indexPart = if (totalCount > 1) "_${index}of${totalCount}" else ""
            return "${platform.prefix}_${safeAuthor}${id}${indexPart}.$ext"
        }
}

data class ExtractionResult(
    val author: String,
    val caption: String?,
    val items: List<MediaItem>,
    val sourceUrl: String,
    val platform: Platform = Platform.INSTAGRAM
)
