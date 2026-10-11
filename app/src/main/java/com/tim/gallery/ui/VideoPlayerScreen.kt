package com.tim.gallery.ui

import android.net.Uri
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.SurfaceView
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.ui.PlayerView
import com.tim.gallery.data.MediaSaver
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.roundToLong

@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
@Composable
fun VideoPlayerScreen(
    uri: Uri,
    title: String,
    onBack: () -> Unit,
    /** Off for personal-folder videos, so stills don't end up in the public gallery. */
    allowCapture: Boolean = true,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    val player = remember(uri) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(uri))
            setSeekParameters(SeekParameters.EXACT)
            prepare()
            playWhenReady = true
        }
    }

    var isPlaying by remember { mutableStateOf(false) }
    var ended by remember { mutableStateOf(false) }
    var duration by remember { mutableLongStateOf(0L) }
    var position by remember { mutableLongStateOf(0L) }
    var scrubbing by remember { mutableStateOf(false) }
    var scrubPos by remember { mutableFloatStateOf(0f) }
    var controlsVisible by remember { mutableStateOf(true) }
    var capturing by remember { mutableStateOf(false) }
    var playerView by remember { mutableStateOf<PlayerView?>(null) }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
            }

            override fun onPlaybackStateChanged(state: Int) {
                ended = state == Player.STATE_ENDED
                if (player.duration != C.TIME_UNSET) duration = player.duration
                if (ended) controlsVisible = true
            }

            override fun onPlayerError(error: PlaybackException) {
                scope.launch { snackbar.showSnackbar("Can't play this video (${error.errorCodeName})") }
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.release()
        }
    }

    // Pause when the app goes to the background
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) player.pause()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    ImmersiveMode()

    // Keep the time readout and seek bar in sync with playback
    LaunchedEffect(player) {
        while (true) {
            if (!scrubbing) position = player.currentPosition
            if (duration <= 0L && player.duration != C.TIME_UNSET) duration = player.duration
            delay(33)
        }
    }

    // Auto-hide controls while playing
    LaunchedEffect(controlsVisible, isPlaying, scrubbing) {
        if (controlsVisible && isPlaying && !scrubbing) {
            delay(3000)
            controlsVisible = false
        }
    }

    fun seekTo(ms: Long) {
        val target = if (duration > 0) ms.coerceIn(0L, duration) else max(0L, ms)
        player.seekTo(target)
        position = target
    }

    fun togglePlay() {
        when {
            ended -> {
                player.seekTo(0)
                player.play()
            }
            player.isPlaying -> player.pause()
            else -> player.play()
        }
    }

    fun stepFrame(direction: Int) {
        stepVideoFrame(player, direction)
        position = player.currentPosition
    }

    suspend fun grabDisplayedFrame(): Bitmap? = grabVideoFrame(playerView, player)

    fun captureStill() {
        if (capturing) return
        player.pause()
        val at = player.currentPosition
        capturing = true
        scope.launch {
            val frame = grabDisplayedFrame()
            val saved = saveVideoStill(context, uri, title, at, frame)
            capturing = false
            snackbar.showSnackbar(
                if (saved != null) "Still saved to ${MediaSaver.FOLDER_LABEL}"
                else "Couldn't capture this frame"
            )
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    useController = false
                    keepScreenOn = true
                    setShutterBackgroundColor(android.graphics.Color.BLACK)
                    this.player = player
                    playerView = this
                }
            },
            modifier = Modifier.fillMaxSize(),
        )

        // Tap to show/hide controls, double-tap left/right side to jump 10s
        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = { controlsVisible = !controlsVisible },
                        onDoubleTap = { p ->
                            val jump = if (p.x < size.width / 2f) -10_000L else 10_000L
                            seekTo(player.currentPosition + jump)
                        },
                    )
                }
        )

        AnimatedVisibility(
            visible = controlsVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.fillMaxSize(),
        ) {
            Box(Modifier.fillMaxSize()) {
                // Top bar
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth()
                        .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.7f), Color.Transparent)))
                        .safeDrawingPadding()
                        .padding(4.dp),
                ) {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
                    }
                    Text(
                        title,
                        color = Color.White,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                }

                // Center: -10s, play/pause, +10s, and big frame-step buttons under them
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.align(Alignment.Center),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(28.dp),
                    ) {
                        RoundButton(Icons.Filled.Replay10, "Back 10 seconds", 56) {
                            seekTo(player.currentPosition - 10_000)
                        }
                        RoundButton(
                            if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                            if (isPlaying) "Pause" else "Play",
                            76,
                        ) { togglePlay() }
                        RoundButton(Icons.Filled.Forward10, "Forward 10 seconds", 56) {
                            seekTo(player.currentPosition + 10_000)
                        }
                    }
                    Spacer(Modifier.height(20.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(56.dp),
                    ) {
                        FrameStepButton(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Previous frame", "−1 frame") { stepFrame(-1) }
                        FrameStepButton(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Next frame", "+1 frame") { stepFrame(1) }
                    }
                }

                // Bottom: seek bar, frame stepping, still capture
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.8f))))
                        .safeDrawingPadding()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    val shown = if (scrubbing) scrubPos.toLong() else position
                    Row(Modifier.fillMaxWidth()) {
                        Text(
                            if (isPlaying) formatDuration(shown) else formatPrecise(shown),
                            color = Color.White,
                            style = MaterialTheme.typography.labelLarge,
                        )
                        Spacer(Modifier.weight(1f))
                        Text(formatDuration(duration), color = Color.White.copy(alpha = 0.8f), style = MaterialTheme.typography.labelLarge)
                    }
                    Slider(
                        value = shown.toFloat().coerceIn(0f, max(duration, 1L).toFloat()),
                        valueRange = 0f..max(duration, 1L).toFloat(),
                        onValueChange = { v ->
                            if (!scrubbing) {
                                scrubbing = true
                                // ExoPlayer's scrubbing mode: tuned for rapid seeks (decode-only
                                // frames, faster codec, audio off). Playback pauses itself while on
                                // and picks back up afterwards if it was playing.
                                player.setScrubbingModeEnabled(true)
                            }
                            scrubPos = v
                            player.seekTo(v.toLong())
                        },
                        onValueChangeFinished = {
                            // land on the exact frame where the finger let go
                            if (scrubbing) seekTo(scrubPos.toLong())
                            player.setScrubbingModeEnabled(false)
                            scrubbing = false
                        },
                        colors = SliderDefaults.colors(
                            thumbColor = Color.White,
                            activeTrackColor = Color.White,
                            inactiveTrackColor = Color.White.copy(alpha = 0.3f),
                        ),
                    )
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        Text(
                            "Hold a frame button to step continuously",
                            color = Color.White.copy(alpha = 0.6f),
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.weight(1f),
                        )
                        if (allowCapture) {
                            FilledTonalButton(onClick = { captureStill() }, enabled = !capturing) {
                                Icon(Icons.Filled.PhotoCamera, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text("Capture still")
                            }
                        }
                    }
                }
            }
        }

        if (capturing) {
            CircularProgressIndicator(color = Color.White, modifier = Modifier.align(Alignment.Center))
        }

        SnackbarHost(
            hostState = snackbar,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .safeDrawingPadding()
                .padding(bottom = 140.dp),
        )
    }
}

@Composable
private fun RoundButton(icon: ImageVector, description: String, sizeDp: Int, onClick: () -> Unit) {
    IconButton(
        onClick = onClick,
        modifier = Modifier
            .size(sizeDp.dp)
            .background(Color.Black.copy(alpha = 0.45f), CircleShape),
    ) {
        Icon(icon, contentDescription = description, tint = Color.White, modifier = Modifier.size((sizeDp * 0.6f).dp))
    }
}

/** Big, thumb-sized frame-step button. Tap for one frame; hold to keep stepping. */
@Composable
internal fun FrameStepButton(icon: ImageVector, description: String, label: String, sizeDp: Int = 64, onStep: () -> Unit) {
    val scope = rememberCoroutineScope()
    val step by rememberUpdatedState(onStep)
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(sizeDp.dp)
                .background(Color.Black.copy(alpha = 0.45f), CircleShape)
                .semantics { contentDescription = description }
                .pointerInput(Unit) {
                    detectTapGestures(
                        onPress = {
                            step()
                            val repeat = scope.launch {
                                delay(450)
                                while (true) {
                                    step()
                                    delay(90)
                                }
                            }
                            tryAwaitRelease()
                            repeat.cancel()
                        },
                    )
                },
        ) {
            Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size((sizeDp * 0.69f).dp))
        }
        Spacer(Modifier.height(4.dp))
        Text(label, color = Color.White, style = MaterialTheme.typography.labelMedium)
    }
}

/** Pauses and moves one frame forward (+1) or back (−1), exactly. */
internal fun stepVideoFrame(player: ExoPlayer, direction: Int) {
    player.pause()
    player.setSeekParameters(SeekParameters.EXACT)
    val fps = player.videoFormat?.frameRate ?: -1f
    val frameMs = if (fps > 0f) (1000f / fps).roundToLong().coerceAtLeast(1L) else 33L
    val duration = player.duration
    val target = player.currentPosition + direction * frameMs
    player.seekTo(if (duration > 0) target.coerceIn(0L, duration) else max(0L, target))
}

/**
 * Copies the frame that's on screen right now, at the video's own resolution. This is
 * exactly what you see, for every format (some, like WebM, can't be seeked precisely by
 * Android's frame grabber, which used to return the first frame).
 */
internal suspend fun grabVideoFrame(playerView: PlayerView?, player: ExoPlayer): Bitmap? {
    val sv = playerView?.videoSurfaceView as? SurfaceView ?: return null
    if (!sv.holder.surface.isValid || sv.width <= 0 || sv.height <= 0) return null
    val vs = player.videoSize
    if (vs.width <= 0 || vs.height <= 0) return null
    var w = (vs.width * vs.pixelWidthHeightRatio).roundToInt()
    var h = vs.height
    // Rotated videos (most phone portrait videos) show turned; match what's on screen
    val viewAspect = sv.width.toFloat() / sv.height
    if (abs(viewAspect - h.toFloat() / w) < abs(viewAspect - w.toFloat() / h)) {
        val t = w
        w = h
        h = t
    }
    val k = min(1f, 4096f / max(w, h))
    w = (w * k).roundToInt().coerceAtLeast(1)
    h = (h * k).roundToInt().coerceAtLeast(1)
    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    return suspendCancellableCoroutine { cont ->
        try {
            PixelCopy.request(
                sv,
                bmp,
                { result -> if (cont.isActive) cont.resume(if (result == PixelCopy.SUCCESS) bmp else null) },
                Handler(Looper.getMainLooper()),
            )
        } catch (e: Exception) {
            if (cont.isActive) cont.resume(null)
        }
    }
}

/** Saves a still from a video: the frame on screen if it can be copied, else Android's frame grabber. */
internal suspend fun saveVideoStill(
    context: android.content.Context,
    uri: android.net.Uri,
    title: String,
    atMs: Long,
    frame: Bitmap?,
): android.net.Uri? {
    val stem = title.substringBeforeLast('.').ifBlank { "video" }
    return if (frame != null) {
        MediaSaver.saveBitmap(context, frame, "${stem}_still_${atMs}ms.jpg")
    } else {
        MediaSaver.saveVideoFrame(context, uri, atMs, title)
    }
}
