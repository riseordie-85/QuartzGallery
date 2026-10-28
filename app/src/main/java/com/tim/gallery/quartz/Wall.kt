package com.tim.gallery.quartz

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/*
 * Quartz backgrounds, the same ones as Quartz Launcher's wallpapers (gradients, the five-point "corners" gradient
 * and the cut-crystal patterns), drawn behind the gallery's main screens.
 */

enum class WallKind(val label: String) { THEME("Plain"), GRADIENT("Gradient"), SOLID("Color") }

enum class GradientStyle(val label: String) { LINEAR("Linear"), RADIAL("Radial"), CORNERS("5-point") }

enum class PatternStyle(val label: String, val faceted: Boolean = false) {
    NONE("None"),
    CRYSTAL("Crystal", true),
    RIPPLE("Ripple", true),
    DIAMONDS("Diamonds", true),
    PYRAMIDS("Pyramids", true),
    SHARDS("Shards", true),
    DOTS("Dots"), GRID("Grid"), STRIPES("Stripes"), CHECKS("Checks"), WAVES("Waves"),
}

/**
 * One background. Gradient colors: linear uses [colors] 0 (start), 1 (middle, if [linearMiddle]) and 2 (end); radial
 * uses 0 (center) and 2 (edge); 5-point uses all five: top left, top right, bottom left, bottom right, middle.
 */
data class WallConfig(
    val kind: WallKind = WallKind.THEME,
    val solid: Int = 0xFF1E2A4A.toInt(),
    val gradient: GradientStyle = GradientStyle.LINEAR,
    val colors: List<Int> = listOf(0xFF4B1D7A.toInt(), 0xFF2546A8.toInt(), 0xFF138A8A.toInt(), 0xFF4E9F3D.toInt(), 0xFF2E5EA8.toInt()),
    val linearMiddle: Boolean = true,
    /** Degrees; 0 runs left to right, 90 top to bottom. */
    val angle: Float = 135f,
    val radialX: Float = 0.5f,
    val radialY: Float = 0.4f,
    val radialSize: Float = 0.8f,
    val pattern: PatternStyle = PatternStyle.NONE,
    val patternColor: Int = 0xFFFFFFFF.toInt(),
    val patternStrength: Float = 0.12f,
    val patternSize: Float = 0.5f,
    /** Picks a different arrangement for patterns with randomness in them (Crystal, Shards, sparkles). */
    val patternSeed: Int = 0,
    /** Tiny glints over the pattern. */
    val sparkles: Boolean = false,
    /** Darkens the whole background, 0–0.7, so photos and text stand out. */
    val dim: Float = 0f,
) {
    val drawn: Boolean get() = kind != WallKind.THEME

    fun color(i: Int): Int = colors.getOrElse(i) { colors.lastOrNull() ?: 0xFF000000.toInt() }

    fun withColor(i: Int, c: Int): WallConfig {
        val list = (0 until 5).map { color(it) }.toMutableList()
        list[i] = c
        return copy(colors = list)
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("kind", kind.name)
        put("solid", solid)
        put("gradient", gradient.name)
        put("colors", JSONArray(colors))
        put("linearMiddle", linearMiddle)
        put("angle", angle.toDouble())
        put("radialX", radialX.toDouble())
        put("radialY", radialY.toDouble())
        put("radialSize", radialSize.toDouble())
        put("pattern", pattern.name)
        put("patternColor", patternColor)
        put("patternStrength", patternStrength.toDouble())
        put("patternSize", patternSize.toDouble())
        put("patternSeed", patternSeed)
        put("sparkles", sparkles)
        put("dim", dim.toDouble())
    }

    companion object {
        fun fromJson(j: JSONObject?): WallConfig? = runCatching {
            if (j == null) return null
            val d = WallConfig()
            fun f(name: String, def: Float) = j.optDouble(name, def.toDouble()).toFloat()
            val arr = j.optJSONArray("colors")
            WallConfig(
                kind = WallKind.entries.firstOrNull { it.name == j.optString("kind") } ?: d.kind,
                solid = j.optInt("solid", d.solid),
                gradient = GradientStyle.entries.firstOrNull { it.name == j.optString("gradient") } ?: d.gradient,
                colors = if (arr != null && arr.length() > 0) (0 until arr.length()).map { arr.getInt(it) } else d.colors,
                linearMiddle = j.optBoolean("linearMiddle", d.linearMiddle),
                angle = f("angle", d.angle),
                radialX = f("radialX", d.radialX),
                radialY = f("radialY", d.radialY),
                radialSize = f("radialSize", d.radialSize),
                pattern = PatternStyle.entries.firstOrNull { it.name == j.optString("pattern") } ?: d.pattern,
                patternColor = j.optInt("patternColor", d.patternColor),
                patternStrength = f("patternStrength", d.patternStrength),
                patternSize = f("patternSize", d.patternSize),
                patternSeed = j.optInt("patternSeed", d.patternSeed),
                sparkles = j.optBoolean("sparkles", d.sparkles),
                dim = f("dim", d.dim),
            )
        }.getOrNull()
    }
}

/** Ready-made backgrounds: Quartz Launcher's stones and gradients. */
object WallPresets {
    data class Preset(val name: String, val config: WallConfig)

    private fun linear(name: String, angle: Float, vararg c: Long) = Preset(
        name,
        WallConfig(
            kind = WallKind.GRADIENT,
            gradient = GradientStyle.LINEAR,
            colors = listOf(c[0].toInt(), c[1].toInt(), c[2].toInt(), c[2].toInt(), c[1].toInt()),
            linearMiddle = true,
            angle = angle,
        ),
    )

    private fun corners(name: String, vararg c: Long) = Preset(
        name,
        WallConfig(kind = WallKind.GRADIENT, gradient = GradientStyle.CORNERS, colors = c.map { it.toInt() }),
    )

    private fun radial(name: String, center: Long, edge: Long, y: Float, size: Float) = Preset(
        name,
        WallConfig(
            kind = WallKind.GRADIENT,
            gradient = GradientStyle.RADIAL,
            colors = listOf(center.toInt(), center.toInt(), edge.toInt(), edge.toInt(), center.toInt()),
            radialX = 0.5f,
            radialY = y,
            radialSize = size,
        ),
    )

    private fun Preset.cut(style: PatternStyle, strength: Float, size: Float, sparkles: Boolean = false) =
        copy(config = config.copy(pattern = style, patternColor = 0xFFFFFFFF.toInt(), patternStrength = strength, patternSize = size, sparkles = sparkles))

    val all: List<Preset> = listOf(
        Preset("Quartz", WallConfig(kind = WallKind.GRADIENT)),
        radial("Blue quartz", 0xFF1E63E0, 0xFF02081A, 0.45f, 0.85f).cut(PatternStyle.RIPPLE, 0.3f, 0.2f),
        radial("Citrine", 0xFFE8A21E, 0xFF7A4306, 0.4f, 1.25f).cut(PatternStyle.CRYSTAL, 0.42f, 0.3f),
        radial("Amethyst", 0xFFA4509E, 0xFF2E0F45, 0.42f, 0.95f).cut(PatternStyle.DIAMONDS, 0.2f, 0.35f, sparkles = true),
        corners("Rose quartz", 0xFF7A2A6A, 0xFFD0566E, 0xFF3A1A6E, 0xFF2C34A8, 0xFFB8346E).cut(PatternStyle.PYRAMIDS, 0.42f, 0.45f),
        linear("Smoky quartz", 90f, 0xFF2A2046, 0xFF33284A, 0xFF1D1838).cut(PatternStyle.SHARDS, 0.2f, 0.5f),
        linear("Dusk", 160f, 0xFF1B1035, 0xFF6B2D6E, 0xFFE07A5F),
        linear("Ocean", 120f, 0xFF061A2B, 0xFF0F5C7A, 0xFF4FB3BF),
        linear("Forest", 100f, 0xFF0B1F14, 0xFF1F5135, 0xFF8DB36B),
        corners("Aurora", 0xFF14203F, 0xFF1D6A6A, 0xFF3A1C5C, 0xFF0E3B2E, 0xFF3F8F7A),
        corners("Ember", 0xFF2A0F1F, 0xFF7A2A1F, 0xFF140A1A, 0xFFB5582A, 0xFF5A1E2E),
        linear("Graphite", 90f, 0xFF15161A, 0xFF24262D, 0xFF3A3D47),
        corners("Frost", 0xFFE8EEF5, 0xFFD5E3F0, 0xFFF4F1EA, 0xFFDCE7E3, 0xFFFFFFFF),
        corners("Lilac mist", 0xFFF3EEFB, 0xFFE6DDF7, 0xFFFFFFFF, 0xFFEDE6FA, 0xFFFAF8FE).cut(PatternStyle.DIAMONDS, 0.12f, 0.35f),
    )
}

/** Draws backgrounds on worker threads (one at a time). */
val WallRenderer = WallPainter()

/** Draws the bars' patterns, on the main thread only, so it never waits for a background being drawn. */
val BarPainter = WallPainter()

/** Draws Quartz backgrounds (and the patterns on the bars, so they line up with the background's). */
class WallPainter {
    private val facets = Facets()

    private val smooth = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)

    /** Draws [c] over a [w]×[h] canvas. One drawing at a time: the paints and pattern buffers are shared. */
    @Synchronized
    fun draw(canvas: Canvas, w: Int, h: Int, c: WallConfig) {
        if (w <= 0 || h <= 0) return
        val width = w.toFloat()
        val height = h.toFloat()
        canvas.save()
        canvas.clipRect(0, 0, w, h)
        when (c.kind) {
            WallKind.THEME -> Unit
            WallKind.SOLID -> canvas.drawColor(c.solid)
            WallKind.GRADIENT -> drawGradient(canvas, width, height, c)
        }
        if (c.drawn && c.pattern != PatternStyle.NONE) {
            if (c.pattern.faceted) {
                facets.draw(canvas, width, height, Rect(0, 0, w, h), c)
            } else {
                drawPattern(canvas, width, height, c)
            }
        }
        canvas.restore()
        if (c.drawn && c.dim > 0.005f) canvas.drawColor(Color.argb((c.dim.coerceIn(0f, 0.8f) * 255).toInt(), 0, 0, 0))
    }

    /**
     * A pattern on its own (a bar's). [w]×[h] is the screen, so it lines up with the background's; [clip] is the part
     * to draw. [scale] runs 0–1 with 0.5 the usual size.
     */
    @Synchronized
    fun drawPatternOnly(canvas: Canvas, w: Float, h: Float, clip: Rect, style: PatternStyle, strength: Float, scale: Float = 0.5f) {
        val c = WallConfig(pattern = style, patternStrength = strength, patternSize = 0.3f, patternColor = Color.WHITE)
        val f = Math.pow(2.0, ((scale.coerceIn(0f, 1f) - 0.5f) * 3f).toDouble()).toFloat()
        if (style.faceted) facets.draw(canvas, w, h, clip, c, cellScale = f) else drawPattern(canvas, w, h, c, cellScale = f, clipRect = clip)
    }

    /** The background at [scale] of a [w]×[h] screen. */
    @Synchronized
    fun render(w: Int, h: Int, scale: Float, c: WallConfig): Bitmap {
        val bw = max(1, (w * scale).toInt())
        val bh = max(1, (h * scale).toInt())
        val bmp = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.scale(bw.toFloat() / w, bh.toFloat() / h)
        draw(canvas, w, h, c)
        return bmp
    }

    private fun drawGradient(canvas: Canvas, w: Float, h: Float, c: WallConfig) {
        when (c.gradient) {
            GradientStyle.LINEAR -> {
                val a = Math.toRadians(c.angle.toDouble())
                val dx = cos(a).toFloat()
                val dy = sin(a).toFloat()
                val half = (abs(w * dx) + abs(h * dy)) / 2f
                val cx = w / 2f
                val cy = h / 2f
                val colors = if (c.linearMiddle) intArrayOf(c.color(0), c.color(1), c.color(2)) else intArrayOf(c.color(0), c.color(2))
                smooth.shader = LinearGradient(cx - dx * half, cy - dy * half, cx + dx * half, cy + dy * half, colors, null, Shader.TileMode.CLAMP)
            }
            GradientStyle.RADIAL -> {
                val r = max(1f, c.radialSize.coerceIn(0.1f, 2f) * max(w, h))
                smooth.shader = RadialGradient(c.radialX * w, c.radialY * h, r, intArrayOf(c.color(0), c.color(2)), null, Shader.TileMode.CLAMP)
            }
            GradientStyle.CORNERS -> {
                canvas.drawBitmap(cornerBitmap(c), null, RectF(0f, 0f, w, h), smooth)
                return
            }
        }
        canvas.drawRect(0f, 0f, w, h, smooth)
        smooth.shader = null
    }

    private var cornerKey: List<Int>? = null
    private var cornerBmp: Bitmap? = null

    /** Four corner colors blended across, with the middle color glowing out from the center. */
    private fun cornerBitmap(c: WallConfig): Bitmap {
        val key = (0 until 5).map { c.color(it) }
        cornerBmp?.let { if (key == cornerKey) return it }
        val n = 96
        val px = IntArray(n * n)
        fun ch(color: Int, shift: Int) = ((color shr shift) and 0xFF).toFloat()
        for (y in 0 until n) {
            val v = y / (n - 1f)
            for (x in 0 until n) {
                val u = x / (n - 1f)
                val d = hypot(u - 0.5f, v - 0.5f)
                var m = (1f - d / 0.6f).coerceIn(0f, 1f)
                m = m * m * (3f - 2f * m) * 0.9f
                var out = 0xFF shl 24
                for (shift in intArrayOf(16, 8, 0)) {
                    val top = ch(key[0], shift) * (1 - u) + ch(key[1], shift) * u
                    val bottom = ch(key[2], shift) * (1 - u) + ch(key[3], shift) * u
                    val base = top * (1 - v) + bottom * v
                    val value = base * (1 - m) + ch(key[4], shift) * m
                    out = out or (value.toInt().coerceIn(0, 255) shl shift)
                }
                px[y * n + x] = out
            }
        }
        val bmp = Bitmap.createBitmap(px, n, n, Bitmap.Config.ARGB_8888)
        cornerKey = key
        cornerBmp = bmp
        return bmp
    }

    private val pat = Paint(Paint.ANTI_ALIAS_FLAG)

    private fun drawPattern(canvas: Canvas, w: Float, h: Float, c: WallConfig, cellScale: Float = 1f, clipRect: Rect? = null) {
        val cell = h * (0.025f + c.patternSize.coerceIn(0f, 1f) * 0.075f) * cellScale
        if (cell < 2f) return
        val alpha = (c.patternStrength.coerceIn(0f, 1f) * 255).toInt()
        pat.color = (c.patternColor and 0x00FFFFFF) or (alpha shl 24)
        pat.shader = null
        val clip = clipRect ?: canvas.clipBounds.takeUnless { it.isEmpty } ?: Rect(0, 0, w.toInt(), h.toInt())
        val x0 = floor(clip.left / cell).toInt() - 1
        val x1 = ceil(clip.right / cell).toInt() + 1
        val y0 = floor(clip.top / cell).toInt() - 1
        val y1 = ceil(clip.bottom / cell).toInt() + 1
        when (c.pattern) {
            PatternStyle.DOTS -> {
                pat.style = Paint.Style.FILL
                for (r in y0..y1) for (col in x0..x1) {
                    val shift = if (r % 2 == 0) 0f else cell / 2f
                    canvas.drawCircle(col * cell + shift + cell / 2f, r * cell + cell / 2f, cell * 0.13f, pat)
                }
            }
            PatternStyle.GRID -> {
                pat.style = Paint.Style.STROKE
                pat.strokeWidth = max(1f, cell * 0.04f)
                for (col in x0..x1) canvas.drawLine(col * cell, clip.top.toFloat(), col * cell, clip.bottom.toFloat(), pat)
                for (r in y0..y1) canvas.drawLine(clip.left.toFloat(), r * cell, clip.right.toFloat(), r * cell, pat)
            }
            PatternStyle.STRIPES -> {
                pat.style = Paint.Style.STROKE
                pat.strokeWidth = cell * 0.3f
                val k0 = floor((clip.left - clip.bottom) / cell).toInt() - 1
                val k1 = ceil((clip.right - clip.top) / cell).toInt() + 1
                for (k in k0..k1) {
                    val b = k * cell
                    canvas.drawLine(clip.top + b, clip.top.toFloat(), clip.bottom + b, clip.bottom.toFloat(), pat)
                }
            }
            PatternStyle.CHECKS -> {
                pat.style = Paint.Style.FILL
                for (r in y0..y1) for (col in x0..x1) {
                    if ((r + col) % 2 == 0) canvas.drawRect(col * cell, r * cell, (col + 1) * cell, (r + 1) * cell, pat)
                }
            }
            PatternStyle.WAVES -> {
                pat.style = Paint.Style.STROKE
                pat.strokeWidth = max(1f, cell * 0.07f)
                val path = Path()
                val amp = cell * 0.22f
                val step = cell / 8f
                for (r in y0..y1) {
                    path.reset()
                    val base = r * cell + cell / 2f
                    var x = clip.left - cell
                    path.moveTo(x, base + amp * sin(x / cell * PI).toFloat())
                    while (x < clip.right + cell) {
                        x += step
                        path.lineTo(x, base + amp * sin(x / cell * PI).toFloat())
                    }
                    canvas.drawPath(path, pat)
                }
            }
            else -> Unit
        }
    }

    /** Blurs [bmp] in place: three box-blur passes each way, which looks like a soft gaussian. */
    fun blur(bmp: Bitmap, radius: Int) {
        if (radius < 1) return
        val w = bmp.width
        val h = bmp.height
        val a = IntArray(w * h)
        val b = IntArray(w * h)
        bmp.getPixels(a, 0, w, 0, 0, w, h)
        val r = radius.coerceAtMost(max(1, min(w, h) / 2))
        repeat(3) {
            boxPass(a, b, w, h, r, horizontal = true)
            boxPass(b, a, w, h, r, horizontal = false)
        }
        bmp.setPixels(a, 0, w, 0, 0, w, h)
    }

    private fun boxPass(src: IntArray, dst: IntArray, w: Int, h: Int, r: Int, horizontal: Boolean) {
        val lines = if (horizontal) h else w
        val len = if (horizontal) w else h
        val div = 2 * r + 1
        for (line in 0 until lines) {
            fun at(i: Int): Int {
                val k = i.coerceIn(0, len - 1)
                return if (horizontal) line * w + k else k * w + line
            }
            var sr = 0
            var sg = 0
            var sb = 0
            for (i in -r..r) {
                val p = src[at(i)]
                sr += (p shr 16) and 0xFF
                sg += (p shr 8) and 0xFF
                sb += p and 0xFF
            }
            for (i in 0 until len) {
                dst[at(i)] = (0xFF shl 24) or ((sr / div) shl 16) or ((sg / div) shl 8) or (sb / div)
                val out = src[at(i - r)]
                val inn = src[at(i + r + 1)]
                sr += ((inn shr 16) and 0xFF) - ((out shr 16) and 0xFF)
                sg += ((inn shr 8) and 0xFF) - ((out shr 8) and 0xFF)
                sb += (inn and 0xFF) - (out and 0xFF)
            }
        }
    }

    /** Average brightness (0 dark – 1 light) of part of [bmp], as a person would judge it. */
    fun brightness(bmp: Bitmap, left: Float = 0f, top: Float = 0f, right: Float = 1f, bottom: Float = 1f): Float {
        val x0 = (left * bmp.width).toInt().coerceIn(0, bmp.width - 1)
        val x1 = (right * bmp.width).toInt().coerceIn(x0 + 1, bmp.width)
        val y0 = (top * bmp.height).toInt().coerceIn(0, bmp.height - 1)
        val y1 = (bottom * bmp.height).toInt().coerceIn(y0 + 1, bmp.height)
        val stepX = max(1, (x1 - x0) / 48)
        val stepY = max(1, (y1 - y0) / 48)
        var sum = 0.0
        var n = 0
        var y = y0
        while (y < y1) {
            var x = x0
            while (x < x1) {
                val p = bmp.getPixel(x, y)
                sum += (0.2126 * ((p shr 16) and 0xFF) + 0.7152 * ((p shr 8) and 0xFF) + 0.0722 * (p and 0xFF)) / 255.0
                n++
                x += stepX
            }
            y += stepY
        }
        return if (n == 0) 0.3f else kotlin.math.sqrt(sum / n).toFloat()
    }
}
