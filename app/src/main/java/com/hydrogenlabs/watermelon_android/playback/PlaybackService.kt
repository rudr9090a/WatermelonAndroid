package com.hydrogenlabs.watermelon_android.playback

import android.app.PendingIntent
import android.content.Intent
import android.util.Log
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.datasource.DataSourceBitmapLoader
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.google.common.util.concurrent.MoreExecutors
import com.hydrogenlabs.watermelon_android.MainActivity
import okhttp3.OkHttpClient
import java.util.concurrent.Executors

/**
 * Owns the single ExoPlayer instance and its MediaSession. Because playback
 * lives in a foreground service, the system can show the media notification
 * and route lock screen, headset and Bluetooth controls to it even while the
 * WebView is not visible.
 */
class PlaybackService : MediaSessionService() {
    private var mediaSession: MediaSession? = null
    private val artworkExecutor = Executors.newSingleThreadExecutor()

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "PlaybackService created")

        val httpClient = OkHttpClient.Builder()
            .cookieJar(WebViewCookieJar())
            .build()
        val dataSourceFactory = OkHttpDataSource.Factory(httpClient)

        val player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .setUsage(C.USAGE_MEDIA)
                    .build(),
                /* handleAudioFocus = */ true,
            )
            .build()
        player.setWakeMode(C.WAKE_MODE_NETWORK)
        player.setHandleAudioBecomingNoisy(true)

        val sessionActivity = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val bitmapLoader = DataSourceBitmapLoader(
            MoreExecutors.listeningDecorator(artworkExecutor),
            OkHttpDataSource.Factory(httpClient),
        )

        mediaSession = MediaSession.Builder(this, player)
            .setSessionActivity(sessionActivity)
            .setBitmapLoader(bitmapLoader)
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    override fun onDestroy() {
        Log.d(TAG, "PlaybackService destroyed")
        mediaSession?.run {
            player.release()
            release()
        }
        mediaSession = null
        artworkExecutor.shutdown()
        super.onDestroy()
    }

    private companion object {
        const val TAG = "WatermelonPlayback"
    }
}
