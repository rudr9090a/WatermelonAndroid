package com.hydrogenlabs.watermelon_android.playback

import android.webkit.CookieManager
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl

/**
 * Exposes the WebView's cookies to OkHttp so native playback uses the same
 * `session` / `username` credentials as the web app. Cookies are read on every
 * request, which means a session refreshed inside the WebView is picked up
 * without restarting playback.
 */
class WebViewCookieJar : CookieJar {
    private val cookieManager: CookieManager
        get() = CookieManager.getInstance()

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        val manager = cookieManager
        cookies.forEach { cookie ->
            manager.setCookie(url.toString(), "${cookie.name}=${cookie.value}")
        }
        manager.flush()
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val header = cookieManager.getCookie(url.toString()).orEmpty()
        if (header.isBlank()) return emptyList()
        return header.split(';')
            .mapNotNull { entry -> Cookie.parse(url, entry.trim()) }
    }
}
