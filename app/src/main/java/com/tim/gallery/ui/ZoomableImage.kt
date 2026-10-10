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
) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var boxSize by remember { mutableStateOf(IntSize.Zero) }
    val tap by rememberUpdatedState(onTap)
    val zoomChanged by rememberUpdatedState(onZoomChange)
    val longPress by rememberUpdatedState(onLongPress)
    val hasLongPress = onLongPress != null

    fun clamp(o: Offset, s: Float): Offset {
        val maxX = boxSize.width * (s - 1f) / 2f
        val maxY = boxSize.height * (s - 1f) / 2f
        return Offset(o.x.coerceIn(-maxX, maxX), o.y.coerceIn(-maxY, maxY))
    }

    // Reset zoom when the user swipes away from this page
    LaunchedEffect(isCurrent) {
        if (!isCurrent && scale != 1f) {
            scale = 1f
            offset = Offset.Zero
        }
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
                            offset = clamp((p - center) * (1f - DOUBLE_TAP_ZOOM), DOUBLE_TAP_ZOOM)
                            scale = DOUBLE_TAP_ZOOM
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
                            val newScale = (scale * zoom).coerceIn(1f, MAX_ZOOM)
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
        AsyncImage(
            model = model,
            contentDescription = contentDescription,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationX = offset.x
                    translationY = offset.y
                },
        )
    }
}
