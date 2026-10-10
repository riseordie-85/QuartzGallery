package com.tim.gallery.ui

import android.app.WallpaperManager
import android.text.format.Formatter
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.produceState
import androidx.compose.material.icons.outlined.DriveFileRenameOutline
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Lock
import com.tim.gallery.personal.MoveToPersonalDialog
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.EmojiEmotions
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DeleteForever
import androidx.compose.material.icons.outlined.DriveFileMove
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.RestoreFromTrash
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.Wallpaper
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.tim.gallery.GalleryViewModel
import com.tim.gallery.data.AlbumOps
import com.tim.gallery.data.GalleryItem
import com.tim.gallery.data.thumb
import com.tim.gallery.edit.StickerMakerDialog

/**
 * Full-screen swipeable viewer. With [albumKey] it pages through that album (or Favorites);
 * otherwise through the Photos tab with its current filter.
 */
@Composable
fun ViewerScreen(
    vm: GalleryViewModel,
    startId: Long,
    albumKey: String?,
    onBack: () -> Unit,
    onPlay: (Long) -> Unit,
    onEdit: (Long) -> Unit,
    onOpenPersonal: () -> Unit,
    onShowOnMap: (Double, Double) -> Unit = { _, _ -> },
) {
    SystemBarIcons(darkBackground = true)
    val inTrash = albumKey == AlbumOps.TRASH_KEY
    val source = remember(albumKey) {
        when {
            albumKey == null -> vm.items
            inTrash -> vm.trash
            else -> vm.albumItems(albumKey)
        }
    }
    val items by source.collectAsStateWithLifecycle()
    val actions = rememberMediaActions(vm)

    if (items.isEmpty()) {
        LaunchedEffect(Unit) { onBack() }
        return
    }

    val context = LocalContext.current
    val initialPage = remember { items.indexOfFirst { it.id == startId }.coerceAtLeast(0) }
    val pagerState = rememberPagerState(initialPage = initialPage) { items.size }
    var chromeVisible by rememberSaveable { mutableStateOf(true) }
    var zoomed by remember { mutableStateOf(false) }
    var infoFor by remember { mutableStateOf<GalleryItem?>(null) }
    var moving by remember { mutableStateOf<List<GalleryItem>?>(null) }
    var wallpaperFor by remember { mutableStateOf<GalleryItem?>(null) }
    var stickerFrom by remember { mutableStateOf<GalleryItem?>(null) }
    var hiding by remember { mutableStateOf<GalleryItem?>(null) }
    var copying by remember { mutableStateOf<List<GalleryItem>?>(null) }
    var renaming by remember { mutableStateOf<GalleryItem?>(null) }
    var confirmDeleteForever by remember { mutableStateOf<GalleryItem?>(null) }
    var menuOpen by remember { mutableStateOf(false) }

    LaunchedEffect(pagerState.currentPage) { zoomed = false }

    val current = items.getOrNull(pagerState.currentPage)

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        HorizontalPager(
            state = pagerState,
            key = { index -> items.getOrNull(index)?.id ?: index },
            beyondViewportPageCount = 1,
            userScrollEnabled = !zoomed,
            modifier = Modifier.fillMaxSize(),
        ) { page ->
            val item = items.getOrNull(page) ?: return@HorizontalPager
            if (item.isVideo) {
                VideoPage(
                    item = item,
                    onTap = { chromeVisible = !chromeVisible },
                    onPlay = { onPlay(item.id) },
                )
            } else {
                val request = remember(item.uri, item.modified) {
                    ImageRequest.Builder(context)
                        .data(item.uri)
                        .memoryCacheKey("${item.uri}#${item.modified}")
                        .size(2560)
                        .build()
                }
                ZoomableImage(
                    model = request,
                    contentDescription = item.name,
                    isCurrent = page == pagerState.currentPage,
                    onTap = { chromeVisible = !chromeVisible },
                    onZoomChange = { if (page == pagerState.currentPage) zoomed = it },
                )
            }
        }

        if (current != null) {
            AnimatedVisibility(
                visible = chromeVisible,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.align(Alignment.TopCenter),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.7f), Color.Transparent)))
                        .statusBarsPadding()
                        .padding(horizontal = 4.dp, vertical = 4.dp),
                ) {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
                    }
                    Column(Modifier.weight(1f)) {
                        Text(
                            formatDate(current.dateMillis),
                            color = Color.White,
                            style = MaterialTheme.typography.titleSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            if (inTrash) "In trash · ${daysLeftLabel(current)}"
                            else "${current.album} · ${pagerState.currentPage + 1} of ${items.size}",
                            color = Color.White.copy(alpha = 0.7f),
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    IconButton(onClick = { infoFor = current }) {
                        Icon(Icons.Outlined.Info, contentDescription = "Details", tint = Color.White)
                    }
                    if (!inTrash) {
                        Box {
                            IconButton(onClick = { menuOpen = true }) {
                                Icon(Icons.Filled.MoreVert, contentDescription = "More", tint = Color.White)
                            }
                            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                                if (!current.isVideo) {
                                    DropdownMenuItem(
                                        text = { Text("Set as wallpaper") },
                                        leadingIcon = { Icon(Icons.Outlined.Wallpaper, contentDescription = null) },
                                        onClick = {
                                            menuOpen = false
                                            wallpaperFor = current
                                        },
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Add to stickers") },
                                        leadingIcon = { Icon(Icons.Outlined.EmojiEmotions, contentDescription = null) },
                                        onClick = {
                                            menuOpen = false
                                            stickerFrom = current
                                        },
                                    )
                                }
                                DropdownMenuItem(
                                    text = { Text("Copy to album") },
                                    leadingIcon = { Icon(Icons.Outlined.ContentCopy, contentDescription = null) },
                                    onClick = {
                                        menuOpen = false
                                        copying = listOf(current)
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text("Rename") },
                                    leadingIcon = { Icon(Icons.Outlined.DriveFileRenameOutline, contentDescription = null) },
                                    onClick = {
                                        menuOpen = false
                                        renaming = current
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text("Move to personal folder") },
                                    leadingIcon = { Icon(Icons.Outlined.Lock, contentDescription = null) },
                                    onClick = {
                                        menuOpen = false
                                        hiding = current
                                    },
                                )
                            }
                        }
                    }
                }
            }

            AnimatedVisibility(
                visible = chromeVisible,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.align(Alignment.BottomCenter),
            ) {
                Row(
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.7f))))
                        .navigationBarsPadding()
                        .padding(vertical = 8.dp),
                ) {
                    if (inTrash) {
                        if (current.isVideo) {
                            ActionButton(Icons.Filled.PlayArrow, "Play") { onPlay(current.id) }
                        }
                        ActionButton(Icons.Outlined.RestoreFromTrash, "Restore") { actions.restore(listOf(current)) }
                        ActionButton(Icons.Outlined.DeleteForever, "Delete forever") { confirmDeleteForever = current }
                    } else {
                        if (current.isVideo) {
                            ActionButton(Icons.Filled.PlayArrow, "Play") { onPlay(current.id) }
                        } else {
                            ActionButton(Icons.Outlined.Tune, "Edit") { onEdit(current.id) }
                        }
                        ActionButton(
                            if (current.isFavorite) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                            "Favorite",
                        ) { actions.setFavorite(listOf(current), favorite = !current.isFavorite) }
                        ActionButton(Icons.Outlined.Share, "Share") { actions.share(listOf(current)) }
                        ActionButton(Icons.Outlined.DriveFileMove, "Move") { moving = listOf(current) }
                        ActionButton(Icons.Outlined.Delete, "Delete") { actions.trash(listOf(current)) }
                    }
                }
            }
        }
    }

    if (!inTrash) MoveToAlbumFlow(
        vm = vm,
        actions = actions,
        items = moving,
        excludeKey = moving?.firstOrNull()?.relativePath,
        onDismiss = { moving = null },
    )

    infoFor?.let { item ->
        InfoDialog(
            item = item,
            onDismiss = { infoFor = null },
            onShowOnMap = { lat, lon ->
                infoFor = null
                onShowOnMap(lat, lon)
            },
        )
    }

    if (!inTrash) MoveToAlbumFlow(
        vm = vm,
        actions = actions,
        items = copying,
        excludeKey = null,
        onDismiss = { copying = null },
        copy = true,
    )

    renaming?.let { item ->
        AlbumNameDialog(
            title = "Rename",
            confirmLabel = "Rename",
            initial = item.name.substringBeforeLast('.', item.name),
            note = item.name.substringAfterLast('.', "").takeIf { it.isNotEmpty() }?.let { "The .$it ending is kept." },
            validate = { AlbumOps.validateFileName(it) },
            onDismiss = { renaming = null },
            onConfirm = { stem ->
                renaming = null
                actions.rename(item, stem)
            },
        )
    }

    hiding?.let { item ->
        MoveToPersonalDialog(
            items = listOf(item),
            actions = actions,
            onDismiss = { hiding = null },
            onSetUp = {
                hiding = null
                onOpenPersonal()
            },
            onFinished = { hiding = null },
        )
    }

    stickerFrom?.let { item ->
        StickerMakerDialog(
            source = item.uri,
            onDismiss = { stickerFrom = null },
            onSaved = {
                stickerFrom = null
                vm.refresh()
                actions.toast("Added to stickers")
            },
        )
    }

    confirmDeleteForever?.let { item ->
        AlertDialog(
            onDismissRequest = { confirmDeleteForever = null },
            title = { Text("Delete forever?") },
            text = { Text("This will be permanently deleted. This can't be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDeleteForever = null
                    actions.deleteForever(listOf(item))
                }) { Text("Delete forever") }
            },
            dismissButton = { TextButton(onClick = { confirmDeleteForever = null }) { Text("Cancel") } },
        )
    }

    wallpaperFor?.let { item ->
        WallpaperDialog(
            onDismiss = { wallpaperFor = null },
            onPick = { which ->
                wallpaperFor = null
                actions.setWallpaper(item, which)
            },
        )
    }
}

@Composable
private fun WallpaperDialog(onDismiss: () -> Unit, onPick: (Int) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Set as wallpaper") },
        text = {
            Column {
                ListItem(
                    headlineContent = { Text("Home screen") },
                    modifier = Modifier.clickable { onPick(WallpaperManager.FLAG_SYSTEM) },
                )
                ListItem(
                    headlineContent = { Text("Lock screen") },
                    modifier = Modifier.clickable { onPick(WallpaperManager.FLAG_LOCK) },
                )
                ListItem(
                    headlineContent = { Text("Home and lock screens") },
                    modifier = Modifier.clickable { onPick(WallpaperManager.FLAG_SYSTEM or WallpaperManager.FLAG_LOCK) },
                )
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun VideoPage(item: GalleryItem, onTap: () -> Unit, onPlay: () -> Unit) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) { detectTapGestures(onTap = { onTap() }) },
    ) {
        AsyncImage(
            model = item.thumb(),
            contentDescription = item.name,
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize(),
        )
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(76.dp)
                .background(Color.Black.copy(alpha = 0.5f), CircleShape)
                .clickable(onClick = onPlay),
        ) {
            Icon(Icons.Filled.PlayArrow, contentDescription = "Play", tint = Color.White, modifier = Modifier.size(48.dp))
        }
    }
}

@Composable
private fun ActionButton(icon: ImageVector, label: String, onClick: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Icon(icon, contentDescription = label, tint = Color.White)
        Text(label, color = Color.White, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun InfoDialog(item: GalleryItem, onDismiss: () -> Unit, onShowOnMap: (Double, Double) -> Unit) {
    val context = LocalContext.current
    val details by produceState<com.tim.gallery.data.MediaDetails?>(null, item.id) {
        value = com.tim.gallery.data.Metadata.read(context, item)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        title = { Text("Details") },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.verticalScroll(rememberScrollState()),
            ) {
                InfoRow("Name", item.name)
                InfoRow("Date", formatDate(item.dateMillis))
                InfoRow("Album", item.album)
                InfoRow("Folder", item.relativePath.trimEnd('/'))
                if (item.width > 0 && item.height > 0) {
                    val mp = item.width.toLong() * item.height / 1_000_000.0
                    InfoRow(
                        "Resolution",
                        "${item.width} × ${item.height}" +
                            if (!item.isVideo && mp >= 0.1) String.format(java.util.Locale.US, "  ·  %.1f MP", mp) else "",
                    )
                }
                if (item.isVideo) InfoRow("Length", formatDuration(item.durationMs))
                InfoRow("Size", Formatter.formatShortFileSize(context, item.sizeBytes))
                InfoRow("Type", item.mimeType)
                val d = details
                if (d == null) {
                    CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
                } else {
                    d.camera?.let { InfoRow("Camera", it) }
                    d.lens?.let { InfoRow("Lens", it) }
                    listOfNotNull(d.aperture, d.shutter, d.iso).takeIf { it.isNotEmpty() }?.let {
                        InfoRow("Exposure", it.joinToString("  ·  "))
                    }
                    d.focalLength?.let { InfoRow("Focal length", it) }
                    d.flash?.let { InfoRow("Flash", it) }
                    d.frameRate?.let { InfoRow("Frame rate", it) }
                    d.bitrate?.let { InfoRow("Bitrate", it) }
                    d.hdr?.let { InfoRow("Dynamic range", it) }
                    d.codec?.let { InfoRow("Video format", it) }
                    if (d.latitude != null && d.longitude != null) {
                        InfoRow(
                            "Location",
                            String.format(java.util.Locale.US, "%.5f, %.5f", d.latitude, d.longitude) +
                                (d.altitude?.let { "  ·  $it" } ?: ""),
                        )
                        TextButton(onClick = { onShowOnMap(d.latitude, d.longitude) }) { Text("Show on map") }
                    }
                }
            }
        },
    )
}

@Composable
private fun InfoRow(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}
