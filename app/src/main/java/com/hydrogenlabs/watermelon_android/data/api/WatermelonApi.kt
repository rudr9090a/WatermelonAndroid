package com.hydrogenlabs.watermelon_android.data.api

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST

@Serializable data class LoginRequest(val username: String, val password: String)
@Serializable data class LoginResponse(val success: Boolean = false, val session: String? = null, val aoi: String? = null)
@Serializable data class DashboardResponse(val songs: List<String> = emptyList())
@Serializable data class SongMetadata(val video_id: String? = null, val title: String? = null, val artist: String? = null, val album: String? = null, val duration: Double? = null, val filename: String? = null)
@Serializable data class PlaylistSong(val id: String)
@Serializable data class Playlist(val id: String, val name: String, val songs: List<PlaylistSong> = emptyList())
@Serializable data class PlaylistsResponse(val playlists: List<Playlist> = emptyList())
@Serializable data class LikeRequest(val username: String, val songid: String)
@Serializable data class LikedSongsResponse(val liked_song_list: List<JsonElement> = emptyList())
@Serializable data class ErrorResponse(val error: String? = null, val detail: String? = null)

interface WatermelonApi {
    @POST("api/auth/login") suspend fun login(@Body request: LoginRequest): LoginResponse
    @POST("api/auth/logout") suspend fun logout(): Unit
    @GET("api/dashboard") suspend fun dashboard(): DashboardResponse
    @GET("api/get_song_data/{songId}") suspend fun songMetadata(@retrofit2.http.Path("songId") songId: String): SongMetadata
    @GET("api/{username}/get_liked_songs") suspend fun likedSongs(@retrofit2.http.Path("username") username: String): LikedSongsResponse
    @GET("api/playlists") suspend fun playlists(): PlaylistsResponse
    @POST("api/{username}/like_song") suspend fun likeSong(@retrofit2.http.Path("username") username: String, @Body request: LikeRequest): Unit
    @retrofit2.http.HTTP(method = "DELETE", path = "api/{username}/unlike_song", hasBody = true)
    suspend fun unlikeSong(@retrofit2.http.Path("username") username: String, @Body request: LikeRequest): Unit
}
