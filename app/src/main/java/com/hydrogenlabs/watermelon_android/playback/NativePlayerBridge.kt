package com.hydrogenlabs.watermelon_android.playback

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import org.json.JSONArray
import org.json.JSONObject

/**
 * Bridges the WebView player UI to the [PlaybackService]'s MediaSession. The
 * page talks to this object through `window.AndroidPlayer`, and receives state
 * back through `window.__watermelonNativeState`.
 *
 * Every call is rejected unless the WebView is currently showing a page from
 * the configured Watermelon origin, so unrelated sites can never drive the
 * native player.
 */
class NativePlayerBridge(
    context: Context,
    private val webViewProvider: () -> WebView?,
    private val isAllowedUrl: (String?) -> Boolean,
) {
    private data class Track(
        val id: String,
        val title: String,
        val artist: String?,
        val album: String?,
        val durationMs: Long?,
        val audioUrl: String,
        val artUrl: String?,
    )

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val pending = ArrayDeque<(MediaController) -> Unit>()

    private var controller: MediaController? = null
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var storedTracks: List<Track> = emptyList()
    private var attached = false

    private val progressTicker = object : Runnable {
        override fun run() {
            val active = controller ?: return
            if (active.isPlaying) {
                pushState(active)
                mainHandler.postDelayed(this, PROGRESS_INTERVAL_MS)
            }
        }
    }

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            if (events.containsAny(
                    Player.EVENT_PLAYBACK_STATE_CHANGED,
                    Player.EVENT_IS_PLAYING_CHANGED,
                    Player.EVENT_MEDIA_ITEM_TRANSITION,
                    Player.EVENT_SHUFFLE_MODE_ENABLED_CHANGED,
                    Player.EVENT_REPEAT_MODE_CHANGED,
                    Player.EVENT_POSITION_DISCONTINUITY,
                    Player.EVENT_PLAYER_ERROR,
                    Player.EVENT_TIMELINE_CHANGED,
                )
            ) {
                if (player is MediaController) {
                    pushState(player)
                    restartTicker(player)
                }
            }
        }
    }

    fun attach() {
        if (attached) return
        attached = true
        val token = SessionToken(appContext, ComponentName(appContext, PlaybackService::class.java))
        val future = MediaController.Builder(appContext, token).buildAsync()
        controllerFuture = future
        future.addListener({
            val built = runCatching { future.get() }.getOrNull() ?: return@addListener
            controller = built
            built.addListener(listener)
            while (true) {
                val command = pending.removeFirstOrNull() ?: break
                command(built)
            }
            pushState(built)
            pushQueue(built)
            restartTicker(built)
        }, MoreExecutors.directExecutor())
    }

    fun detach() {
        attached = false
        mainHandler.removeCallbacks(progressTicker)
        pending.clear()
        controller?.removeListener(listener)
        controller = null
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controllerFuture = null
    }

    @JavascriptInterface
    fun setQueue(payload: String) {
        onMain {
            if (!allowed()) return@onMain
            val parsed = runCatching { JSONObject(payload) }.getOrNull() ?: return@onMain
            val array = parsed.optJSONArray("queue") ?: JSONArray()
            val tracks = (0 until array.length()).mapNotNull { index ->
                array.optJSONObject(index)?.let { entry ->
                    runCatching { parseTrack(entry) }.getOrNull()
                }
            }
            val startIndex = parsed.optInt("startIndex", 0)
                .coerceIn(0, (tracks.size - 1).coerceAtLeast(0))
            val autoplay = parsed.optBoolean("play", true)
            Log.d(TAG, "setQueue tracks=${tracks.size} startIndex=$startIndex autoplay=$autoplay")
            storedTracks = tracks
            withController { player ->
                val items = tracks.map { it.toMediaItem() }
                if (items.isEmpty()) {
                    player.pause()
                    player.clearMediaItems()
                    return@withController
                }
                player.setMediaItems(items, startIndex, 0L)
                player.prepare()
                if (autoplay) player.play()
            }
        }
    }

    @JavascriptInterface
    fun playIndex(index: Int) {
        onMain {
            if (!allowed()) return@onMain
            withController { player ->
                if (index in 0 until player.mediaItemCount) {
                    player.seekToDefaultPosition(index)
                    player.prepare()
                    player.play()
                }
            }
        }
    }

    @JavascriptInterface
    fun play() = onMain {
        if (!allowed()) return@onMain
        withController { it.play() }
    }

    @JavascriptInterface
    fun pause() = onMain {
        if (!allowed()) return@onMain
        withController { it.pause() }
    }

    @JavascriptInterface
    fun toggle() = onMain {
        if (!allowed()) return@onMain
        withController { if (it.isPlaying) it.pause() else it.play() }
    }

    @JavascriptInterface
    fun next() = onMain {
        if (!allowed()) return@onMain
        withController { if (it.hasNextMediaItem()) it.seekToNextMediaItem() }
    }

    @JavascriptInterface
    fun prev() = onMain {
        if (!allowed()) return@onMain
        withController { if (it.hasPreviousMediaItem()) it.seekToPreviousMediaItem() }
    }

    @JavascriptInterface
    fun seek(positionMs: Long) = onMain {
        if (!allowed()) return@onMain
        withController { it.seekTo(positionMs.coerceAtLeast(0L)) }
    }

    @JavascriptInterface
    fun setVolume(volume: Float) = onMain {
        if (!allowed()) return@onMain
        withController { it.volume = volume.coerceIn(0f, 1f) }
    }

    @JavascriptInterface
    fun setShuffle(enabled: Boolean) = onMain {
        if (!allowed()) return@onMain
        withController { it.shuffleModeEnabled = enabled }
    }

    @JavascriptInterface
    fun setRepeat(enabled: Boolean) = onMain {
        if (!allowed()) return@onMain
        withController {
            it.repeatMode = if (enabled) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
        }
    }

    @JavascriptInterface
    fun stop() = onMain {
        if (!allowed()) return@onMain
        storedTracks = emptyList()
        withController {
            it.pause()
            it.stop()
            it.clearMediaItems()
        }
    }

    @JavascriptInterface
    fun requestState() = onMain {
        if (!allowed()) return@onMain
        controller?.let { pushState(it) }
    }

    @JavascriptInterface
    fun requestQueue() = onMain {
        if (!allowed()) return@onMain
        controller?.let { pushQueue(it) }
    }

    private fun allowed(): Boolean = isAllowedUrl(webViewProvider()?.url)

    private fun onMain(block: () -> Unit) {
        mainHandler.post(block)
    }

    private fun withController(block: (MediaController) -> Unit) {
        val active = controller
        if (active != null) {
            block(active)
        } else {
            pending.addLast(block)
        }
    }

    private fun restartTicker(player: MediaController) {
        mainHandler.removeCallbacks(progressTicker)
        if (player.isPlaying) {
            mainHandler.postDelayed(progressTicker, PROGRESS_INTERVAL_MS)
        }
    }

    private fun pushState(player: MediaController) {
        val webView = webViewProvider() ?: return
        if (!isAllowedUrl(webView.url)) return
        webView.evaluateJavascript(
            "window.__watermelonNativeState && window.__watermelonNativeState(" +
                JSONObject.quote(buildStateJson(player)) + ")",
            null,
        )
    }

    private fun pushQueue(player: MediaController) {
        val webView = webViewProvider() ?: return
        if (!isAllowedUrl(webView.url)) return
        webView.evaluateJavascript(
            "window.__watermelonNativeQueue && window.__watermelonNativeQueue(" +
                JSONObject.quote(buildQueueJson(player)) + ")",
            null,
        )
    }

    private fun buildStateJson(player: MediaController): String {
        val status = when {
            player.playerError != null -> "error"
            player.playbackState == Player.STATE_BUFFERING -> "buffering"
            player.playbackState == Player.STATE_ENDED -> "ended"
            player.playbackState == Player.STATE_IDLE -> "idle"
            player.isPlaying -> "playing"
            else -> "paused"
        }
        val duration = player.duration
        val durationMs = if (duration == C.TIME_UNSET || duration <= 0L) 0L else duration
        return JSONObject()
            .put("currentId", player.currentMediaItem?.mediaId ?: JSONObject.NULL)
            .put("positionMs", player.currentPosition.coerceAtLeast(0L))
            .put("durationMs", durationMs)
            .put("status", status)
            .put("error", player.playerError?.errorCodeName ?: JSONObject.NULL)
            .put("shuffle", player.shuffleModeEnabled)
            .put("repeat", player.repeatMode == Player.REPEAT_MODE_ONE)
            .put("hasNext", player.hasNextMediaItem())
            .put("hasPrev", player.hasPreviousMediaItem())
            .toString()
    }

    private fun buildQueueJson(player: MediaController): String {
        val items = (0 until player.mediaItemCount).map { index ->
            val item = player.getMediaItemAt(index)
            val metadata = item.mediaMetadata
            JSONObject()
                .put("id", item.mediaId)
                .put("title", metadata.title?.toString() ?: item.mediaId)
                .put("artist", metadata.artist?.toString() ?: JSONObject.NULL)
                .put("album", metadata.albumTitle?.toString() ?: JSONObject.NULL)
                .put("durationMs", metadata.durationMs ?: JSONObject.NULL)
                .put("audioUrl", item.localConfiguration?.uri?.toString() ?: JSONObject.NULL)
                .put("artUrl", metadata.artworkUri?.toString() ?: JSONObject.NULL)
        }
        return JSONObject().put("queue", JSONArray(items)).toString()
    }

    private fun parseTrack(entry: JSONObject): Track {
        val id = entry.getString("id")
        return Track(
            id = id,
            title = entry.optString("title").ifBlank { id },
            artist = entry.optString("artist").ifBlank { null },
            album = entry.optString("album").ifBlank { null },
            durationMs = if (entry.has("durationMs") && !entry.isNull("durationMs")) {
                entry.optLong("durationMs").takeIf { it > 0L }
            } else {
                null
            },
            audioUrl = entry.getString("audioUrl"),
            artUrl = entry.optString("artUrl").ifBlank { null },
        )
    }

    private fun Track.toMediaItem(): MediaItem {
        val metadata = MediaMetadata.Builder()
            .setTitle(title)
            .setArtist(artist)
            .setAlbumTitle(album)
            .setIsPlayable(true)
            .setIsBrowsable(false)
            .apply {
                artUrl?.let { setArtworkUri(Uri.parse(it)) }
                durationMs?.let { setDurationMs(it) }
            }
            .build()
        return MediaItem.Builder()
            .setMediaId(id)
            .setUri(audioUrl)
            .setMediaMetadata(metadata)
            .build()
    }

    private companion object {
        const val PROGRESS_INTERVAL_MS = 1_000L
        const val TAG = "WatermelonNativeBridge"
    }
}
