package com.tim.gallery.settings

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class ThemeMode(val label: String) { SYSTEM("Auto"), LIGHT("Light"), DARK("Dark") }

enum class GradientType(val label: String) { LINEAR("Linear"), RADIAL("Radial"), REFLECTED("Reflected") }

/** Background gradient for the main screens (Photos, Albums, Trash, Settings). */
data class GradientSettings(
    val enabled: Boolean = false,
    val type: GradientType = GradientType.LINEAR,
    val start: Int = 0xFF2B1B4A.toInt(),
    val end: Int = 0xFF0F4C5C.toInt(),
    /** Direction for linear/reflected, in degrees: 0 = left to right, 90 = top to bottom. */
    val angle: Float = 90f,
)

/** Ready-made gradient color pairs. */
val GRADIENT_PRESETS: List<Pair<String, Pair<Int, Int>>> = listOf(
    "Quartz" to (0xFF2B1B4A.toInt() to 0xFF0F4C5C.toInt()),
    "Rose quartz" to (0xFFF7CAC9.toInt() to 0xFF92A8D1.toInt()),
    "Sunset" to (0xFFFF7E5F.toInt() to 0xFFFEB47B.toInt()),
    "Ocean" to (0xFF2E3192.toInt() to 0xFF1BFFFF.toInt()),
    "Night" to (0xFF0F2027.toInt() to 0xFF2C5364.toInt()),
    "Forest" to (0xFF134E5E.toInt() to 0xFF71B280.toInt()),
    "Peach" to (0xFFFFDDE1.toInt() to 0xFFEE9CA7.toInt()),
    "Ember" to (0xFF200122.toInt() to 0xFF6F0000.toInt()),
)

/** App settings, saved on the phone and shared as flows the UI can watch. */
object AppSettings {
    private var prefs: SharedPreferences? = null

    private val _theme = MutableStateFlow(ThemeMode.SYSTEM)
    val theme: StateFlow<ThemeMode> = _theme.asStateFlow()

    private val _gradient = MutableStateFlow(GradientSettings())
    val gradient: StateFlow<GradientSettings> = _gradient.asStateFlow()

    fun init(context: Context) {
        val p = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        prefs = p
        _theme.value = runCatching { ThemeMode.valueOf(p.getString("theme", null) ?: "SYSTEM") }.getOrDefault(ThemeMode.SYSTEM)
        val d = GradientSettings()
        _gradient.value = GradientSettings(
            enabled = p.getBoolean("g_enabled", d.enabled),
            type = runCatching { GradientType.valueOf(p.getString("g_type", null) ?: d.type.name) }.getOrDefault(d.type),
            start = p.getInt("g_start", d.start),
            end = p.getInt("g_end", d.end),
            angle = p.getFloat("g_angle", d.angle),
        )
    }

    fun setTheme(mode: ThemeMode) {
        _theme.value = mode
        prefs?.edit()?.putString("theme", mode.name)?.apply()
    }

    fun setGradient(g: GradientSettings) {
        _gradient.value = g
        prefs?.edit()
            ?.putBoolean("g_enabled", g.enabled)
            ?.putString("g_type", g.type.name)
            ?.putInt("g_start", g.start)
            ?.putInt("g_end", g.end)
            ?.putFloat("g_angle", g.angle)
            ?.apply()
    }
}
