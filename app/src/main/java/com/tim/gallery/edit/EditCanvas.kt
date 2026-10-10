package com.tim.gallery.edit

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Paint
import android.graphics.Shader
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateRotation
import androidx.compose.foundation.gestures.awaitTouchSlopOrCancellation
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** Checkerboard paint for showing transparency. */
@Composable
private fun rememberCheckerPaint(): Paint {
    val density = LocalDensity.current
    return remember(density) {
        val cell = with(density) { 8.dp.roundToPx() }.coerceAtLeast(2)
        val bmp = Bitmap.createBitmap(cell * 2, cell * 2, Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(bmp)
        c.drawColor(0xFFBDBDBD.toInt())
        val p = Paint().apply { color = 0xFF8E8E8E.toInt() }
        c.drawRect(0f, 0f, cell.toFloat(), cell.toFloat(), p)
        c.drawRect(cell.toFloat(), cell.toFloat(), cell * 2f, cell * 2f, p)
        Paint().apply { shader = BitmapShader(bmp, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT) }
    }
}

/** Fits a w×h box inside the available space and returns (scale, offsetX, offsetY). */
private fun fit(w: Float, h: Float, availW: Float, availH: Float): Triple<Float, Float, Float> {
    val s = min(availW / w, availH / h)
    return Triple(s, (availW - w * s) / 2f, (availH - h * s) / 2f)
}

// ===================================================================== finished picture

/**
 * Shows the finished picture. With [markup] on, stickers and text can be selected,
 * dragged, pinched, twisted, deleted, and (tap again) edited.
 */
@Composable
fun OutputView(
    session: EditSession,
    state: EditState,
    modifier: Modifier = Modifier,
    markup: Boolean = false,
    selectedId: Long? = null,
    onSelect: (Long?) -> Unit = {},
    onLayerChange: (Layer) -> Unit = {},
    onGestureEnd: () -> Unit = {},
    onEditText: (TextLayer) -> Unit = {},
    onDelete: (Long) -> Unit = {},
) {
    val src = session.preview ?: return
    val color = remember { ColorShader() }
    val checker = rememberCheckerPaint()
    val density = LocalDensity.current
    val handleR = with(density) { 14.dp.toPx() }
    val assets: LayerAssets = session
    // Redraw when a downloaded or imported font finishes loading
    val fontVersion by Fonts.version.collectAsState()

    val currentState by rememberUpdatedState(state)
    val currentSelected by rememberUpdatedState(selectedId)
    val select by rememberUpdatedState(onSelect)
    val change by rememberUpdatedState(onLayerChange)
    val end by rememberUpdatedState(onGestureEnd)
    val editText by rememberUpdatedState(onEditText)
    val delete by rememberUpdatedState(onDelete)

    BoxWithConstraints(modifier.fillMaxSize()) {
        val availW = constraints.maxWidth.toFloat()
        val availH = constraints.maxHeight.toFloat()

        // All geometry here is in "output" pixels, mapped to the screen by (scale, ox, oy)
        fun mapping(): Triple<Float, Float, Float> {
            val out = EditRenderer.outputSize(currentState.geometry, session.fullW, session.fullH)
            return fit(out.width, out.height, availW, availH)
        }

        val gestures = if (!markup) Modifier else Modifier.pointerInput(availW, availH) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                val (s, ox, oy) = mapping()
                val out = EditRenderer.outputSize(currentState.geometry, session.fullW, session.fullH)
                fun toOut(p: Offset) = Offset((p.x - ox) / s, (p.y - oy) / s)

                val p0 = toOut(down.position)
                val layers = currentState.layers
                val selected = layers.firstOrNull { it.id == currentSelected }
                val slop = handleR / s

                var mode = GestureMode.NONE
                var target: Layer? = selected
                var wasSelected = false
                if (selected != null) {
                    val sz = EditRenderer.layerSize(selected, out, assets)
                    val del = handlePoint(selected, out, sz, -1f, -1f)
                    val xf = handlePoint(selected, out, sz, 1f, 1f)
                    mode = when {
                        (p0 - del).getDistance() <= slop * 1.4f -> GestureMode.DELETE
                        (p0 - xf).getDistance() <= slop * 1.4f -> GestureMode.HANDLE
                        else -> GestureMode.NONE
                    }
                }
                if (mode == GestureMode.NONE) {
                    val hit = layers.asReversed().firstOrNull { hits(it, p0, out, assets, slop * 0.5f) }
                    if (hit != null) {
                        mode = GestureMode.MOVE
                        wasSelected = hit.id == currentSelected
                        target = hit
                        if (!wasSelected) select(hit.id)
                    }
                }

                var current = target
                val center0 = current?.let { Offset(it.cx * out.width, it.cy * out.height) } ?: Offset.Zero
                val v0 = p0 - center0
                val scale0 = current?.scale ?: 1f
                val rot0 = current?.rotation ?: 0f
                var moved = false
                var travel = 0f
                var pendingPan = Offset.Zero

                do {
                    val event = awaitPointerEvent()
                    val pressed = event.changes.count { it.pressed }
                    event.changes.forEach { travel += it.positionChange().getDistance() }
                    // Movement before the touch slop is saved and applied once dragging starts,
                    // so the layer stays under the finger
                    val eventPan = event.calculatePan()
                    val pan: Offset
                    if (!moved) {
                        pendingPan += eventPan
                        if (travel > viewConfiguration.touchSlop) {
                            moved = true
                            pan = pendingPan
                        } else {
                            pan = Offset.Zero
                        }
                    } else {
                        pan = eventPan
                    }
                    val layer = current
                    if (layer != null && moved) {
                        when (mode) {
                            GestureMode.HANDLE -> {
                                val pos = event.changes.firstOrNull { it.pressed }?.position
                                if (pos != null) {
                                    val v = toOut(pos) - center0
                                    val d0 = max(1f, v0.getDistance())
                                    val newScale = (scale0 * v.getDistance() / d0).coerceIn(0.05f, 8f)
                                    val newRot = rot0 + Math.toDegrees(
                                        (atan2(v.y, v.x) - atan2(v0.y, v0.x)).toDouble()
                                    ).toFloat()
                                    current = layer.moved(layer.cx, layer.cy, newScale, newRot)
                                }
                            }
                            GestureMode.MOVE, GestureMode.NONE -> {
                                // NONE: two fingers anywhere adjust the selected layer
                                if (mode == GestureMode.MOVE || pressed >= 2) {
                                    val zoom = event.calculateZoom()
                                    val rot = event.calculateRotation()
                                    current = layer.moved(
                                        (layer.cx + pan.x / s / out.width).coerceIn(-0.2f, 1.2f),
                                        (layer.cy + pan.y / s / out.height).coerceIn(-0.2f, 1.2f),
                                        (layer.scale * zoom).coerceIn(0.05f, 8f),
                                        layer.rotation + rot,
                                    )
                                }
                            }
                            GestureMode.DELETE -> Unit
                        }
                        val updated = current
                        if (updated != null && updated != layer) change(updated)
                        event.changes.forEach { it.consume() }
                    }
                } while (event.changes.any { it.pressed })

                if (!moved) {
                    when (mode) {
                        GestureMode.DELETE -> target?.let { delete(it.id) }
                        GestureMode.MOVE -> {
                            val t = target
                            if (wasSelected && t is TextLayer) editText(t)
                        }
                        GestureMode.NONE -> select(null)
                        GestureMode.HANDLE -> Unit
                    }
                }
                end()
            }
        }

        Canvas(Modifier.fillMaxSize().then(gestures)) {
            if (fontVersion < 0) return@Canvas
            val out = EditRenderer.outputSize(state.geometry, session.fullW, session.fullH)
            val (s, ox, oy) = fit(out.width, out.height, size.width, size.height)
            drawIntoCanvas { c ->
                val nc = c.nativeCanvas
                nc.save()
                nc.translate(ox, oy)
                if (state.needsAlpha) nc.drawRect(0f, 0f, out.width * s, out.height * s, checker)
                nc.scale(s, s)
                EditRenderer.drawOutput(nc, state, src, session.fullW, session.fullH, color, assets)
                nc.restore()
            }

            // Selection box and handles (screen only, never saved)
            val sel = state.layers.firstOrNull { it.id == selectedId }
            if (markup && sel != null) {
                val sz = EditRenderer.layerSize(sel, out, assets)
                val center = Offset(ox + sel.cx * out.width * s, oy + sel.cy * out.height * s)
                val w = sz.width * s
                val h = sz.height * s
                withTransform({
                    translate(center.x, center.y)
                    rotate(sel.rotation, pivot = Offset.Zero)
                }) {
                    drawRect(
                        color = Color.White,
                        topLeft = Offset(-w / 2f, -h / 2f),
                        size = Size(w, h),
                        style = Stroke(
                            width = 1.5.dp.toPx(),
                            pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f)),
                        ),
                    )
                    // delete (top-left)
                    drawCircle(Color(0xFFE53935), handleR, Offset(-w / 2f, -h / 2f))
                    val x = handleR * 0.4f
                    translate(-w / 2f, -h / 2f) {
                        drawLine(Color.White, Offset(-x, -x), Offset(x, x), 2.dp.toPx())
                        drawLine(Color.White, Offset(-x, x), Offset(x, -x), 2.dp.toPx())
                    }
                    // resize / rotate (bottom-right)
                    drawCircle(Color.White, handleR, Offset(w / 2f, h / 2f))
                    drawCircle(Color(0xFF424242), handleR * 0.35f, Offset(w / 2f, h / 2f))
                }
            }
        }
    }
}

private enum class GestureMode { NONE, MOVE, HANDLE, DELETE }

/** A corner of a layer's box (sx, sy = ±1) in output pixels. */
private fun handlePoint(layer: Layer, out: FSize, size: FSize, sx: Float, sy: Float): Offset {
    val lx = sx * size.width / 2f
    val ly = sy * size.height / 2f
    val rad = Math.toRadians(layer.rotation.toDouble())
    val c = cos(rad).toFloat()
    val s = sin(rad).toFloat()
    return Offset(layer.cx * out.width + lx * c - ly * s, layer.cy * out.height + lx * s + ly * c)
}

private fun hits(layer: Layer, p: Offset, out: FSize, assets: LayerAssets, slop: Float): Boolean {
    val size = EditRenderer.layerSize(layer, out, assets)
    val d = p - Offset(layer.cx * out.width, layer.cy * out.height)
    val rad = Math.toRadians(-layer.rotation.toDouble())
    val c = cos(rad).toFloat()
    val s = sin(rad).toFloat()
    val lx = d.x * c - d.y * s
    val ly = d.x * s + d.y * c
    return abs(lx) <= size.width / 2f + slop && abs(ly) <= size.height / 2f + slop
}

// ===================================================================== crop

private enum class Handle { TL, TR, BL, BR, L, T, R, B, MOVE }

/**
 * Shows the whole straightened photo (its "frame") with a draggable crop box.
 * In Background mode the view also shows space around the photo, and the crop box can be
 * dragged past the photo's edges to add background.
 * [straightening] shows a finer grid while the angle slider is being dragged.
 */
@Composable
fun CropView(
    session: EditSession,
    state: EditState,
    straightening: Boolean,
    onCropChange: (NRect) -> Unit,
    onGestureEnd: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val src = session.preview ?: return
    val color = remember { ColorShader() }
    val checker = rememberCheckerPaint()
    val density = LocalDensity.current
    val g = state.geometry
    val frame = EditRenderer.frameSize(g, session.fullW, session.fullH)
    val ratio = EditRenderer.resolvedRatio(g, session.fullW, session.fullH)

    // The visible area. Kept still while a finger is down, so the view doesn't zoom mid-drag.
    var frozenBounds by remember { mutableStateOf<NRect?>(null) }
    val bounds = frozenBounds ?: EditRenderer.cropBounds(g, session.fullW, session.fullH)

    val currentCrop by rememberUpdatedState(g.crop)
    val currentRatio by rememberUpdatedState(ratio)
    val currentBounds by rememberUpdatedState(bounds)
    val update by rememberUpdatedState(onCropChange)
    val end by rememberUpdatedState(onGestureEnd)

    BoxWithConstraints(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val viewW = bounds.width * frame.width
        val viewH = bounds.height * frame.height
        val (s, _, _) = fit(viewW, viewH, constraints.maxWidth.toFloat(), constraints.maxHeight.toFloat())
        val dispW = viewW * s
        val dispH = viewH * s
        val touchPx = with(density) { 28.dp.toPx() }
        val minPx = with(density) { 56.dp.toPx() }

        Box(Modifier.size(with(density) { dispW.toDp() }, with(density) { dispH.toDp() })) {
            Canvas(
                Modifier
                    .fillMaxSize()
                    .pointerInput(dispW, dispH) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            val b = currentBounds
                            // Frame units (0..1 of the photo) per screen pixel
                            val ux = b.width / dispW
                            val uy = b.height / dispH
                            val minW = min(minPx * ux, 0.5f)
                            val minH = min(minPx * uy, 0.5f)
                            val c0 = currentCrop
                            // Crop box in 0..1 of the view, for picking the handle
                            val shown = NRect(
                                (c0.left - b.left) / b.width,
                                (c0.top - b.top) / b.height,
                                (c0.right - b.left) / b.width,
                                (c0.bottom - b.top) / b.height,
                            )
                            // Pick the handle where the finger landed, not where the drag got going
                            val handle = pickHandle(down.position, shown, dispW, dispH, touchPx, currentRatio != null)
                                ?: return@awaitEachGesture
                            val ratioN = currentRatio?.let { it * frame.height / frame.width }
                            var crop = c0
                            val start = awaitTouchSlopOrCancellation(down.id) { change, _ -> change.consume() }
                                ?: return@awaitEachGesture
                            frozenBounds = b
                            try {
                                val first = start.position - down.position
                                crop = moveCrop(crop, handle, first.x * ux, first.y * uy, minW, minH, ratioN, b)
                                update(crop)
                                drag(start.id) { change ->
                                    val d = change.positionChange()
                                    change.consume()
                                    crop = moveCrop(crop, handle, d.x * ux, d.y * uy, minW, minH, ratioN, b)
                                    update(crop)
                                }
                            } finally {
                                frozenBounds = null
                                end()
                            }
                        }
                    }
            ) {
                // Screen position of a point in frame units (0..1 of the photo)
                fun sx(u: Float) = (u - bounds.left) / bounds.width * size.width
                fun sy(v: Float) = (v - bounds.top) / bounds.height * size.height

                val crop = g.crop
                val l = sx(crop.left)
                val t = sy(crop.top)
                val r = sx(crop.right)
                val b = sy(crop.bottom)

                drawIntoCanvas { c ->
                    val nc = c.nativeCanvas
                    nc.save()
                    nc.clipRect(0f, 0f, size.width, size.height)
                    // Background fill (only inside the crop box: that's what gets saved)
                    if (g.fill == FillMode.BACKGROUND) {
                        if (android.graphics.Color.alpha(g.fillColor) < 255) nc.drawRect(l, t, r, b, checker)
                        val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).also { it.color = g.fillColor }
                        nc.drawRect(l, t, r, b, fillPaint)
                    }
                    nc.translate(sx(0f), sy(0f))
                    nc.scale(size.width / viewW, size.height / viewH)
                    EditRenderer.drawFrame(nc, state, src, session.fullW, session.fullH, color)
                    nc.restore()
                }

                val dim = Color.Black.copy(alpha = 0.6f)
                drawRect(dim, Offset(0f, 0f), Size(size.width, t))
                drawRect(dim, Offset(0f, b), Size(size.width, size.height - b))
                drawRect(dim, Offset(0f, t), Size(l, b - t))
                drawRect(dim, Offset(r, t), Size(size.width - r, b - t))

                // Outline of the photo itself when the box reaches past it
                if (g.fill == FillMode.BACKGROUND && bounds != NRect.FULL) {
                    drawRect(
                        Color.White.copy(alpha = 0.35f),
                        Offset(sx(0f), sy(0f)),
                        Size(sx(1f) - sx(0f), sy(1f) - sy(0f)),
                        style = Stroke(1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f))),
                    )
                }

                val lines = if (straightening) 8 else 3
                val guide = Color.White.copy(alpha = if (straightening) 0.3f else 0.35f)
                for (i in 1 until lines) {
                    val x = l + (r - l) * i / lines
                    val y = t + (b - t) * i / lines
                    drawLine(guide, Offset(x, t), Offset(x, b), 1.dp.toPx())
                    drawLine(guide, Offset(l, y), Offset(r, y), 1.dp.toPx())
                }

                drawRect(Color.White, Offset(l, t), Size(r - l, b - t), style = Stroke(1.5.dp.toPx()))

                val len = 22.dp.toPx()
                val w = 4.dp.toPx()
                fun corner(x: Float, y: Float, dx: Float, dy: Float) {
                    drawLine(Color.White, Offset(x, y), Offset(x + dx * len, y), w)
                    drawLine(Color.White, Offset(x, y), Offset(x, y + dy * len), w)
                }
                corner(l, t, 1f, 1f)
                corner(r, t, -1f, 1f)
                corner(l, b, 1f, -1f)
                corner(r, b, -1f, -1f)
            }
        }
    }
}

private fun pickHandle(p: Offset, c: NRect, w: Float, h: Float, slop: Float, locked: Boolean): Handle? {
    val l = c.left * w
    val t = c.top * h
    val r = c.right * w
    val b = c.bottom * h
    fun near(x: Float, y: Float) = abs(p.x - x) <= slop && abs(p.y - y) <= slop
    val inX = p.x in l..r
    val inY = p.y in t..b
    return when {
        near(l, t) -> Handle.TL
        near(r, t) -> Handle.TR
        near(l, b) -> Handle.BL
        near(r, b) -> Handle.BR
        !locked && abs(p.x - l) <= slop && inY -> Handle.L
        !locked && abs(p.x - r) <= slop && inY -> Handle.R
        !locked && abs(p.y - t) <= slop && inX -> Handle.T
        !locked && abs(p.y - b) <= slop && inX -> Handle.B
        inX && inY -> Handle.MOVE
        else -> null
    }
}

/** coerceIn that never throws, even if the bounds cross. */
private fun clamp(v: Float, lo: Float, hi: Float): Float =
    if (lo > hi) (lo + hi) / 2f else v.coerceIn(lo, hi)

/** Moves or resizes the crop box by (dx, dy) frame units, staying inside [area]. */
private fun moveCrop(
    c: NRect,
    h: Handle,
    dx: Float,
    dy: Float,
    minW: Float,
    minH: Float,
    ratioN: Float?,
    area: NRect,
): NRect {
    if (h == Handle.MOVE) {
        val nx = clamp(c.left + dx, area.left, area.right - c.width)
        val ny = clamp(c.top + dy, area.top, area.bottom - c.height)
        return NRect(nx, ny, nx + c.width, ny + c.height)
    }
    if (ratioN != null) return resizeLocked(c, h, dx, dy, minW, minH, ratioN, area)

    var l = c.left
    var t = c.top
    var r = c.right
    var b = c.bottom
    if (h == Handle.TL || h == Handle.BL || h == Handle.L) l = clamp(l + dx, area.left, r - minW)
    if (h == Handle.TR || h == Handle.BR || h == Handle.R) r = clamp(r + dx, l + minW, area.right)
    if (h == Handle.TL || h == Handle.TR || h == Handle.T) t = clamp(t + dy, area.top, b - minH)
    if (h == Handle.BL || h == Handle.BR || h == Handle.B) b = clamp(b + dy, t + minH, area.bottom)
    return NRect(l, t, r, b)
}

/** Corner drag that keeps the chosen aspect ratio, anchored at the opposite corner. */
private fun resizeLocked(
    c: NRect,
    h: Handle,
    dx: Float,
    dy: Float,
    minW: Float,
    minH: Float,
    rn: Float,
    area: NRect,
): NRect {
    val left = h == Handle.TL || h == Handle.BL
    val up = h == Handle.TL || h == Handle.TR
    val ax = if (left) c.right else c.left
    val ay = if (up) c.bottom else c.top
    val cx = (if (left) c.left else c.right) + dx
    val cy = (if (up) c.top else c.bottom) + dy

    var w = if (abs(dx) >= abs(dy * rn)) abs(cx - ax) else abs(cy - ay) * rn
    val wMax = min(if (left) ax - area.left else area.right - ax, (if (up) ay - area.top else area.bottom - ay) * rn)
    val wMin = min(max(minW, minH * rn), wMax)
    w = clamp(w, wMin, wMax)
    val hh = w / rn

    val l = if (left) ax - w else ax
    val t = if (up) ay - hh else ay
    return NRect(l, t, l + w, t + hh)
}
