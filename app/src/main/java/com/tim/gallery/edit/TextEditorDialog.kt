package com.tim.gallery.edit

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyHorizontalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FormatAlignCenter
import androidx.compose.material.icons.filled.FormatAlignLeft
import androidx.compose.material.icons.filled.FormatAlignRight
import androidx.compose.material.icons.outlined.Contrast
import androidx.compose.material.icons.outlined.FileUpload
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.min
import kotlin.math.roundToInt
import android.graphics.Color as AColor
import androidx.compose.ui.text.font.Typeface as ComposeTypeface

private enum class TextTab(val label: String) {
    STYLES("Styles"),
    FONT("Font"),
    COLOR("Color"),
    OUTLINE("Outline"),
    SHADOW("Shadow"),
    BOX("Box"),
    GLOW("Glow"),
    SPACING("Spacing"),
}

/** One-tap looks. They keep your words and position; some also pick a font. */
private val TEXT_PRESETS: List<Pair<String, (TextLayer) -> TextLayer>> = listOf(
    "Plain" to { t -> t.copy(color = AColor.WHITE, stroke = null, shadow = null, box = null, glow = null, curve = 0f) },
    "Shadow" to { t -> t.copy(stroke = null, shadow = TextShadow(), box = null, glow = null) },
    "Outline" to { t -> t.copy(color = AColor.WHITE, stroke = TextStroke(AColor.BLACK, 0.14f), shadow = null, box = null, glow = null) },
    "Meme" to { t ->
        t.copy(
            fontKey = "g:Anton", color = AColor.WHITE, stroke = TextStroke(AColor.BLACK, 0.18f),
            shadow = null, box = null, glow = null, letterSpacing = 0.02f,
        )
    },
    "Label" to { t ->
        t.copy(color = AColor.WHITE, stroke = null, shadow = null, glow = null, box = TextBox(AColor.BLACK, 0.75f, 0.35f, 0.3f))
    },
    "Highlight" to { t ->
        t.copy(color = AColor.BLACK, stroke = null, shadow = null, glow = null, box = TextBox(0xFFFDD835.toInt(), 1f, 0.25f, 0.12f))
    },
    "Pill" to { t ->
        t.copy(color = AColor.BLACK, stroke = null, shadow = null, glow = null, box = TextBox(AColor.WHITE, 1f, 0.45f, 2f))
    },
    "Neon" to { t ->
        t.copy(color = AColor.WHITE, stroke = null, shadow = null, box = null, glow = TextGlow(0xFFFF2BD6.toInt(), 0.45f))
    },
    "Retro" to { t ->
        t.copy(
            fontKey = "g:Righteous", color = 0xFFFDD835.toInt(), stroke = TextStroke(AColor.BLACK, 0.1f),
            shadow = TextShadow(0xFFE53935.toInt(), 0f, 0.12f, 45f), box = null, glow = null,
        )
    },
    "Elegant" to { t ->
        t.copy(
            fontKey = "g:Playfair Display", color = AColor.WHITE, stroke = null,
            shadow = TextShadow(0x99000000.toInt(), 0.2f, 0.03f, 90f), box = null, glow = null, letterSpacing = 0.08f,
        )
    },
    "Typewriter" to { t ->
        t.copy(fontKey = "g:Special Elite", color = AColor.BLACK, stroke = null, shadow = null, glow = null, box = TextBox(AColor.WHITE, 0.92f, 0.3f, 0.05f))
    },
    "Arc" to { t -> t.copy(curve = 0.35f, stroke = TextStroke(AColor.BLACK, 0.12f), shadow = null) },
)

/**
 * Full-screen text editor: type, then style it (font, color, outline, shadow, box, glow,
 * spacing and curve) with a live preview. [initial] is null for new text.
 */
@Composable
fun TextEditorDialog(
    initial: TextLayer?,
    onDismiss: () -> Unit,
    onDone: (TextLayer) -> Unit,
    placeholder: String = "Type something",
) {
    val context = LocalContext.current
    var draft by remember { mutableStateOf(initial ?: TextLayer(id = 0L, text = "")) }
    var tab by rememberSaveable { mutableStateOf(TextTab.STYLES) }
    var lightPreview by rememberSaveable { mutableStateOf(false) }
    // Color picker request: starting color, whether see-through is allowed, and what to do with the pick
    var picking by remember { mutableStateOf<Triple<Int, Boolean, (Int) -> Unit>?>(null) }
    val focus = remember { FocusRequester() }
    val fontVersion by Fonts.version.collectAsState()

    // Fetch the chosen font if it isn't ready yet
    LaunchedEffect(draft.fontKey) {
        if (Fonts.cached(draft.fontKey) == null) {
            if (Fonts.load(context, draft.fontKey) == null) {
                Toast.makeText(context, "Couldn't load that font. Downloading fonts needs an internet connection.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        // Inside the dialog, so the text field is attached before focus is requested
        LaunchedEffect(Unit) { if (initial == null) runCatching { focus.requestFocus() } }
        Column(
            Modifier
                .fillMaxSize()
                .background(Color(0xF2101010))
                .safeDrawingPadding()
                .imePadding(),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(4.dp)) {
                IconButton(onClick = onDismiss) { Icon(Icons.Filled.Close, contentDescription = "Cancel", tint = Color.White) }
                Box(Modifier.weight(1f))
                IconButton(onClick = { lightPreview = !lightPreview }) {
                    Icon(Icons.Outlined.Contrast, contentDescription = "Preview background", tint = Color.White)
                }
                TextButton(
                    enabled = draft.text.isNotBlank(),
                    onClick = { onDone(draft.copy(text = draft.text.trimEnd())) },
                ) { Text("Done") }
            }

            OutlinedTextField(
                value = draft.text,
                onValueChange = { draft = draft.copy(text = it) },
                placeholder = { Text(placeholder, color = Color.White.copy(alpha = 0.5f)) },
                maxLines = 4,
                textStyle = MaterialTheme.typography.titleMedium.copy(color = Color.White),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Color.White.copy(alpha = 0.7f),
                    unfocusedBorderColor = Color.White.copy(alpha = 0.3f),
                    cursorColor = Color.White,
                ),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).focusRequester(focus),
            )

            // Live preview, drawn exactly the way it will be saved
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(16.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .then(
                        if (lightPreview) Modifier.checkerboard(24f, Color(0xFFF2F2F2), Color(0xFFDCDCDC))
                        else Modifier.checkerboard(24f, Color(0xFF3A3A3A), Color(0xFF2C2C2C))
                    )
            ) {
                Canvas(Modifier.fillMaxSize()) {
                    if (fontVersion < 0) return@Canvas
                    drawTextPreview(draft.copy(text = draft.text.ifBlank { placeholder }), 40.dp.toPx(), 0.92f)
                }
            }

            LazyRow(
                contentPadding = PaddingValues(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(TextTab.entries) { t ->
                    FilterChip(
                        selected = t == tab,
                        onClick = { tab = t },
                        label = { Text(t.label) },
                        colors = darkChipColors(),
                    )
                }
            }

            Box(
                Modifier
                    .fillMaxWidth()
                    .height(176.dp)
                    .padding(vertical = 6.dp)
            ) {
                fun pick(initialColor: Int, alpha: Boolean = true, apply: (Int) -> Unit) {
                    picking = Triple(initialColor, alpha, apply)
                }
                when (tab) {
                    TextTab.STYLES -> StylesTab(draft) { draft = it }
                    TextTab.FONT -> FontTab(draft.fontKey) { draft = draft.copy(fontKey = it) }
                    TextTab.COLOR -> Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        ColorRow(
                            selected = draft.color,
                            allowAlpha = false,
                            onPick = { draft = draft.copy(color = it) },
                            onCustom = { pick(draft.color, alpha = false) { c -> draft = draft.copy(color = c) } },
                        )
                    }
                    TextTab.OUTLINE -> EffectTab(
                        title = "Outline",
                        on = draft.stroke != null,
                        onToggle = { draft = draft.copy(stroke = if (it) TextStroke() else null) },
                    ) {
                        val st = draft.stroke ?: return@EffectTab
                        ColorRow(st.color, onPick = { draft = draft.copy(stroke = st.copy(color = it)) }, onCustom = {
                            pick(st.color) { c -> draft = draft.copy(stroke = draft.stroke?.copy(color = c)) }
                        })
                        Slider2("Thickness", st.width, 0.02f..0.4f, pct(st.width / 0.4f)) { draft = draft.copy(stroke = st.copy(width = it)) }
                    }
                    TextTab.SHADOW -> EffectTab(
                        title = "Shadow",
                        on = draft.shadow != null,
                        onToggle = { draft = draft.copy(shadow = if (it) TextShadow() else null) },
                    ) {
                        val sh = draft.shadow ?: return@EffectTab
                        ColorRow(sh.color, onPick = { draft = draft.copy(shadow = sh.copy(color = it)) }, onCustom = {
                            pick(sh.color) { c -> draft = draft.copy(shadow = draft.shadow?.copy(color = c)) }
                        })
                        Slider2("Blur", sh.blur, 0f..0.6f, pct(sh.blur / 0.6f)) { draft = draft.copy(shadow = sh.copy(blur = it)) }
                        Slider2("Distance", sh.distance, 0f..0.4f, pct(sh.distance / 0.4f)) { draft = draft.copy(shadow = sh.copy(distance = it)) }
                        Slider2("Direction", sh.angle, 0f..360f, "${sh.angle.roundToInt()}°") { draft = draft.copy(shadow = sh.copy(angle = it)) }
                    }
                    TextTab.BOX -> EffectTab(
                        title = "Box behind text",
                        on = draft.box != null,
                        onToggle = { draft = draft.copy(box = if (it) TextBox() else null) },
                    ) {
                        val bx = draft.box ?: return@EffectTab
                        ColorRow(bx.color, onPick = { draft = draft.copy(box = bx.copy(color = it)) }, onCustom = {
                            pick(bx.color) { c -> draft = draft.copy(box = draft.box?.copy(color = c)) }
                        })
                        Slider2("Opacity", bx.opacity, 0.05f..1f, pct(bx.opacity)) { draft = draft.copy(box = bx.copy(opacity = it)) }
                        Slider2("Padding", bx.padding, 0.05f..1f, pct(bx.padding)) { draft = draft.copy(box = bx.copy(padding = it)) }
                        Slider2("Rounded corners", bx.radius, 0f..2f, pct(bx.radius / 2f)) { draft = draft.copy(box = bx.copy(radius = it)) }
                    }
                    TextTab.GLOW -> EffectTab(
                        title = "Glow",
                        on = draft.glow != null,
                        onToggle = { draft = draft.copy(glow = if (it) TextGlow() else null) },
                    ) {
                        val gl = draft.glow ?: return@EffectTab
                        ColorRow(gl.color, onPick = { draft = draft.copy(glow = gl.copy(color = it)) }, onCustom = {
                            pick(gl.color) { c -> draft = draft.copy(glow = draft.glow?.copy(color = c)) }
                        })
                        Slider2("Size", gl.radius, 0.05f..1f, pct(gl.radius)) { draft = draft.copy(glow = gl.copy(radius = it)) }
                    }
                    TextTab.SPACING -> Column(Modifier.verticalScroll(rememberScrollState())) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 16.dp),
                        ) {
                            Text("Align", color = Color.White, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                            listOf(
                                TextAlignOption.LEFT to Icons.Filled.FormatAlignLeft,
                                TextAlignOption.CENTER to Icons.Filled.FormatAlignCenter,
                                TextAlignOption.RIGHT to Icons.Filled.FormatAlignRight,
                            ).forEach { (a, icon) ->
                                IconButton(
                                    onClick = { draft = draft.copy(align = a) },
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(if (draft.align == a) Color.White.copy(alpha = 0.2f) else Color.Transparent),
                                ) { Icon(icon, contentDescription = a.name.lowercase(), tint = Color.White) }
                            }
                        }
                        Slider2("Letter spacing", draft.letterSpacing, -0.05f..0.5f, String.format(Locale.US, "%.2f", draft.letterSpacing)) {
                            draft = draft.copy(letterSpacing = if (kotlin.math.abs(it) < 0.01f) 0f else it)
                        }
                        Slider2("Line spacing", draft.lineSpacing, 0.7f..2.5f, String.format(Locale.US, "%.1f×", draft.lineSpacing)) {
                            draft = draft.copy(lineSpacing = if (kotlin.math.abs(it - 1f) < 0.04f) 1f else it)
                        }
                        Slider2(
                            "Curve (one line only)",
                            draft.curve,
                            -1f..1f,
                            when {
                                draft.curve > 0.02f -> "Arch ${pct(draft.curve)}"
                                draft.curve < -0.02f -> "Smile ${pct(-draft.curve)}"
                                else -> "Straight"
                            },
                        ) { draft = draft.copy(curve = if (kotlin.math.abs(it) < 0.04f) 0f else it) }
                    }
                }
            }
        }

        picking?.let { (initialColor, alpha, apply) ->
            ColorPickerDialog(
                initial = initialColor,
                allowAlpha = alpha,
                onDismiss = { picking = null },
                onPick = {
                    apply(it)
                    picking = null
                },
            )
        }
    }
}

private fun pct(v: Float) = "${(v * 100).roundToInt()}%"

@Composable
private fun darkChipColors() = FilterChipDefaults.filterChipColors(
    labelColor = Color.White,
    selectedContainerColor = Color.White,
    selectedLabelColor = Color.Black,
)

@Composable
private fun Slider2(label: String, value: Float, range: ClosedFloatingPointRange<Float>, valueText: String, onChange: (Float) -> Unit) {
    LabeledSlider(label = label, value = value, range = range, valueText = valueText, onChange = onChange, onDone = {})
}

@Composable
private fun EffectTab(
    title: String,
    on: Boolean,
    onToggle: (Boolean) -> Unit,
    content: @Composable () -> Unit,
) {
    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
            Text(title, color = Color.White, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            Switch(checked = on, onCheckedChange = onToggle)
        }
        if (on) content()
    }
}

/** Draws [layer] centered, shrunk to fit if needed. [textPx] is the letter height for scale 1. */
fun DrawScope.drawTextPreview(layer: TextLayer, textPx: Float, fill: Float) {
    val l = layer.copy(scale = 1f)
    val shortSide = textPx * 10f
    val tf = Fonts.resolve(l.fontKey)
    val t = EditRenderer.buildText(l, shortSide, tf)
    val k = min(1f, min(size.width * fill / t.width, size.height * fill / t.height))
    drawIntoCanvas { c ->
        val nc = c.nativeCanvas
        nc.save()
        nc.translate(size.width / 2f, size.height / 2f)
        nc.scale(k, k)
        EditRenderer.drawText(nc, l, shortSide, tf)
        nc.restore()
    }
}

@Composable
private fun StylesTab(draft: TextLayer, onApply: (TextLayer) -> Unit) {
    val context = LocalContext.current
    val fontVersion by Fonts.version.collectAsState()
    LaunchedEffect(Unit) {
        // Fonts some presets use, so their previews look right
        listOf("g:Anton", "g:Righteous", "g:Playfair Display", "g:Special Elite").forEach { Fonts.load(context, it) }
    }
    LazyHorizontalGrid(
        rows = GridCells.Fixed(2),
        contentPadding = PaddingValues(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        items(TEXT_PRESETS) { (name, apply) ->
            val sample = apply(TextLayer(id = 0L, text = "Aa", fontKey = draft.fontKey))
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .width(92.dp)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(12.dp))
                    .checkerboard(14f, Color(0xFF4A4A4A), Color(0xFF3C3C3C))
                    .clickable { onApply(apply(draft)) }
                    .padding(4.dp),
            ) {
                Canvas(Modifier.weight(1f).fillMaxWidth()) {
                    if (fontVersion < 0) return@Canvas
                    drawTextPreview(sample, 22.dp.toPx(), 0.9f)
                }
                Text(name, color = Color.White, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
private fun FontTab(selected: String, onPick: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val imported by Fonts.imported.collectAsState()
    val fontVersion by Fonts.version.collectAsState()
    var importing by remember { mutableStateOf(false) }

    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        importing = true
        scope.launch {
            val key = Fonts.import(context, uri)
            importing = false
            if (key != null) onPick(key)
            else Toast.makeText(context, "That file isn't a font Android can use (.ttf or .otf).", Toast.LENGTH_LONG).show()
        }
    }

    LaunchedEffect(Unit) {
        Fonts.refreshImported(context)
        // Fetch the Google Fonts in the background so their names show in their own style
        Fonts.googleEntries().forEach { e -> launch { Fonts.load(context, e.key) } }
    }

    val entries = imported + Fonts.systemEntries() + Fonts.googleEntries()
    LazyHorizontalGrid(
        rows = GridCells.Fixed(3),
        contentPadding = PaddingValues(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item {
            FontCell(
                label = if (importing) "Importing…" else "Import font",
                typeface = null,
                selected = false,
                loading = importing,
                icon = true,
            ) {
                importer.launch(
                    arrayOf(
                        "font/ttf", "font/otf", "font/sfnt", "application/x-font-ttf", "application/x-font-otf",
                        "application/font-sfnt", "application/vnd.ms-opentype", "application/octet-stream",
                    )
                )
            }
        }
        items(entries, key = { it.key }) { e ->
            if (fontVersion < 0) return@items
            val tf = Fonts.cached(e.key)
            FontCell(
                label = e.label,
                typeface = tf,
                selected = e.key == selected,
                loading = tf == null && !Fonts.isFailed(e.key),
            ) { onPick(e.key) }
        }
    }
}

@Composable
private fun FontCell(
    label: String,
    typeface: android.graphics.Typeface?,
    selected: Boolean,
    loading: Boolean,
    icon: Boolean = false,
    onClick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxHeight()
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) Color.White else Color.White.copy(alpha = 0.1f))
            .border(BorderStroke(1.dp, Color.White.copy(alpha = 0.25f)), RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp),
    ) {
        if (icon) {
            Icon(Icons.Outlined.FileUpload, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
        }
        Text(
            label,
            color = if (selected) Color.Black else Color.White,
            fontFamily = typeface?.let { FontFamily(ComposeTypeface(it)) },
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (loading) {
            Spacer(Modifier.width(6.dp))
            CircularProgressIndicator(
                color = if (selected) Color.Black else Color.White,
                strokeWidth = 1.5.dp,
                modifier = Modifier.size(12.dp),
            )
        }
    }
}
