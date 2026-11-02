package com.hydrogenlabs.watermelon_android.data.repository

import com.hydrogenlabs.watermelon_android.data.api.LoginRequest
import com.hydrogenlabs.watermelon_android.data.api.LikeRequest
import com.hydrogenlabs.watermelon_android.data.api.Playlist
import com.hydrogenlabs.watermelon_android.data.api.SessionCookieJar
import com.hydrogenlabs.watermelon_android.data.api.WatermelonApi
import com.hydrogenlabs.watermelon_android.data.api.createApi
import com.hydrogenlabs.watermelon_android.data.api.createHttpClient
import com.hydrogenlabs.watermelon_android.data.api.ApiFailure
import com.hydrogenlabs.watermelon_android.data.api.toApiFailure
import okhttp3.OkHttpClient
import okhttp3.HttpUrl.Companion.toHttpUrl
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull

class WatermelonRepository {
    data class Song(val id: String, val title: String, val artist: String?, val album: String?, val duration: Double?)
    private var cookieJar = SessionCookieJar()
    private var api: WatermelonApi? = null
    var sessionToken: String = ""
        private set

    fun connect(url: String) { baseUrl = url; cookieJar = SessionCookieJar(); api = createApi(url, cookieJar) }
    fun playbackClient(): OkHttpClient = createHttpClient(cookieJar)
    fun audioUrl(songId: String): String = "${baseUrl.trimEnd('/')}/api/music/${songId}/audio"
    suspend fun login(baseUrl: String, username: String, password: String) = request { requireApi().login(LoginRequest(username, password)) }.also {
        val packed = it.session ?: error("The server did not return a session")
        val session = packed.substringBeforeLast('=')
        sessionToken = session
        cookieJar.setSession(baseUrl.toHttpUrl(), session, username)
    }
    fun restoreSession(session: String, username: String) {
        sessionToken = session
        cookieJar.restoreSession(baseUrl.toHttpUrl(), session, username)
    }
    suspend fun dashboard() = request { requireApi().dashboard() }
    suspend fun songMetadata(songId: String): Song {
        val metadata = runCatching { request { requireApi().songMetadata(songId) } }.getOrNull()
        return Song(songId, metadata?.title ?: songId, metadata?.artist, metadata?.album, metadata?.duration)
    }
    suspend fun likedSongs(username: String): List<String> {
        val tuple = request { requireApi().likedSongs(username) }.liked_song_list
        val songs = tuple.getOrNull(1) as? JsonArray ?: return emptyList()
        return songs.mapNotNull { it.jsonPrimitive.contentOrNull }
    }
    suspend fun playlists(): List<Playlist> = request { requireApi().playlists() }.playlists
    suspend fun like(username: String, song: String) = request { requireApi().likeSong(username, LikeRequest(username, song)) }
    suspend fun unlike(username: String, song: String) = request { requireApi().unlikeSong(username, LikeRequest(username, song)) }
    suspend fun logout() { request { api?.logout() }; cookieJar.clear() }
    private fun requireApi() = api ?: error("Server is not configured")
    private suspend fun <T> request(block: suspend () -> T): T = try { block() } catch (error: Throwable) { throw error.toApiFailure() }
    private var baseUrl: String = ""
}
