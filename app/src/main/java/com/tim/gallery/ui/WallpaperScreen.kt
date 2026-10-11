package com.tim.gallery.ui

import android.app.WallpaperManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.text.format.DateFormat
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.RotateLeft
import androidx.compose.material.icons.automirrored.outlined.RotateRight
import androidx.compose.material.icons.outlined.AccessTime
import androidx.compose.material.icons.outlined.FitScreen
import androidx.compose.material.icons.outlined.Fullscreen
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.foundation.Canvas as ComposeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tim.gallery.data.GalleryItem
import com.tim.gallery.data.MediaSaver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Date
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * "Set as wallpaper" with a preview: the photo fills the screen exactly as the wallpaper will.
 * Pinch and drag to frame it, rotate it, zoom out to fit (gaps get a soft blur of the photo),
 * and peek at a lock-screen clock. What's on screen is what gets set.
 */
@Composable
fun WallpaperScreen(item: GalleryItem, onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    SystemBarIcons(darkBackground = true)
    PortraitOnPhones()

    val photo by produceState<Bitmap?>(null, item.id) {
        value = withContext(Dispatchers.IO) {
            val screen = screenSize(context)
            runCatching { MediaSaver.decodeUpright(context, item.uri, min(4096, max(screen.first, screen.second) * 2)) }.getOrNull()
        }
        if (value == null) {
            Toast.makeText(context, "Couldn't open this photo", Toast.LENGTH_SHORT).show()
            onClose()
        }
    }
    val blur by produceState<Bitmap?>(null, photo) {
        val p = photo ?: return@produceState
        value = withContext(Dispatchers.Default) { softBlur(p) }
    }

    var quarterTurns by remember { mutableIntStateOf(0) }
    // Zoom relative to "fills the screen" (1 = fill), and offset from the center in screen pixels
    var zoom by remember { mutableFloatStateOf(1f) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }
    var showClock by remember { mutableStateOf(false) }
    var controlsVisible by remember { mutableStateOf(true) }
    var choosing by remember { mutableStateOf(false) }
    var setting by remember { mutableStateOf(false) }

    // While the wallpaper is being set, Back waits (leaving would hide whether it worked)
    BackHandler { if (!setting) onClose() }

    BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black)) {
        // The preview has the whole display's shape (they differ in split-screen or a window)
        val display = remember(constraints.maxWidth, constraints.maxHeight) { screenSize(context) }
        val fit = min(constraints.maxWidth.toFloat() / display.first, constraints.maxHeight.toFloat() / display.second)
        val w = display.first * fit
        val h = display.second * fit
        val density = androidx.compose.ui.platform.LocalDensity.current
        val src = photo

        if (src == null) {
            CircularProgressIndicator(color = Color.White, modifier = Modifier.align(Alignment.Center))
            return@BoxWithConstraints
        }

        val frame = remember(src, quarterTurns, w, h) { WallpaperFrame(src.width, src.height, quarterTurns, w, h) }
        fun clampAll() {
            zoom = zoom.coerceIn(frame.minZoom, MAX_ZOOM)
            val (cx, cy) = frame.clampOffset(zoom, offsetX, offsetY)
            offsetX = cx
            offsetY = cy
        }
        // Screen size changed (rotation, unfolding) or the photo turned: keep it valid
        LaunchedEffect(w, h, quarterTurns) { clampAll() }

        ComposeCanvas(
            Modifier
                .align(Alignment.Center)
                .size(with(density) { w.toDp() }, with(density) { h.toDp() })
                .pointerInput(frame) {
                    detectTapGestures(
                        onTap = { controlsVisible = !controlsVisible },
                        onDoubleTap = {
                            // Toggle between "fill" and "whole photo"
                            zoom = if (abs(zoom - 1f) < 0.01f) frame.minZoom else 1f
                            offsetX = 0f
                            offsetY = 0f
                        },
                    )
                }
                .pointerInput(frame) {
                    detectTransformGestures { centroid, pan, gestureZoom, _ ->
                        val newZoom = (zoom * gestureZoom).coerceIn(frame.minZoom, MAX_ZOOM)
                        val k = newZoom / zoom
                        // Zoom around the fingers: keep the point under them still
                        val fx = centroid.x - w / 2f
                        val fy = centroid.y - h / 2f
                        val nx = (offsetX - fx) * k + fx + pan.x
                        val ny = (offsetY - fy) * k + fy + pan.y
                        zoom = newZoom
                        val (cx, cy) = frame.clampOffset(newZoom, nx, ny)
                        offsetX = cx
                        offsetY = cy
                    }
                },
        ) {
            drawIntoCanvas { c ->
                drawWallpaper(c.nativeCanvas, size.width, size.height, src, blur, quarterTurns, frame.coverScale * zoom, offsetX, offsetY)
            }
        }

        if (showClock) LockClockPreview(Modifier.align(Alignment.TopCenter))

        AnimatedVisibility(
            visible = controlsVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.fillMaxSize(),
        ) {
            Box(Modifier.fillMaxSize()) {
                IconButton(
                    onClick = onClose,
                    enabled = !setting,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .statusBarsPadding()
                        .padding(8.dp)
                        .background(Color.Black.copy(alpha = 0.4f), androidx.compose.foundation.shape.CircleShape),
                ) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
                }
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .background(Color.Black.copy(alpha = 0.55f))
                        .navigationBarsPadding()
                        .padding(horizontal = 8.dp, vertical = 10.dp),
                ) {
                    Row(horizontalArrangement = Arrangement.SpaceEvenly, modifier = Modifier.fillMaxWidth()) {
                        WallpaperTool(Icons.AutoMirrored.Outlined.RotateLeft, "Rotate left") {
                            quarterTurns = (quarterTurns + 3) % 4
                            zoom = 1f; offsetX = 0f; offsetY = 0f
                        }
                        WallpaperTool(Icons.AutoMirrored.Outlined.RotateRight, "Rotate right") {
                            quarterTurns = (quarterTurns + 1) % 4
                            zoom = 1f; offsetX = 0f; offsetY = 0f
                        }
                        val filled = abs(zoom - 1f) < 0.01f
                        WallpaperTool(
                            if (filled) Icons.Outlined.FitScreen else Icons.Outlined.Fullscreen,
                            if (filled) "Whole photo" else "Fill screen",
                        ) {
                            zoom = if (filled) frame.minZoom else 1f
                            offsetX = 0f; offsetY = 0f
                        }
                        WallpaperTool(Icons.Outlined.AccessTime, if (showClock) "Hide clock" else "Lock screen", selected = showClock) {
                            showClock = !showClock
                        }
                        WallpaperTool(Icons.Outlined.Restore, "Reset") {
                            quarterTurns = 0; zoom = 1f; offsetX = 0f; offsetY = 0f
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Pinch and drag to frame it · double-tap to fit",
                        color = Color.White.copy(alpha = 0.7f),
                        fontSize = 12.sp,
                    )
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = { choosing = true }, enabled = !setting, modifier = Modifier.fillMaxWidth(0.8f)) {
                        if (setting) {
                            CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.height(20.dp))
                        } else {
                            Text("Set wallpaper")
                        }
                    }
                }
            }
        }

        if (choosing) {
            WallpaperTargetDialog(
                onDismiss = { choosing = false },
                onPick = { which ->
                    choosing = false
                    setting = true
                    val turns = quarterTurns
                    val scale = frame.coverScale * zoom
                    val ox = offsetX
                    val oy = offsetY
                    val b = blur
                    scope.launch {
                        val ok = withContext(Dispatchers.Default) {
                            runCatching {
                                // Render at the screen's real resolution, framed exactly like the preview
                                val (sw, sh) = display
                                val k = sw / w
                                val outW = sw
                                val outH = sh
                                val out = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
                                drawWallpaper(Canvas(out), outW.toFloat(), outH.toFloat(), src, b, turns, scale * k, ox * k, oy * k)
                                WallpaperManager.getInstance(context).setBitmap(out, null, true, which)
                                out.recycle()
                            }.isSuccess
                        }
                        setting = false
                        Toast.makeText(context, if (ok) "Wallpaper set" else "Couldn't set the wallpaper", Toast.LENGTH_SHORT).show()
                        if (ok) onClose()
                    }
                },
            )
        }
    }
}

private const val MAX_ZOOM = 6f

/** Sizes for a photo turned [quarterTurns] × 90° on a [screenW] × [screenH] screen. */
private class WallpaperFrame(srcW: Int, srcH: Int, quarterTurns: Int, val screenW: Float, val screenH: Float) {
    private val turned = quarterTurns % 2 == 1
    val imgW = (if (turned) srcH else srcW).toFloat()
    val imgH = (if (turned) srcW else srcH).toFloat()

    /** Scale where the photo just fills the screen. */
    val coverScale = max(screenW / imgW, screenH / imgH)

    /** Zoom (relative to fill) where the whole photo just fits. */
    val minZoom = min(screenW / imgW, screenH / imgH) / coverScale

    /** Keeps the photo over the screen: no gaps where it's big enough, centered where it isn't. */
    fun clampOffset(zoom: Float, x: Float, y: Float): Pair<Float, Float> {
        val dw = imgW * coverScale * zoom
        val dh = imgH * coverScale * zoom
        val mx = max(0f, (dw - screenW) / 2f)
        val my = max(0f, (dh - screenH) / 2f)
        return x.coerceIn(-mx, mx) to y.coerceIn(-my, my)
    }
}

/** Draws the wallpaper: soft-blurred fill behind, then the photo turned, scaled and moved. */
private fun drawWallpaper(
    canvas: Canvas,
    w: Float,
    h: Float,
    src: Bitmap,
    blur: Bitmap?,
    quarterTurns: Int,
    scale: Float,
    offsetX: Float,
    offsetY: Float,
) {
    val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    val turned = quarterTurns % 2 == 1
    val imgW = if (turned) src.height else src.width
    val imgH = if (turned) src.width else src.height
    val covers = imgW * scale >= w - 0.5f && imgH * scale >= h - 0.5f &&
        abs(offsetX) <= (imgW * scale - w) / 2f + 0.5f && abs(offsetY) <= (imgH * scale - h) / 2f + 0.5f
    canvas.drawColor(android.graphics.Color.BLACK)
    if (!covers && blur != null) {
        // The fill: the blurred photo covering the screen, a little darker
        val bw = if (turned) blur.height else blur.width
        val bh = if (turned) blur.width else blur.height
        val k = max(w / bw, h / bh)
        val m = Matrix().apply {
            postTranslate(-blur.width / 2f, -blur.height / 2f)
            postRotate(quarterTurns * 90f)
            postScale(k, k)
            postTranslate(w / 2f, h / 2f)
        }
        canvas.drawBitmap(blur, m, paint)
        canvas.drawColor(android.graphics.Color.argb(70, 0, 0, 0))
    }
    val m = Matrix().apply {
        postTranslate(-src.width / 2f, -src.height / 2f)
        postRotate(quarterTurns * 90f)
        postScale(scale, scale)
        postTranslate(w / 2f + offsetX, h / 2f + offsetY)
    }
    canvas.drawBitmap(src, m, paint)
}

/** A very soft blur: shrink the photo to a few pixels and let smooth scaling spread it out. */
private fun softBlur(src: Bitmap): Bitmap {
    var b = src
    var w = src.width
    var h = src.height
    // Halve repeatedly (smooth), down to about 24 px across
    while (max(w, h) > 24) {
        w = max(1, w / 2)
        h = max(1, h / 2)
        val next = Bitmap.createScaledBitmap(b, w, h, true)
        if (b !== src) b.recycle()
        b = next
    }
    return b
}

/** The display's full size in pixels (including behind the system bars, ignoring split-screen). */
private fun screenSize(context: Context): Pair<Int, Int> {
    val wm = context.getSystemService(WindowManager::class.java)
    val bounds = wm.maximumWindowMetrics.bounds
    return bounds.width() to bounds.height()
}

@Composable
private fun LockClockPreview(modifier: Modifier) {
    val context = LocalContext.current
    val now = remember { Date() }
    val time = remember(now) {
        // Hours and minutes in the phone's style, without AM/PM (like the lock screen)
        val locale = java.util.Locale.getDefault()
        val pattern = DateFormat.getBestDateTimePattern(locale, if (DateFormat.is24HourFormat(context)) "Hm" else "hm")
            .replace("a", "").trim()
        java.text.SimpleDateFormat(pattern, locale).format(now)
    }
    val date = remember(now) { android.text.format.DateFormat.format("EEE, MMM d", now).toString() }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier.statusBarsPadding().padding(top = 56.dp),
    ) {
        Text(date, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Medium)
        Text(time, color = Color.White, fontSize = 86.sp, fontWeight = FontWeight.Light)
    }
}

@Composable
private fun WallpaperTool(icon: ImageVector, label: String, selected: Boolean = false, onClick: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 4.dp),
    ) {
        Icon(icon, contentDescription = label, tint = if (selected) Color(0xFF8AB4F8) else Color.White)
        Text(label, color = Color.White, fontSize = 11.sp)
    }
}

/** Home screen, lock screen, or both. */
@Composable
fun WallpaperTargetDialog(onDismiss: () -> Unit, onPick: (Int) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Set as wallpaper") },
        text = {
            Column {
                ListItem(
                    headlineContent = { Text("Home screen") },
                    modifier = Modifier.clickable { onPick(WallpaperManager.FLAG_SYSTEM) },
                )
                ListItem(
                    headlineContent = { Text("Lock screen") },
                    modifier = Modifier.clickable { onPick(WallpaperManager.FLAG_LOCK) },
                )
                ListItem(
                    headlineContent = { Text("Home and lock screens") },
                    modifier = Modifier.clickable { onPick(WallpaperManager.FLAG_SYSTEM or WallpaperManager.FLAG_LOCK) },
                )
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
