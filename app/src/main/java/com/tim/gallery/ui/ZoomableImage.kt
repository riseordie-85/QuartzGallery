package com.tim.gallery.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import coil.compose.AsyncImage

private const val MAX_ZOOM = 6f
private const val DOUBLE_TAP_ZOOM = 2.5f

/** Zoomed in past this, a sharper copy is loaded (up to the full picture). */
private const val HI_RES_ZOOM = 1.3f

/**
 * Photo with pinch-to-zoom, pan and double-tap zoom. Single-finger swipes pass through
 * to the pager when not zoomed in, so you can still swipe between photos.
 */
@Composable
fun ZoomableImage(
    model: Any?,
    contentDescription: String?,
    isCurrent: Boolean,
    onTap: () -> Unit,
    onZoomChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    onLongPress: (() -> Unit)? = null,
    /** Draws the picture instead of loading [model] (used for animated GIFs). Gets the zoom/pan modifier. */
    content: (@Composable (Modifier) -> Unit)? = null,
    /** Keyboard zoom and pan (+, -, 0, arrows while zoomed): a fresh [ZoomKey] each press. */
    zoomKey: ZoomKey? = null,
) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var boxSize by remember { mutableStateOf(IntSize.Zero) }
    // The picture's shape (width / height), once it's loaded: a panorama zooms in further
    var aspect by remember(model) { mutableFloatStateOf(0f) }
    // Zoomed in once: the sharp copy stays loaded on top until the page is left
    var hiRes by remember(model) { mutableStateOf(false) }
    val tap by rememberUpdatedState(onTap)
    val zoomChanged by rememberUpdatedState(onZoomChange)
    val longPress by rememberUpdatedState(onLongPress)
    val hasLongPress = onLongPress != null

    // The picture's size on screen before zooming (it's fitted in the box)
    fun fitted(): Pair<Float, Float> {
        val bw = boxSize.width.toFloat()
        val bh = boxSize.height.toFloat()
        if (aspect <= 0f || bw <= 0f || bh <= 0f) return bw to bh
        return if (bw / bh > aspect) (bh * aspect) to bh else bw to (bw / aspect)
    }
    // Zoom that fills the box (a panorama's height fills the screen)
    fun fillZoom(): Float {
        val (fw, fh) = fitted()
        if (fw <= 0f || fh <= 0f) return 1f
        return maxOf(boxSize.width / fw, boxSize.height / fh)
    }
    fun maxZoom(): Float = maxOf(MAX_ZOOM, fillZoom() * 2f)
    fun doubleTapZoom(): Float = maxOf(DOUBLE_TAP_ZOOM, fillZoom()).coerceAtMost(maxZoom())

    fun clamp(o: Offset, s: Float): Offset {
        // (Only as far as the picture reaches: no panning into the empty space beside it)
        val (fw, fh) = fitted()
        val maxX = maxOf(0f, (fw * s - boxSize.width) / 2f)
        val maxY = maxOf(0f, (fh * s - boxSize.height) / 2f)
        return Offset(o.x.coerceIn(-maxX, maxX), o.y.coerceIn(-maxY, maxY))
    }

    // Reset zoom when the user swipes away from this page
    LaunchedEffect(isCurrent) {
        if (!isCurrent && scale != 1f) {
            scale = 1f
            offset = Offset.Zero
        }
        if (!isCurrent) hiRes = false
    }

    // Keyboard: zoom about the middle, or pan while zoomed in
    LaunchedEffect(zoomKey) {
        val k = zoomKey ?: return@LaunchedEffect
        if (!isCurrent) return@LaunchedEffect
        when (k.action) {
            ZoomKey.Action.IN, ZoomKey.Action.OUT -> {
                val target = (if (k.action == ZoomKey.Action.IN) scale * 1.5f else scale / 1.5f).coerceIn(1f, maxZoom())
                offset = if (target <= 1.001f) Offset.Zero else clamp(offset * (target / scale), target)
                scale = if (target <= 1.001f) 1f else target
                if (scale > HI_RES_ZOOM) hiRes = true
            }
            ZoomKey.Action.RESET -> {
                scale = 1f
                offset = Offset.Zero
            }
            ZoomKey.Action.PAN -> {
                // Arrow keys move the view, so the picture moves the other way
                offset = clamp(offset - Offset(k.dx * boxSize.width * 0.15f, k.dy * boxSize.height * 0.15f), scale)
            }
        }
        zoomChanged(scale > 1.01f)
    }

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .fillMaxSize()
            .clipToBounds()
            .onSizeChanged { boxSize = it }
            .pointerInput(hasLongPress) {
                detectTapGestures(
                    onTap = { tap() },
                    // Only listen for holds when there's something to do (holding would block swipes)
                    onLongPress = if (hasLongPress) ({ longPress?.invoke() }) else null,
                    onDoubleTap = { p ->
                        if (scale > 1.01f) {
                            scale = 1f
                            offset = Offset.Zero
                            zoomChanged(false)
                        } else {
                            val center = Offset(size.width / 2f, size.height / 2f)
                            val z = doubleTapZoom()
                            offset = clamp((p - center) * (1f - z), z)
                            scale = z
                            if (z > HI_RES_ZOOM) hiRes = true
                            zoomChanged(true)
                        }
                    },
                )
            }
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val event = awaitPointerEvent()
                        val fingers = event.changes.count { it.pressed }
                        if (fingers >= 2 || scale > 1.01f) {
                            val zoom = event.calculateZoom()
                            val pan = event.calculatePan()
                            val centroid = event.calculateCentroid(useCurrent = true)
                            val newScale = (scale * zoom).coerceIn(1f, maxZoom())
                            if (newScale > HI_RES_ZOOM) hiRes = true
                            val center = Offset(size.width / 2f, size.height / 2f)

                            var newOffset = offset
                            if (centroid.isSpecified) {
                                // keep the point under the fingers in place while zooming
                                val fromCenter = centroid - center
                                newOffset = fromCenter - (fromCenter - offset) * (newScale / scale)
                            }
                            newOffset += pan

                            scale = newScale
                            offset = if (newScale <= 1.001f) Offset.Zero else clamp(newOffset, newScale)
                            event.changes.forEach { if (it.positionChanged()) it.consume() }
                        }
                    } while (event.changes.any { it.pressed })
                    zoomChanged(scale > 1.01f)
                }
            },
    ) {
        val imageModifier = Modifier
            .fillMaxSize()
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                translationX = offset.x
                translationY = offset.y
            }
        if (content != null) {
            content(imageModifier)
        } else {
            AsyncImage(
                model = model,
                contentDescription = contentDescription,
                contentScale = ContentScale.Fit,
                modifier = imageModifier,
                onSuccess = { st ->
                    val s = st.painter.intrinsicSize
                    if (s.width > 0f && s.height > 0f) aspect = s.width / s.height
                },
            )
            // Zoomed in: a sharper copy on top (up to the full picture, within the GPU's and memory's limits)
            if (hiRes && aspect > 0f && model != null) {
                val ctx = androidx.compose.ui.platform.LocalContext.current
                val request: coil.request.ImageRequest = remember(model, aspect) {
                    val side = com.tim.gallery.data.ImageLimits.maxSide
                    val px = com.tim.gallery.data.ImageLimits.MAX_PIXELS
                    // The biggest box of this shape within the limits (the decoder never enlarges)
                    var w = side.toFloat()
                    var h = w / aspect
                    if (h > side) { h = side.toFloat(); w = h * aspect }
                    val k = minOf(1f, kotlin.math.sqrt(px / (w.toDouble() * h)).toFloat())
                    coil.request.ImageRequest.Builder(ctx)
                        .data(model)
                        .size((w * k).toInt().coerceAtLeast(1), (h * k).toInt().coerceAtLeast(1))
                        .precision(coil.size.Precision.INEXACT)
                        .build()
                }
                AsyncImage(
                    model = request,
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = imageModifier,
                )
            }
        }
    }
}

/** One keyboard zoom or pan step for [ZoomableImage]; a new instance per key press. */
class ZoomKey(val action: Action, val dx: Float = 0f, val dy: Float = 0f) {
    enum class Action { IN, OUT, RESET, PAN }
}
