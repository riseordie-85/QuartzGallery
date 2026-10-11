package com.tim.gallery.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.AddPhotoAlternate
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material.icons.outlined.CleaningServices
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import android.widget.Toast
import com.tim.gallery.edit.BatchWatermarkDialog
import com.tim.gallery.personal.MoveToPersonalDialog
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tim.gallery.GalleryViewModel
import com.tim.gallery.data.Album
import com.tim.gallery.data.AlbumOps
import com.tim.gallery.data.GalleryItem
import kotlinx.coroutines.delay

// ---------------------------------------------------------------- Albums tab

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlbumsScreen(
    vm: GalleryViewModel,
    onOpenAlbum: (String) -> Unit,
    onOpenTrash: () -> Unit,
    onOpenPersonal: () -> Unit,
    onOpenMap: () -> Unit,
    onOpenCleanup: () -> Unit,
    onOpenSettings: () -> Unit,
    bottomBar: @Composable () -> Unit,
) {
    val albums by vm.albums.collectAsStateWithLifecycle()
    val trash by vm.trash.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val canManageMedia = rememberCanManageMedia()
    var creating by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }

    Scaffold(
        containerColor = mainContainerColor(),
        topBar = {
            TopAppBar(
                colors = mainTopBarColors(),
                title = { Text("Albums") },
                actions = {
                    IconButton(onClick = { creating = true }) {
                        Icon(Icons.Outlined.CreateNewFolder, contentDescription = "New album")
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Outlined.Settings, contentDescription = "Settings")
                    }
                    if (!canManageMedia) {
                        Box {
                            IconButton(onClick = { menuOpen = true }) {
                                Icon(Icons.Filled.MoreVert, contentDescription = "More")
                            }
                            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                                DropdownMenuItem(
                                    text = { Text("Move & delete without asking") },
                                    onClick = {
                                        menuOpen = false
                                        openManageMediaSettings(context)
                                    },
                                )
                            }
                        }
                    }
                },
            )
        },
        bottomBar = bottomBar,
    ) { padding ->
        LazyVerticalGrid(
            columns = GridCells.Adaptive(150.dp),
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = padding.calculateTopPadding() + 8.dp,
                bottom = padding.calculateBottomPadding() + 16.dp,
            ),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            items(albums, key = { it.key }) { album ->
                AlbumCard(album = album, onClick = { onOpenAlbum(album.key) })
            }
            item(key = AlbumOps.TRASH_KEY) {
                TrashCard(count = trash.size, onClick = onOpenTrash)
            }
            item(key = "places") {
                IconCard(icon = Icons.Outlined.Map, title = "Places", subtitle = "Photos on a map", onClick = onOpenMap)
            }
            item(key = "cleanup") {
                IconCard(icon = Icons.Outlined.CleaningServices, title = "Free up space", subtitle = "Big files, duplicates", onClick = onOpenCleanup)
            }
            item(key = "personal") {
                IconCard(
                    icon = Icons.Outlined.Lock,
                    title = "Personal",
                    subtitle = "Passcode protected",
                    onClick = onOpenPersonal,
                )
            }
        }
    }

    if (creating) {
        AlbumNameDialog(
            title = "New album",
            confirmLabel = "Create",
            validate = { AlbumOps.validateName(it, AlbumOps.NEW_ALBUM_PARENT, albums) },
            onDismiss = { creating = false },
            onConfirm = { name ->
                creating = false
                val key = AlbumOps.newAlbumPath(name)
                vm.addEmptyAlbum(key)
                onOpenAlbum(key)
            },
        )
    }
}

@Composable
private fun AlbumCard(album: Album, onClick: () -> Unit) {
    Column(Modifier.clickable(onClick = onClick)) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            AlbumThumb(album, size = maxWidth, corner = 18.dp)
        }
        Text(
            album.name,
            style = MaterialTheme.typography.titleSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 8.dp),
        )
        Text(
            if (album.count == 0) "Empty" else itemCount(album.count),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun IconCard(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
    Column(Modifier.clickable(onClick = onClick)) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val side = maxWidth
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(side)
                    .clip(RoundedCornerShape(18.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            ) {
                Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(side * 0.32f))
            }
        }
        Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun TrashCard(count: Int, onClick: () -> Unit) {
    Column(Modifier.clickable(onClick = onClick)) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val side = maxWidth
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(side)
                    .clip(RoundedCornerShape(18.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            ) {
                Icon(
                    Icons.Outlined.DeleteOutline,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(side * 0.32f),
                )
            }
        }
        Text("Trash", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
        Text(
            if (count == 0) "Empty" else itemCount(count),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ---------------------------------------------------------------- One album

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlbumScreen(
    vm: GalleryViewModel,
    albumKey: String,
    onBack: () -> Unit,
    onOpenItem: (Long) -> Unit,
    onAddItems: () -> Unit,
    onRenamed: (String) -> Unit,
    onOpenPersonal: () -> Unit,
    onEdit: (Long) -> Unit = {},
) {
    val items by remember(albumKey) { vm.albumItems(albumKey) }.collectAsStateWithLifecycle()
    val paneActive = rememberPreviewPaneActive()
    var previewId by rememberSaveable(albumKey) { mutableStateOf<Long?>(null) }
    val gridState = androidx.compose.foundation.lazy.grid.rememberLazyGridState()
    val albums by vm.albums.collectAsStateWithLifecycle()
    val album = albums.firstOrNull { it.key == albumKey }
    val actions = rememberMediaActions(vm)

    var selected by remember { mutableStateOf(emptySet<Long>()) }
    var moving by remember { mutableStateOf<List<GalleryItem>?>(null) }
    var watermarking by remember { mutableStateOf<List<GalleryItem>?>(null) }
    var hiding by remember { mutableStateOf<List<GalleryItem>?>(null) }
    var copying by remember { mutableStateOf<List<GalleryItem>?>(null) }
    var menuOpen by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val selectedItems = { items.filter { it.id in selected } }

    BackHandler(enabled = selected.isNotEmpty()) { selected = emptySet() }

    LaunchedEffect(items) {
        if (selected.isNotEmpty()) {
            val ids = items.mapTo(HashSet()) { it.id }
            selected = selected.filterTo(HashSet()) { it in ids }
        }
    }

    // The album disappears once it's deleted or everything is moved out of it
    LaunchedEffect(album == null) {
        if (album == null) {
            delay(1500) // grace period while a just-created or just-renamed album shows up
            onBack()
        }
    }

    val isFavorites = AlbumOps.isFavorites(albumKey)
    val isStickers = AlbumOps.isStickers(albumKey)
    val title = album?.name ?: AlbumOps.nameFromPath(albumKey)
    val canRename = !isFavorites && !isStickers && AlbumOps.parentOf(albumKey) != null

    Scaffold(
        containerColor = mainContainerColor(),
        topBar = {
            if (selected.isNotEmpty()) {
                SelectionTopBar(
                    count = selected.size,
                    onClear = { selected = emptySet() },
                    onSelectAll = { selected = items.mapTo(HashSet()) { it.id } },
                    allFavorite = selectedItems().all { it.isFavorite },
                    onFavorite = {
                        val sel = selectedItems()
                        actions.setFavorite(sel, favorite = !sel.all { it.isFavorite })
                    },
                    onShare = { actions.share(selectedItems()) },
                    onMove = { moving = selectedItems() },
                    onDelete = { actions.trash(selectedItems()) { ok -> if (ok) selected = emptySet() } },
                    onWatermark = { watermarking = selectedItems() },
                    onPersonal = { hiding = selectedItems() },
                    onCopy = { copying = selectedItems() },
                )
            } else {
                TopAppBar(
                    colors = mainTopBarColors(),
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    },
                    title = {
                        Column {
                            Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                if (isFavorites) itemCount(items.size) else albumKey.trimEnd('/'),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    },
                    actions = {
                        if (!isFavorites && !isStickers) IconButton(onClick = onAddItems) {
                            Icon(Icons.Outlined.AddPhotoAlternate, contentDescription = "Add photos and videos")
                        }
                        SortButton()
                        if (!isFavorites) Box {
                            IconButton(onClick = { menuOpen = true }) {
                                Icon(Icons.Filled.MoreVert, contentDescription = "More")
                            }
                            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                                DropdownMenuItem(
                                    text = { Text("Rename album") },
                                    enabled = canRename,
                                    onClick = {
                                        menuOpen = false
                                        renaming = true
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text("Delete album") },
                                    onClick = {
                                        menuOpen = false
                                        confirmDelete = true
                                    },
                                )
                            }
                        }
                    },
                )
            }
        },
    ) { padding ->
      PreviewPaneLayout(
        active = paneActive,
        items = items,
        previewId = previewId,
        onPreviewChange = { previewId = it },
        onOpenFull = onOpenItem,
        onEdit = onEdit,
        actions = actions,
        contentPadding = padding,
      ) { gridModifier ->
        MediaGrid(
            items = items,
            selected = selected,
            onOpen = { if (paneActive) previewId = it.id else onOpenItem(it.id) },
            onSelectionChange = { selected = it },
            contentPadding = padding,
            modifier = gridModifier,
            focusId = if (paneActive) previewId else null,
            gridState = gridState,
            empty = {
                if (isFavorites || isStickers) {
                    Text(
                        if (isFavorites) "No favorites yet. Tap the heart on a photo or video to add it here."
                        else "No stickers yet. Open a photo, tap ⋮ → Add to stickers, or add one while editing.",
                        style = MaterialTheme.typography.bodyLarge,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 64.dp),
                    )
                } else {
                    EmptyAlbum(onAddItems)
                }
            },
        )
      }
    }

    hiding?.let { list ->
        MoveToPersonalDialog(
            items = list,
            actions = actions,
            onDismiss = { hiding = null },
            onSetUp = {
                hiding = null
                onOpenPersonal()
            },
            onFinished = { n ->
                hiding = null
                if (n > 0) selected = emptySet()
            },
        )
    }

    watermarking?.let { list ->
        val ctx = LocalContext.current
        BatchWatermarkDialog(
            items = list,
            onDismiss = { watermarking = null },
            onFinished = { n ->
                watermarking = null
                selected = emptySet()
                vm.refresh()
                Toast.makeText(ctx, if (n == 1) "Saved 1 watermarked copy" else "Saved $n watermarked copies", Toast.LENGTH_SHORT).show()
            },
        )
    }

    MoveToAlbumFlow(
        vm = vm,
        actions = actions,
        items = copying,
        excludeKey = null,
        onDismiss = { copying = null },
        onMoved = { if (it > 0) selected = emptySet() },
        copy = true,
    )

    MoveToAlbumFlow(
        vm = vm,
        actions = actions,
        items = moving,
        excludeKey = albumKey,
        onDismiss = { moving = null },
        onMoved = { if (it > 0) selected = emptySet() },
    )

    if (renaming && album != null) {
        val parent = AlbumOps.parentOf(albumKey) ?: AlbumOps.NEW_ALBUM_PARENT
        AlbumNameDialog(
            title = "Rename album",
            confirmLabel = "Rename",
            initial = album.name,
            validate = { AlbumOps.validateName(it, parent, albums, currentKey = albumKey) },
            note = when {
                AlbumOps.isSystemFolder(albumKey) ->
                    "Heads up: the app that saves here (camera or screenshots) will keep creating a new \"${album.name}\" folder for new items."
                album.count > 0 -> "This renames the folder on your phone, so other apps will see the new name too."
                else -> null
            },
            onDismiss = { renaming = false },
            onConfirm = { name ->
                renaming = false
                actions.renameAlbum(album, name) { newKey -> if (newKey != null) onRenamed(newKey) }
            },
        )
    }

    if (confirmDelete && album != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete \"${album.name}\"?") },
            text = {
                Text(
                    if (album.count == 0) "This empty album will be removed."
                    else "All ${itemCount(album.count)} in this album will be moved to the trash. " +
                        "Android keeps trashed items for about 30 days, so you can still restore them."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    if (album.count == 0) {
                        vm.removeEmptyAlbum(albumKey)
                        onBack()
                    } else {
                        actions.trash(items)
                    }
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun EmptyAlbum(onAddItems: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 64.dp),
    ) {
        Icon(
            Icons.Outlined.PhotoLibrary,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(64.dp),
        )
        Spacer(Modifier.height(12.dp))
        Text("This album is empty", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        Text(
            "Move photos and videos here from your library.",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(20.dp))
        Button(onClick = onAddItems) { Text("Add photos & videos") }
    }
}

// ---------------------------------------------------------------- Picker

/** Pick items from the whole library to move into [targetKey]. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PickerScreen(
    vm: GalleryViewModel,
    targetKey: String,
    onDone: () -> Unit,
) {
    val all by vm.allItems.collectAsStateWithLifecycle()
    val candidates = remember(all, targetKey) { all.filter { it.relativePath != targetKey } }
    val actions = rememberMediaActions(vm)
    var selected by remember { mutableStateOf(emptySet<Long>()) }
    var moving by remember { mutableStateOf(false) }
    val name = AlbumOps.nameFromPath(targetKey)

    Scaffold(
        containerColor = mainContainerColor(),
        topBar = {
            TopAppBar(
                colors = mainTopBarColors(),
                navigationIcon = {
                    IconButton(onClick = onDone) { Icon(Icons.Filled.Close, contentDescription = "Cancel") }
                },
                title = {
                    Column {
                        Text("Add to $name", maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            if (selected.isEmpty()) "Tap photos and videos to select" else "${selected.size} selected",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                actions = {
                    TextButton(
                        enabled = selected.isNotEmpty() && !moving,
                        onClick = {
                            moving = true
                            actions.move(candidates.filter { it.id in selected }, targetKey) { moved ->
                                if (moved > 0) onDone() else moving = false
                            }
                        },
                    ) { Text("Move here") }
                },
            )
        },
    ) { padding ->
        MediaGrid(
            items = candidates,
            selected = selected,
            onOpen = {},
            onSelectionChange = { selected = it },
            contentPadding = padding,
            alwaysSelecting = true,
        )
    }
}
