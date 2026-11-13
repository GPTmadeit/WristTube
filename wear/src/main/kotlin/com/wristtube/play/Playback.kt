package com.wristtube.play

import android.content.ComponentName
import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.net.Uri
import android.util.Log
import androidx.compose.runtime.Immutable
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.wristtube.data.Library
import com.wristtube.data.Prefs
import com.wristtube.net.Http
import com.wristtube.net.WatchNetwork
import com.wristtube.yt.Streams
import com.wristtube.yt.Video
import com.wristtube.yt.YouTube
import com.wristtube.yt.YouTubeException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** What the player is doing, for the screens. */
@Immutable
data class NowPlaying(
    val video: Video,
    val streams: Streams? = null,
    val loading: Boolean = true,
    val error: String? = null,
    /** Sound only, by choice (the toggle) - not because the screen is off. */
    val audioOnly: Boolean = false,
)

/**
 * The one player. It lives as long as the process: the activity draws its video and sends it
 * commands, and [PlaybackService] wraps it in a media session so it keeps playing (and shows up in
 * the watch's media controls) with the screen off.
 *
 * Sound goes to the watch speaker. Wear OS media apps usually refuse the speaker and insist on
 * Bluetooth headphones (Media3 even has a switch for that, off here); with [com.wristtube.data.Settings.speakerOnly]
 * the player also asks Android to keep the speaker when headphones are connected.
 */
@UnstableApi
object Playback {
    private const val TAG = "WristTube/Play"
    private const val NET_HOLDER = "playback"

    /** Bounded request size, see [ChunkedDataSource]. */
    private const val CHUNK_BYTES = 2L shl 20

    private lateinit var app: Context
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null
    private var releaseNet: Job? = null
    private var controller: ListenableFuture<MediaController>? = null
    private var recoveries = 0

    /** True while the player screen is on screen. Off screen, video isn't fetched or decoded. */
    private var visible = false

    var player: ExoPlayer? = null
        private set

    private val _now = MutableStateFlow<NowPlaying?>(null)
    val now: StateFlow<NowPlaying?> = _now

    private val http by lazy { OkHttpDataSource.Factory(Http.client).setUserAgent(Http.USER_AGENT) }
    private val progressive by lazy { ProgressiveMediaSource.Factory(ChunkedDataSource.Factory(http, CHUNK_BYTES)) }
    private val hls by lazy { HlsMediaSource.Factory(http) }

    fun init(ctx: Context) {
        app = ctx.applicationContext
    }

    fun player(): ExoPlayer = player ?: build().also { player = it }

    private fun build(): ExoPlayer {
        val p = ExoPlayer.Builder(app)
            .setTrackSelector(DefaultTrackSelector(app))
            // Start fast (1.5 s buffered), keep up to a minute: the watch's Wi-Fi sleeps between
            // bursts, so a deep buffer rides out its pauses.
            .setLoadControl(DefaultLoadControl.Builder().setBufferDurationsMs(15_000, 60_000, 1_500, 3_000).build())
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(),
                /* handleAudioFocus = */ true,
            )
            // Media3 can refuse to play on a Wear OS watch's speaker. Here the speaker is the point.
            .setSuppressPlaybackOnUnsuitableOutput(false)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .setSeekBackIncrementMs(10_000)
            .setSeekForwardIncrementMs(10_000)
            .build()
        p.addListener(listener)
        applySettings(p)
        return p
    }

    /** Speaker routing, video size cap and what the screen currently allows. */
    fun applySettings(p: ExoPlayer? = player) {
        p ?: return
        val s = Prefs.now
        val speaker = if (s.speakerOnly) builtInSpeaker() else null
        p.setPreferredAudioDevice(speaker)
        // With headphones allowed, pull them out and playback pauses instead of blaring from the wrist.
        p.setHandleAudioBecomingNoisy(!s.speakerOnly)
        applyTracks(p)
    }

    private fun applyTracks(p: ExoPlayer) {
        val audioOnly = (_now.value?.audioOnly ?: Prefs.now.audioOnly) || !visible
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, audioOnly)
            .setMaxVideoSize(Int.MAX_VALUE, Prefs.now.maxHeight)
            .build()
    }

    private fun builtInSpeaker(): AudioDeviceInfo? =
        app.getSystemService(AudioManager::class.java)
            ?.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            ?.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }

    /** Looks [video] up and plays it. */
    fun play(video: Video, startAtMs: Long = 0) {
        val audioOnly = Prefs.now.audioOnly
        bindService()
        job?.cancel()
        recoveries = 0
        _now.value = NowPlaying(video, loading = true, audioOnly = audioOnly)
        Library.watched(video)
        val p = player()
        p.stop()
        p.clearMediaItems()
        job = scope.launch { resolveAndPlay(video, startAtMs, autoStart = true) }
    }

    private suspend fun resolveAndPlay(video: Video, startAtMs: Long, autoStart: Boolean) {
        WatchNetwork.acquire(app, NET_HOLDER)
        // Look the video up over the link that will stream it (stream URLs are tied to an address).
        WatchNetwork.await()
        val s = Prefs.now
        val streams = try {
            YouTube.streams(video.url, s.maxHeight, s.dataSaver)
        } catch (e: YouTubeException) {
            Log.w(TAG, "resolve failed: ${e.message}", e.cause)
            _now.update { it?.copy(loading = false, error = e.message) }
            return
        }
        Log.i(TAG, if (streams.hlsUrl != null) "playing ${streams.video.id}: live, HLS" else
            "playing ${streams.video.id}: ${streams.videoHeight}p video, ${streams.audioKbps} kbps audio")
        _now.update { it?.copy(video = streams.video, streams = streams, loading = false, error = null) }
        // History gets the real title and thumbnail (a video opened by link starts without them).
        Library.watched(streams.video)
        val p = player()
        applySettings(p)
        p.setMediaSource(source(streams), startAtMs)
        p.prepare()
        p.playWhenReady = autoStart
    }

    private fun source(s: Streams): MediaSource {
        val meta = MediaMetadata.Builder()
            .setTitle(s.video.title)
            .setDisplayTitle(s.video.title)
            .setArtist(s.video.channel)
            .setArtworkUri(s.video.thumbnail?.let(Uri::parse))
            .build()
        fun item(uri: String) = MediaItem.Builder().setUri(uri).setMediaId(s.video.id).setMediaMetadata(meta).build()
        s.hlsUrl?.let { return hls.createMediaSource(item(it)) }
        val audio = progressive.createMediaSource(item(s.audioUrl!!))
        val videoUrl = s.videoUrl ?: return audio
        // Video first: a merged source reports the first source's item, metadata included.
        return MergingMediaSource(true, true, progressive.createMediaSource(item(videoUrl)), audio)
    }

    fun toggle() {
        val p = player ?: return
        if (p.playbackState == Player.STATE_ENDED) p.seekTo(0)
        if (p.playbackState == Player.STATE_IDLE && _now.value?.streams != null) {
            retry()
            return
        }
        if (p.isPlaying) p.pause() else p.play()
    }

    fun setAudioOnly(on: Boolean) {
        _now.update { it?.copy(audioOnly = on) }
        player?.let(::applyTracks)
    }

    /** The player screen came into view or left it. */
    fun setVisible(on: Boolean) {
        if (visible == on) return
        visible = on
        player?.let(::applyTracks)
    }

    /** After an error: look the video up again and carry on from where it stopped. */
    fun retry() {
        val current = _now.value ?: return
        val at = player?.currentPosition ?: 0
        job?.cancel()
        _now.update { it?.copy(loading = true, error = null) }
        job = scope.launch { resolveAndPlay(current.video, at, autoStart = true) }
    }

    fun stop() {
        job?.cancel()
        player?.stop()
        player?.clearMediaItems()
        _now.value = null
        scheduleNetRelease(0)
    }

    // --- volume (the crown) -------------------------------------------------------------------------

    private val audio: AudioManager? get() = app.getSystemService(AudioManager::class.java)

    fun volume(): Pair<Int, Int> {
        val am = audio ?: return 0 to 1
        return am.getStreamVolume(AudioManager.STREAM_MUSIC) to am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
    }

    fun nudgeVolume(up: Boolean) {
        audio?.adjustStreamVolume(
            AudioManager.STREAM_MUSIC,
            if (up) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER,
            0,
        )
    }

    /** Where the sound is going right now, for the player and settings screens. */
    fun outputLabel(): String {
        if (Prefs.now.speakerOnly) return "Watch speaker"
        val am = audio ?: return "Watch speaker"
        val attrs = android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_MEDIA).build()
        val device = runCatching { am.getAudioDevicesForAttributes(attrs).firstOrNull() }.getOrNull()
        return when (device?.type) {
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_BLE_SPEAKER -> "Headphones"
            else -> "Watch speaker"
        }
    }

    // --- lifecycle -------------------------------------------------------------------------------------

    /** Binds [PlaybackService], so playback can carry on as a foreground service. */
    private fun bindService() {
        if (controller != null) return
        val token = SessionToken(app, ComponentName(app, PlaybackService::class.java))
        controller = MediaController.Builder(app, token).buildAsync()
    }

    /** Called when the service goes away for good. */
    fun release() {
        job?.cancel()
        controller?.let { f -> runCatching { if (f.isDone) f.get().release() else f.cancel(true) } }
        controller = null
        player?.removeListener(listener)
        player?.release()
        player = null
        _now.value = null
        scheduleNetRelease(0)
    }

    private fun scheduleNetRelease(afterMs: Long) {
        releaseNet?.cancel()
        releaseNet = scope.launch {
            if (afterMs > 0) delay(afterMs)
            WatchNetwork.release(NET_HOLDER)
        }
    }

    private val listener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            val p = player ?: return
            if (isPlaying || p.playWhenReady) {
                releaseNet?.cancel()
                WatchNetwork.acquire(app, NET_HOLDER)
            } else {
                // Paused: let the watch drop Wi-Fi/LTE after a while to save battery.
                scheduleNetRelease(120_000)
            }
        }

        override fun onPlaybackStateChanged(state: Int) {
            if (state == Player.STATE_READY) recoveries = 0
            if (state != Player.STATE_ENDED) return
            val next = _now.value?.streams?.related?.firstOrNull { !it.live }
            if (Prefs.now.autoplay && next != null) play(next)
        }

        override fun onPlayerError(error: PlaybackException) {
            Log.w(TAG, "player error ${error.errorCodeName}", error)
            val http = generateSequence(error.cause) { it.cause }
                .filterIsInstance<HttpDataSource.InvalidResponseCodeException>().firstOrNull()
            // 403/410: the stream URLs expired or belong to another address (the watch moved from
            // the phone's link to Wi-Fi). Network errors: the Wi-Fi napped. Both are worth a retry.
            val recoverable = http?.responseCode in setOf(403, 404, 410) ||
                error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ||
                error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT
            if (recoverable && recoveries < 3) {
                recoveries++
                Log.i(TAG, "recovering (attempt $recoveries)")
                retry()
                return
            }
            val message = when {
                http != null -> "YouTube refused the stream (HTTP ${http.responseCode})."
                error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ||
                    error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ->
                    "Lost the connection."
                error.errorCode in PlaybackException.ERROR_CODE_DECODER_INIT_FAILED..PlaybackException.ERROR_CODE_DECODING_RESOURCES_RECLAIMED ->
                    "The watch couldn't decode this video. Try sound only."
                else -> "Playback stopped (${error.errorCodeName})."
            }
            _now.update { it?.copy(error = message, loading = false) }
        }
    }
}
