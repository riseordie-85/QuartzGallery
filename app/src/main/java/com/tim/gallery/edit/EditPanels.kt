package com.tim.gallery.edit

import android.graphics.Color as AColor
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
                Spacer(Modifier.width(4.dp))
                listOf(AColor.BLACK, AColor.WHITE, AColor.TRANSPARENT).forEach { c ->
                    val sel = g.fillColor == c
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(28.dp)
                            .clip(CircleShape)
                            .background(if (c == AColor.TRANSPARENT) Color.DarkGray else Color(c))
                            .border(BorderStroke(if (sel) 3.dp else 1.dp, Color.White.copy(alpha = if (sel) 1f else 0.4f)), CircleShape)
                            .clickable { onGeometry(g.copy(fillColor = c), null) },
                    ) {
                        if (c == AColor.TRANSPARENT) {
                            Text("∅", color = Color.White, style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }
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
                ColorDots(PALETTE, state.cutoutColor, onPick = { onChange(state.copy(cutoutColor = it)) })
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

@Composable
fun MarkupPanel(
    selected: Layer?,
    onAddText: () -> Unit,
    onAddSticker: () -> Unit,
    onEditText: (TextLayer) -> Unit,
    onBringToFront: (Layer) -> Unit,
    onDuplicate: (Layer) -> Unit,
    onDelete: (Layer) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
        Row(horizontalArrangement = Arrangement.SpaceEvenly, modifier = Modifier.fillMaxWidth()) {
            ToolButton(Icons.Outlined.TextFields, "Add text", onClick = onAddText)
            ToolButton(Icons.Outlined.EmojiEmotions, "Add sticker", onClick = onAddSticker)
            ToolButton(Icons.Outlined.Edit, "Edit text", enabled = selected is TextLayer) {
                (selected as? TextLayer)?.let(onEditText)
            }
            ToolButton(Icons.Outlined.FlipToFront, "To front", enabled = selected != null) { selected?.let(onBringToFront) }
            ToolButton(Icons.Outlined.ContentCopy, "Copy", enabled = selected != null) { selected?.let(onDuplicate) }
            ToolButton(Icons.Outlined.Delete, "Delete", enabled = selected != null) { selected?.let(onDelete) }
        }
        Text(
            if (selected == null) "Tap something to select it."
            else "Drag to move · pinch or use the corner dot to resize and rotate",
            color = PanelDim,
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        )
    }
}
