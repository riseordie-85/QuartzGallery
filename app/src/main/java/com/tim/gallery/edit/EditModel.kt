package com.tim.gallery.edit

import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.Typeface

/** A rectangle in 0..1 coordinates. */
data class NRect(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width get() = right - left
    val height get() = bottom - top

    fun union(o: NRect) = NRect(minOf(left, o.left), minOf(top, o.top), maxOf(right, o.right), maxOf(bottom, o.bottom))

    companion object {
        val FULL = NRect(0f, 0f, 1f, 1f)
    }
}

/** ratio = width / height in pixels. null = free, -1 = the photo's own shape. */
enum class AspectOption(val label: String, val ratio: Float?) {
    FREE("Free", null),
    ORIGINAL("Original", -1f),
    SQUARE("1:1", 1f),
    R4_3("4:3", 4f / 3f),
    R3_4("3:4", 3f / 4f),
    R16_9("16:9", 16f / 9f),
    R9_16("9:16", 9f / 16f),
}

/** What happens to the corners when the photo is straightened. */
enum class FillMode { CROP_IN, BACKGROUND }

data class Geometry(
    /** 90° clockwise turns, 0..3. */
    val quarterTurns: Int = 0,
    /** Mirrored horizontally (applied after the 90° turns). */
    val flipped: Boolean = false,
    /** Straighten angle in degrees, -45..45. */
    val angle: Float = 0f,
    val fill: FillMode = FillMode.CROP_IN,
    /** Corner color in BACKGROUND mode; Color.TRANSPARENT allowed (saves as PNG). */
    val fillColor: Int = Color.BLACK,
    /** Crop box in 0..1 of the frame. */
    val crop: NRect = NRect.FULL,
    val aspect: AspectOption = AspectOption.FREE,
)

enum class Adjust(val label: String, val bipolar: Boolean) {
    EXPOSURE("Exposure", true),
    BRIGHTNESS("Brightness", true),
    CONTRAST("Contrast", true),
    HIGHLIGHTS("Highlights", true),
    SHADOWS("Shadows", true),
    SATURATION("Saturation", true),
    VIBRANCE("Vibrance", true),
    WARMTH("Warmth", true),
    TINT("Tint", true),
    SHARPNESS("Sharpness", false),
    VIGNETTE("Vignette", false),
    GRAIN("Grain", false),
}

/**
 * Filter looks: a color matrix (rows for R, G, B: r, g, b coefficients + offset in 0..1)
 * plus optional vignette and grain. [duotone] maps brightness onto two chosen colors instead.
 */
enum class FilterPreset(
    val label: String,
    val matrix: FloatArray,
    val vignette: Float = 0f,
    val grain: Float = 0f,
    val duotone: Boolean = false,
) {
    NONE("Original", IDENTITY),
    VIVID("Vivid", cm(saturation(1.4f), contrast(1.1f))),
    WARM("Warm", cm(scale(1.08f, 1.0f, 0.88f), saturation(1.1f))),
    COOL("Cool", cm(scale(0.9f, 1.0f, 1.1f), saturation(1.05f))),
    GOLDEN("Golden", cm(scale(1.1f, 1.02f, 0.82f), contrast(1.05f), offset(0.03f, 0.01f, -0.02f))),
    FADE("Fade", cm(contrast(0.82f), saturation(0.8f), offset(0.05f, 0.05f, 0.05f))),
    VINTAGE("Vintage", cm(sepiaMix(0.45f), contrast(0.9f), offset(0.04f, 0.03f, 0.0f)), vignette = 0.35f, grain = 0.3f),
    DRAMATIC("Dramatic", cm(contrast(1.35f), saturation(0.85f)), vignette = 0.35f),
    SEPIA("Sepia", cm(sepiaMix(1f))),
    BW("B&W", cm(saturation(0f), contrast(1.1f))),
    NOIR("Noir", cm(saturation(0f), contrast(1.45f)), vignette = 0.45f, grain = 0.25f),
    INVERT("Invert", INVERT_MATRIX),
    DUOTONE("Duotone", IDENTITY, duotone = true),
}

/** Ready-made duotone pairs: shadows color to highlights color. */
val DUOTONE_PRESETS: List<Pair<Int, Int>> = listOf(
    0xFF1B1F4B.toInt() to 0xFFFFB86B.toInt(), // navy / peach
    0xFF2D0B59.toInt() to 0xFF2EE6D6.toInt(), // purple / aqua
    0xFF0B3D2E.toInt() to 0xFFE8F5A3.toInt(), // forest / lime
    0xFF3A0CA3.toInt() to 0xFFF72585.toInt(), // indigo / magenta
    0xFF101820.toInt() to 0xFFFEE715.toInt(), // black / yellow
    0xFF5C1A1B.toInt() to 0xFFF4D6CC.toInt(), // oxblood / blush
    0xFF00324A.toInt() to 0xFFF2F2F2.toInt(), // deep teal / white
)

enum class CutoutMode(val label: String) {
    OFF("Off"),
    TRANSPARENT("Transparent"),
    BLUR("Blur"),
    COLOR("Color"),
}

/** Fonts that ship with Android. Keys look like "sys:BOLD". */
enum class TextFont(val label: String, val family: String, val style: Int) {
    SANS("Sans", "sans-serif", Typeface.NORMAL),
    BOLD("Bold", "sans-serif", Typeface.BOLD),
    SERIF("Serif", "serif", Typeface.NORMAL),
    MONO("Mono", "monospace", Typeface.NORMAL),
    SCRIPT("Script", "cursive", Typeface.NORMAL),
    CASUAL("Casual", "casual", Typeface.NORMAL),
    CONDENSED("Condensed", "sans-serif-condensed", Typeface.BOLD),
    ;

    val key: String get() = "sys:$name"
    val typeface: Typeface by lazy { Typeface.create(family, style) }
}

enum class TextAlignOption { LEFT, CENTER, RIGHT }

/** Outline around letters. [width] is relative to the text size. */
data class TextStroke(val color: Int = Color.BLACK, val width: Float = 0.12f)

/** Drop shadow. [blur] and [distance] are relative to the text size; [angle] in degrees (90 = straight down). */
data class TextShadow(
    val color: Int = 0xB3000000.toInt(),
    val blur: Float = 0.12f,
    val distance: Float = 0.06f,
    val angle: Float = 90f,
)

/** Background box/highlight behind the text. [padding] and [radius] are relative to the text size. */
data class TextBox(
    val color: Int = Color.BLACK,
    val opacity: Float = 0.75f,
    val padding: Float = 0.35f,
    val radius: Float = 0.45f,
)

/** Soft glow around the letters. [radius] is relative to the text size. */
data class TextGlow(val color: Int = 0xFF2EE6D6.toInt(), val radius: Float = 0.35f)

/**
 * Something placed on top of the photo. Position is the center in 0..1 of the final (cropped)
 * picture; [scale] is relative to its short side; [rotation] is in degrees; [opacity] is 0..1.
 */
sealed class Layer {
    abstract val id: Long
    abstract val cx: Float
    abstract val cy: Float
    abstract val scale: Float
    abstract val rotation: Float
    abstract val opacity: Float

    abstract fun moved(cx: Float, cy: Float, scale: Float, rotation: Float): Layer
    abstract fun withId(id: Long): Layer
    abstract fun withOpacity(opacity: Float): Layer
}

/**
 * An image on top of the photo. [uri] can be a content:// photo, "asset:stickers/…" (built-in pack),
 * "emoji:😀", or "file:///…" (a saved watermark logo).
 */
data class StickerLayer(
    override val id: Long,
    val uri: String,
    override val cx: Float = 0.5f,
    override val cy: Float = 0.5f,
    /** Sticker width as a fraction of the picture's short side. */
    override val scale: Float = 0.4f,
    override val rotation: Float = 0f,
    override val opacity: Float = 1f,
) : Layer() {
    override fun moved(cx: Float, cy: Float, scale: Float, rotation: Float) =
        copy(cx = cx, cy = cy, scale = scale, rotation = rotation)

    override fun withId(id: Long) = copy(id = id)
    override fun withOpacity(opacity: Float) = copy(opacity = opacity)
}

data class TextLayer(
    override val id: Long,
    val text: String,
    val color: Int = Color.WHITE,
    /** "sys:BOLD", "g:Lobster" (Google Fonts) or "file:name.ttf" (imported). */
    val fontKey: String = TextFont.BOLD.key,
    val align: TextAlignOption = TextAlignOption.CENTER,
    val stroke: TextStroke? = null,
    val shadow: TextShadow? = TextShadow(),
    val box: TextBox? = null,
    val glow: TextGlow? = null,
    /** Extra space between letters, in ems (0 = normal). */
    val letterSpacing: Float = 0f,
    /** Line height multiplier (1 = normal). */
    val lineSpacing: Float = 1f,
    /** Bend the text along an arc: -1 (smile) .. 1 (rainbow). One line of text only. */
    val curve: Float = 0f,
    override val cx: Float = 0.5f,
    override val cy: Float = 0.5f,
    /** 1.0 = text height of 10% of the picture's short side. */
    override val scale: Float = 1f,
    override val rotation: Float = 0f,
    override val opacity: Float = 1f,
) : Layer() {
    override fun moved(cx: Float, cy: Float, scale: Float, rotation: Float) =
        copy(cx = cx, cy = cy, scale = scale, rotation = rotation)

    override fun withId(id: Long) = copy(id = id)
    override fun withOpacity(opacity: Float) = copy(opacity = opacity)
}

enum class ShapeKind(val label: String) {
    RECT("Rectangle"),
    ROUNDED("Rounded"),
    OVAL("Circle"),
    LINE("Line"),
    ARROW("Arrow"),
    TRIANGLE("Triangle"),
    STAR("Star"),
    HEART("Heart"),
    BUBBLE("Speech"),
    ;

    /** Lines and arrows are drawn with the outline color only. */
    val isStroke: Boolean get() = this == LINE || this == ARROW
}

data class ShapeLayer(
    override val id: Long,
    val kind: ShapeKind,
    /** Fill color; Color.TRANSPARENT for none. */
    val fill: Int = Color.TRANSPARENT,
    /** Outline color; Color.TRANSPARENT for none. */
    val stroke: Int = 0xFFE53935.toInt(),
    /** Outline thickness as a % of the picture's short side. */
    val strokeWidth: Float = 1f,
    /** Width ÷ height. */
    val aspect: Float = 1f,
    /** Corner rounding for rectangles, 0..0.5 of the short side. */
    val cornerRadius: Float = 0.2f,
    override val cx: Float = 0.5f,
    override val cy: Float = 0.5f,
    /** Shape width as a fraction of the picture's short side. */
    override val scale: Float = 0.4f,
    override val rotation: Float = 0f,
    override val opacity: Float = 1f,
) : Layer() {
    override fun moved(cx: Float, cy: Float, scale: Float, rotation: Float) =
        copy(cx = cx, cy = cy, scale = scale, rotation = rotation)

    override fun withId(id: Long) = copy(id = id)
    override fun withOpacity(opacity: Float) = copy(opacity = opacity)

    companion object {
        /** Sensible starting look for each shape. */
        fun default(id: Long, kind: ShapeKind): ShapeLayer = when (kind) {
            ShapeKind.STAR -> ShapeLayer(id, kind, fill = 0xFFFDD835.toInt(), stroke = Color.TRANSPARENT)
            ShapeKind.HEART -> ShapeLayer(id, kind, fill = 0xFFE53935.toInt(), stroke = Color.TRANSPARENT)
            ShapeKind.BUBBLE -> ShapeLayer(id, kind, fill = Color.WHITE, stroke = Color.BLACK, strokeWidth = 0.6f, aspect = 1.4f)
            ShapeKind.LINE -> ShapeLayer(id, kind, stroke = Color.WHITE, aspect = 8f)
            ShapeKind.ARROW -> ShapeLayer(id, kind, stroke = 0xFFE53935.toInt(), strokeWidth = 1.4f, aspect = 3f)
            ShapeKind.RECT, ShapeKind.ROUNDED -> ShapeLayer(id, kind, aspect = 1.4f)
            else -> ShapeLayer(id, kind)
        }
    }
}

data class EditState(
    val geometry: Geometry = Geometry(),
    val adjust: Map<Adjust, Float> = emptyMap(),
    val filter: FilterPreset = FilterPreset.NONE,
    val filterStrength: Float = 1f,
    val duoShadow: Int = DUOTONE_PRESETS[0].first,
    val duoHighlight: Int = DUOTONE_PRESETS[0].second,
    val cutout: CutoutMode = CutoutMode.OFF,
    val cutoutColor: Int = Color.WHITE,
    val layers: List<Layer> = emptyList(),
) {
    fun adjust(a: Adjust): Float = adjust[a] ?: 0f

    /** Replaces one layer (by id) with [change] applied to it. */
    fun updateLayer(id: Long, change: (Layer) -> Layer): EditState =
        copy(layers = layers.map { if (it.id == id) change(it) else it })

    /** Same picture with no color changes, for the hold-to-compare button. */
    fun withoutColor(): EditState = copy(adjust = emptyMap(), filter = FilterPreset.NONE)

    /** True when the crop box reaches past the photo (Background mode adds space). */
    val cropExtendsPastPhoto: Boolean
        get() = geometry.crop.left < -0.0005f || geometry.crop.top < -0.0005f ||
            geometry.crop.right > 1.0005f || geometry.crop.bottom > 1.0005f

    /** The saved file needs an alpha channel (so it'll be a PNG). */
    val needsAlpha: Boolean
        get() = cutout == CutoutMode.TRANSPARENT ||
            (geometry.fill == FillMode.BACKGROUND && Color.alpha(geometry.fillColor) < 255 &&
                (geometry.angle != 0f || cropExtendsPastPhoto))
}

/** Colors offered for text, background fill and cutout backgrounds. */
val PALETTE: List<Int> = listOf(
    Color.WHITE,
    Color.BLACK,
    0xFFE53935.toInt(), // red
    0xFFFB8C00.toInt(), // orange
    0xFFFDD835.toInt(), // yellow
    0xFF43A047.toInt(), // green
    0xFF1E88E5.toInt(), // blue
    0xFF8E24AA.toInt(), // purple
    0xFFEC407A.toInt(), // pink
    0xFF6D4C41.toInt(), // brown
    0xFF90A4AE.toInt(), // gray
)

// ---------------------------------------------------------------- color matrix helpers

private val IDENTITY = floatArrayOf(
    1f, 0f, 0f, 0f,
    0f, 1f, 0f, 0f,
    0f, 0f, 1f, 0f,
)

private val INVERT_MATRIX = floatArrayOf(
    -1f, 0f, 0f, 1f,
    0f, -1f, 0f, 1f,
    0f, 0f, -1f, 1f,
)

/** Combines matrices (applied in order) into 3 rows of [r, g, b, offset(0..1)]. */
private fun cm(vararg steps: ColorMatrix): FloatArray {
    val m = ColorMatrix()
    for (s in steps) m.postConcat(s)
    val a = m.array
    return floatArrayOf(
        a[0], a[1], a[2], a[4] / 255f,
        a[5], a[6], a[7], a[9] / 255f,
        a[10], a[11], a[12], a[14] / 255f,
    )
}

private fun saturation(s: Float) = ColorMatrix().apply { setSaturation(s) }

private fun contrast(c: Float): ColorMatrix {
    val t = (1f - c) * 127.5f
    return ColorMatrix(
        floatArrayOf(
            c, 0f, 0f, 0f, t,
            0f, c, 0f, 0f, t,
            0f, 0f, c, 0f, t,
            0f, 0f, 0f, 1f, 0f,
        )
    )
}

private fun scale(r: Float, g: Float, b: Float) = ColorMatrix().apply { setScale(r, g, b, 1f) }

private fun offset(r: Float, g: Float, b: Float) = ColorMatrix(
    floatArrayOf(
        1f, 0f, 0f, 0f, r * 255f,
        0f, 1f, 0f, 0f, g * 255f,
        0f, 0f, 1f, 0f, b * 255f,
        0f, 0f, 0f, 1f, 0f,
    )
)

/** Classic sepia, blended with the original by [amount]. */
private fun sepiaMix(amount: Float): ColorMatrix {
    val s = floatArrayOf(
        0.393f, 0.769f, 0.189f,
        0.349f, 0.686f, 0.168f,
        0.272f, 0.534f, 0.131f,
    )
    val out = FloatArray(20)
    for (row in 0..2) {
        for (col in 0..2) {
            val id = if (row == col) 1f else 0f
            out[row * 5 + col] = id + (s[row * 3 + col] - id) * amount
        }
    }
    out[18] = 1f
    return ColorMatrix(out)
}
