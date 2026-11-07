package com.tim.gallery.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.net.Uri
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/** The frames a photo can have on the home screen. */
enum class FrameStyle(val label: String) {
    NONE("No frame"),
    ROUNDED("Rounded"),
    POLAROID("Instant photo"),
    GALLERY("Gallery mat"),
    BLACK("Black"),
    WOOD("Wood"),
    GOLD("Gold"),
    QUARTZ("Quartz"),
}

/**
 * Draws a framed photo with its drop shadow on a transparent background, the size of the widget.
 * Plain Android drawing (no Compose), so the widget can use it in the background.
 */
object FrameRenderer {

    /** The photo, decoded no bigger than needed for [maxSide] pixels (EXIF rotation applied). */
    fun loadPhoto(context: Context, uri: Uri, maxSide: Int): Bitmap? = runCatching {
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val w = info.size.width
            val h = info.size.height
            var sample = 1
            while (max(w, h) / (sample * 2) >= maxSide) sample *= 2
            if (sample > 1) decoder.setTargetSampleSize(sample)
        }
    }.getOrNull()

    /**
     * A [w]×[h] picture of [photo] in [style]. [shadow] 0 (none) – 1 (strong). [fill] crops the photo to
     * fill the widget; otherwise the frame takes the photo's own shape.
     */
    fun render(photo: Bitmap?, w: Int, h: Int, style: FrameStyle, shadow: Float, fill: Boolean): Bitmap {
        val out = Bitmap.createBitmap(max(1, w), max(1, h), Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val s = min(w, h).toFloat()
        val sh = shadow.coerceIn(0f, 1f)

        // Room around the frame for its shadow, which falls a little downward
        val pad = s * (0.025f + 0.07f * sh)
        val avail = RectF(pad, pad * 0.55f, w - pad, h - pad * 1.45f)
        val b = borders(style)
        val unit = min(avail.width(), avail.height())
        val left = b[0] * unit
        val top = b[1] * unit
        val right = b[2] * unit
        val bottom = b[3] * unit

        val outer: RectF
        if (fill || photo == null) {
            outer = RectF(avail)
        } else {
            // The frame takes the photo's shape, as big as fits
            val aspect = photo.width.toFloat() / photo.height
            val maxW = avail.width() - left - right
            val maxH = avail.height() - top - bottom
            var iw = maxW
            var ih = iw / aspect
            if (ih > maxH) {
                ih = maxH
                iw = ih * aspect
            }
            val ow = iw + left + right
            val oh = ih + top + bottom
            outer = RectF(avail.centerX() - ow / 2f, avail.centerY() - oh / 2f, avail.centerX() + ow / 2f, avail.centerY() + oh / 2f)
        }
        val inner = RectF(outer.left + left, outer.top + top, outer.right - right, outer.bottom - bottom)
        val ou = min(outer.width(), outer.height())
        val outerRadius = when (style) {
            FrameStyle.NONE -> ou * 0.012f
            FrameStyle.ROUNDED -> ou * 0.08f
            FrameStyle.POLAROID -> ou * 0.008f
            FrameStyle.QUARTZ -> ou * 0.03f
            else -> ou * 0.004f
        }

        // Drop shadow: a soft one underneath and a tighter one close to the frame, like a real print on a wall
        if (sh > 0.01f) {
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }
            p.maskFilter = BlurMaskFilter(s * (0.02f + 0.05f * sh), BlurMaskFilter.Blur.NORMAL)
            p.alpha = (255 * (0.25f + 0.47f * sh)).toInt()
            c.drawRoundRect(outer.offsetCopy(0f, s * 0.035f * sh), outerRadius, outerRadius, p)
            p.maskFilter = BlurMaskFilter(s * (0.006f + 0.012f * sh), BlurMaskFilter.Blur.NORMAL)
            p.alpha = (255 * (0.18f + 0.32f * sh)).toInt()
            c.drawRoundRect(outer.offsetCopy(0f, s * 0.008f * sh), outerRadius, outerRadius, p)
        }

        when (style) {
            FrameStyle.NONE, FrameStyle.ROUNDED -> Unit
            FrameStyle.POLAROID -> polaroid(c, outer, inner, outerRadius)
            FrameStyle.GALLERY -> gallery(c, outer, inner, ou)
            FrameStyle.BLACK -> molding(c, outer, inner, intArrayOf(0xFF0E0E10.toInt(), 0xFF2E2E33.toInt(), 0xFF141417.toInt(), 0xFF3A3A40.toInt(), 0xFF0B0B0D.toInt()), grain = 0, light = 0.10f)
            FrameStyle.WOOD -> molding(c, outer, inner, intArrayOf(0xFF3B2210.toInt(), 0xFF7A4A24.toInt(), 0xFF5C3418.toInt(), 0xFF8E5A2E.toInt(), 0xFF40250F.toInt()), grain = 1, light = 0.16f)
            FrameStyle.GOLD -> molding(c, outer, inner, intArrayOf(0xFF6E4E14.toInt(), 0xFFE9C766.toInt(), 0xFF9C7424.toInt(), 0xFFF6E39A.toInt(), 0xFF7A5718.toInt()), grain = 2, light = 0.22f)
            FrameStyle.QUARTZ -> quartz(c, outer, inner, outerRadius)
        }

        // The photo
        val photoRadius = when (style) {
            FrameStyle.NONE, FrameStyle.ROUNDED -> outerRadius
            FrameStyle.QUARTZ -> ou * 0.01f
            else -> 0f
        }
        val clip = Path().apply { addRoundRect(inner, photoRadius, photoRadius, Path.Direction.CW) }
        c.save()
        c.clipPath(clip)
        if (photo != null) {
            c.drawBitmap(photo, cropFor(photo, inner), inner, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
        } else {
            c.drawColor(0xFF3A3A44.toInt())
        }
        c.restore()

        // A hint of depth where the photo meets the frame
        val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply { setStyle(Paint.Style.STROKE) }
        when (style) {
            FrameStyle.NONE, FrameStyle.ROUNDED -> {
                edge.color = 0x1F000000
                edge.strokeWidth = max(1f, ou * 0.003f)
                c.drawRoundRect(inner, photoRadius, photoRadius, edge)
            }
            FrameStyle.POLAROID, FrameStyle.GALLERY -> {
                edge.color = 0x33000000
                edge.strokeWidth = max(1f, ou * 0.004f)
                c.drawRect(inner, edge)
            }
            else -> {
                edge.color = 0x80000000.toInt()
                edge.strokeWidth = max(1f, ou * 0.006f)
                c.drawRect(inner, edge)
            }
        }
        return out
    }

    /** Border widths (left, top, right, bottom) as fractions of the space's shorter side. */
    private fun borders(style: FrameStyle): FloatArray = when (style) {
        FrameStyle.NONE, FrameStyle.ROUNDED -> floatArrayOf(0f, 0f, 0f, 0f)
        FrameStyle.POLAROID -> floatArrayOf(0.055f, 0.055f, 0.055f, 0.2f)
        FrameStyle.GALLERY -> floatArrayOf(0.15f, 0.15f, 0.15f, 0.15f)
        FrameStyle.BLACK -> floatArrayOf(0.045f, 0.045f, 0.045f, 0.045f)
        FrameStyle.WOOD -> floatArrayOf(0.09f, 0.09f, 0.09f, 0.09f)
        FrameStyle.GOLD -> floatArrayOf(0.095f, 0.095f, 0.095f, 0.095f)
        FrameStyle.QUARTZ -> floatArrayOf(0.075f, 0.075f, 0.075f, 0.075f)
    }

    /** The part of [photo] that fills [box] (center crop). */
    private fun cropFor(photo: Bitmap, box: RectF): Rect {
        val boxAspect = box.width() / box.height()
        val pw = photo.width.toFloat()
        val ph = photo.height.toFloat()
        return if (pw / ph > boxAspect) {
            val cw = ph * boxAspect
            Rect(((pw - cw) / 2f).toInt(), 0, ((pw + cw) / 2f).toInt(), ph.toInt())
        } else {
            val chh = pw / boxAspect
            Rect(0, ((ph - chh) / 2f).toInt(), pw.toInt(), ((ph + chh) / 2f).toInt())
        }
    }

    // ------------------------------------------------------------------ frames

    private fun polaroid(c: Canvas, o: RectF, i: RectF, r: Float) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.shader = LinearGradient(0f, o.top, 0f, o.bottom, 0xFFFDFCF8.toInt(), 0xFFF1EEE6.toInt(), Shader.TileMode.CLAMP)
        c.drawRoundRect(o, r, r, p)
        // Faint paper edge
        p.shader = null
        p.style = Paint.Style.STROKE
        p.strokeWidth = max(1f, min(o.width(), o.height()) * 0.003f)
        p.color = 0x14000000
        c.drawRoundRect(o, r, r, p)
    }

    /** A thin black frame around a white mat, with the mat's cut bevel around the photo. */
    private fun gallery(c: Canvas, o: RectF, i: RectF, u: Float) {
        val frameW = u * 0.035f
        val mat = RectF(o.left + frameW, o.top + frameW, o.right - frameW, o.bottom - frameW)
        molding(c, o, mat, intArrayOf(0xFF0E0E10.toInt(), 0xFF34343A.toInt(), 0xFF151518.toInt(), 0xFF3C3C42.toInt(), 0xFF0C0C0E.toInt()), grain = 0, light = 0.10f)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.shader = LinearGradient(mat.left, mat.top, mat.right, mat.bottom, 0xFFF8F6F1.toInt(), 0xFFEDEAE2.toInt(), Shader.TileMode.CLAMP)
        c.drawRect(mat, p)
        p.shader = null
        // The frame casts a little shade onto the mat
        val shade = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = u * 0.012f
            color = 0x26000000
            maskFilter = BlurMaskFilter(u * 0.01f, BlurMaskFilter.Blur.NORMAL)
        }
        c.save()
        c.clipRect(mat)
        c.drawRect(mat, shade)
        c.restore()
        // Bevelled mat cut: light on the top and left, a touch darker bottom and right
        val bev = u * 0.009f
        val cut = RectF(i.left - bev, i.top - bev, i.right + bev, i.bottom + bev)
        bevel(c, cut, i, base = 0xFFFFFFFF.toInt(), topShade = 0x00000000, leftShade = 0x08000000, bottomShade = 0x1C000000, rightShade = 0x14000000)
    }

    /**
     * A moulded frame: each side shaded across its width with [colors] (outer edge → inner edge), lit
     * from the top left, optionally with wood grain (1) or fine gilded ridges (2).
     */
    private fun molding(c: Canvas, o: RectF, i: RectF, colors: IntArray, grain: Int, light: Float) {
        val positions = floatArrayOf(0f, 0.25f, 0.5f, 0.78f, 1f)
        for (side in 0 until 4) {
            val piece = trapezoid(o, i, side)
            val p = Paint(Paint.ANTI_ALIAS_FLAG)
            val (from, to) = when (side) {
                0 -> PointF(0f, o.top) to PointF(0f, i.top)
                1 -> PointF(o.right, 0f) to PointF(i.right, 0f)
                2 -> PointF(0f, o.bottom) to PointF(0f, i.bottom)
                else -> PointF(o.left, 0f) to PointF(i.left, 0f)
            }
            p.shader = LinearGradient(from.x, from.y, to.x, to.y, colors, positions, Shader.TileMode.CLAMP)
            c.drawPath(piece, p)
            p.shader = null

            if (grain != 0) {
                c.save()
                c.clipPath(piece)
                val rnd = Random(side * 31 + grain)
                val horizontal = side == 0 || side == 2
                val lines = if (grain == 1) 46 else 7
                val g = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
                val width = if (horizontal) (i.top - o.top) else (i.left - o.left)
                repeat(lines) { k ->
                    val t = if (grain == 1) rnd.nextFloat() else (k + 1f) / (lines + 1f)
                    if (grain == 1) {
                        // Wood: thin dark and light streaks along the side, slightly wavy
                        g.color = if (rnd.nextBoolean()) 0x2E000000 else 0x1CFFE0B0
                        g.strokeWidth = max(0.7f, width * (0.01f + 0.03f * rnd.nextFloat()))
                        val path = Path()
                        if (horizontal) {
                            val y0 = if (side == 0) o.top + t * width else o.bottom - t * width
                            path.moveTo(o.left, y0)
                            var x = o.left
                            val amp = width * 0.04f
                            while (x < o.right) {
                                x += width
                                path.lineTo(x, y0 + (rnd.nextFloat() - 0.5f) * amp)
                            }
                        } else {
                            val x0 = if (side == 3) o.left + t * width else o.right - t * width
                            path.moveTo(x0, o.top)
                            var y = o.top
                            val amp = width * 0.04f
                            while (y < o.bottom) {
                                y += width
                                path.lineTo(x0 + (rnd.nextFloat() - 0.5f) * amp, y)
                            }
                        }
                        c.drawPath(path, g)
                    } else {
                        // Gilt: fine ridges running round the frame
                        g.color = if (k % 2 == 0) 0x33FFF6D0 else 0x2A3A2600
                        g.strokeWidth = max(0.8f, width * 0.025f)
                        val inset = t * width
                        c.drawRect(RectF(o.left + inset, o.top + inset, o.right - inset, o.bottom - inset), g)
                    }
                }
                c.restore()
            }
            // Light from the top left: top and left sides lit, bottom and right in shade
            val shade = when (side) {
                0 -> Color.argb((255 * light).toInt(), 255, 255, 255)
                3 -> Color.argb((255 * light * 0.5f).toInt(), 255, 255, 255)
                2 -> Color.argb((255 * light * 1.6f).toInt().coerceAtMost(255), 0, 0, 0)
                else -> Color.argb((255 * light).toInt(), 0, 0, 0)
            }
            p.color = shade
            c.drawPath(piece, p)
        }
        // Crisp outer edge
        val e = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = max(1f, (i.left - o.left) * 0.04f)
            color = 0x59000000
        }
        c.drawRect(o, e)
    }

    /** Crystal: pale blue glass with facets and a bright rim, like the Quartz look. */
    private fun quartz(c: Canvas, o: RectF, i: RectF, r: Float) {
        val path = Path().apply {
            addRoundRect(o, r, r, Path.Direction.CW)
            addRect(i, Path.Direction.CCW)
        }
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.shader = LinearGradient(
            o.left, o.top, o.right, o.bottom,
            intArrayOf(0xFFE6F1FF.toInt(), 0xFF8FB4E3.toInt(), 0xFFD3E5FA.toInt(), 0xFF6F93C7.toInt(), 0xFFB9D3F2.toInt()),
            floatArrayOf(0f, 0.3f, 0.5f, 0.75f, 1f),
            Shader.TileMode.CLAMP,
        )
        c.drawPath(path, p)
        p.shader = null
        // Facets: each side split into lighter and darker triangles
        c.save()
        c.clipPath(path)
        val rnd = Random(7)
        for (side in 0 until 4) {
            val n = 6
            for (k in 0 until n) {
                val a = k / n.toFloat()
                val b2 = (k + 1) / n.toFloat()
                val tri = Path()
                fun along(edgeOuter: Boolean, t: Float): PointF {
                    val rr = if (edgeOuter) o else i
                    return when (side) {
                        0 -> PointF(rr.left + (rr.right - rr.left) * t, rr.top)
                        1 -> PointF(rr.right, rr.top + (rr.bottom - rr.top) * t)
                        2 -> PointF(rr.right - (rr.right - rr.left) * t, rr.bottom)
                        else -> PointF(rr.left, rr.bottom - (rr.bottom - rr.top) * t)
                    }
                }
                val p1 = along(true, a)
                val p2 = along(true, b2)
                val p3 = along(false, (a + b2) / 2f)
                tri.moveTo(p1.x, p1.y); tri.lineTo(p2.x, p2.y); tri.lineTo(p3.x, p3.y); tri.close()
                p.color = if (rnd.nextBoolean()) Color.argb(40 + rnd.nextInt(50), 255, 255, 255) else Color.argb(20 + rnd.nextInt(40), 20, 40, 90)
                c.drawPath(tri, p)
            }
        }
        c.restore()
        // Lit from the top left
        for (side in 0 until 4) {
            p.color = when (side) {
                0 -> 0x30FFFFFF
                3 -> 0x18FFFFFF
                2 -> 0x30001030
                else -> 0x22001030
            }
            c.save()
            c.clipPath(path)
            c.drawPath(trapezoid(o, i, side), p)
            c.restore()
        }
        // Bright rim of light, strongest at the top left (the Quartz look's edge lighting)
        val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = max(1.5f, (i.left - o.left) * 0.07f)
            shader = LinearGradient(
                o.left, o.top, o.right, o.bottom,
                intArrayOf(0xF2FFFFFF.toInt(), 0x55FFFFFF, 0x26FFFFFF, 0x4DFFFFFF, 0xBFFFFFFF.toInt()),
                floatArrayOf(0f, 0.3f, 0.5f, 0.75f, 1f),
                Shader.TileMode.CLAMP,
            )
        }
        val half = rim.strokeWidth / 2f
        c.drawRoundRect(RectF(o.left + half, o.top + half, o.right - half, o.bottom - half), r, r, rim)
        rim.shader = null
        rim.color = 0x66FFFFFF
        rim.strokeWidth = max(1f, rim.strokeWidth * 0.6f)
        c.drawRect(i, rim)
    }

    /** One side of the frame between [o] and [i]: 0 top, 1 right, 2 bottom, 3 left. */
    private fun trapezoid(o: RectF, i: RectF, side: Int): Path = Path().apply {
        when (side) {
            0 -> { moveTo(o.left, o.top); lineTo(o.right, o.top); lineTo(i.right, i.top); lineTo(i.left, i.top) }
            1 -> { moveTo(o.right, o.top); lineTo(o.right, o.bottom); lineTo(i.right, i.bottom); lineTo(i.right, i.top) }
            2 -> { moveTo(o.right, o.bottom); lineTo(o.left, o.bottom); lineTo(i.left, i.bottom); lineTo(i.right, i.bottom) }
            else -> { moveTo(o.left, o.bottom); lineTo(o.left, o.top); lineTo(i.left, i.top); lineTo(i.left, i.bottom) }
        }
        close()
    }

    private fun bevel(c: Canvas, o: RectF, i: RectF, base: Int, topShade: Int, leftShade: Int, bottomShade: Int, rightShade: Int) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        for (side in 0 until 4) {
            val piece = trapezoid(o, i, side)
            p.color = base
            c.drawPath(piece, p)
            p.color = when (side) {
                0 -> topShade
                1 -> rightShade
                2 -> bottomShade
                else -> leftShade
            }
            c.drawPath(piece, p)
        }
    }

    private fun RectF.offsetCopy(dx: Float, dy: Float) = RectF(left + dx, top + dy, right + dx, bottom + dy)
}

