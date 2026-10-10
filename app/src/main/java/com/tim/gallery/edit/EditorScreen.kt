package com.tim.gallery.edit

import android.content.ContentUris
import android.graphics.Bitmap
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.material3.LinearProgressIndicator
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
import com.tim.gallery.video.GifDecoder
import java.util.concurrent.atomic.AtomicBoolean

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
fun EditorScreen(
    vm: GalleryViewModel,
    item: GalleryItem,
    onClose: () -> Unit,
    /** "Edit again": the saved edit to reopen on [item] (the original). */
    reopen: EditRecipe? = null,
    /** "Edit again": the copy that "Save" updates. */
    editingCopy: GalleryItem? = null,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val actions = rememberMediaActions(vm)
    val session = remember(item.id) { EditSession() }
    val history = remember(item.id) { EditHistory(reopen?.state ?: EditState()) }
    val state = history.state

    var tool by rememberSaveable { mutableStateOf(Tool.CROP) }
    var loadFailed by remember { mutableStateOf(false) }
    var straightening by remember { mutableStateOf(false) }
    var comparing by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var confirmOverwrite by remember { mutableStateOf(false) }
    var confirmDiscard by remember { mutableStateOf(false) }

    // GIFs: every frame gets the edit and it's saved as a GIF again
    var gif by remember { mutableStateOf<GifDecoder?>(null) }
    var animatedWebp by remember { mutableStateOf(false) }
    var gifProgress by remember { mutableStateOf<Float?>(null) }
    val gifCancel = remember { AtomicBoolean(false) }

    var cutoutBusy by remember { mutableStateOf(false) }
    var cutoutError by remember { mutableStateOf<String?>(null) }

    var selectedLayer by remember { mutableStateOf<Long?>(null) }
    var textEditing by remember { mutableStateOf<TextLayer?>(null) }
    var addingText by remember { mutableStateOf(false) }
    var stickerSheet by remember { mutableStateOf(false) }
    var emojiSheet by remember { mutableStateOf(false) }
    var shapeSheet by remember { mutableStateOf(false) }
    var watermarkSheet by remember { mutableStateOf(false) }

    // Color picker and eyedropper requests from the panels
    var colorRequest by remember { mutableStateOf<ColorRequest?>(null) }
    var eyedropRequest by remember { mutableStateOf<ColorRequest?>(null) }
    var eyedropSample by remember { mutableStateOf<Bitmap?>(null) }
    val colorTools = remember {
        ColorTools(
            pick = { initial, alpha, apply -> colorRequest = ColorRequest(initial, alpha, apply) },
            eyedrop = { apply -> eyedropRequest = ColorRequest(0, false, apply) },
        )
    }

    // ---- load the photo
    LaunchedEffect(item.id) {
        if (GifEdit.isGif(item.mimeType, item.name)) {
            gif = GifEdit.open(context, item.uri)
        } else if (item.mimeType.lowercase() == "image/webp") {
            animatedWebp = withContext(Dispatchers.IO) { isAnimatedWebp(context, item.uri) }
        }
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

    // ---- reopening an edit that removed the background: do it again
    LaunchedEffect(session.base) {
        if (session.base != null && reopen != null && reopen.state.cutout != CutoutMode.OFF && session.foreground == null) {
            cutoutBusy = true
            try {
                session.foreground = Cutout.removeBackground(session.base!!)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                cutoutError = "Couldn't redo the background removal."
            }
            cutoutBusy = false
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

    // ---- load the pictures and fonts that layers use (keyed on the sets, so dragging doesn't restart it)
    val neededPictures = remember(state.layers) { state.layers.filterIsInstance<StickerLayer>().map { it.uri }.toSet() }
    val neededFonts = remember(state.layers) { state.layers.filterIsInstance<TextLayer>().map { it.fontKey }.toSet() }
    LaunchedEffect(neededPictures) {
        neededPictures.filter { it !in session.stickers }
            .forEach { uri -> LayerLoader.load(context, uri)?.let { session.stickers[uri] = it } }
    }
    LaunchedEffect(neededFonts) {
        neededFonts.filter { Fonts.cached(it) == null }.forEach { Fonts.load(context, it) }
    }

    // ---- the eyedropper samples the photo (with color changes, without stickers or text)
    LaunchedEffect(eyedropRequest) {
        eyedropSample = null
        if (eyedropRequest == null) return@LaunchedEffect
        val base = session.base ?: return@LaunchedEffect
        val snap = history.state
        val sampleState = snap.copy(
            layers = emptyList(),
            geometry = snap.geometry.copy(crop = NRect.FULL, fillColor = android.graphics.Color.TRANSPARENT),
        )
        eyedropSample = onGpu {
            EditRenderer.export(sampleState, Cutout.scaleDown(base, EditSession.PREVIEW_PX), LayerAssets.NONE)
        } ?: session.preview
    }

    fun update(new: EditState, tag: String? = null) = history.set(new, tag)

    fun newLayerId(): Long = (history.state.layers.maxOfOrNull { it.id } ?: 0L) + 1L

    fun addLayer(layer: Layer) {
        update(history.state.copy(layers = history.state.layers + layer))
        selectedLayer = layer.id
    }

    fun outSize() = EditRenderer.outputSize(history.state.geometry, session.fullW, session.fullH)

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

    /** Gives fonts that are still downloading (or failed earlier) one more try. */
    suspend fun retryFonts(snapshot: EditState) {
        val missing = snapshot.layers.filterIsInstance<TextLayer>().map { it.fontKey }.distinct()
            .filter { Fonts.cached(it) == null }
            .filter { Fonts.load(context, it) == null }
        if (missing.isNotEmpty()) {
            Toast.makeText(
                context,
                "Couldn't download ${missing.joinToString { Fonts.label(it) }} — that text was saved in the standard bold font.",
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    /** The GIF being edited, when the edit can be saved as a GIF (background removal can't). */
    val gifSave: GifDecoder? = gif?.takeIf { state.cutout == CutoutMode.OFF }

    /** Applies the edit to every frame. Null if cancelled or it failed (the person is told). */
    suspend fun renderGif(source: GifDecoder): java.io.File? {
        val base = session.base ?: return null
        val snapshot = history.state
        retryFonts(snapshot)
        gifCancel.set(false)
        gifProgress = 0f
        return try {
            GifEdit.render(context, source, snapshot, session, base.width, base.height, gifCancel) { p ->
                scope.launch { if (gifProgress != null) gifProgress = p }
            }
        } catch (e: GifEdit.Cancelled) {
            null
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            Toast.makeText(context, "Couldn't save the GIF" + if (e is OutOfMemoryError) " — it's too big for this phone." else ".", Toast.LENGTH_LONG).show()
            null
        } finally {
            gifProgress = null
        }
    }

    suspend fun render(): Bitmap? {
        val src = session.source ?: return null
        val snapshot = history.state
        retryFonts(snapshot)
        return onGpu { EditRenderer.export(snapshot, src, session) }
    }

    fun saveGifCopy(source: GifDecoder) {
        saving = true
        scope.launch {
            val file = renderGif(source)
            val stem = item.name.substringBeforeLast('.').ifBlank { "animation" }
            val uri = file?.let { f ->
                try {
                    MediaSaver.saveFile(
                        context = context,
                        file = f,
                        displayName = "${stem}_edit_${System.currentTimeMillis()}.gif",
                        mime = "image/gif",
                        relativePath = item.relativePath,
                        dateTaken = item.dateMillis,
                    )
                } finally {
                    f.delete()
                }
            }
            saving = false
            if (file == null) return@launch // cancelled or already explained
            Toast.makeText(
                context,
                if (uri != null) "Saved a copy next to the original" else "Couldn't save the GIF",
                Toast.LENGTH_SHORT,
            ).show()
            if (uri != null) {
                EditRecipes.save(context, ContentUris.parseId(uri), EditRecipe(item.id, item.uri.toString(), history.state, item.modified, item.sizeBytes))
                vm.refresh()
                onClose()
            }
        }
    }

    fun saveCopy() {
        gifSave?.let {
            saveGifCopy(it)
            return
        }
        if (gif != null && (gif?.frameCount ?: 0) > 1) {
            Toast.makeText(context, "Background removal works on a single picture, so this is saved as a still.", Toast.LENGTH_LONG).show()
        }
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
                // Remember how it was made, for "Edit again"
                EditRecipes.save(context, ContentUris.parseId(uri), EditRecipe(item.id, item.uri.toString(), history.state, item.modified, item.sizeBytes))
                vm.refresh()
                onClose()
            }
        }
    }

    /** "Edit again": replaces the edited copy's picture and remembers the new edit. */
    fun updateCopy() {
        val target = editingCopy ?: return
        val source = gifSave
        if (source != null) {
            if (!GifEdit.isGif(target.mimeType, target.name)) {
                Toast.makeText(context, "Saved as a new GIF copy so it keeps its animation.", Toast.LENGTH_LONG).show()
                saveGifCopy(source)
                return
            }
            saving = true
            actions.withWriteAccess(listOf(target)) { granted ->
                if (!granted) {
                    saving = false
                    return@withWriteAccess
                }
                val file = renderGif(source)
                val ok = file != null && try {
                    MediaSaver.overwriteWithFile(context, target.uri, file)
                } finally {
                    file.delete()
                }
                saving = false
                if (file == null) return@withWriteAccess
                Toast.makeText(context, if (ok) "Edit updated" else "Couldn't save the GIF", Toast.LENGTH_SHORT).show()
                if (ok) {
                    EditRecipes.save(context, target.id, EditRecipe(item.id, item.uri.toString(), history.state, item.modified, item.sizeBytes))
                    vm.refresh()
                    onClose()
                }
            }
            return
        }
        if (state.needsAlpha && target.mimeType.lowercase() != "image/png") {
            Toast.makeText(context, "This edit has transparency, so it's saved as a new PNG copy.", Toast.LENGTH_LONG).show()
            saveCopy()
            return
        }
        saving = true
        actions.withWriteAccess(listOf(target)) { granted ->
            if (!granted) {
                saving = false
                return@withWriteAccess
            }
            val bmp = render()
            val ok = bmp != null && MediaSaver.overwrite(context, target.uri, bmp, MediaSaver.formatFor(target.mimeType))
            saving = false
            Toast.makeText(context, if (ok) "Edit updated" else "Couldn't save the photo", Toast.LENGTH_SHORT).show()
            if (ok) {
                EditRecipes.save(context, target.id, EditRecipe(item.id, item.uri.toString(), history.state, item.modified, item.sizeBytes))
                vm.refresh()
                onClose()
            }
        }
    }

    val overwriteBlocker: String? = when {
        gif != null && state.cutout != CutoutMode.OFF ->
            "Background removal can't be saved into a GIF. Save a copy instead (it'll be a still picture)."
        gif != null -> null
        !MediaSaver.canOverwrite(item.mimeType) -> "This file type can only be saved as a copy."
        state.needsAlpha && item.mimeType.lowercase() == "image/jpeg" ->
            "JPEG can't hold transparency. Save a copy instead (it'll be a PNG)."
        else -> null
    }

    fun overwrite() {
        saving = true
        val source = gifSave
        actions.withWriteAccess(listOf(item)) { granted ->
            if (!granted) {
                saving = false
                return@withWriteAccess
            }
            val ok = if (source != null) {
                val file = renderGif(source)
                if (file == null) {
                    saving = false
                    return@withWriteAccess
                }
                try {
                    MediaSaver.overwriteWithFile(context, item.uri, file)
                } finally {
                    file.delete()
                }
            } else {
                val bmp = render()
                bmp != null && MediaSaver.overwrite(context, item.uri, bmp, MediaSaver.formatFor(item.mimeType))
            }
            saving = false
            Toast.makeText(context, if (ok) "Original replaced" else "Couldn't save the photo", Toast.LENGTH_SHORT).show()
            if (ok) {
                // The pixels a saved edit started from are gone now
                EditRecipes.delete(context, item.id)
                vm.refresh()
                onClose()
            }
        }
    }

    fun close() {
        if (history.changed) confirmDiscard = true else onClose()
    }

    com.tim.gallery.ui.SystemBarIcons(darkBackground = true)

    BackHandler {
        if (eyedropRequest != null) eyedropRequest = null else close()
    }

    val selected = state.layers.firstOrNull { it.id == selectedLayer }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
    Column(
        Modifier
            .fillMaxSize()
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
                if (editingCopy != null) {
                    Button(onClick = { updateCopy() }, enabled = session.source != null && history.changed) { Text("Save") }
                } else {
                    Button(onClick = { saveCopy() }, enabled = session.source != null && history.changed) { Text("Save copy") }
                }
                Box {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Filled.MoreVert, contentDescription = "More", tint = Color.White)
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        if (editingCopy != null) {
                            DropdownMenuItem(
                                text = { Text("Save as a new copy") },
                                enabled = session.source != null,
                                onClick = {
                                    menuOpen = false
                                    saveCopy()
                                },
                            )
                        } else {
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
        }

        val pictureArea: @Composable (Modifier) -> Unit = { m ->
            // ---------------------------------------------------- picture
            Box(m.padding(horizontal = 12.dp, vertical = 8.dp)) {
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
        }
        val toolPanel: @Composable () -> Unit = {
            // GIFs: say what will happen to the animation
            val frames = gif?.frameCount ?: 0
            val note = when {
                session.preview == null -> null
                frames > 1 && state.cutout != CutoutMode.OFF -> "Background removal saves a still picture"
                frames > 1 -> "GIF · edits apply to all $frames frames"
                animatedWebp -> "Animated WebP · edits save a still picture"
                else -> null
            }
            if (note != null) {
                Text(
                    note,
                    color = Color.White.copy(alpha = 0.75f),
                    style = MaterialTheme.typography.labelMedium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp),
                )
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
                            colors = colorTools,
                        )
                        Tool.ADJUST -> AdjustPanel(state, onChange = { s, tag -> update(s, tag) }, onDone = { history.endGesture() })
                        Tool.FILTERS -> FiltersPanel(
                            session,
                            state,
                            onChange = { s, tag -> update(s, tag) },
                            onDone = { history.endGesture() },
                            colors = colorTools,
                        )
                        Tool.CUTOUT -> CutoutPanel(
                            state = state,
                            hasForeground = session.foreground != null,
                            busy = cutoutBusy,
                            error = cutoutError,
                            onRemoveBackground = { removeBackground() },
                            onChange = { update(it) },
                            onSaveSticker = { saveCutoutAsSticker() },
                            colors = colorTools,
                        )
                        Tool.MARKUP -> MarkupPanel(
                            selected = selected,
                            onAddText = { addingText = true },
                            onAddSticker = { stickerSheet = true },
                            onAddEmoji = { emojiSheet = true },
                            onAddShape = { shapeSheet = true },
                            onAddWatermark = { watermarkSheet = true },
                            onEditText = { textEditing = it },
                            onChange = { layer, tag -> update(history.state.updateLayer(layer.id) { layer }, tag) },
                            onDone = { history.endGesture() },
                            onAlign = { layer, anchor ->
                                val (cx, cy) = anchorPosition(layer, anchor, 0.04f, outSize(), session)
                                update(history.state.updateLayer(layer.id) { it.moved(cx, cy, it.scale, it.rotation) })
                            },
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
                                addLayer(copy)
                            },
                            onDelete = { layer ->
                                update(state.copy(layers = state.layers.filterNot { it.id == layer.id }))
                                selectedLayer = null
                            },
                            onDeselect = { selectedLayer = null },
                            colors = colorTools,
                        )
                    }
                }
            }
        }
        val toolTabs: @Composable () -> Unit = {
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

        // Wide screens (tablets, unfolded Fold in landscape): tools beside the picture
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            if (maxWidth > maxHeight && maxWidth >= 600.dp) {
                Row(Modifier.fillMaxSize()) {
                    pictureArea(Modifier.weight(1f).fillMaxHeight())
                    Column(
                        Modifier
                            .width(380.dp)
                            .fillMaxHeight()
                            .verticalScroll(rememberScrollState()),
                    ) {
                        toolTabs()
                        toolPanel()
                    }
                }
            } else {
                Column(Modifier.fillMaxSize()) {
                    pictureArea(Modifier.weight(1f).fillMaxWidth())
                    toolPanel()
                    toolTabs()
                }
            }
        }
    }

    eyedropRequest?.let { req ->
        Box(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            EyedropperOverlay(
                sample = eyedropSample,
                onPick = { c ->
                    eyedropRequest = null
                    ColorRecents.add(c)
                    update(req.apply(history.state, c))
                },
                onCancel = { eyedropRequest = null },
            )
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
                    addLayer(result.copy(id = newLayerId(), cx = 0.5f, cy = 0.5f))
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
                addLayer(StickerLayer(id = newLayerId(), uri = uri))
            },
        )
    }

    if (emojiSheet) {
        EmojiSheet(
            onDismiss = { emojiSheet = false },
            onPick = { e ->
                emojiSheet = false
                addLayer(StickerLayer(id = newLayerId(), uri = "emoji:$e", scale = 0.25f))
            },
        )
    }

    if (shapeSheet) {
        ShapeSheet(
            onDismiss = { shapeSheet = false },
            onPick = { kind ->
                shapeSheet = false
                addLayer(ShapeLayer.default(newLayerId(), kind))
            },
        )
    }

    if (watermarkSheet) {
        WatermarkSheet(
            onDismiss = { watermarkSheet = false },
            onApply = { preset ->
                watermarkSheet = false
                scope.launch {
                    preset.logo?.let { uri ->
                        if (uri !in session.stickers) LayerLoader.load(context, uri)?.let { session.stickers[uri] = it }
                    }
                    preset.text?.fontKey?.let { Fonts.load(context, it) }
                    addLayer(preset.toLayer(newLayerId(), outSize(), session))
                }
            },
        )
    }

    colorRequest?.let { req ->
        ColorPickerDialog(
            initial = req.initial,
            allowAlpha = req.allowAlpha,
            onDismiss = { colorRequest = null },
            onPick = { c ->
                colorRequest = null
                update(req.apply(history.state, c))
            },
            onEyedropper = {
                colorRequest = null
                eyedropRequest = req
            },
        )
    }

    gifProgress?.let { p ->
        AlertDialog(
            onDismissRequest = {},
            title = { Text("Saving GIF") },
            text = {
                Column {
                    LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth())
                    val total = gif?.frameCount ?: 0
                    if (total > 0) {
                        Text(
                            "Frame ${(p * total).toInt().coerceIn(0, total)} of $total",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { gifCancel.set(true) }) { Text("Cancel") } },
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

/** A pending color choice: where it starts, and how to apply it to the latest edit state. */
private class ColorRequest(
    val initial: Int,
    val allowAlpha: Boolean,
    val apply: (EditState, Int) -> EditState,
)

/** True for WebP files that hold an animation (Android can't write those back). */
private fun isAnimatedWebp(context: android.content.Context, uri: android.net.Uri): Boolean = runCatching {
    context.contentResolver.openInputStream(uri)?.use { input ->
        val head = ByteArray(21)
        var n = 0
        while (n < head.size) {
            val r = input.read(head, n, head.size - n)
            if (r <= 0) break
            n += r
        }
        n == head.size && String(head, 0, 4, Charsets.ISO_8859_1) == "RIFF" &&
            String(head, 8, 4, Charsets.ISO_8859_1) == "WEBP" &&
            String(head, 12, 4, Charsets.ISO_8859_1) == "VP8X" &&
            (head[20].toInt() and 0x02) != 0
    } ?: false
}.getOrDefault(false)
