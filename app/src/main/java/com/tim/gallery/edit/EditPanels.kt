package com.tim.gallery.edit

import android.graphics.Color as AColor
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.runtime.collectAsState
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material.icons.automirrored.outlined.BrandingWatermark
import androidx.compose.material.icons.outlined.Category
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material3.IconButton
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Flip
import androidx.compose.material.icons.filled.RotateLeft
import androidx.compose.material.icons.filled.RotateRight
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.EmojiEmotions
import androidx.compose.material.icons.outlined.FlipToFront
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material.icons.outlined.TextFields
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import java.util.Locale
import kotlin.math.roundToInt

private val PanelText = Color.White
private val PanelDim = Color.White.copy(alpha = 0.65f)

@Composable
private fun chipColors() = FilterChipDefaults.filterChipColors(
    labelColor = PanelText,
    iconColor = PanelText,
    selectedContainerColor = Color.White,
    selectedLabelColor = Color.Black,
    selectedLeadingIconColor = Color.Black,
)

@Composable
fun ToolButton(icon: ImageVector, label: String, enabled: Boolean = true, onClick: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        val tint = if (enabled) PanelText else PanelText.copy(alpha = 0.35f)
        Icon(icon, contentDescription = label, tint = tint)
        Text(label, color = tint, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
fun ColorDots(
    colors: List<Int>,
    selected: Int?,
    onPick: (Int) -> Unit,
    leading: (@Composable () -> Unit)? = null,
) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) item { leading() }
        items(colors) { c ->
            val isSel = c == selected
            Box(
                Modifier
                    .size(if (isSel) 34.dp else 30.dp)
                    .clip(CircleShape)
                    .background(Color(c))
                    .border(
                        BorderStroke(if (isSel) 3.dp else 1.dp, if (isSel) Color.White else Color.White.copy(alpha = 0.4f)),
                        CircleShape,
                    )
                    .clickable { onPick(c) }
            )
        }
    }
}

/** Slider that reports drag end, with a label and value readout. */
@Composable
fun LabeledSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    valueText: String,
    onChange: (Float) -> Unit,
    onDone: () -> Unit,
    onReset: (() -> Unit)? = null,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, color = PanelText, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
            Text(valueText, color = PanelDim, style = MaterialTheme.typography.labelLarge)
            if (onReset != null) {
                TextButton(onClick = onReset) { Text("Reset") }
            }
        }
        Slider(
            value = value.coerceIn(range.start, range.endInclusive),
            onValueChange = onChange,
            onValueChangeFinished = onDone,
            valueRange = range,
            colors = SliderDefaults.colors(
                thumbColor = Color.White,
                activeTrackColor = Color.White,
                inactiveTrackColor = Color.White.copy(alpha = 0.25f),
            ),
        )
    }
}

// ===================================================================== Crop

@Composable
fun CropPanel(
    state: EditState,
    onGeometry: (Geometry, String?) -> Unit,
    onDone: () -> Unit,
    onStraightening: (Boolean) -> Unit,
    w: Float,
    h: Float,
    colors: ColorTools,
) {
    val g = state.geometry
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        LabeledSlider(
            label = "Straighten",
            value = g.angle,
            range = -45f..45f,
            valueText = String.format(Locale.US, "%.1f°", g.angle),
            onChange = {
                onStraightening(true)
                // Snap to level near zero
                val a = if (kotlin.math.abs(it) < 0.4f) 0f else it
                onGeometry(GeometryOps.setAngle(g, a, w, h), "angle")
            },
            onDone = {
                onStraightening(false)
                onDone()
            },
            onReset = if (g.angle != 0f) ({ onGeometry(GeometryOps.setAngle(g, 0f, w, h), null) }) else null,
        )

        // Corners when straightened: crop in, or keep everything on a background
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 16.dp),
        ) {
            FilterChip(
                selected = g.fill == FillMode.CROP_IN,
                onClick = { onGeometry(GeometryOps.setFill(g, FillMode.CROP_IN, w, h), null) },
                label = { Text("Crop in") },
                colors = chipColors(),
            )
            FilterChip(
                selected = g.fill == FillMode.BACKGROUND,
                onClick = { onGeometry(GeometryOps.setFill(g, FillMode.BACKGROUND, w, h), null) },
                label = { Text("Background") },
                colors = chipColors(),
            )
            if (g.fill == FillMode.BACKGROUND) {
                Text(
                    "Drag the box past the photo to add space",
                    color = PanelDim,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.weight(1f),
                )
            }
        }

        if (g.fill == FillMode.BACKGROUND) {
            ColorRow(
                selected = g.fillColor,
                onPick = { onGeometry(g.copy(fillColor = it), null) },
                onCustom = {
                    colors.pick(g.fillColor, true) { s, c -> s.copy(geometry = s.geometry.copy(fillColor = c)) }
                },
                onEyedropper = {
                    colors.eyedrop { s, c -> s.copy(geometry = s.geometry.copy(fillColor = c)) }
                },
                includeNone = true,
                colors = listOf(AColor.BLACK, AColor.WHITE) + PALETTE.drop(2),
            )
        }

        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(AspectOption.entries) { option ->
                FilterChip(
                    selected = option == g.aspect,
                    onClick = { onGeometry(GeometryOps.setAspect(g, option, w, h), null) },
                    label = { Text(option.label) },
                    colors = chipColors(),
                )
            }
        }

        Row(
            horizontalArrangement = Arrangement.SpaceEvenly,
            modifier = Modifier.fillMaxWidth(),
        ) {
            ToolButton(Icons.Filled.RotateLeft, "Rotate left") { onGeometry(GeometryOps.rotate(g, false, w, h), null) }
            ToolButton(Icons.Filled.RotateRight, "Rotate right") { onGeometry(GeometryOps.rotate(g, true, w, h), null) }
            ToolButton(Icons.Filled.Flip, "Flip") { onGeometry(GeometryOps.flip(g), null) }
            ToolButton(Icons.Outlined.Restore, "Reset", enabled = g != Geometry()) { onGeometry(Geometry(), null) }
        }
    }
}

// ===================================================================== Adjust

@Composable
fun AdjustPanel(
    state: EditState,
    onChange: (EditState, String?) -> Unit,
    onDone: () -> Unit,
) {
    var current by rememberSaveable { mutableStateOf(Adjust.EXPOSURE) }
    val value = state.adjust(current)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        LabeledSlider(
            label = current.label,
            value = value,
            range = if (current.bipolar) -1f..1f else 0f..1f,
            valueText = (value * 100).roundToInt().let { if (current.bipolar && it > 0) "+$it" else "$it" },
            onChange = { v ->
                val snapped = if (current.bipolar && kotlin.math.abs(v) < 0.02f) 0f else v
                onChange(state.copy(adjust = state.adjust + (current to snapped)), "adjust-${current.name}")
            },
            onDone = onDone,
            onReset = if (value != 0f) ({ onChange(state.copy(adjust = state.adjust - current), null) }) else null,
        )
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(Adjust.entries) { a ->
                val changed = state.adjust(a) != 0f
                FilterChip(
                    selected = a == current,
                    onClick = { current = a },
                    label = { Text(if (changed) "${a.label} •" else a.label) },
                    colors = chipColors(),
                )
            }
        }
        if (state.adjust.values.any { it != 0f }) {
            TextButton(
                onClick = { onChange(state.copy(adjust = emptyMap()), null) },
                modifier = Modifier.padding(start = 8.dp),
            ) { Text("Reset all adjustments") }
        }
    }
}

// ===================================================================== Filters

@Composable
fun FiltersPanel(
    session: EditSession,
    state: EditState,
    onChange: (EditState, String?) -> Unit,
    onDone: () -> Unit,
    colors: ColorTools,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (state.filter != FilterPreset.NONE) {
            LabeledSlider(
                label = "${state.filter.label} strength",
                value = state.filterStrength,
                range = 0f..1f,
                valueText = "${(state.filterStrength * 100).roundToInt()}",
                onChange = { onChange(state.copy(filterStrength = it), "filter-strength") },
                onDone = onDone,
            )
        }
        if (state.filter == FilterPreset.DUOTONE) {
            DuotoneControls(state, onChange, colors)
        }
        LooksRow(state, onChange)
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(FilterPreset.entries) { preset ->
                FilterThumb(
                    session = session,
                    state = state,
                    preset = preset,
                    selected = preset == state.filter,
                    onClick = { onChange(state.copy(filter = preset, filterStrength = 1f), null) },
                )
            }
        }
    }
}

/** Your saved looks (adjustments + filter). Tap to apply, long-press to delete. */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun LooksRow(state: EditState, onChange: (EditState, String?) -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    androidx.compose.runtime.LaunchedEffect(Unit) { Looks.init(context) }
    val looks by Looks.looks.collectAsState()
    var naming by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<Look?>(null) }
    val hasLook = state.adjust.isNotEmpty() || state.filter != FilterPreset.NONE
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        item {
            Text("Your looks", color = PanelDim, style = MaterialTheme.typography.labelMedium)
        }
        items(looks, key = { it.name }) { look ->
            Box(
                Modifier
                    .clip(RoundedCornerShape(16.dp))
                    .border(BorderStroke(1.dp, Color.White.copy(alpha = 0.4f)), RoundedCornerShape(16.dp))
                    .combinedClickable(
                        onClick = { onChange(look.applyTo(state), null) },
                        onLongClick = { deleting = look },
                    )
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Text(look.name, color = PanelText, style = MaterialTheme.typography.labelLarge)
            }
        }
        item {
            TextButton(onClick = { naming = true }, enabled = hasLook) { Text("+ Save this look") }
        }
    }
    if (naming) {
        var name by remember { mutableStateOf("") }
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { naming = false },
            title = { Text("Save look") },
            text = {
                androidx.compose.material3.OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(30) },
                    label = { Text("Name") },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(enabled = name.isNotBlank(), onClick = {
                    Looks.add(context, Look.from(name.trim(), state))
                    naming = false
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { naming = false }) { Text("Cancel") } },
        )
    }
    deleting?.let { look ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete “${look.name}”?") },
            confirmButton = {
                TextButton(onClick = {
                    Looks.remove(context, look)
                    deleting = null
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } },
        )
    }
}

/** Shadow and highlight colors for the duotone filter, plus ready-made pairs. */
@Composable
private fun DuotoneControls(state: EditState, onChange: (EditState, String?) -> Unit, colors: ColorTools) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        item {
            SwatchButton("Shadows", state.duoShadow) {
                colors.pick(state.duoShadow, false) { s, c -> s.copy(duoShadow = c) }
            }
        }
        item {
            IconButton(onClick = { onChange(state.copy(duoShadow = state.duoHighlight, duoHighlight = state.duoShadow), null) }) {
                Icon(Icons.Outlined.SwapHoriz, contentDescription = "Swap colors", tint = PanelText)
            }
        }
        item {
            SwatchButton("Highlights", state.duoHighlight) {
                colors.pick(state.duoHighlight, false) { s, c -> s.copy(duoHighlight = c) }
            }
        }
        item { Spacer(Modifier.width(4.dp)) }
        items(DUOTONE_PRESETS) { (a, b) ->
            val sel = state.duoShadow == a && state.duoHighlight == b
            Row(
                Modifier
                    .size(if (sel) 34.dp else 30.dp)
                    .clip(CircleShape)
                    .border(BorderStroke(if (sel) 3.dp else 1.dp, Color.White.copy(alpha = if (sel) 1f else 0.4f)), CircleShape)
                    .clickable { onChange(state.copy(duoShadow = a, duoHighlight = b), null) }
            ) {
                Box(Modifier.weight(1f).height(34.dp).background(Color(a)))
                Box(Modifier.weight(1f).height(34.dp).background(Color(b)))
            }
        }
    }
}

/** A labeled color chip that opens the picker. */
@Composable
fun SwatchButton(label: String, color: Int, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(RoundedCornerShape(18.dp))
            .border(BorderStroke(1.dp, Color.White.copy(alpha = 0.4f)), RoundedCornerShape(18.dp))
            .clickable(onClick = onClick)
            .padding(start = 6.dp, end = 12.dp, top = 5.dp, bottom = 5.dp),
    ) {
        Box(
            Modifier
                .size(24.dp)
                .clip(CircleShape)
                .checkerboard(6f)
                .background(Color(color))
                .border(BorderStroke(1.dp, Color.White.copy(alpha = 0.6f)), CircleShape)
        )
        Spacer(Modifier.width(8.dp))
        Text(label, color = PanelText, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
private fun FilterThumb(
    session: EditSession,
    state: EditState,
    preset: FilterPreset,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val thumb = session.thumb ?: return
    val color = remember { ColorShader() }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.clickable(onClick = onClick),
    ) {
        Canvas(
            Modifier
                .size(72.dp)
                .clip(RoundedCornerShape(12.dp))
                .border(
                    BorderStroke(if (selected) 3.dp else 0.dp, if (selected) Color.White else Color.Transparent),
                    RoundedCornerShape(12.dp),
                )
        ) {
            drawIntoCanvas { c ->
                val nc = c.nativeCanvas
                nc.save()
                nc.scale(size.width / thumb.width, size.height / thumb.height)
                EditRenderer.drawColorOnly(nc, state.copy(filter = preset, filterStrength = 1f), thumb, color)
                nc.restore()
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            preset.label,
            color = if (selected) PanelText else PanelDim,
            style = MaterialTheme.typography.labelSmall,
        )
    }
}

// ===================================================================== Cutout

@Composable
fun CutoutPanel(
    state: EditState,
    hasForeground: Boolean,
    busy: Boolean,
    error: String?,
    onRemoveBackground: () -> Unit,
    onChange: (EditState) -> Unit,
    onSaveSticker: () -> Unit,
    colors: ColorTools,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        if (!hasForeground) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp),
            ) {
                Text(
                    "Find the main subject and remove or replace the background. Runs on your phone.",
                    color = PanelDim,
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(10.dp))
                if (busy) {
                    CircularProgressIndicator(color = Color.White)
                } else {
                    FilledTonalButton(onClick = onRemoveBackground) { Text("Remove background") }
                }
                if (error != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(error, color = Color(0xFFFFB4AB), style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
                }
            }
        } else {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(CutoutMode.entries) { mode ->
                    FilterChip(
                        selected = state.cutout == mode,
                        onClick = { onChange(state.copy(cutout = mode)) },
                        label = { Text(mode.label) },
                        colors = chipColors(),
                    )
                }
            }
            if (state.cutout == CutoutMode.COLOR) {
                ColorRow(
                    selected = state.cutoutColor,
                    allowAlpha = false,
                    onPick = { onChange(state.copy(cutoutColor = it)) },
                    onCustom = { colors.pick(state.cutoutColor, false) { s, c -> s.copy(cutoutColor = c) } },
                    onEyedropper = { colors.eyedrop { s, c -> s.copy(cutoutColor = c) } },
                )
            }
            Row(Modifier.padding(horizontal = 16.dp)) {
                OutlinedButton(onClick = onSaveSticker) {
                    Icon(Icons.Outlined.EmojiEmotions, contentDescription = null, tint = PanelText, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Save cutout as sticker", color = PanelText)
                }
            }
        }
    }
}

// ===================================================================== Markup

/** Opens the color picker / eyedropper. [apply] builds the new state from the latest state and the chosen color. */
class ColorTools(
    val pick: (initial: Int, allowAlpha: Boolean, apply: (EditState, Int) -> EditState) -> Unit,
    val eyedrop: (apply: (EditState, Int) -> EditState) -> Unit,
)

private enum class LayerProp(val label: String) {
    FILL("Fill"),
    OUTLINE("Outline"),
    COLOR("Color"),
    THICKNESS("Thickness"),
    STRETCH("Stretch"),
    CORNERS("Corners"),
    OPACITY("Opacity"),
    POSITION("Position"),
}

private fun propsFor(layer: Layer): List<LayerProp> = when (layer) {
    is ShapeLayer -> when {
        layer.kind.isStroke -> listOf(LayerProp.COLOR, LayerProp.THICKNESS)
        layer.kind == ShapeKind.ROUNDED -> listOf(LayerProp.FILL, LayerProp.OUTLINE, LayerProp.THICKNESS, LayerProp.STRETCH, LayerProp.CORNERS)
        else -> listOf(LayerProp.FILL, LayerProp.OUTLINE, LayerProp.THICKNESS, LayerProp.STRETCH)
    } + listOf(LayerProp.OPACITY, LayerProp.POSITION)
    is StickerLayer -> listOf(LayerProp.OPACITY, LayerProp.STRETCH, LayerProp.POSITION)
    else -> listOf(LayerProp.OPACITY, LayerProp.POSITION)
}

@Composable
fun MarkupPanel(
    selected: Layer?,
    onAddText: () -> Unit,
    onAddSticker: () -> Unit,
    onAddEmoji: () -> Unit,
    onAddShape: () -> Unit,
    onAddWatermark: () -> Unit,
    onEditText: (TextLayer) -> Unit,
    onChange: (Layer, String?) -> Unit,
    onDone: () -> Unit,
    onAlign: (Layer, Anchor) -> Unit,
    onBringToFront: (Layer) -> Unit,
    onDuplicate: (Layer) -> Unit,
    onDelete: (Layer) -> Unit,
    onDeselect: () -> Unit,
    colors: ColorTools,
) {
    if (selected == null) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
            Row(horizontalArrangement = Arrangement.SpaceEvenly, modifier = Modifier.fillMaxWidth()) {
                ToolButton(Icons.Outlined.TextFields, "Text", onClick = onAddText)
                ToolButton(Icons.Outlined.Image, "Sticker", onClick = onAddSticker)
                ToolButton(Icons.Outlined.EmojiEmotions, "Emoji", onClick = onAddEmoji)
                ToolButton(Icons.Outlined.Category, "Shape", onClick = onAddShape)
                ToolButton(Icons.AutoMirrored.Outlined.BrandingWatermark, "Watermark", onClick = onAddWatermark)
            }
            Text(
                "Add something, or tap something on the photo to change it.",
                color = PanelDim,
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            )
        }
        return
    }

    val props = propsFor(selected)
    var prop by remember(selected.id) { mutableStateOf(props.first()) }
    if (prop !in props) prop = props.first()
    val id = selected.id

    Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(props) { p ->
                FilterChip(
                    selected = p == prop,
                    onClick = { prop = p },
                    label = { Text(p.label) },
                    colors = chipColors(),
                )
            }
        }

        Box(Modifier.fillMaxWidth().heightIn(min = 64.dp), contentAlignment = Alignment.Center) {
            val shape = selected as? ShapeLayer
            when (prop) {
                LayerProp.OPACITY -> LabeledSlider(
                    label = "Opacity",
                    value = selected.opacity,
                    range = 0.05f..1f,
                    valueText = "${(selected.opacity * 100).roundToInt()}%",
                    onChange = { onChange(selected.withOpacity(it), "opacity-$id") },
                    onDone = onDone,
                )
                LayerProp.POSITION -> Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier.padding(horizontal = 16.dp),
                ) {
                    Text("Snap to", color = PanelDim, style = MaterialTheme.typography.labelLarge)
                    AnchorGrid(selected = null, onPick = { onAlign(selected, it) }, tint = Color.White)
                }
                LayerProp.FILL -> if (shape != null) ColorRow(
                    selected = shape.fill,
                    onPick = { onChange(shape.copy(fill = it), null) },
                    onCustom = { colors.pick(shape.fill, true) { s, c -> s.updateLayer(id) { (it as ShapeLayer).copy(fill = c) } } },
                    onEyedropper = { colors.eyedrop { s, c -> s.updateLayer(id) { (it as ShapeLayer).copy(fill = c) } } },
                    includeNone = true,
                )
                LayerProp.OUTLINE, LayerProp.COLOR -> if (shape != null) ColorRow(
                    selected = shape.stroke,
                    onPick = { onChange(shape.copy(stroke = it), null) },
                    onCustom = { colors.pick(shape.stroke, true) { s, c -> s.updateLayer(id) { (it as ShapeLayer).copy(stroke = c) } } },
                    onEyedropper = { colors.eyedrop { s, c -> s.updateLayer(id) { (it as ShapeLayer).copy(stroke = c) } } },
                    includeNone = prop == LayerProp.OUTLINE,
                )
                LayerProp.THICKNESS -> if (shape != null) LabeledSlider(
                    label = "Thickness",
                    value = shape.strokeWidth,
                    range = 0.1f..6f,
                    valueText = String.format(Locale.US, "%.1f", shape.strokeWidth),
                    onChange = { onChange(shape.copy(strokeWidth = it), "stroke-$id") },
                    onDone = onDone,
                )
                LayerProp.STRETCH -> if (selected is StickerLayer) LabeledSlider(
                    label = "Stretch (wide ↔ tall)",
                    value = -kotlin.math.ln(selected.stretch),
                    range = -2f..2f,
                    valueText = if (kotlin.math.abs(selected.stretch - 1f) < 0.01f) "Original" else String.format(Locale.US, "%.2f×", selected.stretch),
                    onChange = { v ->
                        val snapped = if (kotlin.math.abs(v) < 0.05f) 0f else v
                        onChange(selected.copy(stretch = kotlin.math.exp(-snapped)), "stretch-$id")
                    },
                    onDone = onDone,
                ) else if (shape != null) LabeledSlider(
                    label = "Stretch (wide ↔ tall)",
                    value = -kotlin.math.ln(shape.aspect),
                    range = -2f..2f,
                    valueText = String.format(Locale.US, "%.2f : 1", shape.aspect),
                    onChange = { v ->
                        val snapped = if (kotlin.math.abs(v) < 0.05f) 0f else v
                        onChange(shape.copy(aspect = kotlin.math.exp(-snapped)), "aspect-$id")
                    },
                    onDone = onDone,
                )
                LayerProp.CORNERS -> if (shape != null) LabeledSlider(
                    label = "Corner rounding",
                    value = shape.cornerRadius,
                    range = 0f..0.5f,
                    valueText = "${(shape.cornerRadius * 200).roundToInt()}%",
                    onChange = { onChange(shape.copy(cornerRadius = it), "corner-$id") },
                    onDone = onDone,
                )
            }
        }

        Row(horizontalArrangement = Arrangement.SpaceEvenly, modifier = Modifier.fillMaxWidth()) {
            if (selected is TextLayer) {
                ToolButton(Icons.Outlined.Edit, "Edit text") { onEditText(selected) }
            }
            ToolButton(Icons.Outlined.FlipToFront, "To front") { onBringToFront(selected) }
            ToolButton(Icons.Outlined.ContentCopy, "Copy") { onDuplicate(selected) }
            ToolButton(Icons.Outlined.Delete, "Delete") { onDelete(selected) }
            ToolButton(Icons.Outlined.Check, "Done", onClick = onDeselect)
        }
    }
}
