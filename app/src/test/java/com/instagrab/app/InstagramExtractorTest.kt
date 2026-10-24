package com.instagrab.app

import com.instagrab.app.data.model.MediaItem
import com.instagrab.app.data.parser.InstagramExtractor
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.regex.Pattern

class InstagramExtractorTest {

    @Test
    fun testShortcodeToMediaId() {
        val extractor = InstagramExtractor(FakeSessionManager())
        // CxOWiQNP2MO -> 3192588296739840782
        val mediaId = extractor.shortcodeToMediaId("CxOWiQNP2MO")
        assertEquals("3192588296739840782", mediaId)

        val id2 = extractor.shortcodeToMediaId("B-0a_123")
        assertTrue(id2.toLong() > 0)
    }

    @Test
    fun testStoryUrlRegexWithMediaIdAndQueryParams() {
        val pattern = Pattern.compile("instagram\\.com/stories/([^/?#&]+)(?:/([0-9]+))?")
        
        // Typical share link copied from Instagram App
        val url = "https://www.instagram.com/stories/taylorswift/3482348923489234892/?utm_source=ig_story_item_share&igsh=MTRoY3V3"
        val matcher = pattern.matcher(url)
        
        assertTrue(matcher.find())
        assertEquals("taylorswift", matcher.group(1))
        assertEquals("3482348923489234892", matcher.group(2))
    }

    @Test
    fun testStoryUrlRegexWithoutMediaId() {
        val pattern = Pattern.compile("instagram\\.com/stories/([^/?#&]+)(?:/([0-9]+))?")
        
        val url = "https://www.instagram.com/stories/cristiano/?utm_source=ig_story"
        val matcher = pattern.matcher(url)
        
        assertTrue(matcher.find())
        assertEquals("cristiano", matcher.group(1))
        assertNull(matcher.group(2))
    }

    @Test
    fun testParseVideoPostJson() {
        val extractor = InstagramExtractor(FakeSessionManager())
        val mockJson = JSONObject("""
            {
                "items": [{
                    "id": "3192588296739840782",
                    "user": { "username": "taylorswift" },
                    "caption": { "text": "The Eras Tour" },
                    "media_type": 2,
                    "video_versions": [
                        { "url": "https://video.cdninstagram.com/v/t50.2886-16/test.mp4", "width": 1080, "height": 1920 }
                    ],
                    "image_versions2": {
                        "candidates": [
                            { "url": "https://scontent.cdninstagram.com/v/thumb.jpg", "width": 1080, "height": 1920 }
                        ]
                    }
                }]
            }
        """.trimIndent())

        val result = extractor.parsePostJson(mockJson, "CxOWiQNP2MO", "https://instagram.com/reel/CxOWiQNP2MO")
        assertNotNull(result)
        assertEquals("taylorswift", result!!.author)
        assertEquals(1, result.items.size)

        val item = result.items[0]
        assertTrue("Must be marked as video", item.isVideo)
        assertTrue("Download URL must be the real mp4", item.downloadUrl.endsWith(".mp4"))
        assertTrue("Suggested filename must end with .mp4", item.suggestedFilename.endsWith(".mp4"))
    }

    @Test
    fun testParseUncroppedPhotoPostJson() {
        val extractor = InstagramExtractor(FakeSessionManager())
        val mockJson = JSONObject("""
            {
                "items": [{
                    "id": "12345",
                    "user": { "username": "photographer" },
                    "caption": { "text": "Portrait" },
                    "media_type": 1,
                    "image_versions2": {
                        "candidates": [
                            { "url": "https://scontent.cdninstagram.com/v/original_1080x1350.jpg", "width": 1080, "height": 1350 },
                            { "url": "https://scontent.cdninstagram.com/v/cropped_640x640.jpg", "width": 640, "height": 640 }
                        ]
                    }
                }]
            }
        """.trimIndent())

        val result = extractor.parsePostJson(mockJson, "ABCDE", "https://instagram.com/p/ABCDE")
        assertNotNull(result)
        val item = result!!.items[0]
        assertFalse("Must NOT be marked as video", item.isVideo)
        assertEquals("https://scontent.cdninstagram.com/v/original_1080x1350.jpg", item.downloadUrl)
        assertTrue("Filename must end with .jpg", item.suggestedFilename.endsWith(".jpg"))
    }

    @Test
    fun testParseCarouselPostJson() {
        val extractor = InstagramExtractor(FakeSessionManager())
        val mockJson = JSONObject("""
            {
                "items": [{
                    "id": "99999",
                    "user": { "username": "traveler" },
                    "carousel_media": [
                        {
                            "id": "c1",
                            "media_type": 1,
                            "image_versions2": {
                                "candidates": [{ "url": "https://scontent.cdninstagram.com/c1.jpg" }]
                            }
                        },
                        {
                            "id": "c2",
                            "media_type": 2,
                            "video_versions": [{ "url": "https://video.cdninstagram.com/c2.mp4" }],
                            "image_versions2": {
                                "candidates": [{ "url": "https://scontent.cdninstagram.com/c2_thumb.jpg" }]
                            }
                        }
                    ]
                }]
            }
        """.trimIndent())

        val result = extractor.parsePostJson(mockJson, "CAROUSEL1", "https://instagram.com/p/CAROUSEL1")
        assertNotNull(result)
        assertEquals(2, result!!.items.size)

        val item1 = result.items[0]
        assertFalse(item1.isVideo)
        assertEquals("https://scontent.cdninstagram.com/c1.jpg", item1.downloadUrl)
        assertEquals("IG_traveler_c1_1of2.jpg", item1.suggestedFilename)

        val item2 = result.items[1]
        assertTrue(item2.isVideo)
        assertEquals("https://video.cdninstagram.com/c2.mp4", item2.downloadUrl)
        assertEquals("IG_traveler_c2_2of2.mp4", item2.suggestedFilename)
    }

    // Helper stub for unit tests
    private class FakeSessionManager : com.instagrab.app.data.auth.SessionManager() {
        override fun isLoggedIn(): Boolean = true
        override fun getCookieHeader(): String = "sessionid=test_session; ds_user_id=12345; csrftoken=abcde"
    }
}
