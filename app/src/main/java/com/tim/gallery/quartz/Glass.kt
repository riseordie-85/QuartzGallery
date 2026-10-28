package com.tim.gallery.quartz

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.layer.CompositingStrategy
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.collectLatest
import kotlin.math.PI
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

// ------------------------------------------------------------------ the background, rendered

/** The background drawn for a [w]×[h] screen: sharp, a small blurred copy for glass, and how bright it is. */
class WallImage(
    val config: WallConfig,
    val w: Int,
    val h: Int,
    val full: ImageBitmap?,
    val blurred: ImageBitmap?,
    /** 0 dark – 1 light, as people see it. */
    val brightness: Float,
)

/** The background behind this screen (null while it's the plain theme color). */
val LocalWall = staticCompositionLocalOf<WallImage?> { null }

internal object WallCache {
    private var last: WallImage? = null

    @Synchronized
    fun cached(config: WallConfig, w: Int, h: Int): WallImage? = last?.takeIf { it.config == config && it.w == w && it.h == h }

    /** One background at a time, so slider steps queue up instead of piling up big bitmaps. */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val dispatcher = kotlinx.coroutines.Dispatchers.Default.limitedParallelism(1)

    /** Renders [config]; [scale] < 1 for a quick first look while a slider moves. Gives up early once cancelled. */
    suspend fun build(config: WallConfig, w: Int, h: Int, scale: Float): WallImage {
        kotlinx.coroutines.currentCoroutineContext().ensureActive()
        val small = WallRenderer.render(w, h, 0.125f, config)
        kotlinx.coroutines.currentCoroutineContext().ensureActive()
        val full = WallRenderer.render(w, h, scale, config)
        val brightness = WallRenderer.brightness(small)
        WallRenderer.blur(small, max(2, (min(w, h) * 0.125f / 40f).roundToInt()))
        val image = WallImage(config, w, h, full.asImage(), small.asImage(), brightness)
        if (scale >= 1f) synchronized(this) { last = image }
        return image
    }

    private fun Bitmap.asImage(): ImageBitmap {
        prepareToDraw()
        return asImageBitmap()
    }
}

// ------------------------------------------------------------------ what scrolls under the bars

/**
 * The content of one screen (its grid or list), recorded so the bars can draw it blurred behind them.
 * [scroll] reads the scroll position, so the bars redraw as it moves.
 */
@Stable
class GlassSource {
    var layer: GraphicsLayer? = null
    var position by mutableStateOf(Offset.Zero)
    var size by mutableStateOf(IntSize.Zero)

    /** Bumped a few times after scrolling stops, so the glass catches thumbnails that load late. */
    var settle by mutableIntStateOf(0)
    var scroll: () -> Any? = { null }

    /** Read from a bar's drawing: anything that should redraw the glass. */
    fun watch() {
        settle
        scroll()
    }
}

val LocalGlassSource = staticCompositionLocalOf<GlassSource?> { null }

/** Inside a screen's content: how to read its scroll position (for a bar floating inside the content, like the preview pane's). */
val LocalGlassScroll = staticCompositionLocalOf<() -> Any?> { { null } }

/**
 * Records this screen's content for the glass, and fades it out under the status bar so photos don't
 * clash with the clock and icons. Bars inside the content can't blur it (they'd draw themselves).
 */
@Composable
fun GlassContent(
    source: GlassSource,
    modifier: Modifier = Modifier,
    /** Fade the content out under the status bar (off for content inside content that already does). */
    fadeTop: Boolean = true,
    content: @Composable BoxScope.() -> Unit,
) {
    val layer = rememberGraphicsLayer()
    source.layer = layer
    val statusBar = WindowInsets.statusBars.getTop(LocalDensity.current).toFloat()
    val fade = with(LocalDensity.current) { 12.dp.toPx() }
    LaunchedEffect(source) {
        snapshotFlow { source.scroll() }.collectLatest {
            for (wait in longArrayOf(120, 300, 600, 1200)) {
                delay(wait)
                source.settle++
            }
        }
    }
    Box(
        modifier
            .onGloballyPositioned {
                source.position = it.positionInWindow()
                source.size = it.size
            }
            .drawWithContent {
                val top = if (fadeTop) statusBar - source.position.y else 0f
                layer.compositingStrategy = if (top > 0f) CompositingStrategy.Offscreen else CompositingStrategy.Auto
                layer.record {
                    this@drawWithContent.drawContent()
                    if (top > 0f) {
                        // Clear at the very top, solid just below the status bar
                        drawRect(
                            Brush.verticalGradient(0f to Color.Transparent, 1f to Color.Black, startY = top * 0.35f, endY = top + fade),
                            blendMode = BlendMode.DstIn,
                        )
                    }
                }
                drawLayer(layer)
            },
    ) {
        CompositionLocalProvider(LocalGlassSource provides null, LocalGlassScroll provides source.scroll) { content() }
    }
}

// ------------------------------------------------------------------ bar shapes

/** A bar's outline. [top] bars hang from the top; [flush] bars touch their screen edge (square corners there). */
class BarShape(
    private val outline: BarOutline,
    private val radiusDp: Float,
    private val top: Boolean,
    private val flush: Boolean,
) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val length = size.width
        val thick = size.height
        val r = min(with(density) { radiusDp.dp.toPx() }, thick / 2f)
        val outer = if (flush) 0f else r
        // Worked out for a bar along the bottom (v = 0 is the side facing into the screen), then flipped for the top
        fun at(u: Float, v: Float) = if (top) Offset(u, thick - v) else Offset(u, v)
        return when (outline) {
            BarOutline.ROUNDED -> {
                val inner = CornerRadius(r)
                val out = CornerRadius(outer)
                Outline.Rounded(
                    if (top) RoundRect(Rect(Offset.Zero, size), out, out, inner, inner)
                    else RoundRect(Rect(Offset.Zero, size), inner, inner, out, out),
                )
            }
            BarOutline.SLANTED -> {
                val s = min(thick * 0.5f, length / 4f)
                Outline.Generic(rounded(listOf(at(s, 0f), at(length - s, 0f), at(length, thick), at(0f, thick)), listOf(r * 0.6f, r * 0.6f, outer * 0.6f, outer * 0.6f)))
            }
            BarOutline.POINTED -> {
                val p = min(thick * 0.42f, length / 4f)
                val h = r * 0.4f
                val (points, radii) = if (flush) {
                    listOf(at(p, 0f), at(length - p, 0f), at(length, thick / 2f), at(length, thick), at(0f, thick), at(0f, thick / 2f)) to
                        listOf(h, h, h, 0f, 0f, h)
                } else {
                    listOf(at(0f, thick / 2f), at(p, 0f), at(length - p, 0f), at(length, thick / 2f), at(length - p, thick), at(p, thick)) to
                        List(6) { h }
                }
                Outline.Generic(rounded(points, radii))
            }
            BarOutline.WAVE -> {
                val amp = min(thick * 0.1f, with(density) { 5.dp.toPx() })
                val waves = max(1, (length / (thick * 3f)).roundToInt())
                val steps = waves * 24
                val corner = r * 0.6f
                val points = ArrayList<Offset>()
                val radii = ArrayList<Float>()
                // Floating bars wave on both long sides; attached ones only on the inner side
                fun side(v0: Float, sign: Float, forward: Boolean) {
                    for (s in 0..steps) {
                        val k = if (forward) s else steps - s
                        val u = length * k / steps
                        if (k != 0 && k != steps && (u < corner * 2f || u > length - corner * 2f)) continue
                        points += at(u, v0 + sign * (amp - amp * sin(2f * PI.toFloat() * waves * k / steps)))
                        radii += if (k == 0 || k == steps) corner else 0f
                    }
                }
                side(0f, 1f, forward = true)
                if (flush) {
                    points += at(length, thick); radii += 0f
                    points += at(0f, thick); radii += 0f
                } else {
                    side(thick, -1f, forward = false)
                }
                Outline.Generic(rounded(points, radii))
            }
        }
    }

    private fun rounded(points: List<Offset>, radii: List<Float>): Path {
        val path = Path()
        val n = points.size
        for (i in 0 until n) {
            val p0 = points[(i - 1 + n) % n]
            val p1 = points[i]
            val p2 = points[(i + 1) % n]
            val d0 = hypot(p0.x - p1.x, p0.y - p1.y)
            val d2 = hypot(p2.x - p1.x, p2.y - p1.y)
            val r = min(radii[i], min(d0, d2) / 2f)
            val a = if (d0 > 0f) p1 + (p0 - p1) * (r / d0) else p1
            val b = if (d2 > 0f) p1 + (p2 - p1) * (r / d2) else p1
            if (i == 0) path.moveTo(a.x, a.y) else path.lineTo(a.x, a.y)
            if (r > 0f) path.quadraticBezierTo(p1.x, p1.y, b.x, b.y)
        }
        path.close()
        return path
    }

    override fun equals(other: Any?) = other is BarShape && other.outline == outline && other.radiusDp == radiusDp &&
        other.top == top && other.flush == flush

    override fun hashCode() = ((outline.hashCode() * 31 + radiusDp.hashCode()) * 31 + top.hashCode()) * 31 + flush.hashCode()
}

// ------------------------------------------------------------------ bar surfaces

/** Dark text on light glass, white on dark: whichever reads with more contrast. */
object Ink {
    val Dark = Color(0xFF15141C)
    val Light = Color.White
    private val darkLum = Dark.luminance()

    /**
     * How light [tint] looks over something [behind] bright. Both use the "as people see it" scale
     * (square root of luminance); the mixing itself is done in linear light, as the screen does it.
     */
    fun brightness(tint: Color, behind: Float): Float {
        val l = tint.alpha * tint.copy(alpha = 1f).luminance() + (1 - tint.alpha) * behind * behind
        return kotlin.math.sqrt(l.coerceIn(0f, 1f))
    }

    /** True when white text reads better than dark on something this bright. */
    fun prefersLight(brightness: Float): Boolean {
        val l = brightness * brightness
        return (1.05f / (l + 0.05f)) >= ((l + 0.05f) / (darkLum + 0.05f))
    }

    fun on(brightness: Float): Color = if (prefersLight(brightness)) Light else Dark
}

/** How bright a bar in [style] looks over a background [behind] bright. */
fun barBrightness(style: BarStyle, behind: Float): Float {
    val a = Ink.brightness(Color(style.color), behind)
    return if (style.fill == BarFill.GRADIENT) (a + Ink.brightness(Color(style.color2), behind)) / 2f else a
}

/**
 * A bar's background, like Quartz Launcher's dock: frosted glass (the background and the photos under it,
 * blurred), the bar's color or gradient over it, an optional crystal pattern that lines up with the
 * background's, and a rim of light. [base] is the plain screen color when there's no Quartz background.
 */
@Composable
fun Modifier.barSurface(style: BarStyle, shape: Shape, look: QuartzLook, base: Color, ink: Color): Modifier {
    val wall = LocalWall.current
    val source = LocalGlassSource.current
    val blurLayer = rememberGraphicsLayer()
    val view = LocalView.current
    val blurPx = with(LocalDensity.current) { look.blurRadius.dp.toPx() }
    var origin by remember { mutableStateOf(Offset.Zero) }
    return this
        .onGloballyPositioned { origin = it.positionInWindow() }
        // The bar takes touches on its own background, so they don't reach the photos scrolling under it
        .pointerInput(Unit) {}
        .drawBehind {
            val outline = shape.createOutline(size, layoutDirection, this)
            val path = Path().apply { addOutline(outline) }
            clipPath(path) {
                if (look.blur) {
                    // The background under the bar, already blurred (or the plain screen color)
                    val img = wall?.blurred
                    if (img != null) {
                        withTransform({
                            translate(-origin.x, -origin.y)
                            scale(wall.w / img.width.toFloat(), wall.h / img.height.toFloat(), pivot = Offset.Zero)
                        }) { drawImage(img, filterQuality = FilterQuality.Low) }
                    } else {
                        drawRect(base)
                    }
                    // The photos scrolling under it, blurred live
                    val content = source?.layer
                    if (source != null && content != null && blurPx >= 1f) {
                        source.watch()
                        val m = blurPx * 2f
                        blurLayer.renderEffect = BlurEffect(blurPx, blurPx, TileMode.Clamp)
                        blurLayer.record(size = IntSize((size.width + 2 * m).roundToInt(), (size.height + 2 * m).roundToInt())) {
                            translate(source.position.x - origin.x + m, source.position.y - origin.y + m) { drawLayer(content) }
                        }
                        blurLayer.topLeft = IntOffset(-m.roundToInt(), -m.roundToInt())
                        drawLayer(blurLayer)
                    }
                }
                val tint = Color(style.color)
                if (style.fill == BarFill.GRADIENT) {
                    val colors = listOf(tint, Color(style.color2))
                    val brush = when (style.gradientDirection) {
                        GradientDirection.HORIZONTAL -> Brush.horizontalGradient(colors)
                        GradientDirection.VERTICAL -> Brush.verticalGradient(colors)
                        GradientDirection.DIAGONAL -> Brush.linearGradient(colors, start = Offset.Zero, end = Offset(size.width, size.height))
                        GradientDirection.RADIAL -> Brush.radialGradient(colors, center = center, radius = max(size.width, size.height) / 2f)
                    }
                    drawRect(brush)
                } else {
                    drawRect(tint)
                }
                if (style.pattern != PatternStyle.NONE) {
                    // In screen coordinates, so it lines up with the same pattern on the background
                    val screenW = view.rootView.width.toFloat().coerceAtLeast(1f)
                    val screenH = view.rootView.height.toFloat().coerceAtLeast(1f)
                    drawIntoCanvas { canvas ->
                        val native = canvas.nativeCanvas
                        native.save()
                        native.translate(-origin.x, -origin.y)
                        val clip = android.graphics.Rect(
                            origin.x.toInt(), origin.y.toInt(),
                            (origin.x + size.width).toInt() + 1, (origin.y + size.height).toInt() + 1,
                        )
                        native.clipRect(clip)
                        BarPainter.drawPatternOnly(native, screenW, screenH, clip, style.pattern, style.patternStrength, style.patternSize)
                        native.restore()
                    }
                }
            }
            drawEdgeLight(outline, style.edge, ink)
        }
}

/**
 * The edge of a glass panel: a hairline, or a rim of light brightest at the top left with a glint at the
 * bottom right, like light catching the edge of glass.
 */
fun DrawScope.drawEdgeLight(outline: Outline, edge: BarEdge, ink: Color) {
    val px = 1.dp.toPx()
    val lightGlass = ink.luminance() < 0.5f
    when (edge) {
        BarEdge.NONE -> Unit
        BarEdge.HAIRLINE -> drawOutline(outline, ink.copy(alpha = 0.22f), style = Stroke(px * 2f))
        BarEdge.SOFT, BarEdge.BRIGHT -> {
            val bright = edge == BarEdge.BRIGHT
            val k = if (bright) 1f else 0.55f
            // On light glass a faint dark line keeps the edge visible under the white rim
            if (lightGlass) drawOutline(outline, Color.Black.copy(alpha = if (bright) 0.16f else 0.10f), style = Stroke(px * 2f))
            fun w(a: Float) = Color.White.copy(alpha = (a * k).coerceIn(0f, 1f))
            val rim = Brush.linearGradient(
                0f to w(0.95f),
                0.3f to w(0.32f),
                0.5f to w(0.15f),
                0.75f to w(0.30f),
                1f to w(0.75f),
                start = Offset.Zero,
                end = Offset(size.width, size.height),
            )
            drawOutline(outline, rim, style = Stroke(px * if (bright) 3.2f else 2.4f))
            if (bright) {
                drawOutline(outline, Brush.verticalGradient(0f to Color.White.copy(alpha = 0.14f), 0.45f to Color.Transparent))
            }
        }
    }
}
