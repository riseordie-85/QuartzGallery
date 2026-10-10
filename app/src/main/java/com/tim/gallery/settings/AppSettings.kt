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

enum class SortField(val label: String) {
    TAKEN("Date taken"),
    ADDED("Date added"),
    SIZE("Size"),
    NAME("Name"),
}

data class SortSettings(val field: SortField = SortField.TAKEN, val descending: Boolean = true) {
    val byDate: Boolean get() = this.field == SortField.TAKEN || this.field == SortField.ADDED
}

/** Thumbnail sizes the grid steps through when you pinch (smallest = most columns). */
val GRID_CELL_SIZES = listOf(64, 80, 104, 140, 200)

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

    private val _sort = MutableStateFlow(SortSettings())
    val sort: StateFlow<SortSettings> = _sort.asStateFlow()

    /** Grid thumbnail size in dp (pinch the grid to change it). */
    private val _gridCell = MutableStateFlow(104)
    val gridCell: StateFlow<Int> = _gridCell.asStateFlow()

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
        _sort.value = SortSettings(
            field = runCatching { SortField.valueOf(p.getString("sort_field", null) ?: "TAKEN") }.getOrDefault(SortField.TAKEN),
            descending = p.getBoolean("sort_desc", true),
        )
        _gridCell.value = p.getInt("grid_cell", 104).let { v -> GRID_CELL_SIZES.minByOrNull { kotlin.math.abs(it - v) } ?: 104 }
    }

    fun setSort(s: SortSettings) {
        _sort.value = s
        prefs?.edit()?.putString("sort_field", s.field.name)?.putBoolean("sort_desc", s.descending)?.apply()
    }

    /** One pinch step: [bigger] thumbnails (fewer columns) or smaller (more columns). */
    fun stepGrid(bigger: Boolean) {
        val i = GRID_CELL_SIZES.indexOf(_gridCell.value).coerceAtLeast(0)
        val next = GRID_CELL_SIZES[(if (bigger) i + 1 else i - 1).coerceIn(0, GRID_CELL_SIZES.lastIndex)]
        if (next == _gridCell.value) return
        _gridCell.value = next
        prefs?.edit()?.putInt("grid_cell", next)?.apply()
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
