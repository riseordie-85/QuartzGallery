package com.tim.gallery.edit

import android.graphics.Bitmap
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Redo
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material.icons.outlined.Compare
import androidx.compose.material.icons.outlined.ContentCut
import androidx.compose.material.icons.outlined.Crop
import androidx.compose.material.icons.outlined.EmojiEmotions
import androidx.compose.material.icons.outlined.PhotoFilter
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.tim.gallery.GalleryViewModel
import com.tim.gallery.data.Cutout
import com.tim.gallery.data.CutoutException
import com.tim.gallery.data.GalleryItem
import com.tim.gallery.data.MediaSaver
import com.tim.gallery.ui.rememberMediaActions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class Tool(val label: String, val icon: ImageVector) {
    CROP("Crop", Icons.Outlined.Crop),
    ADJUST("Adjust", Icons.Outlined.Tune),
    FILTERS("Filters", Icons.Outlined.PhotoFilter),
    CUTOUT("Cutout", Icons.Outlined.ContentCut),
    MARKUP("Markup", Icons.Outlined.EmojiEmotions),
}

/**
 * Photo editor: crop & straighten, adjustments, filters, background removal, text & stickers.
 * Saves a copy next to the original, or (optionally) overwrites the original.
 */
@Composable
fun EditorScreen(vm: GalleryViewModel, item: GalleryItem, onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val actions = rememberMediaActions(vm)
    val session = remember(item.id) { EditSession() }
    val history = remember(item.id) { EditHistory(EditState()) }
    val state = history.state

    var tool by rememberSaveable { mutableStateOf(Tool.CROP) }
    var loadFailed by remember { mutableStateOf(false) }
    var straightening by remember { mutableStateOf(false) }
    var comparing by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var confirmOverwrite by remember { mutableStateOf(false) }
    var confirmDiscard by remember { mutableStateOf(false) }

    var cutoutBusy by remember { mutableStateOf(false) }
    var cutoutError by remember { mutableStateOf<String?>(null) }

    var selectedLayer by remember { mutableStateOf<Long?>(null) }
    var textEditing by remember { mutableStateOf<TextLayer?>(null) }
    var addingText by remember { mutableStateOf(false) }
    var stickerSheet by remember { mutableStateOf(false) }

    // ---- load the photo
    LaunchedEffect(item.id) {
        val base = withContext(Dispatchers.IO) {
            runCatching { MediaSaver.decodeUpright(context, item.uri, 4096) }.getOrNull()
        }
        if (base == null) {
            loadFailed = true
            return@LaunchedEffect
        }
        session.base = base
        session.thumb = withContext(Dispatchers.Default) {
            squareThumb(Cutout.scaleDown(base, EditSession.PREVIEW_PX), EditSession.THUMB_PX)
        }
    }

    // ---- rebuild the source whenever the cutout option changes
    LaunchedEffect(session.base, session.foreground, state.cutout, state.cutoutColor) {
        val base = session.base ?: return@LaunchedEffect
        val mode = state.cutout
        val color = state.cutoutColor
        val full = onGpu { composeCutout(session, mode, color) ?: base } ?: base
        val preview = withContext(Dispatchers.Default) { Cutout.scaleDown(full, EditSession.PREVIEW_PX) }
        session.source = full
        session.preview = preview
    }

    // ---- load sticker images that layers use
    LaunchedEffect(state.layers) {
        state.layers.filterIsInstance<StickerLayer>().map { it.uri }.distinct()
            .filter { it !in session.stickers }
            .forEach { uri ->
                val bmp = withContext(Dispatchers.IO) {
                    runCatching { MediaSaver.decodeUpright(context, Uri.parse(uri), 1024) }.getOrNull()
                }
                if (bmp != null) session.stickers[uri] = bmp
            }
    }

    fun update(new: EditState, tag: String? = null) = history.set(new, tag)

    fun newLayerId(): Long = (state.layers.maxOfOrNull { it.id } ?: 0L) + 1L

    fun removeBackground() {
        val base = session.base ?: return
        cutoutBusy = true
        cutoutError = null
        scope.launch {
            try {
                session.foreground = Cutout.removeBackground(base)
                update(history.state.copy(cutout = CutoutMode.TRANSPARENT))
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: CutoutException) {
                cutoutError = e.message
            } catch (e: Exception) {
                cutoutError = "Background removal failed."
            }
            cutoutBusy = false
        }
    }

    fun saveCutoutAsSticker() {
        val fg = session.foreground ?: return
        scope.launch {
            val uri = saveSticker(context, fg)
            if (uri != null) vm.refresh()
            Toast.makeText(context, if (uri != null) "Saved to stickers" else "Couldn't save the sticker", Toast.LENGTH_SHORT).show()
        }
    }

    suspend fun render(): Bitmap? {
        val src = session.source ?: return null
        val snapshot = history.state
        val stickers = session.stickerLookup()
        return onGpu { EditRenderer.export(snapshot, src, stickers) }
    }

    fun saveCopy() {
        saving = true
        scope.launch {
            val bmp = render()
            val format = if (state.needsAlpha || item.mimeType == "image/png") {
                Bitmap.CompressFormat.PNG
            } else {
                Bitmap.CompressFormat.JPEG
            }
            val stem = item.name.substringBeforeLast('.').ifBlank { "photo" }
            val uri = bmp?.let {
                MediaSaver.saveBitmap(
                    context = context,
                    bitmap = it,
                    displayName = "${stem}_edit_${System.currentTimeMillis()}.${MediaSaver.extensionFor(format)}",
                    format = format,
                    relativePath = item.relativePath,
                    dateTaken = item.dateMillis,
                    exifFrom = item.uri,
                )
            }
            saving = false
            Toast.makeText(
                context,
                if (uri != null) "Saved a copy next to the original" else "Couldn't save the photo",
                Toast.LENGTH_SHORT,
            ).show()
            if (uri != null) {
                vm.refresh()
                onClose()
            }
        }
    }

    val overwriteBlocker: String? = when {
        !MediaSaver.canOverwrite(item.mimeType) -> "This file type can only be saved as a copy."
        state.needsAlpha && item.mimeType.lowercase() == "image/jpeg" ->
            "JPEG can't hold transparency. Save a copy instead (it'll be a PNG)."
        else -> null
    }

    fun overwrite() {
        saving = true
        actions.withWriteAccess(listOf(item)) { granted ->
            if (!granted) {
                saving = false
                return@withWriteAccess
            }
            val bmp = render()
            val ok = bmp != null && MediaSaver.overwrite(context, item.uri, bmp, MediaSaver.formatFor(item.mimeType))
            saving = false
            Toast.makeText(context, if (ok) "Original replaced" else "Couldn't save the photo", Toast.LENGTH_SHORT).show()
            if (ok) {
                vm.refresh()
                onClose()
            }
        }
    }

    fun close() {
        if (history.changed) confirmDiscard = true else onClose()
    }

    BackHandler { close() }

    val selected = state.layers.firstOrNull { it.id == selectedLayer }

    Column(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        // ---------------------------------------------------- top bar
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(4.dp)) {
            IconButton(onClick = { close() }) { Icon(Icons.Filled.Close, contentDescription = "Close", tint = Color.White) }
            IconButton(onClick = { history.undo() }, enabled = history.canUndo) {
                Icon(Icons.Filled.Undo, contentDescription = "Undo", tint = Color.White.copy(alpha = if (history.canUndo) 1f else 0.3f))
            }
            IconButton(onClick = { history.redo() }, enabled = history.canRedo) {
                Icon(Icons.Filled.Redo, contentDescription = "Redo", tint = Color.White.copy(alpha = if (history.canRedo) 1f else 0.3f))
            }
            Box(Modifier.weight(1f))
            if (saving) {
                CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp, modifier = Modifier.padding(horizontal = 16.dp).size(22.dp))
            } else {
                Button(onClick = { saveCopy() }, enabled = session.source != null && history.changed) { Text("Save copy") }
                Box {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Filled.MoreVert, contentDescription = "More", tint = Color.White)
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("Overwrite original") },
                            enabled = session.source != null && history.changed,
                            onClick = {
                                menuOpen = false
                                confirmOverwrite = true
                            },
                        )
                    }
                }
            }
        }

        // ---------------------------------------------------- picture
        Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
            when {
                loadFailed -> Text(
                    "Couldn't open this photo for editing.",
                    color = Color.White,
                    modifier = Modifier.align(Alignment.Center),
                )
                session.preview == null -> CircularProgressIndicator(color = Color.White, modifier = Modifier.align(Alignment.Center))
                tool == Tool.CROP -> CropView(
                    session = session,
                    state = state,
                    straightening = straightening,
                    onCropChange = { update(state.copy(geometry = state.geometry.copy(crop = it)), "crop") },
                    onGestureEnd = { history.endGesture() },
                )
                else -> OutputView(
                    session = session,
                    state = if (comparing) state.withoutColor() else state,
                    markup = tool == Tool.MARKUP,
                    selectedId = if (tool == Tool.MARKUP) selectedLayer else null,
                    onSelect = { selectedLayer = it },
                    onLayerChange = { changed ->
                        update(state.copy(layers = state.layers.map { if (it.id == changed.id) changed else it }), "layer-${changed.id}")
                    },
                    onGestureEnd = { history.endGesture() },
                    onEditText = { textEditing = it },
                    onDelete = { id ->
                        update(state.copy(layers = state.layers.filterNot { it.id == id }))
                        selectedLayer = null
                    },
                )
            }

            // Hold to see the photo without color changes
            if (session.preview != null && (tool == Tool.ADJUST || tool == Tool.FILTERS) &&
                (state.adjust.isNotEmpty() || state.filter != FilterPreset.NONE)
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.5f))
                        .pointerInput(Unit) {
                            awaitEachGesture {
                                awaitFirstDown()
                                comparing = true
                                waitForUpOrCancellation()
                                comparing = false
                            }
                        },
                ) {
                    Icon(Icons.Outlined.Compare, contentDescription = "Hold to compare", tint = Color.White)
                }
            }
        }

        // ---------------------------------------------------- tool panel
        Box(Modifier.fillMaxWidth().heightIn(min = 150.dp).padding(vertical = 4.dp), contentAlignment = Alignment.Center) {
            if (session.preview != null) {
                when (tool) {
                    Tool.CROP -> CropPanel(
                        state = state,
                        onGeometry = { g, tag -> update(state.copy(geometry = g), tag) },
                        onDone = { history.endGesture() },
                        onStraightening = { straightening = it },
                        w = session.fullW,
                        h = session.fullH,
                    )
                    Tool.ADJUST -> AdjustPanel(state, onChange = { s, tag -> update(s, tag) }, onDone = { history.endGesture() })
                    Tool.FILTERS -> FiltersPanel(session, state, onChange = { s, tag -> update(s, tag) }, onDone = { history.endGesture() })
                    Tool.CUTOUT -> CutoutPanel(
                        state = state,
                        hasForeground = session.foreground != null,
                        busy = cutoutBusy,
                        error = cutoutError,
                        onRemoveBackground = { removeBackground() },
                        onChange = { update(it) },
                        onSaveSticker = { saveCutoutAsSticker() },
                    )
                    Tool.MARKUP -> MarkupPanel(
                        selected = selected,
                        onAddText = { addingText = true },
                        onAddSticker = { stickerSheet = true },
                        onEditText = { textEditing = it },
                        onBringToFront = { layer ->
                            update(state.copy(layers = state.layers.filterNot { it.id == layer.id } + layer))
                        },
                        onDuplicate = { layer ->
                            val copy = layer.withId(newLayerId()).moved(
                                (layer.cx + 0.05f).coerceAtMost(1f),
                                (layer.cy + 0.05f).coerceAtMost(1f),
                                layer.scale,
                                layer.rotation,
                            )
                            update(state.copy(layers = state.layers + copy))
                            selectedLayer = copy.id
                        },
                        onDelete = { layer ->
                            update(state.copy(layers = state.layers.filterNot { it.id == layer.id }))
                            selectedLayer = null
                        },
                    )
                }
            }
        }

        // ---------------------------------------------------- tool tabs
        Row(horizontalArrangement = Arrangement.SpaceEvenly, modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
            Tool.entries.forEach { t ->
                val active = t == tool
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (active) Color.White.copy(alpha = 0.14f) else Color.Transparent)
                        .clickable {
                            tool = t
                            if (t != Tool.MARKUP) selectedLayer = null
                        }
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                ) {
                    Icon(t.icon, contentDescription = t.label, tint = if (active) Color.White else Color.White.copy(alpha = 0.6f))
                    Text(
                        t.label,
                        color = if (active) Color.White else Color.White.copy(alpha = 0.6f),
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        }
    }

    // ---------------------------------------------------- dialogs
    if (addingText || textEditing != null) {
        val editing = textEditing
        TextEditorDialog(
            initial = editing,
            onDismiss = {
                addingText = false
                textEditing = null
            },
            onDone = { result ->
                if (editing != null) {
                    update(state.copy(layers = state.layers.map { if (it.id == editing.id) result else it }))
                } else {
                    val layer = result.copy(id = newLayerId(), cx = 0.5f, cy = 0.5f)
                    update(state.copy(layers = state.layers + layer))
                    selectedLayer = layer.id
                }
                addingText = false
                textEditing = null
            },
        )
    }

    if (stickerSheet) {
        StickerSheet(
            vm = vm,
            onDismiss = { stickerSheet = false },
            onPick = { uri ->
                stickerSheet = false
                val layer = StickerLayer(id = newLayerId(), uri = uri.toString())
                update(state.copy(layers = state.layers + layer))
                selectedLayer = layer.id
            },
        )
    }

    if (confirmOverwrite) {
        AlertDialog(
            onDismissRequest = { confirmOverwrite = false },
            title = { Text("Overwrite the original?") },
            text = {
                Text(
                    overwriteBlocker
                        ?: "The original photo will be replaced with this edit. This can't be undone.",
                    textAlign = TextAlign.Start,
                )
            },
            confirmButton = {
                if (overwriteBlocker == null) {
                    TextButton(onClick = {
                        confirmOverwrite = false
                        overwrite()
                    }) { Text("Overwrite") }
                } else {
                    TextButton(onClick = {
                        confirmOverwrite = false
                        saveCopy()
                    }) { Text("Save copy") }
                }
            },
            dismissButton = { TextButton(onClick = { confirmOverwrite = false }) { Text("Cancel") } },
        )
    }

    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text("Discard edits?") },
            text = { Text("Your changes haven't been saved.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDiscard = false
                    onClose()
                }) { Text("Discard") }
            },
            dismissButton = { TextButton(onClick = { confirmDiscard = false }) { Text("Keep editing") } },
        )
    }
}
