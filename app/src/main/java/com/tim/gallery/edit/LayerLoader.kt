package com.tim.gallery.edit

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.net.Uri
import android.text.TextPaint
import com.tim.gallery.data.Cutout
import com.tim.gallery.data.MediaSaver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.ceil
import kotlin.math.max

/**
 * Loads the pictures that sticker layers point at. A layer's uri can be:
 * - "content://…"            a photo from the phone (your Stickers folder)
 * - "asset:stickers/…"       a built-in sticker that ships with the app
 * - "emoji:😀"               an emoji, drawn with the phone's emoji font
 * - "file:///…"              a picture saved inside the app (watermark logos)
 */
object LayerLoader {
    const val MAX_PX = 1024

    suspend fun load(context: Context, uri: String, maxPx: Int = MAX_PX): Bitmap? = withContext(Dispatchers.IO) {
        runCatching {
            when {
                uri.startsWith("emoji:") -> renderEmoji(uri.removePrefix("emoji:"), 512)
                uri.startsWith("asset:") -> context.assets.open(uri.removePrefix("asset:")).use { input ->
                    BitmapFactory.decodeStream(input)?.let { Cutout.scaleDown(it, maxPx) }
                }
                uri.startsWith("file://") -> {
                    val path = Uri.parse(uri).path ?: return@runCatching null
                    BitmapFactory.decodeFile(path)?.let { Cutout.scaleDown(it, maxPx) }
                }
                else -> MediaSaver.decodeUpright(context, Uri.parse(uri), maxPx)
            }
        }.getOrNull()
    }

    /** Draws an emoji into a transparent square bitmap, trimmed to the glyph. */
    fun renderEmoji(emoji: String, px: Int): Bitmap {
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = px * 0.8f
            typeface = Typeface.DEFAULT
        }
        val bounds = Rect()
        paint.getTextBounds(emoji, 0, emoji.length, bounds)
        val w = max(1, ceil(max(paint.measureText(emoji), bounds.width().toFloat())).toInt())
        val fm = paint.fontMetricsInt
        val h = max(1, fm.descent - fm.ascent)
        val side = max(w, h)
        val bmp = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val x = (side - paint.measureText(emoji)) / 2f
        val y = (side - h) / 2f - fm.ascent
        c.drawText(emoji, x, y, paint)
        return Cutout.trimForSticker(bmp)
    }

    /** A Coil-friendly model for showing a layer uri in a thumbnail. */
    fun previewModel(uri: String): Any = when {
        uri.startsWith("asset:") -> "file:///android_asset/" + uri.removePrefix("asset:")
        else -> uri
    }
}

/** Fixed set of pictures (for batch watermarking and previews). */
class MapAssets(private val bitmaps: Map<String, Bitmap>) : LayerAssets {
    override fun bitmap(uri: String): Bitmap? = bitmaps[uri]
}

/** The sticker packs that ship inside the app (app/src/main/assets/stickers/<pack>/). */
object StickerPacks {
    data class Pack(val name: String, val stickers: List<String>)

    private val EXTENSIONS = setOf("webp", "png", "jpg", "jpeg")
    private var cached: List<Pack>? = null

    suspend fun list(context: Context): List<Pack> = cached ?: withContext(Dispatchers.IO) {
        val am = context.assets
        val packs = (am.list("stickers") ?: emptyArray())
            .sortedBy { it.lowercase() }
            .mapNotNull { dir ->
                val files = (am.list("stickers/$dir") ?: emptyArray())
                    .filter { it.substringAfterLast('.', "").lowercase() in EXTENSIONS }
                    .sortedBy { it.lowercase() }
                if (files.isEmpty()) null
                else Pack(prettyName(dir), files.map { "asset:stickers/$dir/$it" })
            }
        cached = packs
        packs
    }

    private fun prettyName(dir: String) =
        dir.replace('_', ' ').replace('-', ' ').trim().replaceFirstChar { it.uppercase() }
}

/** Files saved inside the app (survive until the app is uninstalled). */
fun appFileUri(file: File): String = Uri.fromFile(file).toString()
