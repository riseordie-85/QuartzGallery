package com.tim.gallery.edit

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Bitmaps an editing session works with. Plain class holding Compose state. */
class EditSession : LayerAssets {
    /** The photo, upright, as big as [com.tim.gallery.data.ImageLimits] allows. */
    var base: Bitmap? by mutableStateOf(null)

    /** [base] with the background made transparent, once "Remove background" has run. */
    var foreground: Bitmap? by mutableStateOf(null)

    /** Cached blurred copy of [base] for the "blur background" option. */
    var blurred: Bitmap? = null

    /** [base] after the cutout option (what gets edited and saved). */
    var source: Bitmap? by mutableStateOf(null)

    /** Smaller copy of [source] for the live preview. */
    var preview: Bitmap? by mutableStateOf(null)

    /** Small square for filter thumbnails. */
    var thumb: Bitmap? by mutableStateOf(null)

    /** Sticker, emoji and logo images by layer uri. */
    val stickers = mutableStateMapOf<String, Bitmap>()

    override fun bitmap(uri: String): Bitmap? = stickers[uri]

    val fullW: Float get() = base?.width?.toFloat() ?: 1f
    val fullH: Float get() = base?.height?.toFloat() ?: 1f

    companion object {
        /** Longest side of the small copies (filter thumbnails, the eyedropper's sample). */
        const val PREVIEW_PX = 1600
        const val THUMB_PX = 160
    }
}

/**
 * Runs GPU work (off-screen rendering) on a background thread, and retries on the main
 * thread if that device's GPU driver insists. Returns null if it fails both ways.
 */
suspend fun <T : Any> onGpu(block: () -> T): T? =
    withContext(Dispatchers.Default) { runCatching(block).getOrNull() }
        ?: withContext(Dispatchers.Main) { runCatching(block).getOrNull() }

/** Builds the source bitmap for a cutout option. Call off the main thread. */
fun composeCutout(session: EditSession, mode: CutoutMode, color: Int): Bitmap? {
    val base = session.base ?: return null
    val fg = session.foreground
    if (mode == CutoutMode.OFF || fg == null) return base
    return when (mode) {
        CutoutMode.OFF -> base
        CutoutMode.TRANSPARENT -> fg
        CutoutMode.COLOR -> {
            val out = Bitmap.createBitmap(base.width, base.height, Bitmap.Config.ARGB_8888)
            val c = Canvas(out)
            c.drawColor(color)
            c.drawBitmap(fg, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG))
            out
        }
        CutoutMode.BLUR -> {
            val blurred = session.blurred ?: EditRenderer.blurred(base).also { session.blurred = it }
            val out = blurred.copy(Bitmap.Config.ARGB_8888, true)
            Canvas(out).drawBitmap(fg, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG))
            out
        }
    }
}

/** Center-cropped square thumbnail. */
fun squareThumb(src: Bitmap, size: Int): Bitmap {
    val side = minOf(src.width, src.height)
    val x = (src.width - side) / 2
    val y = (src.height - side) / 2
    val square = Bitmap.createBitmap(src, x, y, side, side)
    return Bitmap.createScaledBitmap(square, size, size, true)
}

/**
 * Undo/redo for the edit. Consecutive changes with the same [tag] (e.g. dragging one slider)
 * collapse into a single undo step.
 */
class EditHistory(initial: EditState) {
    var state: EditState by mutableStateOf(initial)
        private set
    var canUndo by mutableStateOf(false)
        private set
    var canRedo by mutableStateOf(false)
        private set

    private val undoStack = ArrayDeque<EditState>()
    private val redoStack = ArrayDeque<EditState>()
    private var lastTag: String? = null
    private val original = initial

    val changed: Boolean get() = state != original

    fun set(new: EditState, tag: String? = null) {
        if (new == state) return
        if (tag == null || tag != lastTag) {
            undoStack.addLast(state)
            if (undoStack.size > 80) undoStack.removeFirst()
        }
        redoStack.clear()
        state = new
        lastTag = tag
        refresh()
    }

    /** Ends a run of tagged changes, so the next change gets its own undo step. */
    fun endGesture() {
        lastTag = null
    }

    fun undo() {
        val prev = undoStack.removeLastOrNull() ?: return
        redoStack.addLast(state)
        state = prev
        lastTag = null
        refresh()
    }

    fun redo() {
        val next = redoStack.removeLastOrNull() ?: return
        undoStack.addLast(state)
        state = next
        lastTag = null
        refresh()
    }

    private fun refresh() {
        canUndo = undoStack.isNotEmpty()
        canRedo = redoStack.isNotEmpty()
    }
}

// ---------------------------------------------------------------- geometry edits

object GeometryOps {
    fun rotate(g: Geometry, clockwise: Boolean, w: Float, h: Float): Geometry {
        val dir = if (clockwise) 1 else -1
        // Turns are applied before the mirror, so a mirrored photo turns the other way
        val turns = g.quarterTurns + if (g.flipped) -dir else dir
        val ng = g.copy(quarterTurns = ((turns % 4) + 4) % 4)
        return recenter(ng, w, h)
    }

    fun flip(g: Geometry): Geometry = g.copy(
        flipped = !g.flipped,
        angle = -g.angle,
        crop = NRect(1f - g.crop.right, g.crop.top, 1f - g.crop.left, g.crop.bottom),
    )

    fun setAngle(g: Geometry, angle: Float, w: Float, h: Float): Geometry {
        val ng = g.copy(angle = angle)
        // In background mode the frame changes size; keep a locked ratio correct
        return if (g.fill == FillMode.BACKGROUND && g.aspect.ratio != null) recenter(ng, w, h) else ng
    }

    fun setFill(g: Geometry, fill: FillMode, w: Float, h: Float): Geometry =
        if (g.fill == fill) g else recenter(g.copy(fill = fill), w, h)

    fun setAspect(g: Geometry, aspect: AspectOption, w: Float, h: Float): Geometry =
        recenter(g.copy(aspect = aspect), w, h)

    /**
     * Resets the crop box for the current ratio. Crop-in mode takes the largest box inside the
     * photo; Background mode takes the smallest box that holds the whole photo, adding space
     * (filled with the background color) to reach the ratio.
     */
    private fun recenter(g: Geometry, w: Float, h: Float): Geometry {
        val ratio = EditRenderer.resolvedRatio(g, w, h)
        val frame = EditRenderer.frameSize(g, w, h)
        val crop = if (g.fill == FillMode.BACKGROUND) {
            EditRenderer.fitCrop(ratio, frame)
        } else {
            EditRenderer.centeredCrop(ratio, frame)
        }
        return g.copy(crop = crop)
    }
}

/**
 * The live preview's copy of [full]: about 3 MP, and never more than 8192 px (or the GPU's limit) on
 * a side. Measured by pixels, not the longest side, so a long panorama stays sharp on screen.
 */
fun previewOf(full: Bitmap): Bitmap {
    val k = com.tim.gallery.data.ImageLimits.fitScale(
        full.width, full.height,
        minOf(8192, com.tim.gallery.data.ImageLimits.maxSide), com.tim.gallery.data.ImageLimits.PREVIEW_PIXELS,
    )
    if (k >= 1f) return full
    return Bitmap.createScaledBitmap(full, (full.width * k).toInt().coerceAtLeast(1), (full.height * k).toInt().coerceAtLeast(1), true)
}
