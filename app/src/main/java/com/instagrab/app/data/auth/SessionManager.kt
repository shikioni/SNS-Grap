package com.instagrab.app.data.auth

import android.content.Context
import android.content.SharedPreferences
import android.webkit.CookieManager

/**
 * Manages Instagram session cookies.
 * 
 * SECURITY GUARANTEE:
 * 1. The application NEVER intercepts, requests, or stores user passwords.
 * 2. Login is performed strictly inside Meta's official Instagram Web page over HTTPS.
 * 3. Once authenticated by Meta, session cookies (e.g., sessionid, ds_user_id) are retrieved
 *    from Android's standard CookieManager and saved ONLY inside this app's private sandbox.
 * 4. Cookies are never transmitted to any third-party server, strictly communicating only with instagram.com.
 */
open class SessionManager(private val context: Context? = null) {

    private val prefs: SharedPreferences? = context?.getSharedPreferences(
        PREFS_NAME,
        Context.MODE_PRIVATE
    )

    companion object {
        private const val PREFS_NAME = "ig_session_prefs"
        private const val KEY_COOKIES = "ig_cookies"
        private const val KEY_USER_ID = "ig_user_id"
        private const val KEY_USERNAME = "ig_username"
        const val IG_LOGIN_URL = "https://www.instagram.com/accounts/login/"
    }

    /**
     * Extracts and saves cookies from CookieManager for instagram.com
     */
    open fun syncFromCookieManager(): Boolean {
        val cookieManager = CookieManager.getInstance()
        val cookies = cookieManager.getCookie("https://www.instagram.com") ?: return false
        
        if (cookies.contains("sessionid")) {
            val userId = extractCookieValue(cookies, "ds_user_id")
            prefs?.edit()?.apply {
                putString(KEY_COOKIES, cookies)
                putString(KEY_USER_ID, userId)
                apply()
            }
            return true
        }
        return false
    }

    /**
     * Returns true if a valid sessionid cookie is present.
     */
    open fun isLoggedIn(): Boolean {
        val cookies = getCookieHeader()
        return cookies.contains("sessionid=")
    }

    /**
     * Returns the cookie string to be attached to OkHttp request headers.
     */
    open fun getCookieHeader(): String {
        val saved = prefs?.getString(KEY_COOKIES, "") ?: ""
        if (saved.isNotEmpty()) return saved

        // Fallback to CookieManager if available
        val webViewCookies = CookieManager.getInstance().getCookie("https://www.instagram.com")
        return webViewCookies ?: ""
    }

    fun getUserId(): String? {
        return prefs?.getString(KEY_USER_ID, null)
    }

    fun getUsername(): String? {
        return prefs?.getString(KEY_USERNAME, null)
    }

    fun setUsername(username: String) {
        prefs?.edit()?.putString(KEY_USERNAME, username)?.apply()
    }

    /**
     * Clears stored cookies and logs out from WebView
     */
    fun clearSession() {
        prefs?.edit()?.clear()?.apply()
        val cookieManager = CookieManager.getInstance()
        cookieManager.removeAllCookies(null)
        cookieManager.flush()
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
}
