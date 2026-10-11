package com.tim.gallery.edit

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Colorize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.util.Locale
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import android.graphics.Color as AColor

// ===================================================================== recent colors

/** Colors you picked with the picker or eyedropper, newest first (shared by every color row). */
object ColorRecents {
    private const val MAX = 12
    private var prefs: android.content.SharedPreferences? = null
    private val _recents = MutableStateFlow<List<Int>>(emptyList())
    val recents: StateFlow<List<Int>> = _recents.asStateFlow()

    fun init(context: Context) {
        val p = context.getSharedPreferences("colors", Context.MODE_PRIVATE)
        prefs = p
        _recents.value = p.getString("recent", "").orEmpty()
            .split(',')
            .mapNotNull { it.trim().toLongOrNull()?.toInt() }
    }

    fun add(color: Int) {
        val list = (listOf(color) + _recents.value.filter { it != color }).take(MAX)
        _recents.value = list
        prefs?.edit()?.putString("recent", list.joinToString(",") { (it.toLong() and 0xFFFFFFFFL).toString() })?.apply()
    }
}

// ===================================================================== helpers

fun hexOf(color: Int, withAlpha: Boolean): String =
    if (withAlpha && AColor.alpha(color) < 255) String.format(Locale.US, "#%08X", color)
    else String.format(Locale.US, "#%06X", color and 0xFFFFFF)

/** Parses "#RRGGBB", "RRGGBB" or "#AARRGGBB". Returns null if it isn't a color. */
fun parseHex(text: String): Int? {
    val t = text.trim().removePrefix("#")
    if (!t.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) return null
    return when (t.length) {
        6 -> (0xFF000000L or t.toLong(16)).toInt()
        8 -> t.toLong(16).toInt()
        3 -> {
            val r = t[0].toString().repeat(2)
            val g = t[1].toString().repeat(2)
            val b = t[2].toString().repeat(2)
            (0xFF000000L or "$r$g$b".toLong(16)).toInt()
        }
        else -> null
    }
}

/** Gray checkerboard, for showing see-through colors. */
fun Modifier.checkerboard(
    cell: Float = 18f,
    light: Color = Color(0xFFBDBDBD),
    dark: Color = Color(0xFF8E8E8E),
): Modifier = drawBehind {
    drawRect(light)
    val n = (size.width / cell).toInt() + 1
    val m = (size.height / cell).toInt() + 1
    for (i in 0..n) for (j in 0..m) {
        if ((i + j) % 2 == 0) {
            drawRect(dark, Offset(i * cell, j * cell), Size(cell, cell))
        }
    }
}

private val RainbowBrush = Brush.sweepGradient(
    listOf(Color.Red, Color.Yellow, Color.Green, Color.Cyan, Color.Blue, Color.Magenta, Color.Red)
)

// ===================================================================== color row

/**
 * A scrolling row of colors: eyedropper (optional), the full picker, "none" (optional),
 * your recent colors, then the standard palette.
 */
@Composable
fun ColorRow(
    selected: Int?,
    onPick: (Int) -> Unit,
    onCustom: (() -> Unit)?,
    modifier: Modifier = Modifier,
    onEyedropper: (() -> Unit)? = null,
    includeNone: Boolean = false,
    colors: List<Int> = PALETTE,
    dotSize: Int = 30,
    outline: Color = Color.White,
    /** False for places that need a solid color: see-through recent colors are left out. */
    allowAlpha: Boolean = true,
) {
    val recents by ColorRecents.recents.collectAsStateWithLifecycle()
    val extra = recents.filter { it !in colors && (allowAlpha || AColor.alpha(it) == 255) }
    val shown = (if (includeNone) listOf(AColor.TRANSPARENT) else emptyList()) + extra + colors
    // In the wide editor panel the dots wrap onto several lines; equal cells keep them lined up
    val wide = LocalWidePanel.current
    val cell: @Composable (@Composable () -> Unit) -> Unit = { content ->
        if (wide) Box(Modifier.size((dotSize + 4).dp), contentAlignment = Alignment.Center) { content() } else content()
    }
    PanelRow(modifier = modifier, spacing = 10.dp) {
        if (onEyedropper != null) {
            cell {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(dotSize.dp)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.15f))
                        .border(BorderStroke(1.dp, outline.copy(alpha = 0.5f)), CircleShape)
                        .clickable(onClick = onEyedropper),
                ) {
                    Icon(Icons.Outlined.Colorize, contentDescription = "Pick a color from the photo", tint = outline, modifier = Modifier.size((dotSize * 0.6f).dp))
                }
            }
        }
        if (onCustom != null) {
            cell {
                val custom = selected != null && selected !in shown
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(if (custom) (dotSize + 4).dp else dotSize.dp)
                        .clip(CircleShape)
                        .background(RainbowBrush)
                        .border(BorderStroke(if (custom) 3.dp else 1.dp, outline.copy(alpha = if (custom) 1f else 0.5f)), CircleShape)
                        .clickable(onClick = onCustom),
                ) {
                    if (custom && selected != null) {
                        Box(
                            Modifier
                                .size((dotSize * 0.5f).dp)
                                .clip(CircleShape)
                                .checkerboard(6f)
                                .background(Color(selected))
                        )
                    } else {
                        Text("+", color = Color.White, style = MaterialTheme.typography.titleMedium)
                    }
                }
            }
        }
        shown.forEach { c ->
          cell {
            val isSel = c == selected
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(if (isSel) (dotSize + 4).dp else dotSize.dp)
                    .clip(CircleShape)
                    .then(if (AColor.alpha(c) < 255) Modifier.checkerboard(8f) else Modifier)
                    .background(Color(c))
                    .border(BorderStroke(if (isSel) 3.dp else 1.dp, outline.copy(alpha = if (isSel) 1f else 0.4f)), CircleShape)
                    .clickable { onPick(c) },
            ) {
                if (c == AColor.TRANSPARENT) {
                    Text("∅", color = Color.Black, style = MaterialTheme.typography.labelLarge)
                }
            }
          }
        }
    }
}

// ===================================================================== picker dialog

/**
 * Full color picker: color wheel, brightness, optional opacity, hex and RGB entry, recent colors,
 * and (when [onEyedropper] is set) a button to pick a color straight from the photo.
 */
@Composable
fun ColorPickerDialog(
    initial: Int,
    allowAlpha: Boolean,
    onDismiss: () -> Unit,
    onPick: (Int) -> Unit,
    onEyedropper: (() -> Unit)? = null,
    title: String = "Pick a color",
) {
    val hsv0 = remember {
        FloatArray(3).also { AColor.colorToHSV(initial, it) }
    }
    var hue by remember { mutableFloatStateOf(hsv0[0]) }
    var sat by remember { mutableFloatStateOf(hsv0[1]) }
    var value by remember { mutableFloatStateOf(hsv0[2]) }
    var alpha by remember { mutableFloatStateOf(if (allowAlpha) AColor.alpha(initial) / 255f else 1f) }
    val color = AColor.HSVToColor((alpha * 255).roundToInt(), floatArrayOf(hue, sat, value))
    // What's typed in the hex box while you're typing it (null = show the current color)
    var typedHex by remember { mutableStateOf<String?>(null) }

    fun setColor(c: Int) {
        val hsv = FloatArray(3)
        AColor.colorToHSV(c, hsv)
        // Keep the hue when picking a gray, so the wheel doesn't jump
        if (hsv[1] > 0.001f && hsv[2] > 0.001f) hue = hsv[0]
        sat = hsv[1]
        value = hsv[2]
        if (allowAlpha) alpha = AColor.alpha(c) / 255f
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.verticalScroll(rememberScrollState()),
            ) {
                // Before / after
                Row(
                    Modifier
                        .fillMaxWidth()
                        .height(36.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .checkerboard()
                ) {
                    Box(Modifier.weight(1f).fillMaxSize().background(Color(initial)))
                    Box(Modifier.weight(1f).fillMaxSize().background(Color(color)))
                }

                HueSatWheel(
                    hue = hue,
                    sat = sat,
                    value = value,
                    onChange = { h, s ->
                        typedHex = null
                        hue = h
                        sat = s
                        if (value < 0.05f) value = 1f
                    },
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                )

                Text("Brightness", style = MaterialTheme.typography.labelMedium)
                Slider(value = value, onValueChange = { typedHex = null; value = it }, valueRange = 0f..1f)
                if (allowAlpha) {
                    Text("Opacity  ${(alpha * 100).roundToInt()}%", style = MaterialTheme.typography.labelMedium)
                    Slider(value = alpha, onValueChange = { typedHex = null; alpha = it }, valueRange = 0f..1f)
                }

                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = typedHex ?: hexOf(color, allowAlpha),
                        onValueChange = { t ->
                            typedHex = t
                            // Only full codes while typing, so "#123" doesn't jump to "#112233" mid-entry
                            val digits = t.trim().removePrefix("#").length
                            if (digits == 6 || (allowAlpha && digits == 8)) {
                                parseHex(t)?.let { c -> setColor(if (allowAlpha) c else c or 0xFF000000.toInt()) }
                            }
                        },
                        label = { Text("Hex") },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                        modifier = Modifier.weight(1.6f),
                    )
                    ChannelField("R", AColor.red(color), Modifier.weight(1f)) {
                        typedHex = null
                        setColor(AColor.argb(AColor.alpha(color), it, AColor.green(color), AColor.blue(color)))
                    }
                    ChannelField("G", AColor.green(color), Modifier.weight(1f)) {
                        typedHex = null
                        setColor(AColor.argb(AColor.alpha(color), AColor.red(color), it, AColor.blue(color)))
                    }
                    ChannelField("B", AColor.blue(color), Modifier.weight(1f)) {
                        typedHex = null
                        setColor(AColor.argb(AColor.alpha(color), AColor.red(color), AColor.green(color), it))
                    }
                }

                ColorRow(
                    selected = color,
                    onPick = { c ->
                        typedHex = null
                        setColor(if (c == AColor.TRANSPARENT && !allowAlpha) AColor.BLACK else c)
                    },
                    onCustom = null,
                    includeNone = allowAlpha,
                    allowAlpha = allowAlpha,
                    dotSize = 26,
                    outline = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.fillMaxWidth(),
                )

                if (onEyedropper != null) {
                    FilledTonalButton(onClick = onEyedropper) {
                        Icon(Icons.Outlined.Colorize, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Pick from photo")
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                ColorRecents.add(color)
                onPick(color)
            }) { Text("Done") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun ChannelField(label: String, v: Int, modifier: Modifier, onValue: (Int) -> Unit) {
    var text by remember(v) { mutableStateOf(v.toString()) }
    OutlinedTextField(
        value = text,
        onValueChange = { t ->
            val digits = t.filter { it.isDigit() }.take(3)
            text = digits
            digits.toIntOrNull()?.let { onValue(it.coerceIn(0, 255)) }
        },
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = modifier,
    )
}

/** Color wheel: angle = hue, distance from the center = saturation. */
@Composable
fun HueSatWheel(
    hue: Float,
    sat: Float,
    value: Float,
    onChange: (Float, Float) -> Unit,
    modifier: Modifier = Modifier,
    sizeDp: Int = 220,
) {
    val density = LocalDensity.current
    val ringPx = with(density) { 3.dp.toPx() }
    Canvas(
        modifier
            .size(sizeDp.dp)
            .pointerInput(Unit) {
                fun report(p: Offset) {
                    val r = min(size.width, size.height) / 2f
                    val dx = p.x - size.width / 2f
                    val dy = p.y - size.height / 2f
                    var deg = Math.toDegrees(atan2(dy, dx).toDouble()).toFloat()
                    if (deg < 0f) deg += 360f
                    onChange(deg, (hypot(dx, dy) / r).coerceIn(0f, 1f))
                }
                awaitEachGesture {
                    val down = awaitFirstDown()
                    down.consume()
                    report(down.position)
                    do {
                        val e = awaitPointerEvent()
                        e.changes.forEach { ch ->
                            if (ch.pressed) report(ch.position)
                            ch.consume()
                        }
                    } while (e.changes.any { it.pressed })
                }
            }
    ) {
        val r = min(size.width, size.height) / 2f
        val c = Offset(size.width / 2f, size.height / 2f)
        drawCircle(RainbowBrush, r, c)
        drawCircle(Brush.radialGradient(listOf(Color.White, Color.White.copy(alpha = 0f)), c, r), r, c)
        if (value < 1f) drawCircle(Color.Black.copy(alpha = 1f - value), r, c)
        val a = Math.toRadians(hue.toDouble())
        val p = Offset(c.x + (cos(a) * sat * r).toFloat(), c.y + (sin(a) * sat * r).toFloat())
        drawCircle(Color(AColor.HSVToColor(floatArrayOf(hue, sat, value))), ringPx * 4f, p)
        drawCircle(Color.White, ringPx * 4f, p, style = Stroke(ringPx))
        drawCircle(Color.Black.copy(alpha = 0.4f), ringPx * 4f + ringPx, p, style = Stroke(ringPx / 2f))
    }
}

// ===================================================================== eyedropper

/**
 * Shows [sample] (the photo) and lets you drag a magnifier over it; lifting your finger picks
 * that color. The color is always fully opaque.
 */
@Composable
fun EyedropperOverlay(sample: Bitmap?, onPick: (Int) -> Unit, onCancel: () -> Unit) {
    val density = LocalDensity.current
    var touch by remember { mutableStateOf<Offset?>(null) }
    var picked by remember { mutableStateOf<Int?>(null) }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (sample == null) {
            androidx.compose.material3.CircularProgressIndicator(color = Color.White, modifier = Modifier.align(Alignment.Center))
        } else {
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val availW = constraints.maxWidth.toFloat()
                val availH = constraints.maxHeight.toFloat()
                val s = min(availW / sample.width, availH / sample.height)
                val ox = (availW - sample.width * s) / 2f
                val oy = (availH - sample.height * s) / 2f
                val image = remember(sample) { sample.asImageBitmap() }

                fun sampleAt(p: Offset): Int? {
                    val x = ((p.x - ox) / s).roundToInt()
                    val y = ((p.y - oy) / s).roundToInt()
                    if (x !in 0 until sample.width || y !in 0 until sample.height) return null
                    val c = sample.getPixel(x, y)
                    if (AColor.alpha(c) == 0) return null
                    return c or 0xFF000000.toInt()
                }

                Canvas(
                    Modifier
                        .fillMaxSize()
                        .pointerInput(sample) {
                            awaitEachGesture {
                                val down = awaitFirstDown()
                                touch = down.position
                                picked = sampleAt(down.position)
                                do {
                                    val e = awaitPointerEvent()
                                    e.changes.firstOrNull { it.pressed }?.let { ch ->
                                        touch = ch.position
                                        sampleAt(ch.position)?.let { picked = it }
                                    }
                                    e.changes.forEach { it.consume() }
                                } while (e.changes.any { it.pressed })
                                val result = picked
                                touch = null
                                if (result != null) onPick(result)
                            }
                        }
                ) {
                    drawImage(
                        image,
                        srcOffset = IntOffset.Zero,
                        srcSize = IntSize(sample.width, sample.height),
                        dstOffset = IntOffset(ox.roundToInt(), oy.roundToInt()),
                        dstSize = IntSize((sample.width * s).roundToInt(), (sample.height * s).roundToInt()),
                    )
                    val t = touch
                    val pc = picked
                    if (t != null) {
                        val lift = with(density) { 80.dp.toPx() }
                        val rad = with(density) { 34.dp.toPx() }
                        val center = Offset(t.x, (t.y - lift).coerceAtLeast(rad + 8f))
                        drawCircle(Color.White, with(density) { 6.dp.toPx() }, t, style = Stroke(with(density) { 2.dp.toPx() }))
                        drawCircle(Color.Black.copy(alpha = 0.5f), rad + 6f, center)
                        drawCircle(if (pc != null) Color(pc) else Color.Transparent, rad, center)
                        drawCircle(Color.White, rad, center, style = Stroke(with(density) { 3.dp.toPx() }))
                    }
                }
            }
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.6f))
                .padding(horizontal = 16.dp, vertical = 4.dp),
        ) {
            Text(
                picked?.let { "Drag to pick · ${hexOf(it, false)}" } ?: "Touch and drag to pick a color",
                color = Color.White,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Start,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onCancel) { Text("Cancel", color = Color.White) }
        }
    }
}
