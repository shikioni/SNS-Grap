package com.instagrab.app

import com.instagrab.app.data.model.MediaItem
import com.instagrab.app.data.model.Platform
import com.instagrab.app.data.parser.BlueskyExtractor
import com.instagrab.app.data.parser.ThreadsExtractor
import com.instagrab.app.data.parser.TwitterExtractor
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.util.regex.Pattern

class SNSExtractorsTest {

    @Test
    fun testPlatformDetection() {
        assertEquals(
            Platform.INSTAGRAM,
            Platform.detect("https://www.instagram.com/reel/C-123456789/?igsh=abc")
        )
        assertEquals(
            Platform.TWITTER,
            Platform.detect("https://x.com/user/status/1840000000000000000")
        )
        assertEquals(
            Platform.TWITTER,
            Platform.detect("https://twitter.com/NASA/status/12345678")
        )
        assertEquals(
            Platform.TIKTOK,
            Platform.detect("https://www.tiktok.com/@cookierun_dev/video/7039716639834656002")
        )
        assertEquals(
            Platform.TIKTOK,
            Platform.detect("https://vt.tiktok.com/ZSxxx/")
        )
        assertEquals(
            Platform.BLUESKY,
            Platform.detect("https://bsky.app/profile/bsky.app/post/3l3vgf77uco2g")
        )
        assertEquals(
            Platform.THREADS,
            Platform.detect("https://www.threads.net/@zuck/post/Cx123456789")
        )
        assertEquals(
            Platform.THREADS,
            Platform.detect("https://threads.net/t/Cx123456789")
        )
        assertEquals(
            Platform.THREADS,
            Platform.detect("www.threads.com/share/7Zabc123")
        )
        assertEquals(
            Platform.THREADS,
            Platform.detect("https://www.threads.com/share/7Zabc123")
        )
        assertEquals(
            Platform.FACEBOOK,
            Platform.detect("https://www.facebook.com/reel/1234567890")
        )
        assertEquals(
            Platform.FACEBOOK,
            Platform.detect("https://www.facebook.com/watch/?v=1234567890")
        )
        assertEquals(
            Platform.FACEBOOK,
            Platform.detect("https://fb.watch/1234567890/")
        )
        assertEquals(
            Platform.DOUYIN,
            Platform.detect("https://v.douyin.com/R7_Atq5Ljhk/")
        )
        assertEquals(
            Platform.DOUYIN,
            Platform.detect("7.10 复制打开抖音，看看【《高妹来了》足模综艺预热的作品】# 广东小妈 # 习惯了微醺入睡  https://v.douyin.com/R7_Atq5Ljhk/ j@C.hB WMW:/ 05/07 :7pm")
        )
        assertEquals(
            Platform.DOUYIN,
            Platform.detect("https://www.douyin.com/video/7698754950691913329")
        )
        assertEquals(
            Platform.UNKNOWN,
            Platform.detect("https://example.com/some/path")
        )
    }

    @Test
    fun testTwitterRegexAndFilename() {
        val pattern = Pattern.compile("(?:status|statuses)/([0-9]+)")
        val url = "https://x.com/jack/status/20?s=20"
        val matcher = pattern.matcher(url)
        assertTrue(matcher.find())
        assertEquals("20", matcher.group(1))

        val item = MediaItem(
            id = "20",
            downloadUrl = "https://video.twimg.com/test.mp4",
            previewThumbnailUrl = "https://pbs.twimg.com/thumb.jpg",
            isVideo = true,
            author = "jack",
            platform = Platform.TWITTER
        )
        assertEquals("X_jack_20.mp4", item.suggestedFilename)
    }

    @Test
    fun testBlueskyRegexAndFilename() {
        val pattern = Pattern.compile("profile/([^/]+)/post/([^/?#]+)")
        val url = "https://bsky.app/profile/bsky.app/post/3l3vgf77uco2g"
        val matcher = pattern.matcher(url)
        assertTrue(matcher.find())
        assertEquals("bsky.app", matcher.group(1))
        assertEquals("3l3vgf77uco2g", matcher.group(2))

        val item = MediaItem(
            id = "3l3vgf77uco2g",
            downloadUrl = "https://bsky.social/xrpc/com.atproto.sync.getBlob?did=did:plc:123&cid=cid456",
            previewThumbnailUrl = "https://video.bsky.app/thumb.jpg",
            isVideo = true,
            author = "bsky.app",
            platform = Platform.BLUESKY
        )
        assertEquals("Bsky_bsky.app_3l3vgf77uco2g.mp4", item.suggestedFilename)
    }

    @Test
    fun testThreadsRegexAndFilename() {
        val pattern = Pattern.compile("(?:post|t|share|s)/([A-Za-z0-9_-]+)")
        val url = "https://www.threads.net/@zuck/post/C-xxxxxxx?x=1"
        val matcher = pattern.matcher(url)
        assertTrue(matcher.find())
        assertEquals("C-xxxxxxx", matcher.group(1))

        val shareUrl = "www.threads.com/share/7Zabc123"
        val shareMatcher = pattern.matcher(shareUrl)
        assertTrue(shareMatcher.find())
        assertEquals("7Zabc123", shareMatcher.group(1))

        val item = MediaItem(
            id = "C-xxxxxxx",
            downloadUrl = "https://scontent.cdninstagram.com/v/t50.video.mp4",
            previewThumbnailUrl = "https://scontent.cdninstagram.com/thumb.jpg",
            isVideo = true,
            author = "zuck",
            platform = Platform.THREADS
        )
        assertEquals("Threads_zuck_C-xxxxxxx.mp4", item.suggestedFilename)
    }

    @Test
    fun testTikTokRegexAndFilename() {
        val pattern = Pattern.compile("(?:video|v)/([0-9]+)")
        val url = "https://www.tiktok.com/@cookierun_dev/video/7039716639834656002"
        val matcher = pattern.matcher(url)
        assertTrue(matcher.find())
        assertEquals("7039716639834656002", matcher.group(1))

        val item = MediaItem(
            id = "7039716639834656002",
            downloadUrl = "https://v16m.tiktokcdn-us.com/video.mp4",
            previewThumbnailUrl = "https://p16.tiktokcdn.com/cover.jpg",
            isVideo = true,
            author = "cookierun_dev",
            platform = Platform.TIKTOK
        )
        assertEquals("TikTok_cookierun_dev_7039716639834656002.mp4", item.suggestedFilename)
    }

    @Test
    fun testTikTokRealExtraction() = runBlocking {
        val extractor = com.instagrab.app.data.parser.TikTokExtractor()
        val result = extractor.extract("https://www.tiktok.com/@cookierun_dev/video/7039716639834656002")
        assertEquals(Platform.TIKTOK, result.platform)
        assertTrue(result.items.isNotEmpty())
        assertTrue(result.items[0].isVideo)
        println("TikTok download URL: ${result.items[0].downloadUrl}")
        assertTrue(result.items[0].downloadUrl.contains("http"))
        assertTrue(result.items[0].suggestedFilename.startsWith("TikTok_"))
    }

    @Test
    fun testBlueskyRealExtraction() = runBlocking {
        val extractor = BlueskyExtractor()
        // Official Bluesky video announcement post
        val result = extractor.extract("https://bsky.app/profile/bsky.app/post/3l3vgf77uco2g")
        assertEquals(Platform.BLUESKY, result.platform)
        assertEquals(1, result.items.size)
        assertTrue(result.items[0].isVideo)
        assertTrue(result.items[0].downloadUrl.contains("com.atproto.sync.getBlob"))
        assertTrue(result.items[0].suggestedFilename.startsWith("Bsky_"))
        assertTrue(result.items[0].suggestedFilename.endsWith(".mp4"))
    }

    @Test
    fun testTwitterRealExtraction() = runBlocking {
        val extractor = TwitterExtractor()
        // Max Olson Hurricane Ian surge video tweet
        val result = extractor.extract("https://x.com/MesoMax919/status/1575560063510810624")
        assertEquals(Platform.TWITTER, result.platform)
        assertTrue(result.items.isNotEmpty())
        assertTrue(result.items[0].isVideo)
        println("Twitter download URL: ${result.items[0].downloadUrl}")
        assertTrue(result.items[0].downloadUrl.contains(".mp4"))
    }

    @Test
    fun testFacebookRegexAndFilename() {
        val pattern = Pattern.compile("(?:videos/|reel/|watch/\\?v=|watch/|share/r/|share/v/|v=)([0-9]+)")
        val reelUrl = "https://www.facebook.com/reel/997648763333705"
        val matcher = pattern.matcher(reelUrl)
        assertTrue(matcher.find())
        assertEquals("997648763333705", matcher.group(1))

        val watchUrl = "https://www.facebook.com/watch?v=10153231379946729"
        val m2 = pattern.matcher(watchUrl)
        assertTrue(m2.find())
        assertEquals("10153231379946729", m2.group(1))

        val item = MediaItem(
            id = "997648763333705",
            downloadUrl = "https://video.fbcdn.net/test.mp4",
            previewThumbnailUrl = "https://scontent.fbcdn.net/thumb.jpg",
            isVideo = true,
            author = "BBCNews",
            platform = Platform.FACEBOOK
        )
        assertEquals("FB_BBCNews_997648763333705.mp4", item.suggestedFilename)
    }

    @Test
    fun testDouyinExtractionAndFilename() {
        val shareText = "7.10 复制打开抖音，看看【《高妹来了》足模综艺预热的作品】# 广东小妈 # 习惯了微醺入睡  https://v.douyin.com/R7_Atq5Ljhk/ j@C.hB WMW:/ 05/07 :7pm"
        val extractedUrl = Platform.extractUrl(shareText)
        assertEquals("https://v.douyin.com/R7_Atq5Ljhk/", extractedUrl)

        val idPattern = Pattern.compile("(?:video|note)/([0-9]+)")
        val canonicalUrl = "https://www.iesdouyin.com/share/video/7698754950691913329/?region=TW"
        val matcher = idPattern.matcher(canonicalUrl)
        assertTrue(matcher.find())
        assertEquals("7698754950691913329", matcher.group(1))

        val item = MediaItem(
            id = "7698754950691913329",
            downloadUrl = "https://v5-se-gddgtc-default.365yg.com/test.mp4",
            previewThumbnailUrl = "https://p5.douyinpic.com/thumb.jpg",
            isVideo = true,
            author = "高妹来了",
            platform = Platform.DOUYIN
        )
        assertEquals("DY_高妹来了_7698754950691913329.mp4", item.suggestedFilename)
    }

    @Test
    fun testThreadsRealExtractionWithShareUrl() = runBlocking {
        val extractor = ThreadsExtractor()
        val result = extractor.extract("https://www.threads.com/share/BAX93Xhcyd/")
        assertEquals(Platform.THREADS, result.platform)
        assertEquals("ruru.___s", result.author)
        assertTrue(result.items.isNotEmpty())
        val item = result.items[0]
        assertTrue(item.isVideo)
        assertEquals("De3HohzH5Iu", item.id)
        assertTrue(item.downloadUrl.contains(".mp4"))
        assertTrue(item.suggestedFilename.startsWith("Threads_ruru.___s_De3HohzH5Iu"))
        println("Threads extracted author: ${result.author}")
        println("Threads extracted caption: ${result.caption}")
        println("Threads extracted downloadUrl: ${item.downloadUrl}")
        println("Threads suggested filename: ${item.suggestedFilename}")
    }

    @Test
    fun testDouyinLiveExtraction() = runBlocking {
        try {
            val extractor = com.instagrab.app.data.parser.DouyinExtractor()
            val shareText = "7.10 复制打开抖音，看看【《高妹来了》足模综艺预热的作品】# 广东小妈 # 习惯了微醺入睡  https://v.douyin.com/R7_Atq5Ljhk/ j@C.hB WMW:/ 05/07 :7pm"
            val result = extractor.extract(shareText)
            assertEquals(Platform.DOUYIN, result.platform)
            assertTrue(result.items.isNotEmpty())
            assertTrue(result.items[0].isVideo)
            assertTrue(result.items[0].downloadUrl.startsWith("http"))
            println("Douyin live download URL: ${result.items[0].downloadUrl}")
        } catch (e: Exception) {
            println("Douyin live test skipped if network unavailable: $e")
        }
    }
}
