package com.tim.gallery.edit

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import com.tim.gallery.video.GifDecoder
import com.tim.gallery.video.GifEncoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Edits on GIFs: the same crop, rotation, colors, filters and markup are applied to every
 * frame, and the result is saved as a GIF again (with its original timing).
 */
object GifEdit {

    /** Thrown when the person cancels a GIF save. */
    class Cancelled : Exception("Cancelled")

    /** Biggest GIF file this will read into memory. */
    private const val MAX_BYTES = 64L * 1024 * 1024

    fun isGif(mimeType: String?, name: String?): Boolean =
        mimeType?.lowercase() == "image/gif" || name?.lowercase()?.endsWith(".gif") == true

    /** Opens a GIF for editing, or null if it can't be read. */
    suspend fun open(context: Context, uri: Uri): GifDecoder? = withContext(Dispatchers.IO) {
        runCatching {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                if (pfd.statSize > MAX_BYTES) return@runCatching null
            }
            context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        }.getOrNull()?.let { GifDecoder.open(it) }
    }

    /**
     * Applies [state] to every frame of [gif] and writes the new GIF to a temporary file
     * (the caller saves it and deletes it). [baseWidth] × [baseHeight] is the size of the
     * picture the edit was made on; frames are scaled to match it.
     */
    suspend fun render(
        context: Context,
        gif: GifDecoder,
        state: EditState,
        assets: LayerAssets,
        baseWidth: Int,
        baseHeight: Int,
        cancel: AtomicBoolean = AtomicBoolean(false),
        onProgress: (Float) -> Unit = {},
    ): File = withContext(Dispatchers.Default) {
        val tmp = File(context.cacheDir, "gifedit_${System.nanoTime()}.gif")
        try {
            tmp.outputStream().buffered(1 shl 16).use { stream ->
                val reader = gif.reader()
                val frame = Bitmap.createBitmap(gif.width, gif.height, Bitmap.Config.ARGB_8888)
                var encoder: GifEncoder? = null
                var outW = 0
                var outH = 0
                var outPixels = IntArray(0)
                while (reader.next()) {
                    ensureActive()
                    if (cancel.get()) throw Cancelled()
                    frame.setPixels(reader.pixels, 0, gif.width, 0, 0, gif.width, gif.height)
                    val src = if (frame.width == baseWidth && frame.height == baseHeight) {
                        frame
                    } else {
                        Bitmap.createScaledBitmap(frame, baseWidth, baseHeight, true)
                    }
                    var out = onGpu { EditRenderer.export(state, src, assets) } ?: throw IOException("Couldn't render frame ${reader.index + 1}")
                    if (src !== frame) src.recycle()
                    if (encoder == null) {
                        outW = out.width
                        outH = out.height
                        outPixels = IntArray(outW * outH)
                        encoder = GifEncoder(stream, outW, outH, 100, keepTransparency = true)
                    } else if (out.width != outW || out.height != outH) {
                        val fitted = Bitmap.createScaledBitmap(out, outW, outH, true)
                        out.recycle()
                        out = fitted
                    }
                    out.getPixels(outPixels, 0, outW, 0, 0, outW, outH)
                    out.recycle()
                    encoder.addFrame(outPixels, reader.delayMs)
                    onProgress((reader.index + 1f) / gif.frameCount)
                }
                frame.recycle()
                (encoder ?: throw IOException("No frames")).finish()
            }
            tmp
        } catch (e: Throwable) {
            tmp.delete()
            throw e
        }
    }
}
