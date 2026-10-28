package com.tim.gallery

import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.SaveAlt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.tim.gallery.data.AlbumOps
import com.tim.gallery.data.GalleryItem
import com.tim.gallery.data.MediaSaver
import com.tim.gallery.edit.EditorScreen
import com.tim.gallery.ui.VideoPlayerScreen
import com.tim.gallery.ui.ZoomableImage
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * Photos and videos another app hands over: "Open with", "Edit with", or Share (one or several).
 * They aren't in the gallery yet, so they open in their own small screens with Save and Edit.
 */
sealed class Incoming {
    /** One item to view; [edit] opens it straight in the editor. */
    data class One(val item: GalleryItem, val edit: Boolean) : Incoming()

    /** Several shared items. */
    data class Many(val items: List<GalleryItem>) : Incoming()

    companion object {
        /** What [intent] hands over, or null when it's just a normal launch (or nothing usable). */
        fun from(intent: Intent?, resolver: ContentResolver): Incoming? {
            intent ?: return null
            val uris: List<Uri> = when (intent.action) {
                Intent.ACTION_VIEW, Intent.ACTION_EDIT -> listOfNotNull(intent.data)
                Intent.ACTION_SEND -> listOfNotNull(
                    intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java) ?: intent.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.uri,
                )
                Intent.ACTION_SEND_MULTIPLE -> intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
                    ?: intent.clipData?.let { c -> (0 until c.itemCount).mapNotNull { c.getItemAt(it).uri } }
                    ?: emptyList()
                else -> return null
            }
            val items = uris.mapNotNull { external(it, if (uris.size == 1) intent.type else null, resolver) }
            return when {
                items.isEmpty() -> null
                items.size == 1 -> One(items[0], edit = intent.action == Intent.ACTION_EDIT && !items[0].isVideo)
                else -> Many(items)
            }
        }

        /** A stand-in gallery item for another app's file (negative id = not one of ours). */
        private fun external(uri: Uri, typeHint: String?, resolver: ContentResolver): GalleryItem? {
            val mime = runCatching { resolver.getType(uri) }.getOrNull()
                ?: typeHint?.takeIf { !it.endsWith("/*") }
                ?: guessMime(uri.lastPathSegment)
                ?: typeHint
                ?: return null
            if (!mime.startsWith("image/") && !mime.startsWith("video/")) return null
            var name: String? = null
            var size = 0L
            runCatching {
                resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                    if (c.moveToFirst()) {
                        name = c.getString(0)
                        if (!c.isNull(1)) size = c.getLong(1)
                    }
                }
            }
            val now = System.currentTimeMillis()
            return GalleryItem(
                id = -1L - (abs(uri.toString().hashCode().toLong())),
                uri = uri,
                name = name ?: uri.lastPathSegment?.substringAfterLast('/') ?: if (mime.startsWith("video/")) "Video" else "Photo",
                mimeType = mime,
                isVideo = mime.startsWith("video/"),
                dateMillis = now,
                durationMs = 0L,
                width = 0,
                height = 0,
                sizeBytes = size,
                album = "",
                relativePath = MediaSaver.FOLDER_LABEL + "/",
                isFavorite = false,
                modified = 0L,
            )
        }

        private fun guessMime(name: String?): String? {
            val ext = name?.substringAfterLast('.', "")?.lowercase().orEmpty()
            return when (ext) {
                "jpg", "jpeg" -> "image/jpeg"
                "png" -> "image/png"
                "webp" -> "image/webp"
                "gif" -> "image/gif"
                "heic", "heif" -> "image/heic"
                "avif" -> "image/avif"
                "mp4", "m4v" -> "video/mp4"
                "webm" -> "video/webm"
                "mkv" -> "video/x-matroska"
                "3gp" -> "video/3gpp"
                "mov" -> "video/quicktime"
                else -> null
            }
        }
    }
}

@Composable
fun IncomingRoot(incoming: Incoming, onClose: () -> Unit) {
    val vm: GalleryViewModel = viewModel()
    when (incoming) {
        is Incoming.One -> {
            var editing by remember { mutableStateOf(incoming.edit) }
            if (editing) {
                // Straight to the editor ("Edit with") closes the app when done; from the viewer, back to it
                EditorScreen(vm = vm, item = incoming.item, onClose = { if (incoming.edit) onClose() else editing = false })
            } else {
                IncomingViewer(incoming.item, onEdit = { editing = true }, onClose = onClose)
            }
        }
        is Incoming.Many -> IncomingMany(vm, incoming.items, onClose)
    }
}

/** One photo or video from another app, with Save to gallery (and Edit for photos). */
@Composable
private fun IncomingViewer(item: GalleryItem, onEdit: (() -> Unit)?, onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var saving by remember { mutableStateOf(false) }
    var saved by remember { mutableStateOf(false) }
    fun save() {
        if (saving || saved) return
        saving = true
        scope.launch {
            val uri = MediaSaver.saveFromUri(context, item.uri, item.name, item.mimeType)
            saving = false
            saved = uri != null
            Toast.makeText(context, if (uri != null) "Saved to Pictures/Gallery" else "Couldn't save it", Toast.LENGTH_SHORT).show()
        }
    }
    val actions: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit = {
        if (saving) {
            CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp, modifier = Modifier.padding(12.dp).size(22.dp))
        } else {
            IconButton(onClick = { save() }, enabled = !saved) {
                Icon(Icons.Outlined.SaveAlt, contentDescription = "Save to gallery", tint = Color.White.copy(alpha = if (saved) 0.4f else 1f))
            }
        }
        if (onEdit != null && !item.isVideo) {
            IconButton(onClick = onEdit) { Icon(Icons.Outlined.Edit, contentDescription = "Edit", tint = Color.White) }
        }
    }
    BackHandler { onClose() }
    if (item.isVideo) {
        VideoPlayerScreen(uri = item.uri, title = item.name, onBack = onClose, actions = actions)
        return
    }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        val animated = com.tim.gallery.ui.rememberAnimatedImage(item.uri, item.mimeType, item.name)
        ZoomableImage(
            model = item.uri,
            content = animated?.let { d -> { m -> com.tim.gallery.ui.AnimatedDrawableView(d, playing = true, modifier = m) } },
            contentDescription = item.name,
            isCurrent = true,
            onTap = {},
            onZoomChange = {},
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.6f), Color.Transparent)))
                .statusBarsPadding()
                .padding(4.dp),
        ) {
            IconButton(onClick = onClose) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
            }
            Text(
                item.name,
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            actions()
        }
    }
}

/** Several shared items: look through them, and save them all to an album. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun IncomingMany(vm: GalleryViewModel, items: List<GalleryItem>, onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var open by remember { mutableStateOf<GalleryItem?>(null) }
    var choosing by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf<Int?>(null) }
    val albums by vm.albums.collectAsStateWithLifecycle()

    open?.let { item ->
        IncomingViewer(item, onEdit = null, onClose = { open = null })
        return
    }

    fun saveAll(path: String, label: String) {
        progress = 0
        scope.launch {
            var ok = 0
            items.forEachIndexed { i, item ->
                if (MediaSaver.saveFromUri(context, item.uri, item.name, item.mimeType, path) != null) ok++
                progress = i + 1
            }
            progress = null
            Toast.makeText(
                context,
                if (ok == items.size) "Saved ${items.size} to $label" else "Saved $ok of ${items.size} to $label",
                Toast.LENGTH_LONG,
            ).show()
            if (ok > 0) onClose()
        }
    }

    BackHandler { onClose() }
    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Close") } },
                title = { Text("${items.size} shared items") },
            )
        },
        bottomBar = {
            Box(Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp), contentAlignment = Alignment.Center) {
                val p = progress
                if (p != null) {
                    Text("Saving $p of ${items.size}…")
                } else {
                    Button(onClick = { choosing = true }, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Outlined.SaveAlt, contentDescription = null)
                        Text("  Save all to an album")
                    }
                }
            }
        },
    ) { padding ->
        LazyVerticalGrid(
            columns = GridCells.Adaptive(110.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
            modifier = Modifier.fillMaxSize().padding(padding),
        ) {
            itemsIndexed(items) { _, item ->
                Box(
                    Modifier
                        .aspectRatio(1f)
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .clickable { open = item },
                    contentAlignment = Alignment.Center,
                ) {
                    AsyncImage(
                        model = item.uri,
                        contentDescription = item.name,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                    if (item.isVideo) {
                        Icon(
                            Icons.Filled.PlayArrow,
                            contentDescription = "Video",
                            tint = Color.White,
                            modifier = Modifier.size(36.dp).background(Color.Black.copy(alpha = 0.45f), CircleShape),
                        )
                    }
                }
            }
        }
    }

    if (choosing) {
        val targets = remember(albums) {
            listOf(MediaSaver.FOLDER_LABEL + "/" to "Gallery (Pictures/Gallery)") +
                albums.filter { !it.key.startsWith("@") && !AlbumOps.isStickers(it.key) && it.key.trimEnd('/') != MediaSaver.FOLDER_LABEL }
                    .map { it.key to it.name }
        }
        AlertDialog(
            onDismissRequest = { choosing = false },
            title = { Text("Save to which album?") },
            text = {
                LazyColumn(Modifier.fillMaxWidth()) {
                    items(targets) { (path, label) ->
                        ListItem(
                            headlineContent = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            supportingContent = if (path.startsWith(MediaSaver.FOLDER_LABEL)) null else ({ Text(path.trimEnd('/'), maxLines = 1, overflow = TextOverflow.Ellipsis) }),
                            modifier = Modifier.clickable {
                                choosing = false
                                saveAll(path, label.substringBefore(" ("))
                            },
                        )
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { choosing = false }) { Text("Cancel") } },
        )
    }
}
