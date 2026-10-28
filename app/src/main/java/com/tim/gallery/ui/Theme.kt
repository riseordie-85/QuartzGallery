package com.tim.gallery.ui

import android.content.Context
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TopAppBarColors
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.layout.onSizeChanged
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import com.tim.gallery.settings.AppSettings
import com.tim.gallery.settings.GradientSettings
import com.tim.gallery.settings.GradientType
import com.tim.gallery.settings.ThemeMode
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

private fun galleryColorScheme(context: Context, dark: Boolean): ColorScheme = when {
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
        if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    dark -> darkColorScheme()
    else -> lightColorScheme()
}

/** App theme: follows the Light / Dark / Auto choice in Settings, with the phone's wallpaper colors. */
@Composable
fun GalleryTheme(content: @Composable () -> Unit) {
    val mode by AppSettings.theme.collectAsState()
    val dark = when (mode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    SystemBarIcons(dark)
    MaterialTheme(colorScheme = galleryColorScheme(LocalContext.current, dark), content = content)
}

/** Light or dark status/navigation bar icons, to stay visible on the screen behind them. */
@Composable
fun SystemBarIcons(darkBackground: Boolean) {
    val view = LocalView.current
    SideEffect {
        val window = view.context.findActivity()?.window ?: return@SideEffect
        val c = WindowCompat.getInsetsController(window, view)
        c.isAppearanceLightStatusBars = !darkBackground
        c.isAppearanceLightNavigationBars = !darkBackground
    }
}

/** True inside [MainBackground] while the gradient is on (screens then draw no background of their own). */
val LocalGradientActive = staticCompositionLocalOf { false }

/** The gradient as a brush for a w×h area. */
fun gradientBrush(g: GradientSettings, size: Size): Brush {
    val start = Color(g.start)
    val end = Color(g.end)
    val center = Offset(size.width / 2f, size.height / 2f)
    if (g.type == GradientType.RADIAL) {
        return Brush.radialGradient(listOf(start, end), center, hypot(size.width, size.height) / 2f)
    }
    val rad = Math.toRadians(g.angle.toDouble())
    val dx = cos(rad).toFloat()
    val dy = sin(rad).toFloat()
    val half = (abs(size.width * dx) + abs(size.height * dy)) / 2f
    val a = Offset(center.x - dx * half, center.y - dy * half)
    val b = Offset(center.x + dx * half, center.y + dy * half)
    val colors = if (g.type == GradientType.REFLECTED) listOf(start, end, start) else listOf(start, end)
    return Brush.linearGradient(colors, a, b)
}

/** Whether light text reads better on this gradient. */
fun gradientIsDark(g: GradientSettings): Boolean =
    (android.graphics.Color.luminance(g.start) + android.graphics.Color.luminance(g.end)) / 2f < 0.45f

/** A first guess at how bright a background looks, before it's rendered. */
private fun estimateBrightness(c: com.tim.gallery.quartz.WallConfig): Float {
    val used = when {
        c.kind == com.tim.gallery.quartz.WallKind.SOLID -> listOf(c.solid)
        c.gradient == com.tim.gallery.quartz.GradientStyle.CORNERS -> (0 until 5).map { c.color(it) }
        c.gradient == com.tim.gallery.quartz.GradientStyle.RADIAL -> listOf(c.color(0), c.color(2))
        c.linearMiddle -> listOf(c.color(0), c.color(1), c.color(2))
        else -> listOf(c.color(0), c.color(2))
    }
    val avg = used.map { kotlin.math.sqrt(android.graphics.Color.luminance(it)) }.average().toFloat()
    return avg * (1f - c.dim.coerceIn(0f, 0.8f))
}

/**
 * Background for the main screens: the Quartz background (gradient, 5-point gradient or color, with an optional
 * crystal pattern) when one is chosen. Text switches to light or dark so it stays readable on it.
 */
@Composable
fun MainBackground(content: @Composable () -> Unit) {
    val look by AppSettings.look.collectAsState()
    val bg = look.background
    if (!bg.drawn) {
        SystemBarIcons(MaterialTheme.colorScheme.background.luminance() < 0.5f)
        CompositionLocalProvider(com.tim.gallery.quartz.LocalWall provides null, content = content)
        return
    }
    var size by remember { mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }
    val wall by produceState(com.tim.gallery.quartz.WallCache.cached(bg, size.width, size.height), bg, size) {
        if (size.width <= 0 || size.height <= 0) return@produceState
        com.tim.gallery.quartz.WallCache.cached(bg, size.width, size.height)?.let {
            value = it
            return@produceState
        }
        // A quick small one first (while a slider moves), then the sharp one
        value = withContext(Dispatchers.Default) { com.tim.gallery.quartz.WallCache.build(bg, size.width, size.height, 0.3f) }
        kotlinx.coroutines.delay(120)
        value = withContext(Dispatchers.Default) { com.tim.gallery.quartz.WallCache.build(bg, size.width, size.height, 1f) }
    }
    val current = wall?.takeIf { it.w == size.width && it.h == size.height }
    val brightness = current?.takeIf { it.config == bg }?.brightness ?: estimateBrightness(bg)
    val dark = brightness < 0.6f
    SystemBarIcons(dark)
    Box(
        Modifier
            .fillMaxSize()
            .onSizeChanged { size = it }
            .drawBehind {
                val img = current?.full
                if (img != null) {
                    drawImage(img, dstSize = androidx.compose.ui.unit.IntSize(this.size.width.toInt(), this.size.height.toInt()), filterQuality = androidx.compose.ui.graphics.FilterQuality.Low)
                } else {
                    drawRect(Color(bg.color(0)))
                }
            },
    ) {
        MaterialTheme(colorScheme = galleryColorScheme(LocalContext.current, dark)) {
            // Text without its own color (month headings, names...) follows the background too;
            // otherwise it falls back to black on the see-through background
            CompositionLocalProvider(
                LocalGradientActive provides true,
                com.tim.gallery.quartz.LocalWall provides current?.copyBrightness(brightness),
                androidx.compose.material3.LocalContentColor provides MaterialTheme.colorScheme.onBackground,
                content = content,
            )
        }
    }
}

private fun com.tim.gallery.quartz.WallImage.copyBrightness(b: Float) =
    if (b == brightness) this else com.tim.gallery.quartz.WallImage(config, w, h, full, blurred, b)

/** Scaffold background for main screens: see-through when the gradient is on. */
@Composable
fun mainContainerColor(): Color =
    if (LocalGradientActive.current) Color.Transparent else MaterialTheme.colorScheme.background

/** Top bar colors for main screens: see-through when the gradient is on. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun mainTopBarColors(): TopAppBarColors =
    if (LocalGradientActive.current) {
        TopAppBarDefaults.topAppBarColors(
            containerColor = Color.Transparent,
            scrolledContainerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
        )
    } else {
        TopAppBarDefaults.topAppBarColors()
    }

/** Bottom navigation background for main screens. */
@Composable
fun mainNavBarColor(): Color =
    if (LocalGradientActive.current) MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.7f)
    else MaterialTheme.colorScheme.surfaceContainer
