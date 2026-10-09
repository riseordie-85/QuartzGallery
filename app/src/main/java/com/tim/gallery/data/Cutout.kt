package com.tim.gallery.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import com.google.android.gms.tasks.Task
import com.google.mlkit.common.MlKitException
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.subject.SubjectSegmentation
import com.google.mlkit.vision.segmentation.subject.SubjectSegmenterOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import java.nio.FloatBuffer
import kotlin.coroutines.resumeWithException
import kotlin.math.max
import kotlin.math.roundToInt

/** Why background removal didn't produce a result. */
class CutoutException(message: String) : Exception(message)

/**
 * On-device background removal using ML Kit subject segmentation
 * (runs through Google Play services; nothing is uploaded).
 */
object Cutout {

    /** The model works on a smaller copy; the mask is then scaled up to full size. */
    private const val SEGMENT_PX = 2048

    /**
     * Returns a copy of [image] with the background made transparent, or throws [CutoutException]
     * with a message suitable for showing the user.
     */
    suspend fun removeBackground(image: Bitmap): Bitmap = withContext(Dispatchers.Default) {
        val small = scaleDown(image, SEGMENT_PX)
        val options = SubjectSegmenterOptions.Builder()
            .enableForegroundConfidenceMask()
            .build()
        val segmenter = SubjectSegmentation.getClient(options)
        val maskOrNull: FloatBuffer? = try {
            val result = segmenter.process(InputImage.fromBitmap(small, 0)).await()
            result.foregroundConfidenceMask
        } catch (e: CancellationException) {
            throw e
        } catch (e: MlKitException) {
            throw CutoutException(
                if (e.errorCode == MlKitException.UNAVAILABLE) {
                    "Background removal is still downloading through Google Play services. Try again in a minute."
                } else {
                    "Background removal failed (${e.message})."
                }
            )
        } catch (e: Exception) {
            throw CutoutException("Background removal failed (${e.message}).")
        } finally {
            (segmenter as? java.io.Closeable)?.close()
        }
        val mask = maskOrNull ?: throw CutoutException("Couldn't find a subject in this photo.")

        // Confidence (0..1) per pixel -> alpha, with a little edge tightening
        val w = small.width
        val h = small.height
        mask.rewind()
        val pixels = IntArray(w * h)
        var any = false
        for (i in pixels.indices) {
            val c = if (mask.hasRemaining()) mask.get() else 0f
            val a = smoothstep(0.25f, 0.75f, c)
            val alpha = (a * 255f).roundToInt()
            if (alpha > 8) any = true
            pixels[i] = alpha shl 24
        }
        if (!any) throw CutoutException("Couldn't find a subject in this photo.")
        val maskBitmap = Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)

        val out = image.copy(Bitmap.Config.ARGB_8888, true)
        out.setHasAlpha(true)
        Canvas(out).drawBitmap(
            maskBitmap,
            null,
            Rect(0, 0, out.width, out.height),
            Paint(Paint.FILTER_BITMAP_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN) },
        )
        if (small !== image) small.recycle()
        maskBitmap.recycle()
        out
    }

    /** Crops away transparent margins (plus a little padding) and caps the size, for stickers. */
    fun trimForSticker(bitmap: Bitmap, maxPx: Int = 1024): Bitmap {
        val w = bitmap.width
        val h = bitmap.height
        var left = w
        var top = h
        var right = -1
        var bottom = -1
        if (bitmap.hasAlpha()) {
            val row = IntArray(w)
            for (y in 0 until h) {
                bitmap.getPixels(row, 0, w, 0, y, w, 1)
                for (x in 0 until w) {
                    if (Color.alpha(row[x]) > 10) {
                        if (x < left) left = x
                        if (x > right) right = x
                        if (y < top) top = y
                        if (y > bottom) bottom = y
                    }
                }
            }
        }
        val trimmed = if (right < 0) {
            bitmap
        } else {
            val pad = (max(right - left, bottom - top) * 0.03f).roundToInt()
            val l = (left - pad).coerceAtLeast(0)
            val t = (top - pad).coerceAtLeast(0)
            val r = (right + pad).coerceAtMost(w - 1)
            val b = (bottom + pad).coerceAtMost(h - 1)
            Bitmap.createBitmap(bitmap, l, t, r - l + 1, b - t + 1)
        }
        return scaleDown(trimmed, maxPx)
    }

    fun scaleDown(bitmap: Bitmap, maxPx: Int): Bitmap {
        val longest = max(bitmap.width, bitmap.height)
        if (longest <= maxPx) return bitmap
        val s = maxPx.toFloat() / longest
        return Bitmap.createScaledBitmap(
            bitmap,
            (bitmap.width * s).roundToInt().coerceAtLeast(1),
            (bitmap.height * s).roundToInt().coerceAtLeast(1),
            true,
        )
    }

    /** Loads an image for sticker-making / editing. */
    fun load(context: Context, uri: android.net.Uri, maxPx: Int): Bitmap =
        MediaSaver.decodeUpright(context, uri, maxPx)

    private fun smoothstep(e0: Float, e1: Float, x: Float): Float {
        val t = ((x - e0) / (e1 - e0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }
}

/** Bridges a Play services Task into a coroutine. */
suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { cont ->
    addOnSuccessListener { cont.resume(it) }
    addOnFailureListener { cont.resumeWithException(it) }
    addOnCanceledListener { cont.resumeWithException(CutoutException("Background removal was cancelled.")) }
}
