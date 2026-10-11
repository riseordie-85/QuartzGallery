package com.tim.gallery.edit

import android.content.Context
import android.graphics.Bitmap
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tim.gallery.data.Cutout
import com.tim.gallery.data.CutoutException
import com.tim.gallery.data.GalleryItem
import com.tim.gallery.data.MediaSaver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

// ===================================================================== model

/** Nine places to snap something to. */
enum class Anchor(val fx: Float, val fy: Float, val label: String) {
    TOP_LEFT(0f, 0f, "Top left"),
    TOP(0.5f, 0f, "Top"),
    TOP_RIGHT(1f, 0f, "Top right"),
    LEFT(0f, 0.5f, "Left"),
    CENTER(0.5f, 0.5f, "Center"),
    RIGHT(1f, 0.5f, "Right"),
    BOTTOM_LEFT(0f, 1f, "Bottom left"),
    BOTTOM(0.5f, 1f, "Bottom"),
    BOTTOM_RIGHT(1f, 1f, "Bottom right"),
}

/**
 * A saved watermark: either styled text ([text]) or a logo picture ([logo], a file inside the app),
 * plus where it goes, how big, and how see-through.
 */
data class WatermarkPreset(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "Watermark",
    val text: TextLayer? = TextLayer(id = 0L, text = "© Tim", shadow = TextShadow()),
    val logo: String? = null,
    val anchor: Anchor = Anchor.BOTTOM_RIGHT,
    /** Gap from the edges, as a fraction of the picture's short side. */
    val margin: Float = 0.04f,
    /** 0..1: logo width, or text height ×3, as a fraction of the picture's short side. */
    val size: Float = 0.2f,
    val opacity: Float = 0.7f,
) {
    val isLogo: Boolean get() = logo != null

    /** The watermark as a layer, placed on a picture of size [out]. */
    fun toLayer(id: Long, out: FSize, assets: LayerAssets): Layer {
        val base: Layer = if (logo != null) {
            StickerLayer(id = id, uri = logo, scale = size, opacity = opacity)
        } else {
            (text ?: TextLayer(id = id, text = name)).copy(id = id, scale = size * 3f, rotation = 0f, opacity = opacity)
        }
        val (cx, cy) = anchorPosition(base, anchor, margin, out, assets)
        return base.moved(cx, cy, base.scale, base.rotation)
    }
}

/** Center (0..1) that puts [layer] at [anchor], [margin] (fraction of the short side) from the edges. */
fun anchorPosition(layer: Layer, anchor: Anchor, margin: Float, out: FSize, assets: LayerAssets): Pair<Float, Float> {
    val s = EditRenderer.layerSize(layer, out, assets)
    val rad = Math.toRadians(layer.rotation.toDouble())
    val c = abs(cos(rad)).toFloat()
    val sn = abs(sin(rad)).toFloat()
    val bw = s.width * c + s.height * sn
    val bh = s.width * sn + s.height * c
    val m = margin * out.shortSide
    fun place(f: Float, extent: Float, total: Float): Float = when (f) {
        0f -> m + extent / 2f
        1f -> total - m - extent / 2f
        else -> total / 2f
    }
    return place(anchor.fx, bw, out.width) / out.width to place(anchor.fy, bh, out.height) / out.height
}

// ===================================================================== storage

/** Watermark presets, saved as JSON inside the app; logos live in files/watermarks/. */
object WatermarkStore {
    private val _presets = MutableStateFlow<List<WatermarkPreset>>(emptyList())
    val presets: StateFlow<List<WatermarkPreset>> = _presets.asStateFlow()
    private var loaded = false

    private fun file(context: Context) = File(context.filesDir, "watermarks.json")
    private fun logoDir(context: Context) = File(context.filesDir, "watermarks").apply { mkdirs() }

    fun init(context: Context) {
        if (loaded) return
        loaded = true
        _presets.value = runCatching {
            val f = file(context)
            if (!f.exists()) emptyList()
            else JSONArray(f.readText()).let { arr -> (0 until arr.length()).map { presetFromJson(arr.getJSONObject(it)) } }
        }.getOrDefault(emptyList())
    }

    fun save(context: Context, preset: WatermarkPreset) {
        val list = _presets.value
        val old = list.firstOrNull { it.id == preset.id }
        if (old?.logo != null && old.logo != preset.logo) deleteLogo(old.logo)
        _presets.value = if (old == null) list + preset else list.map { if (it.id == preset.id) preset else it }
        write(context)
    }

    fun delete(context: Context, preset: WatermarkPreset) {
        preset.logo?.let { deleteLogo(it) }
        _presets.value = _presets.value.filterNot { it.id == preset.id }
        write(context)
    }

    /** Stores a logo picture inside the app and returns its uri. */
    suspend fun saveLogo(context: Context, bitmap: Bitmap): String? = withContext(Dispatchers.IO) {
        runCatching {
            val f = File(logoDir(context), "logo_${System.currentTimeMillis()}.png")
            f.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            appFileUri(f)
        }.getOrNull()
    }

    private fun deleteLogo(uri: String) {
        runCatching { android.net.Uri.parse(uri).path?.let { File(it).delete() } }
    }

    private fun write(context: Context) {
        val arr = JSONArray()
        _presets.value.forEach { arr.put(presetToJson(it)) }
        runCatching { file(context).writeText(arr.toString()) }
    }

    private fun presetToJson(p: WatermarkPreset) = JSONObject().apply {
        put("id", p.id)
        put("name", p.name)
        p.text?.let { put("text", textToJson(it)) }
        p.logo?.let { put("logo", it) }
        put("anchor", p.anchor.name)
        put("margin", p.margin.toDouble())
        put("size", p.size.toDouble())
        put("opacity", p.opacity.toDouble())
    }

    private fun presetFromJson(o: JSONObject) = WatermarkPreset(
        id = o.optString("id", UUID.randomUUID().toString()),
        name = o.optString("name", "Watermark"),
        text = o.optJSONObject("text")?.let { textFromJson(it) },
        logo = o.optString("logo", "").ifBlank { null },
        anchor = runCatching { Anchor.valueOf(o.optString("anchor")) }.getOrDefault(Anchor.BOTTOM_RIGHT),
        margin = o.optDouble("margin", 0.04).toFloat(),
        size = o.optDouble("size", 0.2).toFloat(),
        opacity = o.optDouble("opacity", 0.7).toFloat(),
    )

    internal fun textToJson(t: TextLayer) = JSONObject().apply {
        put("text", t.text)
        put("color", t.color)
        put("font", t.fontKey)
        put("align", t.align.name)
        t.stroke?.let { put("stroke", JSONObject().put("color", it.color).put("width", it.width.toDouble())) }
        t.shadow?.let {
            put(
                "shadow",
                JSONObject().put("color", it.color).put("blur", it.blur.toDouble())
                    .put("distance", it.distance.toDouble()).put("angle", it.angle.toDouble()),
            )
        }
        t.box?.let {
            put(
                "box",
                JSONObject().put("color", it.color).put("opacity", it.opacity.toDouble())
                    .put("padding", it.padding.toDouble()).put("radius", it.radius.toDouble()),
            )
        }
        t.glow?.let { put("glow", JSONObject().put("color", it.color).put("radius", it.radius.toDouble())) }
        put("letterSpacing", t.letterSpacing.toDouble())
        put("lineSpacing", t.lineSpacing.toDouble())
        put("curve", t.curve.toDouble())
    }

    private fun JSONObject.f(name: String, def: Float) = optDouble(name, def.toDouble()).toFloat()

    internal fun textFromJson(o: JSONObject) = TextLayer(
        id = 0L,
        text = o.optString("text", ""),
        color = o.optInt("color", android.graphics.Color.WHITE),
        fontKey = o.optString("font", TextFont.BOLD.key),
        align = runCatching { TextAlignOption.valueOf(o.optString("align")) }.getOrDefault(TextAlignOption.CENTER),
        stroke = o.optJSONObject("stroke")?.let { TextStroke(it.optInt("color"), it.f("width", 0.12f)) },
        shadow = o.optJSONObject("shadow")?.let {
            TextShadow(it.optInt("color"), it.f("blur", 0.12f), it.f("distance", 0.06f), it.f("angle", 90f))
        },
        box = o.optJSONObject("box")?.let {
            TextBox(it.optInt("color"), it.f("opacity", 0.75f), it.f("padding", 0.35f), it.f("radius", 0.45f))
        },
        glow = o.optJSONObject("glow")?.let { TextGlow(it.optInt("color"), it.f("radius", 0.35f)) },
        letterSpacing = o.f("letterSpacing", 0f),
        lineSpacing = o.f("lineSpacing", 1f),
        curve = o.f("curve", 0f),
    )
}

// ===================================================================== small UI pieces

/** 3×3 grid of positions. */
@Composable
fun AnchorGrid(selected: Anchor?, onPick: (Anchor) -> Unit, tint: Color) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        for (row in 0..2) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                for (col in 0..2) {
                    val a = Anchor.entries[row * 3 + col]
                    val sel = a == selected
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(30.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(if (sel) tint else tint.copy(alpha = 0.12f))
                            .border(BorderStroke(1.dp, tint.copy(alpha = 0.4f)), RoundedCornerShape(6.dp))
                            .clickable { onPick(a) },
                    ) {
                        Box(
                            Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(if (sel) Color.Black.copy(alpha = 0.6f) else tint.copy(alpha = 0.8f))
                        )
                    }
                }
            }
        }
    }
}

/** Gray sample "photo" with the watermark on it. */
private fun DrawScope.drawWatermarkPreview(preset: WatermarkPreset, assets: LayerAssets) {
    drawRect(
        Brush.linearGradient(
            listOf(Color(0xFF5B7A99), Color(0xFFB59A7A), Color(0xFF3E4F3A)),
        )
    )
    val out = FSize(size.width, size.height)
    val layer = preset.toLayer(1L, out, assets)
    drawIntoCanvas { c -> EditRenderer.drawLayer(c.nativeCanvas, layer, out, assets) }
}

/** Loads logo pictures for a set of presets (for previews). */
@Composable
private fun rememberLogoAssets(presets: List<WatermarkPreset>): LayerAssets {
    val context = LocalContext.current
    val map = remember { mutableStateMapOf<String, Bitmap>() }
    LaunchedEffect(presets) {
        presets.mapNotNull { it.logo }.filter { it !in map }.forEach { uri ->
            LayerLoader.load(context, uri, 512)?.let { map[uri] = it }
        }
    }
    return object : LayerAssets {
        override fun bitmap(uri: String): Bitmap? = map[uri]
    }
}

// ===================================================================== list / picker sheet

/**
 * Your saved watermarks. With [onApply], tapping one adds it to the photo; otherwise this is
 * just for managing them (Settings).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WatermarkSheet(onDismiss: () -> Unit, onApply: ((WatermarkPreset) -> Unit)?) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        WatermarkList(onApply = onApply)
    }
}

/**
 * Your saved watermarks with edit and delete (in a sheet, or right in the editor's side panel
 * on wide screens). Tapping one calls [onApply]; without it, tapping edits.
 */
@Composable
fun WatermarkList(onApply: ((WatermarkPreset) -> Unit)?, listMaxHeight: androidx.compose.ui.unit.Dp = 480.dp) {
    val context = LocalContext.current
    LaunchedEffect(Unit) { WatermarkStore.init(context) }
    val presets by WatermarkStore.presets.collectAsState()
    var editing by remember { mutableStateOf<WatermarkPreset?>(null) }
    var creating by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf<WatermarkPreset?>(null) }
    val assets = rememberLogoAssets(presets)
    val fontVersion by Fonts.version.collectAsState()
    LaunchedEffect(presets) { presets.mapNotNull { it.text?.fontKey }.distinct().forEach { Fonts.load(context, it) } }

    Column {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp),
        ) {
            Text("Watermarks", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            TextButton(onClick = { creating = true }) {
                Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("New")
            }
        }
        if (presets.isEmpty()) {
            Text(
                "Save your name, signature or logo as a watermark, then add it to any photo with one tap — " +
                    "or to many photos at once from the selection menu.",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 24.dp),
            )
            FilledTonalButton(
                onClick = { creating = true },
                modifier = Modifier.align(Alignment.CenterHorizontally).padding(bottom = 32.dp),
            ) { Text("Create a watermark") }
        } else {
            LazyColumn(Modifier.heightIn(max = listMaxHeight).padding(bottom = 24.dp)) {
                items(presets, key = { it.id }) { p ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { if (onApply != null) onApply(p) else editing = p }
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                    ) {
                        Canvas(
                            Modifier
                                .width(84.dp)
                                .height(56.dp)
                                .clip(RoundedCornerShape(8.dp))
                        ) {
                            if (fontVersion < 0) return@Canvas
                            drawWatermarkPreview(p, assets)
                        }
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(p.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                (if (p.isLogo) "Logo" else "Text") + " · " + p.anchor.label + " · ${(p.opacity * 100).roundToInt()}%",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        IconButton(onClick = { editing = p }) { Icon(Icons.Outlined.Edit, contentDescription = "Edit") }
                        IconButton(onClick = { confirmDelete = p }) { Icon(Icons.Outlined.Delete, contentDescription = "Delete") }
                    }
                }
            }
        }
    }

    if (creating || editing != null) {
        WatermarkEditorDialog(
            initial = editing,
            onDismiss = {
                creating = false
                editing = null
            },
            onSaved = {
                creating = false
                editing = null
            },
        )
    }

    confirmDelete?.let { p ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Delete “${p.name}”?") },
            text = { Text("Photos you already watermarked aren't changed.") },
            confirmButton = {
                TextButton(onClick = {
                    WatermarkStore.delete(context, p)
                    confirmDelete = null
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Cancel") } },
        )
    }
}

// ===================================================================== editor

/** Create or edit a watermark preset. Saves it to [WatermarkStore]. */
@Composable
fun WatermarkEditorDialog(
    initial: WatermarkPreset?,
    onDismiss: () -> Unit,
    onSaved: (WatermarkPreset) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var draft by remember { mutableStateOf(initial ?: WatermarkPreset()) }
    var editingText by remember { mutableStateOf(false) }
    // A logo picked in this session that isn't stored yet
    var newLogo by remember { mutableStateOf<Bitmap?>(null) }
    var originalLogo by remember { mutableStateOf<Bitmap?>(null) }
    var savedLogo by remember { mutableStateOf<Bitmap?>(null) }
    var busy by remember { mutableStateOf(false) }
    var cutting by remember { mutableStateOf(false) }
    val fontVersion by Fonts.version.collectAsState()
    com.tim.gallery.ui.PortraitOnPhones()

    LaunchedEffect(Unit) {
        initial?.logo?.let { savedLogo = LayerLoader.load(context, it, 1024) }
    }
    LaunchedEffect(draft.text?.fontKey) { draft.text?.fontKey?.let { Fonts.load(context, it) } }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val bmp = withContext(Dispatchers.IO) { runCatching { MediaSaver.decodeUpright(context, uri, 1024) }.getOrNull() }
            if (bmp == null) {
                Toast.makeText(context, "Couldn't open that image", Toast.LENGTH_SHORT).show()
            } else {
                originalLogo = bmp
                newLogo = bmp
                draft = draft.copy(logo = PENDING_LOGO, text = null)
            }
        }
    }

    val logoBitmap = newLogo ?: savedLogo
    val assets = object : LayerAssets {
        override fun bitmap(uri: String): Bitmap? = logoBitmap
    }
    val chipColors = FilterChipDefaults.filterChipColors(
        labelColor = Color.White,
        selectedContainerColor = Color.White,
        selectedLabelColor = Color.Black,
    )

    fun save() {
        busy = true
        scope.launch {
            var preset = draft.copy(name = draft.name.ifBlank { "Watermark" })
            val logo = newLogo
            if (preset.logo == PENDING_LOGO) {
                val uri = logo?.let { WatermarkStore.saveLogo(context, Cutout.trimForSticker(it)) }
                if (uri == null) {
                    busy = false
                    Toast.makeText(context, "Couldn't save the logo", Toast.LENGTH_SHORT).show()
                    return@launch
                }
                preset = preset.copy(logo = uri)
            }
            WatermarkStore.save(context, preset)
            busy = false
            onSaved(preset)
        }
    }

    // Text, logo, position and size settings (beside the preview on wide screens, below it otherwise)
    val controls: @Composable ColumnScope.() -> Unit = {
                OutlinedTextField(
                    value = draft.name,
                    onValueChange = { draft = draft.copy(name = it) },
                    label = { Text("Name") },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedLabelColor = Color.White,
                        unfocusedLabelColor = Color.White.copy(alpha = 0.6f),
                        focusedBorderColor = Color.White.copy(alpha = 0.7f),
                        unfocusedBorderColor = Color.White.copy(alpha = 0.3f),
                        cursorColor = Color.White,
                    ),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                )

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(horizontal = 16.dp)) {
                    FilterChip(
                        selected = !draft.isLogo,
                        onClick = {
                            if (draft.isLogo) draft = draft.copy(logo = null, text = draft.text ?: TextLayer(0L, "© ${draft.name}"))
                        },
                        label = { Text("Text") },
                        colors = chipColors,
                    )
                    FilterChip(
                        selected = draft.isLogo,
                        onClick = {
                            if (!draft.isLogo) {
                                if (logoBitmap != null) {
                                    draft = draft.copy(logo = if (newLogo != null) PENDING_LOGO else initial?.logo, text = null)
                                }
                                else picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                            }
                        },
                        label = { Text("Logo / image") },
                        colors = chipColors,
                    )
                }

                if (!draft.isLogo) {
                    OutlinedButton(onClick = { editingText = true }, modifier = Modifier.padding(horizontal = 16.dp)) {
                        Icon(Icons.Outlined.Edit, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(
                            draft.text?.text?.let { "“$it” · change text & style" } ?: "Write text",
                            color = Color.White,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(horizontal = 16.dp)) {
                        OutlinedButton(onClick = {
                            picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                        }) { Text("Choose image", color = Color.White) }
                        val canCut = originalLogo != null
                        if (canCut) {
                            OutlinedButton(
                                enabled = !cutting,
                                onClick = {
                                    val src = originalLogo ?: return@OutlinedButton
                                    if (newLogo !== src) {
                                        newLogo = src
                                        return@OutlinedButton
                                    }
                                    cutting = true
                                    scope.launch {
                                        try {
                                            newLogo = Cutout.removeBackground(src)
                                        } catch (e: kotlinx.coroutines.CancellationException) {
                                            throw e
                                        } catch (e: CutoutException) {
                                            Toast.makeText(context, e.message, Toast.LENGTH_SHORT).show()
                                        } catch (e: Exception) {
                                            Toast.makeText(context, "Background removal failed", Toast.LENGTH_SHORT).show()
                                        }
                                        cutting = false
                                    }
                                },
                            ) {
                                if (cutting) CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
                                else Text(if (newLogo === originalLogo) "Remove background" else "Keep background", color = Color.White)
                            }
                        }
                    }
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier.padding(horizontal = 16.dp),
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Position", color = Color.White, style = MaterialTheme.typography.labelLarge)
                        Text(draft.anchor.label, color = Color.White.copy(alpha = 0.6f), style = MaterialTheme.typography.bodySmall)
                    }
                    AnchorGrid(draft.anchor, onPick = { draft = draft.copy(anchor = it) }, tint = Color.White)
                }
                LabeledSlider(
                    label = "Size",
                    value = draft.size,
                    range = 0.03f..1f,
                    valueText = "${(draft.size * 100).roundToInt()}",
                    onChange = { draft = draft.copy(size = it) },
                    onDone = {},
                )
                LabeledSlider(
                    label = "Distance from edge",
                    value = draft.margin,
                    range = 0f..0.2f,
                    valueText = "${(draft.margin * 500).roundToInt()}",
                    onChange = { draft = draft.copy(margin = it) },
                    onDone = {},
                )
                LabeledSlider(
                    label = "Opacity",
                    value = draft.opacity,
                    range = 0.05f..1f,
                    valueText = "${(draft.opacity * 100).roundToInt()}%",
                    onChange = { draft = draft.copy(opacity = it) },
                    onDone = {},
                )
    }
    val preview: @Composable (Modifier) -> Unit = { m ->
        Canvas(m.clip(RoundedCornerShape(12.dp))) {
            if (fontVersion < 0) return@Canvas
            drawWatermarkPreview(draft, assets)
        }
    }

    FullScreenDialog(onDismissRequest = { if (!busy) onDismiss() }) {
        Column(Modifier.fillMaxSize()) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(4.dp)) {
                IconButton(onClick = onDismiss, enabled = !busy) { Icon(Icons.Filled.Close, contentDescription = "Cancel", tint = Color.White) }
                Text(
                    if (initial == null) "New watermark" else "Edit watermark",
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                if (busy) {
                    CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp, modifier = Modifier.padding(horizontal = 16.dp).size(20.dp))
                } else {
                    TextButton(
                        enabled = (draft.text?.text?.isNotBlank() == true) || (draft.logo != null && logoBitmap != null),
                        onClick = { save() },
                    ) { Text("Save") }
                }
            }

            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                val spaceHeight = maxHeight
                if (maxWidth > maxHeight && maxWidth >= 600.dp) {
                    Row(Modifier.fillMaxSize()) {
                        Box(Modifier.weight(1f).fillMaxHeight().padding(16.dp), contentAlignment = Alignment.Center) {
                            preview(Modifier.aspectRatio(3f / 2f))
                        }
                        Column(
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier
                                .width(400.dp)
                                .fillMaxHeight()
                                .verticalScroll(rememberScrollState())
                                .padding(vertical = 12.dp),
                            content = controls,
                        )
                    }
                } else {
                    Column(Modifier.fillMaxSize()) {
                        // Never taller than half the space, so the settings stay in view
                        Box(
                            Modifier.fillMaxWidth().heightIn(max = spaceHeight * 0.5f).padding(horizontal = 16.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            preview(Modifier.aspectRatio(3f / 2f))
                        }
                        Column(
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier
                                .weight(1f)
                                .verticalScroll(rememberScrollState())
                                .padding(vertical = 12.dp),
                            content = controls,
                        )
                    }
                }
            }
        }

        if (editingText) {
            TextEditorDialog(
                initial = draft.text,
                placeholder = "© Your name",
                onDismiss = { editingText = false },
                onDone = {
                    draft = draft.copy(text = it.copy(id = 0L, cx = 0.5f, cy = 0.5f, scale = 1f, rotation = 0f, opacity = 1f))
                    editingText = false
                },
            )
        }
    }
}

/** Placeholder uri for a logo that's picked but not stored yet. */
private const val PENDING_LOGO = "pending:logo"

// ===================================================================== batch

/**
 * Adds a watermark to many photos at once. Each photo gets a new copy next to the original
 * (originals aren't changed). Videos are skipped.
 */
@Composable
fun BatchWatermarkDialog(items: List<GalleryItem>, onDismiss: () -> Unit, onFinished: (Int) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { WatermarkStore.init(context) }
    val presets by WatermarkStore.presets.collectAsState()
    var chosen by remember { mutableStateOf<String?>(null) }
    var creating by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<WatermarkPreset?>(null) }
    var progress by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    val photos = remember(items) { items.filter { !it.isVideo } }
    val assets = rememberLogoAssets(presets)
    val fontVersion by Fonts.version.collectAsState()
    LaunchedEffect(presets) { presets.mapNotNull { it.text?.fontKey }.distinct().forEach { Fonts.load(context, it) } }
    val skipped = items.size - photos.size

    LaunchedEffect(presets) { if (chosen == null || presets.none { it.id == chosen }) chosen = presets.firstOrNull()?.id }

    fun run() {
        val preset = presets.firstOrNull { it.id == chosen } ?: return
        progress = 0 to photos.size
        scope.launch {
            var ok = 0
            photos.forEachIndexed { i, item ->
                if (watermarkOne(context, item, preset)) ok++
                progress = (i + 1) to photos.size
            }
            progress = null
            onFinished(ok)
        }
    }

    AlertDialog(
        onDismissRequest = { if (progress == null) onDismiss() },
        title = { Text("Add watermark") },
        text = {
            val p = progress
            if (p != null) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Watermarking ${p.first} of ${p.second}…")
                    LinearProgressIndicator(
                        progress = { if (p.second == 0) 0f else p.first.toFloat() / p.second },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        "Saves a watermarked copy of ${photos.size} photo${if (photos.size == 1) "" else "s"} next to the originals." +
                            if (skipped > 0) " $skipped video${if (skipped == 1) "" else "s"} will be skipped." else "",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(4.dp))
                    Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                        presets.forEach { preset ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .selectable(selected = chosen == preset.id, onClick = { chosen = preset.id })
                                    .padding(vertical = 2.dp),
                            ) {
                                RadioButton(selected = chosen == preset.id, onClick = { chosen = preset.id })
                                Canvas(
                                    Modifier
                                        .width(60.dp)
                                        .height(40.dp)
                                        .clip(RoundedCornerShape(6.dp))
                                ) {
                                    if (fontVersion < 0) return@Canvas
                                    drawWatermarkPreview(preset, assets)
                                }
                                Spacer(Modifier.width(10.dp))
                                Text(preset.name, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                                IconButton(onClick = { editing = preset }) {
                                    Icon(Icons.Outlined.Edit, contentDescription = "Edit ${preset.name}")
                                }
                            }
                        }
                    }
                    TextButton(onClick = { creating = true }) {
                        Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(if (presets.isEmpty()) "Create a watermark" else "New watermark")
                    }
                }
            }
        },
        confirmButton = {
            if (progress == null) {
                TextButton(enabled = chosen != null && photos.isNotEmpty(), onClick = { run() }) { Text("Apply") }
            }
        },
        dismissButton = {
            if (progress == null) TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )

    if (creating || editing != null) {
        WatermarkEditorDialog(
            initial = editing,
            onDismiss = {
                creating = false
                editing = null
            },
            onSaved = {
                creating = false
                editing = null
                chosen = it.id
            },
        )
    }
}

/** Watermarks one photo and saves the copy. Returns true on success. */
private suspend fun watermarkOne(context: Context, item: GalleryItem, preset: WatermarkPreset): Boolean {
    val src = withContext(Dispatchers.IO) {
        runCatching { MediaSaver.decodeUpright(context, item.uri, EditRenderer.MAX_OUTPUT_PX) }.getOrNull()
    } ?: return false
    val logo = preset.logo?.let { LayerLoader.load(context, it, 2048) }
    if (preset.logo != null && logo == null) return false
    preset.text?.fontKey?.let { Fonts.load(context, it) }
    val assets = MapAssets(if (preset.logo != null && logo != null) mapOf(preset.logo to logo) else emptyMap())
    val out = FSize(src.width.toFloat(), src.height.toFloat())
    val state = EditState(layers = listOf(preset.toLayer(1L, out, assets)))
    val stem = item.name.substringBeforeLast('.').ifBlank { "photo" }
    // GIFs keep their animation: the watermark goes on every frame
    if (GifEdit.isGif(item.mimeType, item.name)) {
        val gif = GifEdit.open(context, item.uri)
        if (gif != null) {
            val file = runCatching { GifEdit.render(context, gif, state, assets, src.width, src.height) }
                .onFailure { if (it is kotlinx.coroutines.CancellationException) throw it }
                .getOrNull()
            src.recycle()
            file ?: return false
            return try {
                MediaSaver.saveFile(
                    context = context,
                    file = file,
                    displayName = "${stem}_wm_${System.currentTimeMillis() % 100000}.gif",
                    mime = "image/gif",
                    relativePath = item.relativePath,
                    dateTaken = item.dateMillis,
                ) != null
            } finally {
                file.delete()
            }
        }
    }
    val result = onGpu { EditRenderer.export(state, src, assets) } ?: return false
    val format = when (item.mimeType.lowercase()) {
        "image/png" -> Bitmap.CompressFormat.PNG
        "image/webp" -> Bitmap.CompressFormat.WEBP_LOSSY
        else -> Bitmap.CompressFormat.JPEG
    }
    val uri = MediaSaver.saveBitmap(
        context = context,
        bitmap = result,
        displayName = "${stem}_wm_${System.currentTimeMillis() % 100000}.${MediaSaver.extensionFor(format)}",
        format = format,
        relativePath = item.relativePath,
        dateTaken = item.dateMillis,
        exifFrom = item.uri,
    )
    src.recycle()
    result.recycle()
    return uri != null
}
