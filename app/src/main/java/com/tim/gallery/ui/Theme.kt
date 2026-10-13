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

/**
 * Background for the main screens. With the gradient on, it paints the gradient and switches
 * the text colors to light or dark so they stay readable on it.
 */
@Composable
fun MainBackground(content: @Composable () -> Unit) {
    val g by AppSettings.gradient.collectAsState()
    if (!g.enabled) {
        SystemBarIcons(MaterialTheme.colorScheme.background.luminance() < 0.5f)
        content()
        return
    }
    val dark = gradientIsDark(g)
    SystemBarIcons(dark)
    Box(Modifier.fillMaxSize().drawBehind { drawRect(gradientBrush(g, size)) }) {
        MaterialTheme(colorScheme = galleryColorScheme(LocalContext.current, dark)) {
            // Text without its own color (month headings, names...) follows the gradient too;
            // otherwise it falls back to black on the see-through background
            CompositionLocalProvider(
                LocalGradientActive provides true,
                androidx.compose.material3.LocalContentColor provides MaterialTheme.colorScheme.onBackground,
                content = content,
            )
        }
    }
}

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
