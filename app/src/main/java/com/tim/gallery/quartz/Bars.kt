package com.tim.gallery.quartz

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.sp
import androidx.compose.ui.draw.drawWithContent
import com.tim.gallery.settings.AppSettings
import com.tim.gallery.ui.LocalGradientActive
import com.tim.gallery.ui.mainContainerColor

/** How bright the screen behind the bars is (0 dark – 1 light). */
@Composable
fun backgroundBrightness(): Float {
    val wall = LocalWall.current
    if (wall != null && LocalGradientActive.current) return wall.brightness
    return kotlin.math.sqrt(MaterialTheme.colorScheme.background.luminance())
}

/** Big month headings: the accent on a light screen, light text on a dark one. */
@Composable
fun quartzHeadingColor(): Color {
    val look by AppSettings.look.collectAsState()
    val accent = Color(look.accent)
    return if (!Ink.prefersLight(backgroundBrightness())) {
        if (accent.luminance() < 0.25f) accent else Ink.Dark
    } else {
        lerp(accent, Color.White, 0.85f)
    }
}

/**
 * A main screen: its bars float over the content, which runs underneath them (frosted when blur is on).
 * [scroll] reads the content's scroll position, so the glass follows it.
 */
@Composable
fun QuartzScaffold(
    modifier: Modifier = Modifier,
    topBar: @Composable () -> Unit = {},
    bottomBar: @Composable () -> Unit = {},
    snackbarHost: @Composable () -> Unit = {},
    floatingActionButton: @Composable () -> Unit = {},
    scroll: () -> Any? = { null },
    content: @Composable (PaddingValues) -> Unit,
) {
    val source = remember { GlassSource() }
    source.scroll = scroll
    CompositionLocalProvider(LocalGlassSource provides source) {
        Scaffold(
            modifier = modifier,
            topBar = topBar,
            bottomBar = bottomBar,
            snackbarHost = snackbarHost,
            floatingActionButton = floatingActionButton,
            containerColor = mainContainerColor(),
        ) { padding ->
            GlassContent(source, Modifier.fillMaxSize()) { content(padding) }
        }
    }
}

/** Colors for a bar in [style] on this screen: its text and icons. */
@Composable
private fun barInk(style: BarStyle): Color = Ink.on(barBrightness(style, backgroundBrightness()))

/**
 * The top bar: a floating pill (or a bar attached to the top) in the top bar's Quartz style.
 * Same slots as Material's TopAppBar.
 */
@Composable
fun QuartzTopBar(
    title: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    navigationIcon: @Composable () -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {},
) {
    val look by AppSettings.look.collectAsState()
    val style = look.topBar
    val ink = barInk(style)
    val shape = remember(style.outline, style.radius, style.floating) { BarShape(style.outline, style.radius.toFloat(), top = true, flush = !style.floating) }
    val base = MaterialTheme.colorScheme.background
    // Slanted and pointed ends need room so the end buttons aren't cut off
    val ends = when (style.outline) {
        BarOutline.SLANTED, BarOutline.POINTED -> 22.dp
        else -> 4.dp
    }
    val row: @Composable () -> Unit = {
        // Buttons, text fields and subtitles in the bar take its ink color
        MaterialTheme(
            colorScheme = MaterialTheme.colorScheme.copy(
                primary = ink,
                onSurface = ink,
                onSurfaceVariant = ink.copy(alpha = 0.75f),
            ),
        ) {
          CompositionLocalProvider(LocalContentColor provides ink) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().height(56.dp).padding(horizontal = ends),
            ) {
                navigationIcon()
                Box(Modifier.weight(1f).padding(start = 12.dp, end = 4.dp)) {
                    ProvideTextStyle(MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Normal)) { title() }
                }
                actions()
            }
          }
        }
    }
    if (style.floating) {
        Box(modifier.fillMaxWidth().statusBarsPadding().padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 8.dp)) {
            Box(Modifier.fillMaxWidth().barSurface(style, shape, look, base, ink)) { row() }
        }
    } else {
        Box(modifier.fillMaxWidth().barSurface(style, shape, look, base, ink).statusBarsPadding().padding(bottom = 4.dp)) { row() }
    }
}

/** A dropdown in the top bar's colors: rounded, items centered, the chosen one outlined. */
@Composable
fun QuartzDropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    offset: DpOffset = DpOffset(0.dp, 0.dp),
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    val look by AppSettings.look.collectAsState()
    val base = MaterialTheme.colorScheme.surface
    val tint = Color(look.topBar.color)
    // Mostly see-through bars (frosted glass) get a solid menu in the screen's own color
    val color = if (tint.alpha < 0.6f) base else tint.copy(alpha = 1f).compositeOver(base)
    val ink = Ink.on(kotlin.math.sqrt(color.luminance()))
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        modifier = modifier,
        offset = offset,
        shape = RoundedCornerShape(24.dp),
        containerColor = color,
    ) {
        MaterialTheme(
            colorScheme = MaterialTheme.colorScheme.copy(
                onSurface = ink,
                onSurfaceVariant = ink.copy(alpha = 0.75f),
                surface = color,
                outlineVariant = ink.copy(alpha = 0.2f),
            ),
        ) {
            CompositionLocalProvider(LocalContentColor provides ink) { content() }
        }
    }
}

/** A big centered choice in a [QuartzDropdownMenu]; the [selected] one gets an outline. */
@Composable
fun QuartzMenuChoice(label: String, selected: Boolean, onClick: () -> Unit) {
    val ink = LocalContentColor.current
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 3.dp)
            .clip(RoundedCornerShape(50))
            .then(if (selected) Modifier.border(BorderStroke(2.5.dp, ink), RoundedCornerShape(50)) else Modifier)
            .clickable(onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 8.dp),
    ) {
        Text(label, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = ink)
    }
}

/** "Photos ▾": All / Photos / Videos, in the top bar. */
@Composable
fun TypeDropdown(type: com.tim.gallery.TypeFilter, onType: (com.tim.gallery.TypeFilter) -> Unit) {
    var open by remember { mutableStateOf(false) }
    // The menu is centered under the word ("All", "Photos"...), not the word plus its arrow
    var textWidth by remember { mutableStateOf(0) }
    val density = androidx.compose.ui.platform.LocalDensity.current
    val menuWidth = 170.dp
    val textCenter = 10.dp + with(density) { textWidth.toDp() } / 2
    Box {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .clickable { open = true }
                .padding(start = 10.dp, end = 2.dp, top = 6.dp, bottom = 6.dp),
        ) {
            Text(
                type.label,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                modifier = Modifier.onSizeChanged { textWidth = it.width },
            )
            Icon(Icons.Filled.ArrowDropDown, contentDescription = "Show all, photos or videos", modifier = Modifier.size(30.dp))
        }
        QuartzDropdownMenu(
            expanded = open,
            onDismissRequest = { open = false },
            modifier = Modifier.width(menuWidth),
            offset = DpOffset(textCenter - menuWidth / 2, 8.dp),
        ) {
            com.tim.gallery.TypeFilter.entries.forEach { t ->
                QuartzMenuChoice(t.label, selected = t == type) {
                    open = false
                    onType(t)
                }
            }
        }
    }
}

/** One destination in the [QuartzNavBar]. */
class NavItem(val label: String, val icon: ImageVector, val selected: Boolean, val onClick: () -> Unit)

/**
 * The Photos / Albums bar: a floating pill (left, middle, right or full width) or a bar attached to the
 * bottom, in the bottom bar's Quartz style. The current tab is a pill in the accent color.
 */
@Composable
fun QuartzNavBar(items: List<NavItem>, modifier: Modifier = Modifier) {
    val look by AppSettings.look.collectAsState()
    val style = look.navBar
    val ink = barInk(style)
    val accent = Color(look.accent)
    val accentInk = Ink.on(kotlin.math.sqrt(accent.luminance()))
    // Light glass: the accent for the other tabs too (like the mockup's navy), if it's dark enough to read
    val idle = if (ink == Ink.Dark && accent.luminance() < 0.25f) accent else ink
    val shape = remember(style.outline, style.radius, style.floating) { BarShape(style.outline, style.radius.toFloat(), top = false, flush = !style.floating) }
    val base = MaterialTheme.colorScheme.background
    val wide = !style.floating || look.navPosition == NavPosition.WIDE
    val ends = when (style.outline) {
        BarOutline.SLANTED, BarOutline.POINTED -> 26.dp
        else -> 7.dp
    }
    val itemShape = RoundedCornerShape((style.radius - 6).coerceAtLeast(6).dp)
    val row: @Composable (Modifier) -> Unit = { m ->
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = if (wide) Arrangement.SpaceEvenly else Arrangement.spacedBy(4.dp),
            modifier = m.padding(horizontal = ends, vertical = 7.dp),
        ) {
            items.forEach { item ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .then(if (wide) Modifier.weight(1f) else Modifier)
                        .clip(itemShape)
                        .background(if (item.selected) accent else Color.Transparent)
                        .clickable(onClick = item.onClick)
                        .padding(horizontal = 20.dp, vertical = 11.dp),
                    horizontalArrangement = Arrangement.Center,
                ) {
                    val c = if (item.selected) accentInk else idle
                    Icon(item.icon, contentDescription = null, tint = c, modifier = Modifier.size(24.dp))
                    Spacer(Modifier.width(10.dp))
                    Text(
                        item.label,
                        color = c,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
    if (style.floating) {
        val align = when (look.navPosition) {
            NavPosition.LEFT -> Alignment.CenterStart
            NavPosition.RIGHT -> Alignment.CenterEnd
            else -> Alignment.Center
        }
        Box(modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 10.dp), contentAlignment = align) {
            row(
                Modifier
                    .then(if (wide) Modifier.fillMaxWidth() else Modifier)
                    .barSurface(style, shape, look, base, ink),
            )
        }
    } else {
        Box(modifier.fillMaxWidth().barSurface(style, shape, look, base, ink).navigationBarsPadding()) {
            row(Modifier.fillMaxWidth())
        }
    }
}

/** One line of text that shrinks (down to [minSize]) rather than cut off, e.g. the app name in a narrow bar. */
@Composable
fun FitText(text: String, modifier: Modifier = Modifier, minSize: androidx.compose.ui.unit.TextUnit = 14.sp) {
    val base = androidx.compose.material3.LocalTextStyle.current
    var style by remember(text, base) { mutableStateOf(base) }
    var ready by remember(text, base) { mutableStateOf(false) }
    Text(
        text,
        style = style,
        maxLines = 1,
        softWrap = false,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier.drawWithContent { if (ready) drawContent() },
        onTextLayout = { r ->
            if (r.hasVisualOverflow && style.fontSize.value * 0.92f >= minSize.value) {
                style = style.copy(fontSize = style.fontSize * 0.92f)
            } else {
                ready = true
            }
        },
    )
}
