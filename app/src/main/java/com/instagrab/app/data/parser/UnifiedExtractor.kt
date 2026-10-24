package com.instagrab.app.data.parser

import com.instagrab.app.data.auth.SessionManager
import com.instagrab.app.data.model.ExtractionResult
import com.instagrab.app.data.model.Platform

class UnifiedExtractor(
    private val sessionManager: SessionManager
) {
    val instagramExtractor = InstagramExtractor(sessionManager)
    val twitterExtractor = TwitterExtractor()
    val tiktokExtractor = TikTokExtractor()
    val blueskyExtractor = BlueskyExtractor()
    val threadsExtractor = ThreadsExtractor()
    val facebookExtractor = FacebookExtractor()
    val douyinExtractor = DouyinExtractor()

    suspend fun extract(rawUrl: String): ExtractionResult {
        val cleanUrl = Platform.extractUrl(rawUrl)
        if (cleanUrl.isEmpty()) {
            throw IllegalArgumentException("請輸入社群媒體網址")
        }

        val platform = Platform.detect(rawUrl)
        return when (platform) {
            Platform.INSTAGRAM -> instagramExtractor.extract(cleanUrl)
            Platform.TWITTER -> twitterExtractor.extract(cleanUrl)
            Platform.TIKTOK -> tiktokExtractor.extract(cleanUrl)
            Platform.BLUESKY -> blueskyExtractor.extract(cleanUrl)
            Platform.THREADS -> threadsExtractor.extract(cleanUrl)
            Platform.FACEBOOK -> facebookExtractor.extract(cleanUrl)
            Platform.DOUYIN -> douyinExtractor.extract(rawUrl)
            Platform.UNKNOWN -> {
                throw IllegalArgumentException("不支援的網址！\n請貼上 FB、陸抖 (抖音)、TikTok、IG、Threads 或 X (Twitter) 連結。")
            }
        }
    }

    fun detectPlatform(url: String): Platform = Platform.detect(url)
}
