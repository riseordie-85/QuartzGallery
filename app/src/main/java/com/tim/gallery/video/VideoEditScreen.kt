package com.tim.gallery.video

import android.graphics.Bitmap
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.ContentCut
import androidx.compose.material.icons.outlined.Gif
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.ui.PlayerView
import com.tim.gallery.GalleryViewModel
import com.tim.gallery.data.GalleryItem
import com.tim.gallery.ui.SystemBarIcons
import com.tim.gallery.ui.formatPrecise
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

private enum class Export { CLIP, GIF, FRAMES }

/**
 * Video tools: pick a range on the filmstrip, then save it as a clip (optionally without sound),
 * as an animated GIF, or as individual frames.
 */
@OptIn(UnstableApi::class)
@Composable
fun VideoEditScreen(vm: GalleryViewModel, item: GalleryItem, onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    SystemBarIcons(darkBackground = true)
    com.tim.gallery.ui.PortraitOnPhones()

    val info by produceState<VideoInfo?>(null, item.id) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { VideoTools.info(context, item.uri) }
    }
    val durationMs = max(1L, info?.durationMs ?: item.durationMs)
    var startMs by remember { mutableLongStateOf(0L) }
    var endMs by remember { mutableLongStateOf(-1L) }
    val end = if (endMs < 0) durationMs else endMs
    var mute by remember { mutableStateOf(false) }
    var playing by remember { mutableStateOf(false) }
    var position by remember { mutableLongStateOf(0L) }

    var exporting by remember { mutableStateOf<Export?>(null) }
    var progress by remember { mutableFloatStateOf(0f) }
    var job by remember { mutableStateOf<Job?>(null) }
    var gifDialog by remember { mutableStateOf(false) }
    var framesDialog by remember { mutableStateOf(false) }

    val player = remember(item.id) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(item.uri))
            setSeekParameters(SeekParameters.EXACT)
            prepare()
        }
    }
    DisposableEffect(player) {
        val l = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                playing = isPlaying
            }
        }
        player.addListener(l)
        onDispose {
            player.removeListener(l)
            player.release()
        }
    }
    LaunchedEffect(mute) { player.volume = if (mute) 0f else 1f }
    // Pause when the app goes to the background
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, e ->
            if (e == androidx.lifecycle.Lifecycle.Event.ON_STOP) player.pause()
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }

    // Keep playback inside the chosen range (loops)
    val currentStart by rememberUpdatedState(startMs)
    val currentEnd by rememberUpdatedState(end)
    LaunchedEffect(player) {
        while (true) {
            position = player.currentPosition
            if (player.isPlaying && position >= currentEnd) player.seekTo(currentStart)
            delay(30)
        }
    }

    fun runExport(kind: Export, block: suspend () -> String) {
        exporting = kind
        progress = 0f
        player.pause()
        job = scope.launch {
            val message = try {
                block()
            } catch (e: kotlinx.coroutines.CancellationException) {
                "Cancelled"
            } catch (e: Exception) {
                e.message ?: "Something went wrong"
            }
            exporting = null
            job = null
            vm.refresh()
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
        }
    }

    val rangeMs = end - startMs
    val fps = info?.fps ?: 30f

    Column(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(4.dp)) {
            IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White) }
            Text("Video tools", color = Color.White, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        }

        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            AndroidView(
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        useController = false
                        setShutterBackgroundColor(android.graphics.Color.BLACK)
                        this.player = player
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )
            IconButton(
                onClick = {
                    if (player.isPlaying) {
                        player.pause()
                    } else {
                        if (player.currentPosition < startMs || player.currentPosition >= end) player.seekTo(startMs)
                        player.play()
                    }
                },
                modifier = Modifier.size(64.dp).background(Color.Black.copy(alpha = 0.45f), CircleShape),
            ) {
                Icon(if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, contentDescription = if (playing) "Pause" else "Play", tint = Color.White, modifier = Modifier.size(40.dp))
            }
        }

        // ---- range picker
        val stripHeight = 56.dp
        val stripPx = with(density) { stripHeight.roundToPx() }
        val thumbs by produceState<List<Bitmap?>>(emptyList(), item.id) {
            value = VideoTools.filmstrip(context, item.uri, 10, stripPx)
        }
        Text(
            "${formatPrecise(startMs)} – ${formatPrecise(end)}  ·  ${String.format(java.util.Locale.US, "%.1f", rangeMs / 1000f)} s",
            color = Color.White,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 6.dp),
        )
        RangeStrip(
            thumbs = thumbs,
            durationMs = durationMs,
            startMs = startMs,
            endMs = end,
            positionMs = { position },
            onChange = { s, e, seekTo ->
                startMs = s
                endMs = e
                player.pause()
                player.seekTo(seekTo)
            },
            modifier = Modifier.fillMaxWidth().height(stripHeight).padding(horizontal = 16.dp),
        )
        Text(
            "Drag the ends to choose the part you want. Clips start at the nearest keyframe, so they may begin a moment earlier.",
            color = Color.White.copy(alpha = 0.6f),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 16.dp)) {
            Text("Remove sound", color = Color.White, modifier = Modifier.weight(1f))
            Switch(checked = mute, onCheckedChange = { mute = it })
        }
        Row(
            horizontalArrangement = Arrangement.SpaceEvenly,
            modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
        ) {
            ExportButton(Icons.Outlined.ContentCut, "Save clip", enabled = exporting == null && rangeMs > 0) {
                runExport(Export.CLIP) {
                    VideoTools.trim(context, item, startMs, end, mute) { progress = it }
                    "Saved the clip next to the original"
                }
            }
            ExportButton(Icons.Outlined.Gif, "Save GIF", enabled = exporting == null && rangeMs > 0) { gifDialog = true }
            ExportButton(Icons.Outlined.PhotoLibrary, "Save frames", enabled = exporting == null && rangeMs > 0) { framesDialog = true }
        }
    }

    exporting?.let { kind ->
        AlertDialog(
            onDismissRequest = {},
            title = {
                Text(
                    when (kind) {
                        Export.CLIP -> "Saving clip…"
                        Export.GIF -> "Making GIF…"
                        Export.FRAMES -> "Saving frames…"
                    }
                )
            },
            text = { LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth()) },
            confirmButton = { TextButton(onClick = { job?.cancel() }) { Text("Cancel") } },
        )
    }

    if (gifDialog) {
        var width by remember { mutableIntStateOf(480) }
        var gifFps by remember { mutableIntStateOf(15) }
        val tooLong = rangeMs > 20_000
        AlertDialog(
            onDismissRequest = { gifDialog = false },
            title = { Text("Save as GIF") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Size", style = MaterialTheme.typography.labelLarge)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(320 to "Small", 480 to "Medium", 720 to "Large").forEach { (w, label) ->
                            FilterChip(selected = width == w, onClick = { width = w }, label = { Text("$label · $w px") })
                        }
                    }
                    Text("Smoothness", style = MaterialTheme.typography.labelLarge)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(10, 15, 24).forEach { f ->
                            FilterChip(selected = gifFps == f, onClick = { gifFps = f }, label = { Text("$f fps") })
                        }
                    }
                    Text(
                        if (tooLong) "GIFs get huge quickly — pick 20 seconds or less."
                        else "About ${(rangeMs / 1000f * gifFps).roundToInt()} frames. Saved to Pictures/Gallery.",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (tooLong) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                TextButton(enabled = !tooLong, onClick = {
                    gifDialog = false
                    runExport(Export.GIF) {
                        VideoTools.saveGif(context, item, startMs, end, width, gifFps) { progress = it }
                        "GIF saved to Pictures/Gallery"
                    }
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { gifDialog = false }) { Text("Cancel") } },
        )
    }

    if (framesDialog) {
        val options = listOf(
            1 to "Every frame",
            2 to "Every 2nd frame",
            5 to "Every 5th frame",
            max(1, fps.roundToInt()) to "1 per second",
        ).distinctBy { it.first }
        var step by remember { mutableIntStateOf(1) }
        val count = max(1, (rangeMs / 1000f * fps / step).roundToInt())
        val tooMany = count > 600
        AlertDialog(
            onDismissRequest = { framesDialog = false },
            title = { Text("Save frames as photos") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    options.forEach { (s, label) ->
                        FilterChip(selected = step == s, onClick = { step = s }, label = { Text(label) })
                    }
                    Text(
                        if (tooMany) "That's $count photos — pick a shorter part or fewer frames (600 max)."
                        else "$count full-resolution photos, saved to a new album named after the video.",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (tooMany) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                TextButton(enabled = !tooMany, onClick = {
                    framesDialog = false
                    runExport(Export.FRAMES) {
                        val n = VideoTools.saveFrames(context, item, startMs, end, step, count) { progress = it }
                        "Saved $n frames"
                    }
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { framesDialog = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun ExportButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, enabled: Boolean, onClick: () -> Unit) {
    TextButton(onClick = onClick, enabled = enabled) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, contentDescription = null, tint = if (enabled) Color.White else Color.White.copy(alpha = 0.35f))
            Text(label, color = if (enabled) Color.White else Color.White.copy(alpha = 0.35f), style = MaterialTheme.typography.labelMedium)
        }
    }
}

/** Filmstrip with two draggable ends and a playhead. */
@Composable
private fun RangeStrip(
    thumbs: List<Bitmap?>,
    durationMs: Long,
    startMs: Long,
    endMs: Long,
    /** Read only while drawing, so playback doesn't recompose the whole screen. */
    positionMs: () -> Long,
    onChange: (start: Long, end: Long, seekTo: Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val change by rememberUpdatedState(onChange)
    val s by rememberUpdatedState(startMs)
    val e by rememberUpdatedState(endMs)
    val minGap = 200L
    BoxWithConstraints(modifier) {
        val widthPx = constraints.maxWidth.toFloat()
        fun xOf(ms: Long) = ms.toFloat() / durationMs * widthPx
        Row(Modifier.fillMaxSize().clip(RoundedCornerShape(8.dp))) {
            if (thumbs.isEmpty()) {
                Box(Modifier.fillMaxSize().background(Color.DarkGray))
            } else {
                thumbs.forEach { b ->
                    Box(Modifier.weight(1f).fillMaxHeight().background(Color.DarkGray)) {
                        if (b != null) Image(b.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                    }
                }
            }
        }
        val handleW = with(density) { 14.dp.toPx() }
        Canvas(
            Modifier
                .fillMaxSize()
                .pointerInput(widthPx, durationMs) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        val x0 = down.position.x
                        // Grab whichever end is closer
                        val grabStart = abs(x0 - xOf(s)) <= abs(x0 - xOf(e))
                        down.consume()
                        do {
                            val ev = awaitPointerEvent()
                            val p = ev.changes.firstOrNull { it.pressed } ?: break
                            p.consume()
                            val ms = (p.position.x / widthPx * durationMs).toLong().coerceIn(0L, durationMs)
                            if (grabStart) {
                                val ns = ms.coerceAtMost(e - minGap).coerceAtLeast(0L)
                                change(ns, e, ns)
                            } else {
                                val ne = ms.coerceAtLeast(s + minGap).coerceAtMost(durationMs)
                                change(s, ne, ne)
                            }
                        } while (ev.changes.any { it.pressed })
                    }
                }
        ) {
            val l = xOf(startMs)
            val r = xOf(endMs)
            val dim = Color.Black.copy(alpha = 0.6f)
            drawRect(dim, Offset.Zero, Size(l, size.height))
            drawRect(dim, Offset(r, 0f), Size(size.width - r, size.height))
            drawRoundRect(Color(0xFFFFD54F), Offset(l, 0f), Size(r - l, size.height), CornerRadius(8f), style = Stroke(3.dp.toPx()))
            drawRoundRect(Color(0xFFFFD54F), Offset(l - handleW / 2f, 0f), Size(handleW, size.height), CornerRadius(handleW / 2f))
            drawRoundRect(Color(0xFFFFD54F), Offset(r - handleW / 2f, 0f), Size(handleW, size.height), CornerRadius(handleW / 2f))
            val px = xOf(positionMs())
            drawLine(Color.White, Offset(px, -4f), Offset(px, size.height + 4f), 2.dp.toPx())
        }
    }
}
