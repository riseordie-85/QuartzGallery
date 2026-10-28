package com.tim.gallery.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Casino
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.PhotoAlbum
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tim.gallery.edit.ColorPickerDialog
import com.tim.gallery.quartz.BarEdge
import com.tim.gallery.quartz.BarFill
import com.tim.gallery.quartz.BarOutline
import com.tim.gallery.quartz.BarStyle
import com.tim.gallery.quartz.GradientDirection
import com.tim.gallery.quartz.GradientStyle
import com.tim.gallery.quartz.NavItem
import com.tim.gallery.quartz.NavPosition
import com.tim.gallery.quartz.PatternStyle
import com.tim.gallery.quartz.QuartzLook
import com.tim.gallery.quartz.QuartzNavBar
import com.tim.gallery.quartz.QuartzScaffold
import com.tim.gallery.quartz.QuartzTopBar
import com.tim.gallery.quartz.WallConfig
import com.tim.gallery.quartz.WallKind
import com.tim.gallery.quartz.WallPresets
import com.tim.gallery.quartz.WallRenderer
import com.tim.gallery.settings.AppSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/** Which color the picker is choosing. */
private sealed class Picking(val title: String, val alpha: Boolean) {
    class WallColor(val index: Int, title: String) : Picking(title, false)
    object Solid : Picking("Background color", false)
    object PatternColor : Picking("Pattern color", false)
    class Bar(val top: Boolean, val second: Boolean) : Picking(if (second) "Second color" else "Bar color", true)
    object Accent : Picking("Accent color", false)
}

/**
 * Quartz look: the background (Quartz Launcher's gradients, 5-point gradients and crystal patterns) and both
 * bars (glass, color or gradient, outline, pattern, edge lighting). Everything shows live: this screen's own
 * background and bars, plus a sample Photos / Albums bar at the bottom.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LookScreen(onBack: () -> Unit) {
    val look by AppSettings.look.collectAsState()
    var picking by remember { mutableStateOf<Picking?>(null) }
    var topTab by remember { mutableStateOf(true) }
    val listState = rememberLazyListState()
    fun set(l: QuartzLook) = AppSettings.setLook(l)
    fun setBg(w: WallConfig) = set(look.copy(background = w))
    val bg = look.background
    val bar = if (topTab) look.topBar else look.navBar
    fun setBar(b: BarStyle) = set(if (topTab) look.copy(topBar = b) else look.copy(navBar = b))

    QuartzScaffold(
        scroll = { listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset },
        topBar = {
            QuartzTopBar(
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
                title = { Text("Quartz look") },
                actions = {
                    TextButton(onClick = { set(QuartzLook()) }) {
                        Icon(Icons.Outlined.RestartAlt, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Reset")
                    }
                },
            )
        },
        // A sample of the Photos / Albums bar, to see changes as they're made
        bottomBar = {
            QuartzNavBar(
                listOf(
                    NavItem("Photos", Icons.Outlined.Image, selected = true) {},
                    NavItem("Albums", Icons.Outlined.PhotoAlbum, selected = false) {},
                ),
            )
        },
    ) { padding ->
        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding() + 16.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            // ------------------------------------------------ background
            item { Heading("Background") }
            item {
                Panel {
                    Label("Presets")
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        item {
                            PresetTile("Plain", null, selected = !bg.drawn) { setBg(bg.copy(kind = WallKind.THEME)) }
                        }
                        items(WallPresets.all) { p ->
                            PresetTile(p.name, p.config, selected = bg.drawn && bg == p.config) { setBg(p.config) }
                        }
                    }
                    Segments(WallKind.entries, bg.kind, { it.label }) { setBg(bg.copy(kind = it)) }
                    when (bg.kind) {
                        WallKind.THEME -> Hint("Follows the light or dark theme. Pick a preset or Gradient for a Quartz background.")
                        WallKind.SOLID -> Swatches(listOf("Color" to bg.solid)) { picking = Picking.Solid }
                        WallKind.GRADIENT -> GradientControls(bg, ::setBg) { i, title -> picking = Picking.WallColor(i, title) }
                    }
                }
            }
            if (bg.drawn) item {
                Panel {
                    Label("Pattern")
                    Chips(PatternStyle.entries, bg.pattern, { it.label }) { setBg(bg.copy(pattern = it)) }
                    if (bg.pattern != PatternStyle.NONE) {
                        SliderRow("Strength", bg.patternStrength, 0f..0.8f, "${(bg.patternStrength * 100).roundToInt()}%") { setBg(bg.copy(patternStrength = it)) }
                        SliderRow("Size", bg.patternSize, 0f..1f, "${(bg.patternSize * 100).roundToInt()}%") { setBg(bg.copy(patternSize = it)) }
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (!bg.pattern.faceted) Swatches(listOf("Color" to bg.patternColor)) { picking = Picking.PatternColor }
                            TextButton(onClick = { setBg(bg.copy(patternSeed = bg.patternSeed + 1)) }) {
                                Icon(Icons.Outlined.Casino, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Shuffle")
                            }
                        }
                        SwitchRow("Sparkles", bg.sparkles) { setBg(bg.copy(sparkles = it)) }
                    }
                    SliderRow("Dim", bg.dim, 0f..0.7f, "${(bg.dim * 100).roundToInt()}%") { setBg(bg.copy(dim = it)) }
                }
            }

            // ------------------------------------------------ bars
            item { Heading("Bars") }
            item {
                Panel {
                    Segments(listOf(true, false), topTab, { if (it) "Top bar" else "Photos / Albums bar" }) { topTab = it }
                    Label("Presets")
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        items(QuartzLook.BAR_PRESETS) { (name, style) ->
                            BarPresetTile(name, style, selected = bar == style) {
                                // Keep where the bar sits; take the look
                                setBar(style.copy(floating = bar.floating, radius = bar.radius, outline = bar.outline))
                            }
                        }
                    }
                    Segments(BarFill.entries, bar.fill, { it.label }) { setBar(bar.copy(fill = it)) }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Swatches(
                            if (bar.fill == BarFill.GRADIENT) listOf("From" to bar.color, "To" to bar.color2) else listOf("Color" to bar.color),
                        ) { i -> picking = Picking.Bar(topTab, second = i == 1) }
                        if (bar.fill == BarFill.GRADIENT) {
                            IconButton(onClick = { setBar(bar.copy(color = bar.color2, color2 = bar.color)) }) {
                                Icon(Icons.Outlined.SwapHoriz, contentDescription = "Swap colors")
                            }
                        }
                    }
                    SliderRow("See-through", 1f - Color(bar.color).alpha, 0f..1f, "${((1f - Color(bar.color).alpha) * 100).roundToInt()}%") { v ->
                        val a = 1f - v
                        setBar(
                            bar.copy(
                                color = Color(bar.color).copy(alpha = a).toArgbInt(),
                                color2 = Color(bar.color2).copy(alpha = a).toArgbInt(),
                            ),
                        )
                    }
                    if (bar.fill == BarFill.GRADIENT) {
                        Label("Direction")
                        Chips(GradientDirection.entries, bar.gradientDirection, { it.label }) { setBar(bar.copy(gradientDirection = it)) }
                    }
                    Label("Edge lighting")
                    Segments(BarEdge.entries, bar.edge, { it.label }) { setBar(bar.copy(edge = it)) }
                    Label("Shape")
                    Chips(BarOutline.entries, bar.outline, { it.label }) { setBar(bar.copy(outline = it)) }
                    SliderRow("Roundness", bar.radius.toFloat(), 0f..BarStyle.PILL.toFloat(), if (bar.radius >= BarStyle.PILL) "Pill" else "${bar.radius} dp") {
                        setBar(bar.copy(radius = it.roundToInt()))
                    }
                    SwitchRow("Floating", bar.floating, "Off attaches the bar to the screen edge") { setBar(bar.copy(floating = it)) }
                    if (!topTab && bar.floating) {
                        Label("Position")
                        Chips(NavPosition.entries, look.navPosition, { it.label }) { set(look.copy(navPosition = it)) }
                    }
                    Label("Pattern")
                    Chips(PatternStyle.entries, bar.pattern, { it.label }) { setBar(bar.copy(pattern = it)) }
                    if (bar.pattern != PatternStyle.NONE) {
                        SliderRow("Strength", bar.patternStrength, 0f..0.8f, "${(bar.patternStrength * 100).roundToInt()}%") { setBar(bar.copy(patternStrength = it)) }
                        SliderRow("Size", bar.patternSize, 0f..1f, "${(bar.patternSize * 100).roundToInt()}%") { setBar(bar.copy(patternSize = it)) }
                    }
                }
            }

            // ------------------------------------------------ glass and accents
            item { Heading("Glass & accents") }
            item {
                Panel {
                    SwitchRow("Frosted glass", look.blur, "Photos scrolling under the bars are blurred. Turn off on slower phones.") { set(look.copy(blur = it)) }
                    if (look.blur) {
                        SliderRow("Blur", look.blurRadius.toFloat(), 4f..48f, "${look.blurRadius} dp") { set(look.copy(blurRadius = it.roundToInt())) }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Accent", style = MaterialTheme.typography.bodyLarge)
                            Hint("The chosen tab, and the month headings on light backgrounds")
                        }
                        Swatches(listOf("Accent" to look.accent)) { picking = Picking.Accent }
                    }
                    SwitchRow("Big month headings", look.bigHeadings) { set(look.copy(bigHeadings = it)) }
                }
            }
        }
    }

    picking?.let { p ->
        val initial = when (p) {
            is Picking.WallColor -> bg.color(p.index)
            Picking.Solid -> bg.solid
            Picking.PatternColor -> bg.patternColor
            is Picking.Bar -> (if (p.top) look.topBar else look.navBar).let { if (p.second) it.color2 else it.color }
            Picking.Accent -> look.accent
        }
        ColorPickerDialog(
            initial = initial,
            allowAlpha = p.alpha,
            title = p.title,
            onDismiss = { picking = null },
            onPick = { c ->
                picking = null
                when (p) {
                    is Picking.WallColor -> setBg(bg.withColor(p.index, c))
                    Picking.Solid -> setBg(bg.copy(solid = c))
                    Picking.PatternColor -> setBg(bg.copy(patternColor = c))
                    is Picking.Bar -> {
                        val b = if (p.top) look.topBar else look.navBar
                        val nb = if (p.second) b.copy(color2 = c) else b.copy(color = c)
                        set(if (p.top) look.copy(topBar = nb) else look.copy(navBar = nb))
                    }
                    Picking.Accent -> set(look.copy(accent = c))
                }
            },
        )
    }
}

private fun Color.toArgbInt(): Int = android.graphics.Color.argb(
    (alpha * 255).roundToInt(), (red * 255).roundToInt(), (green * 255).roundToInt(), (blue * 255).roundToInt(),
)

@Composable
private fun GradientControls(bg: WallConfig, setBg: (WallConfig) -> Unit, pick: (Int, String) -> Unit) {
    Segments(GradientStyle.entries, bg.gradient, { it.label }) { setBg(bg.copy(gradient = it)) }
    when (bg.gradient) {
        GradientStyle.LINEAR -> {
            Swatches(
                if (bg.linearMiddle) listOf("Start" to bg.color(0), "Middle" to bg.color(1), "End" to bg.color(2))
                else listOf("Start" to bg.color(0), "End" to bg.color(2)),
            ) { i -> if (bg.linearMiddle) pick(i, listOf("Start", "Middle", "End")[i]) else pick(if (i == 0) 0 else 2, if (i == 0) "Start" else "End") }
            SwitchRow("Middle color", bg.linearMiddle) { setBg(bg.copy(linearMiddle = it)) }
            SliderRow("Direction", bg.angle, 0f..360f, "${bg.angle.roundToInt()}°") { v ->
                val snapped = listOf(0f, 45f, 90f, 135f, 180f, 225f, 270f, 315f, 360f).firstOrNull { kotlin.math.abs(it - v) < 4f } ?: v
                setBg(bg.copy(angle = snapped))
            }
        }
        GradientStyle.RADIAL -> {
            Swatches(listOf("Center" to bg.color(0), "Edge" to bg.color(2))) { i -> pick(if (i == 0) 0 else 2, if (i == 0) "Center color" else "Edge color") }
            SliderRow("Across", bg.radialX, 0f..1f, "${(bg.radialX * 100).roundToInt()}%") { setBg(bg.copy(radialX = it)) }
            SliderRow("Down", bg.radialY, 0f..1f, "${(bg.radialY * 100).roundToInt()}%") { setBg(bg.copy(radialY = it)) }
            SliderRow("Size", bg.radialSize, 0.2f..2f, "${(bg.radialSize * 100).roundToInt()}%") { setBg(bg.copy(radialSize = it)) }
        }
        GradientStyle.CORNERS -> {
            Hint("Five points: each corner has its own color, and the middle glows out over them.")
            // Laid out like the screen: two corners on top, the middle, two corners below
            val names = listOf("Top left", "Top right", "Bottom left", "Bottom right", "Middle")
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                    Swatch(names[0], bg.color(0)) { pick(0, names[0]) }
                    Swatch(names[1], bg.color(1)) { pick(1, names[1]) }
                }
                Swatch(names[4], bg.color(4)) { pick(4, names[4]) }
                Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                    Swatch(names[2], bg.color(2)) { pick(2, names[2]) }
                    Swatch(names[3], bg.color(3)) { pick(3, names[3]) }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- building blocks

@Composable
private fun Heading(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        color = com.tim.gallery.quartz.quartzHeadingColor(),
        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 6.dp),
    )
}

@Composable
private fun Panel(content: @Composable () -> Unit) {
    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.78f)),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) { content() }
    }
}

@Composable
private fun Label(text: String) {
    Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun <T> Segments(options: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit) {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        options.forEachIndexed { i, o ->
            SegmentedButton(
                selected = o == selected,
                onClick = { onSelect(o) },
                shape = SegmentedButtonDefaults.itemShape(i, options.size),
                icon = {},
                label = { Text(label(o), maxLines = 1, overflow = TextOverflow.Ellipsis) },
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun <T> Chips(options: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { o -> FilterChip(selected = o == selected, onClick = { onSelect(o) }, label = { Text(label(o)) }) }
    }
}

@Composable
private fun SliderRow(label: String, value: Float, range: ClosedFloatingPointRange<Float>, readout: String, onChange: (Float) -> Unit) {
    Column {
        Row {
            Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Text(readout, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Slider(value = value.coerceIn(range.start, range.endInclusive), onValueChange = onChange, valueRange = range)
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, hint: String? = null, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable { onChange(!checked) }) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            if (hint != null) Hint(hint)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun Swatches(colors: List<Pair<String, Int>>, onPick: (Int) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        colors.forEachIndexed { i, (name, c) -> Swatch(name, c) { onPick(i) } }
    }
}

@Composable
private fun Swatch(label: String, color: Int, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .border(BorderStroke(1.dp, MaterialTheme.colorScheme.outline), RoundedCornerShape(20.dp))
            .clickable(onClick = onClick)
            .padding(start = 6.dp, end = 12.dp, top = 6.dp, bottom = 6.dp),
    ) {
        Box(
            Modifier
                .size(24.dp)
                .clip(CircleShape)
                .background(Color.White)
                .background(Color(color))
                .border(BorderStroke(1.dp, MaterialTheme.colorScheme.outline), CircleShape),
        )
        Spacer(Modifier.width(8.dp))
        Text(label, style = MaterialTheme.typography.labelLarge, maxLines = 1)
    }
}

/** A small picture of a background preset. */
@Composable
private fun PresetTile(name: String, config: WallConfig?, selected: Boolean, onClick: () -> Unit) {
    val image by produceState<ImageBitmap?>(null, config) {
        if (config == null) return@produceState
        value = withContext(Dispatchers.Default) { WallRenderer.render(540, 960, 0.15f, config).asImageBitmap() }
    }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.width(64.dp).clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(2.dp),
    ) {
        Box(
            Modifier
                .size(width = 56.dp, height = 92.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.background)
                .border(
                    if (selected) BorderStroke(3.dp, MaterialTheme.colorScheme.primary) else BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                    RoundedCornerShape(12.dp),
                ),
        ) {
            image?.let { Image(it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
        }
        Text(name, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** A small pill in a bar preset's colors. */
@Composable
private fun BarPresetTile(name: String, style: BarStyle, selected: Boolean, onClick: () -> Unit) {
    val colors = if (style.fill == BarFill.GRADIENT) listOf(Color(style.color), Color(style.color2)) else listOf(Color(style.color), Color(style.color))
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.width(78.dp).clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(2.dp),
    ) {
        Box(
            Modifier
                .size(width = 70.dp, height = 34.dp)
                .clip(RoundedCornerShape(50))
                .background(MaterialTheme.colorScheme.background)
                .background(androidx.compose.ui.graphics.Brush.horizontalGradient(colors))
                .border(
                    if (selected) BorderStroke(3.dp, MaterialTheme.colorScheme.primary) else BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                    RoundedCornerShape(50),
                ),
        )
        Spacer(Modifier.height(2.dp))
        Text(name, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
