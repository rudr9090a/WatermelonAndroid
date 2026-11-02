package com.hydrogenlabs.watermelon_android.data.api

import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType

class SessionCookieJar : CookieJar {
    private val cookies = mutableListOf<Cookie>()
    override fun saveFromResponse(url: HttpUrl, received: List<Cookie>) {
        cookies.removeAll { old -> received.any { it.name == old.name } }
        cookies += received
    }
    override fun loadForRequest(url: HttpUrl): List<Cookie> = cookies.filter { it.matches(url) }
    fun setSession(url: HttpUrl, session: String, username: String) {
        cookies.removeAll { it.name == "session" || it.name == "username" }
        val sessionCookie = Cookie.Builder().domain(url.host).path("/").name("session").value(session)
        val usernameCookie = Cookie.Builder().domain(url.host).path("/").name("username").value(username)
        if (url.isHttps) { sessionCookie.secure(); usernameCookie.secure() }
        cookies += sessionCookie.build()
        cookies += usernameCookie.build()
    }
    fun restoreSession(url: HttpUrl, session: String, username: String) = setSession(url, session, username)
    fun clear() = cookies.clear()
}

fun createHttpClient(cookieJar: SessionCookieJar): OkHttpClient = OkHttpClient.Builder()
        .cookieJar(cookieJar)
        .addInterceptor { chain ->
            val request = chain.request().newBuilder()
                .header("X-Watermelon-Client", "android")
                .header("X-Watermelon-Client-Version", "0.1.0")
                .build()
            chain.proceed(request)
        }
        .build()

fun createApi(baseUrl: String, cookieJar: SessionCookieJar): WatermelonApi {
    val normalized = if (baseUrl.endsWith('/')) baseUrl else "$baseUrl/"
    val client = createHttpClient(cookieJar)
    return Retrofit.Builder()
        .baseUrl(normalized)
        .client(client)
        .addConverterFactory(Json { ignoreUnknownKeys = true }.asConverterFactory("application/json".toMediaType()))
        .build()
        .create(WatermelonApi::class.java)
}
