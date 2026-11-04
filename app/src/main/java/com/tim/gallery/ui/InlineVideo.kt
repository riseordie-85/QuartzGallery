package com.tim.gallery.ui

import android.graphics.Bitmap
import android.view.TextureView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import com.tim.gallery.data.GalleryItem
import kotlinx.coroutines.delay
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Sound for videos in the viewer. Every launch of the app starts muted; turning the sound on (the
 * speaker button, or a volume key while a video is showing) keeps it on for the next videos, until
 * the app is left.
 */
object Playback {
    var muted by mutableStateOf(true)

    /** A video is on screen in the viewer (volume keys then turn the sound on). */
    var videoShowing by mutableStateOf(false)

    /** The app went to the background: next time, start muted again. */
    fun appLeft() {
        muted = true
    }
}

/**
 * The viewer's one video player. It moves to whichever video page is current, so videos play right
 * where you swipe to them.
 */
class InlineVideo(val player: ExoPlayer) {
    /** The item loaded into the player. */
    var itemId by mutableStateOf<Long?>(null)
        private set
    var uri: android.net.Uri? = null
        private set

    /** The item whose first frame is on screen (until then its thumbnail shows). */
    var shownId by mutableStateOf<Long?>(null)
    var isPlaying by mutableStateOf(false)
    var ended by mutableStateOf(false)
    var duration by mutableLongStateOf(0L)
    var position by mutableLongStateOf(0L)

    /** Width / height of the picture as shown (0 until known). */
    var aspect by mutableFloatStateOf(0f)

    /** Why the video can't play (null while it's fine). */
    var error by mutableStateOf<String?>(null)

    /** The user is dragging the timeline: playback position comes from the drag, not the player. */
    var scrubbing by mutableStateOf(false)
    private var resumeAfterScrub = false

    internal var texture: TextureView? = null

    fun load(item: GalleryItem) {
        if (itemId == item.id && uri == item.uri) return
        itemId = item.id
        uri = item.uri
        shownId = null
        ended = false
        position = 0L
        duration = item.durationMs.coerceAtLeast(0L)
        aspect = 0f
        error = null
        player.setMediaItem(MediaItem.fromUri(item.uri))
        player.prepare()
        player.playWhenReady = true
    }

    fun clear() {
        if (itemId == null) return
        itemId = null
        uri = null
        shownId = null
        error = null
        player.stop()
        player.clearMediaItems()
    }

    fun togglePlay() {
        if (player.playbackState == Player.STATE_IDLE && itemId != null) {
            // After an error: try again
            error = null
            player.prepare()
            player.play()
            return
        }
        when {
            ended -> {
                player.seekTo(0)
                player.play()
            }
            player.isPlaying -> player.pause()
            else -> player.play()
        }
    }

    fun seekTo(ms: Long) {
        val target = if (duration > 0) ms.coerceIn(0L, duration) else max(0L, ms)
        player.seekTo(target)
        position = target
        if (ended && target < duration) ended = false
    }

    fun jump(deltaMs: Long) = seekTo(player.currentPosition + deltaMs)

    fun stepFrame(direction: Int) {
        stepVideoFrame(player, direction)
        position = player.currentPosition
    }

    fun startScrub() {
        if (scrubbing) return
        scrubbing = true
        resumeAfterScrub = player.isPlaying || player.playWhenReady && !ended
        player.pause()
        // ExoPlayer's scrubbing mode: every seek lands on the exact frame under the playhead (not just
        // keyframes), with the decoder sped up and seeks it can't keep up with dropped, so it stays live
        player.setSeekParameters(SeekParameters.EXACT)
        player.setScrubbingModeParameters(SMOOTH_SCRUBBING)
        player.setScrubbingModeEnabled(true)
    }

    fun scrub(ms: Long) {
        val target = if (duration > 0) ms.coerceIn(0L, duration) else max(0L, ms)
        position = target
        player.seekTo(target)
    }

    fun endScrub() {
        if (!scrubbing) return
        player.setScrubbingModeEnabled(false)
        player.setSeekParameters(SeekParameters.EXACT)
        player.seekTo(position)
        scrubbing = false
        ended = false
        if (resumeAfterScrub && (duration <= 0 || position < duration - 50)) player.play()
    }

    /** The frame on screen, at the video's own resolution (for Capture still). */
    fun grabFrame(): Bitmap? {
        val tv = texture ?: return null
        // Nothing on screen yet: let the caller fall back to reading the frame from the file
        if (!tv.isAvailable || shownId == null || shownId != itemId) return null
        val vs = player.videoSize
        if (vs.width <= 0 || vs.height <= 0) return tv.bitmap
        val w0 = vs.width * vs.pixelWidthHeightRatio
        val h0 = vs.height.toFloat()
        val k = min(1f, 4096f / max(w0, h0))
        return runCatching { tv.getBitmap((w0 * k).roundToInt().coerceAtLeast(1), (h0 * k).roundToInt().coerceAtLeast(1)) }.getOrNull()
    }

    private companion object {
        /** Show as many in-between frames as the phone can decode, not just the safe keyframes. */
        val SMOOTH_SCRUBBING: androidx.media3.exoplayer.ScrubbingModeParameters =
            androidx.media3.exoplayer.ScrubbingModeParameters.Builder()
                .setDisabledTrackTypes(setOf(C.TRACK_TYPE_AUDIO))
                // Exact frames: no "close enough" shortcuts
                .setFractionalSeekTolerance(null, null)
                .setShouldIncreaseCodecOperatingRate(true)
                .setShouldEnableDynamicScheduling(true)
                .setAllowSkippingMediaCodecFlush(true)
                .setAllowSkippingKeyFrameReset(true)
                .setUseDecodeOnlyFlag(true)
                .build()
    }

    internal fun applySound(muted: Boolean) {
        player.volume = if (muted) 0f else 1f
        // Muted videos don't interrupt music that's playing; with sound they take over like any player
        player.setAudioAttributes(
            AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(),
            !muted,
        )
    }
}

@Composable
fun rememberInlineVideo(): InlineVideo {
    val context = LocalContext.current
    val video = remember {
        InlineVideo(
            ExoPlayer.Builder(context).build().apply {
                setSeekParameters(SeekParameters.EXACT)
                repeatMode = Player.REPEAT_MODE_OFF
            },
        )
    }
    val player = video.player
    DisposableEffect(video) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                video.isPlaying = isPlaying
            }

            override fun onPlaybackStateChanged(state: Int) {
                video.ended = state == Player.STATE_ENDED
                if (player.duration != C.TIME_UNSET && player.duration > 0) video.duration = player.duration
            }

            override fun onPlayerError(e: androidx.media3.common.PlaybackException) {
                video.error = e.errorCodeName.removePrefix("ERROR_CODE_").lowercase().replace('_', ' ')
            }

            override fun onRenderedFirstFrame() {
                video.shownId = video.itemId
            }

            override fun onVideoSizeChanged(videoSize: VideoSize) {
                if (videoSize.width > 0 && videoSize.height > 0) {
                    video.aspect = videoSize.width * videoSize.pixelWidthHeightRatio / videoSize.height
                }
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.release()
            Playback.videoShowing = false
        }
    }
    // Sound follows the session's mute setting
    LaunchedEffect(video) {
        snapshotFlow { Playback.muted }.collect { video.applySound(it) }
    }
    // Position for the time readout and the timeline
    LaunchedEffect(video) {
        while (true) {
            if (!video.scrubbing && video.itemId != null) video.position = player.currentPosition
            delay(33)
        }
    }
    // Pause when the app goes to the background
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_PAUSE) player.pause() }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    return video
}

/**
 * The playing video, fitted in the page. A TextureView, so it slides with the page while swiping.
 * Shown only once its first frame is ready (the thumbnail underneath covers the gap).
 */
@Composable
fun InlineVideoSurface(video: InlineVideo, itemId: Long, modifier: Modifier = Modifier) {
    val aspect = video.aspect
    val visible = video.shownId == itemId && aspect > 0f
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        AndroidView(
            factory = { ctx ->
                TextureView(ctx).also { tv ->
                    video.texture = tv
                    video.player.setVideoTextureView(tv)
                }
            },
            onRelease = { tv ->
                video.player.clearVideoTextureView(tv)
                if (video.texture === tv) video.texture = null
            },
            update = { tv -> tv.alpha = if (visible) 1f else 0f },
            modifier = if (aspect > 0f) Modifier.aspectRatio(aspect) else Modifier.fillMaxSize(),
        )
    }
}

/** "0:03 / 0:15" style times. */
fun formatClock(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
}
