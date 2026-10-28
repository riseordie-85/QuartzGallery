package com.tim.gallery.quartz

import org.json.JSONObject

/** How a bar is filled: its color (see-through over frosted glass), or a gradient from it to a second color. */
enum class BarFill(val label: String) { GLASS("Color"), GRADIENT("Gradient") }

enum class BarOutline(val label: String) { ROUNDED("Rounded"), SLANTED("Slanted"), POINTED("Pointed"), WAVE("Wave") }

/** The rim of light around a bar, like Quartz Launcher's dock. */
enum class BarEdge(val label: String) { NONE("None"), HAIRLINE("Hairline"), SOFT("Soft"), BRIGHT("Bright") }

enum class GradientDirection(val label: String) { HORIZONTAL("Across"), VERTICAL("Down"), DIAGONAL("Diagonal"), RADIAL("From middle") }

/** Where the floating Photos / Albums bar sits. */
enum class NavPosition(val label: String) { LEFT("Left"), CENTER("Middle"), RIGHT("Right"), WIDE("Full width") }

/** One bar's look (the top bar and the Photos / Albums bar each have their own). */
data class BarStyle(
    /** The bar's color; see-through shows the frosted glass under it. */
    val color: Int,
    val fill: BarFill = BarFill.GLASS,
    val color2: Int = 0xFF2546A8.toInt(),
    val gradientDirection: GradientDirection = GradientDirection.HORIZONTAL,
    val outline: BarOutline = BarOutline.ROUNDED,
    /** Corner roundness in dp; [PILL] or more makes round ends. */
    val radius: Int = PILL,
    /** Floating (with a gap around it) or attached to the screen edge. */
    val floating: Boolean = true,
    val pattern: PatternStyle = PatternStyle.NONE,
    val patternStrength: Float = 0.3f,
    val patternSize: Float = 0.5f,
    val edge: BarEdge = BarEdge.SOFT,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("color", color)
        put("fill", fill.name)
        put("color2", color2)
        put("gradientDirection", gradientDirection.name)
        put("outline", outline.name)
        put("radius", radius)
        put("floating", floating)
        put("pattern", pattern.name)
        put("patternStrength", patternStrength.toDouble())
        put("patternSize", patternSize.toDouble())
        put("edge", edge.name)
    }

    companion object {
        const val PILL = 40

        fun fromJson(j: JSONObject?, d: BarStyle): BarStyle {
            if (j == null) return d
            return runCatching {
                BarStyle(
                    color = j.optInt("color", d.color),
                    fill = BarFill.entries.firstOrNull { it.name == j.optString("fill") } ?: d.fill,
                    color2 = j.optInt("color2", d.color2),
                    gradientDirection = GradientDirection.entries.firstOrNull { it.name == j.optString("gradientDirection") } ?: d.gradientDirection,
                    outline = BarOutline.entries.firstOrNull { it.name == j.optString("outline") } ?: d.outline,
                    radius = j.optInt("radius", d.radius),
                    floating = j.optBoolean("floating", d.floating),
                    pattern = PatternStyle.entries.firstOrNull { it.name == j.optString("pattern") } ?: d.pattern,
                    patternStrength = j.optDouble("patternStrength", d.patternStrength.toDouble()).toFloat(),
                    patternSize = j.optDouble("patternSize", d.patternSize.toDouble()).toFloat(),
                    edge = BarEdge.entries.firstOrNull { it.name == j.optString("edge") } ?: d.edge,
                )
            }.getOrDefault(d)
        }
    }
}

/** Everything about the gallery's Quartz look. */
data class QuartzLook(
    val background: WallConfig = WallConfig(),
    val topBar: BarStyle = DEFAULT_TOP,
    val navBar: BarStyle = DEFAULT_NAV,
    /** The selected tab, the open menu item and the month headings. */
    val accent: Int = 0xFF27306E.toInt(),
    val navPosition: NavPosition = NavPosition.LEFT,
    /** Frosted glass: the photos scrolling under the bars are blurred. */
    val blur: Boolean = true,
    /** How strong the blur is, in dp. */
    val blurRadius: Int = 24,
    /** Big bold month headings. */
    val bigHeadings: Boolean = true,
) {
    fun toJson(): String = JSONObject().apply {
        put("background", background.toJson())
        put("topBar", topBar.toJson())
        put("navBar", navBar.toJson())
        put("accent", accent)
        put("navPosition", navPosition.name)
        put("blur", blur)
        put("blurRadius", blurRadius)
        put("bigHeadings", bigHeadings)
    }.toString()

    companion object {
        /** The mockup's look: a purple pill on top, a frosted-glass pill below. */
        val DEFAULT_TOP = BarStyle(color = 0xF24A2388.toInt(), edge = BarEdge.NONE)
        val DEFAULT_NAV = BarStyle(color = 0x8CFFFFFF.toInt(), edge = BarEdge.BRIGHT)

        fun fromJson(text: String?): QuartzLook? = runCatching {
            if (text.isNullOrBlank()) return null
            val j = JSONObject(text)
            val d = QuartzLook()
            QuartzLook(
                background = WallConfig.fromJson(j.optJSONObject("background")) ?: d.background,
                topBar = BarStyle.fromJson(j.optJSONObject("topBar"), DEFAULT_TOP),
                navBar = BarStyle.fromJson(j.optJSONObject("navBar"), DEFAULT_NAV),
                accent = j.optInt("accent", d.accent),
                navPosition = NavPosition.entries.firstOrNull { it.name == j.optString("navPosition") } ?: d.navPosition,
                blur = j.optBoolean("blur", d.blur),
                blurRadius = j.optInt("blurRadius", d.blurRadius),
                bigHeadings = j.optBoolean("bigHeadings", d.bigHeadings),
            )
        }.getOrNull()

        /** Bar looks to start from. */
        val BAR_PRESETS: List<Pair<String, BarStyle>> = listOf(
            "Amethyst" to DEFAULT_TOP,
            "Frosted" to DEFAULT_NAV,
            "Smoke" to BarStyle(color = 0x99101018.toInt(), edge = BarEdge.SOFT),
            "Midnight" to BarStyle(color = 0xF21B2150.toInt(), edge = BarEdge.SOFT),
            "Aurora" to BarStyle(color = 0xE6233E8C.toInt(), fill = BarFill.GRADIENT, color2 = 0xE6138A8A.toInt(), edge = BarEdge.BRIGHT),
            "Rose" to BarStyle(color = 0xE67A2A6A.toInt(), fill = BarFill.GRADIENT, color2 = 0xE6D0566E.toInt(), edge = BarEdge.SOFT),
            "Citrine" to BarStyle(color = 0xE6B8780F.toInt(), fill = BarFill.GRADIENT, color2 = 0xE6E8A21E.toInt(), gradientDirection = GradientDirection.RADIAL, pattern = PatternStyle.CRYSTAL, patternStrength = 0.35f, edge = BarEdge.BRIGHT),
            "Crystal" to BarStyle(color = 0x66FFFFFF, pattern = PatternStyle.DIAMONDS, patternStrength = 0.25f, edge = BarEdge.BRIGHT),
        )
    }
}
