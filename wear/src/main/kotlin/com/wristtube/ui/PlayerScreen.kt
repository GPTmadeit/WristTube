package com.wristtube.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Forward10
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Replay10
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.rotary.onRotaryScrollEvent
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.compose.ContentFrame
import androidx.media3.ui.compose.SURFACE_TYPE_SURFACE_VIEW
import androidx.media3.ui.compose.state.rememberPresentationState
import androidx.wear.compose.material3.CircularProgressIndicator
import androidx.wear.compose.material3.FilledIconButton
import androidx.wear.compose.material3.FilledTonalIconButton
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.IconButton
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import coil3.compose.AsyncImage
import com.wristtube.data.Prefs
import com.wristtube.play.NowPlaying
import com.wristtube.play.Playback
import kotlinx.coroutines.delay
import kotlin.math.sqrt

/** The player's state as the screen needs it, refreshed from player events and a ticker. */
@Stable
class PlayerUi {
    var playing by mutableStateOf(false)
    var buffering by mutableStateOf(false)
    var ended by mutableStateOf(false)
    var position by mutableLongStateOf(0L)
    var duration by mutableLongStateOf(0L)
    var buffered by mutableLongStateOf(0L)
    var showingVideo by mutableStateOf(false)
    var live by mutableStateOf(false)

    fun refresh(p: Player) {
        playing = p.isPlaying
        buffering = p.playbackState == Player.STATE_BUFFERING
        ended = p.playbackState == Player.STATE_ENDED
        position = p.currentPosition.coerceAtLeast(0)
        duration = p.duration.takeIf { it != C.TIME_UNSET } ?: 0L
        buffered = p.bufferedPosition.coerceAtLeast(0)
        showingVideo = p.currentTracks.isTypeSelected(C.TRACK_TYPE_VIDEO)
        live = p.isCurrentMediaItemLive
    }
}

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun PlayerScreen(onUpNext: () -> Unit, onDone: () -> Unit) {
    val now by Playback.now.collectAsStateWithLifecycle()
    val settings by Prefs.settings.collectAsStateWithLifecycle()
    val np = now
    if (np == null) {
        // Stopped from the media controls, or the service went away.
        LaunchedEffect(Unit) { onDone() }
        Box(Modifier.fillMaxSize().background(Color.Black))
        return
    }
    val player = remember { Playback.player() }
    val ui = remember(player) { PlayerUi() }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onEvents(p: Player, events: Player.Events) = ui.refresh(p)
        }
        player.addListener(listener)
        ui.refresh(player)
        onDispose { player.removeListener(listener) }
    }
    LaunchedEffect(player) {
        while (true) {
            ui.refresh(player)
            delay(500)
        }
    }

    // Off screen (swiped away, wrist down), only the sound carries on.
    LifecycleStartEffect(Unit) {
        Playback.setVisible(true)
        onStopOrDispose { Playback.setVisible(false) }
    }

    // Keep the screen awake while a video is actually playing on it.
    val view = LocalView.current
    val awake = ui.playing && ui.showingVideo
    DisposableEffect(awake) {
        view.keepScreenOn = awake
        onDispose { view.keepScreenOn = false }
    }

    AskForNotificationsOnce()

    var controls by remember { mutableStateOf(true) }
    var touched by remember { mutableIntStateOf(0) }
    val forceControls = !ui.playing || np.loading || np.error != null || ui.ended
    LaunchedEffect(controls, touched, forceControls) {
        if (controls && !forceControls) {
            delay(3_500)
            controls = false
        }
    }

    // The crown is the volume knob: clockwise is louder.
    var volume by remember { mutableStateOf(Playback.volume()) }
    var volumeShownAt by remember { mutableLongStateOf(0L) }
    var rotary by remember { mutableFloatStateOf(0f) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .onRotaryScrollEvent { e ->
                rotary += e.verticalScrollPixels
                while (rotary > ROTARY_STEP) {
                    Playback.nudgeVolume(up = true); rotary -= ROTARY_STEP
                }
                while (rotary < -ROTARY_STEP) {
                    Playback.nudgeVolume(up = false); rotary += ROTARY_STEP
                }
                volume = Playback.volume()
                volumeShownAt = System.currentTimeMillis()
                true
            }
            .focusRequester(focus)
            .focusable()
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { controls = !controls; touched++ },
                    onDoubleTap = {
                        Prefs.update { it.copy(zoom = (it.zoom + 1) % 3) }
                        touched++
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        val videoOn = ui.showingVideo && !np.audioOnly
        if (videoOn) {
            VideoFrame(player, settings.zoom)
        } else {
            Artwork(np, dim = controls)
        }

        if (np.loading || (ui.buffering && !controls)) {
            CircularProgressIndicator(modifier = Modifier.size(44.dp))
        }

        ProgressRing(ui, emphasized = controls)

        val sinceVolume = System.currentTimeMillis() - volumeShownAt
        if (sinceVolume < 1_600) {
            VolumeRing(volume.first, volume.second)
            LaunchedEffect(volumeShownAt) {
                delay(1_700)
                volumeShownAt = 0L
            }
        }

        AnimatedVisibility(visible = controls || forceControls, enter = fadeIn(), exit = fadeOut()) {
            Controls(
                np = np,
                ui = ui,
                onToggle = { Playback.toggle(); touched++ },
                onBack = { player.seekBack(); touched++ },
                onForward = { player.seekForward(); touched++ },
                onAudioOnly = { Playback.setAudioOnly(!np.audioOnly); touched++ },
                onUpNext = onUpNext,
                onRetry = { Playback.retry() },
            )
        }
    }
}

private const val ROTARY_STEP = 36f

/**
 * The video, sized for a round screen. Zoom 0 fits the whole picture inside the circle, 1 fills
 * the width (the corners fall outside the circle), 2 fills the screen and crops.
 */
@androidx.annotation.OptIn(UnstableApi::class)
@Composable
private fun VideoFrame(player: Player, zoom: Int) {
    val presentation = rememberPresentationState(player)
    val size = presentation.videoSizeDp
    val aspect = if (size != null && size != Size.Unspecified && size.height > 0f) size.width / size.height else 16f / 9f
    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val d = minOf(maxWidth, maxHeight)
        val frame = when (zoom) {
            // The largest rectangle of this shape that fits in a circle of diameter d.
            0 -> Modifier.size(d * (aspect / sqrt(aspect * aspect + 1f)), d * (1f / sqrt(aspect * aspect + 1f)))
            1 -> if (aspect >= 1f) Modifier.size(d, d / aspect) else Modifier.size(d * aspect, d)
            else -> Modifier.fillMaxSize()
        }
        ContentFrame(
            player = player,
            modifier = frame,
            surfaceType = SURFACE_TYPE_SURFACE_VIEW,
            contentScale = if (zoom == 2) ContentScale.Crop else ContentScale.Fit,
            keepContentOnReset = false,
            shutter = { Box(Modifier.fillMaxSize().background(Color.Black)) },
        )
    }
}

/** Sound only: the thumbnail in a circle, with the title under it. */
@Composable
private fun Artwork(np: NowPlaying, dim: Boolean) {
    Column(
        Modifier.fillMaxSize().padding(horizontal = 26.dp).alpha(if (dim) 0.35f else 1f),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        AsyncImage(
            model = np.video.thumbnail,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(104.dp).clip(CircleShape).background(Palette.card),
        )
        Spacer(Modifier.height(8.dp))
        Text(
            np.video.title,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text(np.video.channel, style = MaterialTheme.typography.labelSmall, color = Palette.muted, maxLines = 1)
    }
}

@Composable
private fun Controls(
    np: NowPlaying,
    ui: PlayerUi,
    onToggle: () -> Unit,
    onBack: () -> Unit,
    onForward: () -> Unit,
    onAudioOnly: () -> Unit,
    onUpNext: () -> Unit,
    onRetry: () -> Unit,
) {
    Box(Modifier.fillMaxSize().background(Color(0x99000000)), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                np.video.title,
                modifier = Modifier.fillMaxWidth(0.72f),
                style = MaterialTheme.typography.labelSmall,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(6.dp))
            val error = np.error
            if (error != null) {
                Text(
                    error,
                    modifier = Modifier.fillMaxWidth(0.78f),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center,
                    maxLines = 3,
                )
                Spacer(Modifier.height(6.dp))
                FilledIconButton(onClick = onRetry, modifier = Modifier.size(52.dp)) {
                    Icon(Icons.Rounded.Refresh, contentDescription = "Try again")
                }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    IconButton(onClick = onBack, enabled = !ui.live) {
                        Icon(Icons.Rounded.Replay10, contentDescription = "Back 10 seconds")
                    }
                    FilledIconButton(onClick = onToggle, modifier = Modifier.size(56.dp), enabled = !np.loading) {
                        Icon(playIcon(ui.playing), contentDescription = if (ui.playing) "Pause" else "Play", modifier = Modifier.size(30.dp))
                    }
                    IconButton(onClick = onForward, enabled = !ui.live) {
                        Icon(Icons.Rounded.Forward10, contentDescription = "Forward 10 seconds")
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    when {
                        np.loading -> "Loading..."
                        ui.live -> "LIVE"
                        else -> "${duration(ui.position / 1000)} / ${duration(ui.duration / 1000)}"
                    },
                    fontSize = 12.sp,
                    color = Palette.muted,
                )
            }
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                FilledTonalIconButton(onClick = onAudioOnly, modifier = Modifier.size(38.dp)) {
                    Icon(
                        if (np.audioOnly) Icons.Rounded.Videocam else Icons.Rounded.Headphones,
                        contentDescription = if (np.audioOnly) "Show video" else "Sound only",
                        modifier = Modifier.size(20.dp),
                    )
                }
                FilledTonalIconButton(onClick = onUpNext, modifier = Modifier.size(38.dp)) {
                    Icon(Icons.AutoMirrored.Rounded.QueueMusic, contentDescription = "Up next", modifier = Modifier.size(20.dp))
                }
            }
        }
    }
}

/** Progress around the bezel: played in the accent colour, buffered dimmer. */
@Composable
private fun ProgressRing(ui: PlayerUi, emphasized: Boolean) {
    if (ui.live || ui.duration <= 0) return
    val played = (ui.position.toFloat() / ui.duration).coerceIn(0f, 1f)
    val buffered = (ui.buffered.toFloat() / ui.duration).coerceIn(0f, 1f)
    Canvas(Modifier.fillMaxSize()) {
        val w = (if (emphasized) 5.dp else 3.dp).toPx()
        val inset = w / 2 + 1.dp.toPx()
        val arcSize = Size(size.width - inset * 2, size.height - inset * 2)
        val tl = Offset(inset, inset)
        drawArc(Color(0x33FFFFFF), -90f, 360f, false, tl, arcSize, style = Stroke(w))
        drawArc(Color(0x66FFFFFF), -90f, 360f * buffered, false, tl, arcSize, style = Stroke(w, cap = StrokeCap.Round))
        drawArc(Palette.accent, -90f, 360f * played, false, tl, arcSize, style = Stroke(w, cap = StrokeCap.Round))
    }
}

/** Volume on the right edge while the crown turns. */
@Composable
private fun VolumeRing(level: Int, max: Int) {
    val fraction = if (max > 0) level.toFloat() / max else 0f
    Box(Modifier.fillMaxSize()) {
        Canvas(Modifier.fillMaxSize()) {
            val w = 7.dp.toPx()
            val inset = w / 2 + 9.dp.toPx()
            val arcSize = Size(size.width - inset * 2, size.height - inset * 2)
            val tl = Offset(inset, inset)
            drawArc(Color(0x55FFFFFF), 50f, -100f, false, tl, arcSize, style = Stroke(w, cap = StrokeCap.Round))
            drawArc(Color.White, 50f, -100f * fraction, false, tl, arcSize, style = Stroke(w, cap = StrokeCap.Round))
        }
        // Up in the top-right, clear of the skip button that sits at mid-height on the right.
        Row(
            Modifier.align(Alignment.TopEnd).padding(top = 66.dp, end = 40.dp)
                .background(Color(0xCC000000), CircleShape).padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Rounded.VolumeUp, contentDescription = null, modifier = Modifier.size(16.dp))
            Text(" $level", fontSize = 13.sp, fontWeight = FontWeight.Bold)
        }
    }
}

/** Android 13+ hides the media notification (and the watch's media controls) without this. */
@Composable
private fun AskForNotificationsOnce() {
    if (Build.VERSION.SDK_INT < 33) return
    val ctx = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(Unit) {
        val granted = ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        if (!granted && !Prefs.flag("askedNotifications")) {
            Prefs.setFlag("askedNotifications")
            runCatching { launcher.launch(Manifest.permission.POST_NOTIFICATIONS) }
        }
    }
}
