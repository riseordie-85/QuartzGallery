package com.tim.gallery.ui

import kotlin.math.roundToInt

import kotlinx.coroutines.launch

import androidx.compose.ui.graphics.graphicsLayer

import androidx.compose.foundation.layout.fillMaxHeight

import androidx.compose.foundation.layout.height

import androidx.compose.foundation.layout.offset

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.automirrored.outlined.Sort
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material.icons.automirrored.outlined.BrandingWatermark
import androidx.compose.material.icons.automirrored.outlined.DriveFileMove
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import com.tim.gallery.settings.SortField
import com.tim.gallery.settings.AppSettings
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.runtime.collectAsState
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import androidx.compose.runtime.produceState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.tim.gallery.GalleryViewModel
import com.tim.gallery.data.Album
import com.tim.gallery.data.AlbumOps
import com.tim.gallery.data.GalleryItem
import com.tim.gallery.data.thumb
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

fun Set<Long>.toggle(id: Long): Set<Long> = if (id in this) this - id else this + id

private data class Section(val key: String, val title: String, val items: List<GalleryItem>)

private fun buildSections(items: List<GalleryItem>, byAdded: Boolean = false): List<Section> {
    val fmt = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.getDefault())
    val zone = ZoneId.systemDefault()
    // Consecutive runs, so any date order (newest or oldest first) keeps its order
    val out = ArrayList<Section>()
    val used = HashMap<String, Int>()
    var current: YearMonth? = null
    var bucket = ArrayList<GalleryItem>()
    // Keys stay the same when a new month appears (so the grid doesn't jump)
    fun add(month: YearMonth) {
        val base = month.toString()
        val n = used.merge(base, 1, Int::plus) ?: 1
        out += Section(if (n == 1) base else "${base}_$n", month.format(fmt), bucket)
    }
    for (item in items) {
        val month = YearMonth.from(Instant.ofEpochMilli(if (byAdded) item.addedMillis else item.dateMillis).atZone(zone))
        if (month != current) {
            current?.let { add(it) }
            current = month
            bucket = ArrayList()
        }
        bucket += item
    }
    current?.let { add(it) }
    return out
}

/** Pinch on the grid to make thumbnails bigger or smaller. */
private fun Modifier.pinchToResize(onStep: (bigger: Boolean) -> Unit): Modifier = pointerInput(Unit) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        var zoom = 1f
        do {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            if (event.changes.count { it.pressed } >= 2) {
                zoom *= event.calculateZoom()
                if (zoom > 1.35f) {
                    onStep(true)
                    zoom = 1f
                } else if (zoom < 1f / 1.35f) {
                    onStep(false)
                    zoom = 1f
                }
                event.changes.forEach { it.consume() }
            }
        } while (event.changes.any { it.pressed })
    }
}

/**
 * Month-grouped grid used by the Photos tab, albums, trash and the picker.
 * Long-press starts selecting (keep your finger down and drag to select a range);
 * while anything is selected, taps toggle selection.
 */
@Composable
fun MediaGrid(
    items: List<GalleryItem>,
    selected: Set<Long>,
    onOpen: (GalleryItem) -> Unit,
    onSelectionChange: (Set<Long>) -> Unit,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
    alwaysSelecting: Boolean = false,
    groupByMonth: Boolean = true,
    badge: ((GalleryItem) -> String?)? = null,
    header: (@Composable () -> Unit)? = null,
    /** The item shown in the preview pane: outlined, and scrolled into view when it changes. */
    focusId: Long? = null,
    /** Pass one from the screen so the scroll position survives layout changes (rotation, preview pane). */
    gridState: androidx.compose.foundation.lazy.grid.LazyGridState = rememberLazyGridState(),
    empty: @Composable () -> Unit = {
        Text(
            "Nothing to show here yet.",
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.fillMaxWidth().padding(48.dp),
        )
    },
) {
    val sort by AppSettings.sort.collectAsState()
    val cellDp by AppSettings.gridCell.collectAsState()
    val look by AppSettings.look.collectAsState()
    val headingColor = com.tim.gallery.quartz.quartzHeadingColor()
    val grouped = groupByMonth && sort.byDate
    val byAdded = sort.field == SortField.ADDED
    // Grouping thousands of items by month happens in the background
    val sections by produceState(
        // Small lists group right away; big ones show ungrouped for a moment instead of stalling the screen
        initialValue = remember {
            if (grouped && items.size < 3000) buildSections(items, byAdded) else listOf(Section("all", "", items))
        },
        items,
        grouped,
        byAdded,
    ) {
        value = withContext(Dispatchers.Default) {
            if (grouped) buildSections(items, byAdded) else listOf(Section("all", "", items))
        }
    }
    val ids = remember(sections) { sections.flatMap { s -> s.items.map { it.id } } }
    val selecting = alwaysSelecting || selected.isNotEmpty()
    val dragSession = remember { DragSelectSession() }

    // Keep the previewed item in view (e.g. while swiping through the preview pane)
    val scrolledTo = remember { longArrayOf(Long.MIN_VALUE) }
    LaunchedEffect(focusId, sections) {
        val id = focusId ?: return@LaunchedEffect
        // Only when the previewed item changes, not on every list update (favorites, new photos...)
        if (id == scrolledTo[0]) return@LaunchedEffect
        if (gridState.layoutInfo.visibleItemsInfo.any { it.key == id }) {
            scrolledTo[0] = id
            return@LaunchedEffect
        }
        var index = (if (header != null) 1 else 0) + (if (items.isEmpty()) 1 else 0)
        for (section in sections) {
            if (section.title.isNotEmpty()) index++
            val i = section.items.indexOfFirst { it.id == id }
            if (i >= 0) {
                scrolledTo[0] = id
                gridState.animateScrollToItem(index + i)
                return@LaunchedEffect
            }
            index += section.items.size
        }
    }

    // For the fast scroller's bubble: which month a grid position belongs to
    val sectionStarts = remember(sections, header != null, items.isEmpty()) {
        var index = (if (header != null) 1 else 0) + (if (items.isEmpty()) 1 else 0)
        sections.map { section ->
            val start = index
            index += (if (section.title.isNotEmpty()) 1 else 0) + section.items.size
            start to section
        }
    }
    val labelAt: (Int) -> String? = { gridIndex ->
        val i = sectionStarts.indexOfLast { it.first <= gridIndex }
        sectionStarts.getOrNull(i)?.let { (start, section) ->
            section.title.ifEmpty {
                // Not grouped by month: use the date of the item there
                val within = (gridIndex - start).coerceIn(0, section.items.lastIndex)
                section.items.getOrNull(within)?.let { monthLabel(it.dateMillis) }
            }
        }
    }

    val fastScroll = remember { FastScrollState() }
    Box(modifier.fastScrollGestures(fastScroll, gridState)) {
        LazyVerticalGrid(
            columns = GridCells.Adaptive(cellDp.dp),
            state = gridState,
            contentPadding = contentPadding,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
            modifier = Modifier
                .fillMaxSize()
                .pinchToResize { AppSettings.stepGrid(it) }
                .dragToSelect(
                    state = gridState,
                    ids = ids,
                    selected = selected,
                    contentPadding = contentPadding,
                    drag = dragSession,
                    onSelectionChange = onSelectionChange,
                ),
        ) {
            if (header != null) {
                item(key = "header", span = { GridItemSpan(maxLineSpan) }, contentType = "header") { header() }
            }
            if (items.isEmpty()) {
                item(key = "empty", span = { GridItemSpan(maxLineSpan) }, contentType = "empty") { empty() }
            }
            sections.forEach { section ->
                if (section.title.isNotEmpty()) {
                    item(key = "h_${section.key}", span = { GridItemSpan(maxLineSpan) }, contentType = "month") {
                        if (look.bigHeadings) {
                            Text(
                                section.title,
                                style = MaterialTheme.typography.headlineMedium,
                                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                                color = headingColor,
                                maxLines = 1,
                                modifier = Modifier.padding(start = 14.dp, end = 14.dp, top = 18.dp, bottom = 10.dp),
                            )
                        } else {
                            Text(
                                section.title,
                                style = MaterialTheme.typography.titleSmall,
                                modifier = Modifier.padding(start = 12.dp, top = 16.dp, bottom = 6.dp),
                            )
                        }
                    }
                }
                items(section.items, key = { it.id }, contentType = { "tile" }) { item ->
                    MediaTile(
                        item = item,
                        focused = item.id == focusId,
                        selected = item.id in selected,
                        selecting = selecting,
                        badge = badge?.invoke(item),
                        onClick = {
                            // A long-press that started a drag-selection also "taps" on release; skip it
                            if (!dragSession.active) {
                                if (selecting) onSelectionChange(selected.toggle(item.id)) else onOpen(item)
                            }
                        },
                    )
                }
            }
        }
        FastScroller(
            fs = fastScroll,
            state = gridState,
            labelAt = labelAt,
            topPadding = contentPadding.calculateTopPadding(),
            bottomPadding = contentPadding.calculateBottomPadding(),
        )
    }
}

@Composable
private fun MediaTile(
    item: GalleryItem,
    focused: Boolean = false,
    selected: Boolean,
    selecting: Boolean,
    badge: String?,
    onClick: () -> Unit,
) {
    val inset by animateDpAsState(if (selected) 10.dp else 0.dp, label = "selectInset")
    Box(
        Modifier
            .aspectRatio(1f)
            .clickable(onClick = onClick)
            .then(
                if (focused) Modifier.border(3.dp, MaterialTheme.colorScheme.primary) else Modifier
            )
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .padding(inset)
                .clip(RoundedCornerShape(if (selected) 10.dp else 0.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
        ) {
            AsyncImage(
                model = item.thumb(),
                contentDescription = item.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            if (badge != null) {
                Text(
                    badge,
                    color = Color.White,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(4.dp)
                        .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(4.dp))
                        .padding(horizontal = 5.dp, vertical = 1.dp),
                )
            }
            if (item.mimeType.equals("image/gif", ignoreCase = true)) {
                Text(
                    "GIF",
                    color = Color.White,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(4.dp)
                        .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(4.dp))
                        .padding(horizontal = 5.dp, vertical = 1.dp),
                )
            }
            if (item.isFavorite) {
                Icon(
                    Icons.Filled.Favorite,
                    contentDescription = "Favorite",
                    tint = Color.White,
                    modifier = Modifier.align(Alignment.BottomStart).padding(5.dp).size(16.dp),
                )
            }
            if (item.isVideo) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(4.dp)
                        .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(4.dp))
                        .padding(horizontal = 4.dp, vertical = 1.dp),
                ) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = null, tint = Color.White, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(2.dp))
                    Text(formatDuration(item.durationMs), color = Color.White, style = MaterialTheme.typography.labelSmall)
                }
            }
        }
        if (selecting) {
            Icon(
                if (selected) Icons.Filled.CheckCircle else Icons.Outlined.RadioButtonUnchecked,
                contentDescription = if (selected) "Selected" else "Not selected",
                tint = if (selected) MaterialTheme.colorScheme.primary else Color.White,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(6.dp)
                    .size(24.dp)
                    .background(
                        if (selected) MaterialTheme.colorScheme.surface else Color.Black.copy(alpha = 0.25f),
                        CircleShape,
                    ),
            )
        }
    }
}

/** Top bar shown while items are selected. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SelectionTopBar(
    count: Int,
    onClear: () -> Unit,
    onSelectAll: () -> Unit,
    allFavorite: Boolean,
    onFavorite: () -> Unit,
    onShare: () -> Unit,
    onMove: () -> Unit,
    onDelete: () -> Unit,
    onWatermark: (() -> Unit)? = null,
    onPersonal: (() -> Unit)? = null,
    onCopy: (() -> Unit)? = null,
) {
    var menuOpen by remember { mutableStateOf(false) }
    com.tim.gallery.quartz.QuartzTopBar(
        navigationIcon = {
            IconButton(onClick = onClear) { Icon(Icons.Filled.Close, contentDescription = "Clear selection") }
        },
        title = { Text("$count") },
        actions = {
            IconButton(onClick = onSelectAll) { Icon(Icons.Outlined.SelectAll, contentDescription = "Select all") }
            IconButton(onClick = onFavorite) {
                Icon(
                    if (allFavorite) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                    contentDescription = if (allFavorite) "Remove from favorites" else "Add to favorites",
                )
            }
            IconButton(onClick = onShare) { Icon(Icons.Outlined.Share, contentDescription = "Share") }
            IconButton(onClick = onMove) { Icon(Icons.AutoMirrored.Outlined.DriveFileMove, contentDescription = "Move to album") }
            IconButton(onClick = onDelete) { Icon(Icons.Outlined.Delete, contentDescription = "Delete") }
            if (onWatermark != null || onPersonal != null || onCopy != null) {
                Box {
                    IconButton(onClick = { menuOpen = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "More") }
                    com.tim.gallery.quartz.QuartzDropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        if (onCopy != null) {
                            DropdownMenuItem(
                                text = { Text("Copy to album") },
                                leadingIcon = { Icon(Icons.Outlined.ContentCopy, contentDescription = null) },
                                onClick = {
                                    menuOpen = false
                                    onCopy()
                                },
                            )
                        }
                        if (onWatermark != null) {
                            DropdownMenuItem(
                                text = { Text("Add watermark") },
                                leadingIcon = { Icon(Icons.AutoMirrored.Outlined.BrandingWatermark, contentDescription = null) },
                                onClick = {
                                    menuOpen = false
                                    onWatermark()
                                },
                            )
                        }
                        if (onPersonal != null) {
                            DropdownMenuItem(
                                text = { Text("Move to personal folder") },
                                leadingIcon = { Icon(Icons.Outlined.Lock, contentDescription = null) },
                                onClick = {
                                    menuOpen = false
                                    onPersonal()
                                },
                            )
                        }
                    }
                }
            }
        },
    )
}

/**
 * "Move to album" sheet, with a "New album" option that asks for a name.
 * Shows nothing while [items] is null.
 */
@Composable
fun MoveToAlbumFlow(
    vm: GalleryViewModel,
    actions: MediaActions,
    items: List<GalleryItem>?,
    excludeKey: String?,
    onDismiss: () -> Unit,
    onMoved: (Int) -> Unit = {},
    copy: Boolean = false,
) {
    if (items == null) return
    val albums by vm.albums.collectAsStateWithLifecycle()
    var askingName by remember { mutableStateOf(false) }

    fun go(key: String) {
        onDismiss()
        if (copy) actions.copy(items, key, onMoved) else actions.move(items, key, onFinished = onMoved)
    }

    if (!askingName) {
        MoveToAlbumSheet(
            albums = albums.filter { it.key != excludeKey && !AlbumOps.isFavorites(it.key) },
            count = items.size,
            verb = if (copy) "Copy" else "Move",
            onDismiss = onDismiss,
            onNewAlbum = { askingName = true },
            onPick = { key -> go(key) },
        )
    } else {
        AlbumNameDialog(
            title = "New album",
            confirmLabel = if (copy) "Create & copy" else "Create & move",
            validate = { AlbumOps.validateName(it, AlbumOps.NEW_ALBUM_PARENT, albums) },
            onDismiss = onDismiss,
            onConfirm = { name -> go(AlbumOps.newAlbumPath(name)) },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MoveToAlbumSheet(
    albums: List<Album>,
    count: Int,
    verb: String,
    onDismiss: () -> Unit,
    onNewAlbum: () -> Unit,
    onPick: (String) -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Text(
            "$verb ${itemCount(count)} to…",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
        )
        LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
            item(key = "new") {
                ListItem(
                    headlineContent = { Text("New album") },
                    leadingContent = {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .size(48.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(MaterialTheme.colorScheme.primaryContainer),
                        ) {
                            Icon(Icons.Outlined.CreateNewFolder, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
                        }
                    },
                    modifier = Modifier.clickable(onClick = onNewAlbum),
                )
            }
            items(albums, key = { it.key }) { album ->
                ListItem(
                    headlineContent = { Text(album.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    supportingContent = {
                        Text(
                            "${album.key.trimEnd('/')} · ${itemCount(album.count)}",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    leadingContent = { AlbumThumb(album, 48.dp, 10.dp) },
                    modifier = Modifier.clickable { onPick(album.key) },
                )
            }
        }
    }
}

@Composable
fun AlbumThumb(album: Album, size: Dp, corner: Dp, modifier: Modifier = Modifier) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(corner))
            .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        val cover = album.cover
        if (cover != null) {
            AsyncImage(
                model = cover.thumb(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Icon(
                Icons.Outlined.PhotoLibrary,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(size * 0.4f),
            )
        }
    }
}

/** Name prompt for creating or renaming an album, with live validation. */
@Composable
fun AlbumNameDialog(
    title: String,
    confirmLabel: String,
    validate: (String) -> String?,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
    initial: String = "",
    note: String? = null,
) {
    var name by remember { mutableStateOf(initial) }
    var touched by remember { mutableStateOf(false) }
    val error = validate(name)
    val unchanged = initial.isNotEmpty() && name.trim() == initial

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = {
                        name = it
                        touched = true
                    },
                    label = { Text("Album name") },
                    singleLine = true,
                    isError = touched && error != null && !unchanged,
                    supportingText = {
                        if (touched && error != null && !unchanged) Text(error)
                    },
                )
                if (note != null) {
                    Text(note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        },
        confirmButton = {
            TextButton(enabled = error == null && !unchanged, onClick = { onConfirm(name.trim()) }) {
                Text(confirmLabel)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Sort menu (shared by the Photos tab and albums). */
@Composable
fun SortButton() {
    val sort by AppSettings.sort.collectAsState()
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) {
            Icon(Icons.AutoMirrored.Outlined.Sort, contentDescription = "Sort")
        }
        com.tim.gallery.quartz.QuartzDropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            SortField.entries.forEach { f ->
                DropdownMenuItem(
                    text = { Text(f.label) },
                    leadingIcon = {
                        if (sort.field == f) Icon(Icons.Filled.Check, contentDescription = "Selected")
                    },
                    onClick = {
                        AppSettings.setSort(sort.copy(field = f))
                        open = false
                    },
                )
            }
            HorizontalDivider()
            val (first, second) = when (sort.field) {
                SortField.TAKEN, SortField.ADDED -> "Newest first" to "Oldest first"
                SortField.SIZE -> "Largest first" to "Smallest first"
                SortField.NAME -> "Z to A" to "A to Z"
            }
            listOf(true to first, false to second).forEach { (desc, label) ->
                DropdownMenuItem(
                    text = { Text(label) },
                    leadingIcon = {
                        if (sort.descending == desc) Icon(Icons.Filled.Check, contentDescription = "Selected")
                    },
                    onClick = {
                        AppSettings.setSort(sort.copy(descending = desc))
                        open = false
                    },
                )
            }
        }
    }
}

private val monthFormat = java.time.format.DateTimeFormatter.ofPattern("MMMM yyyy", java.util.Locale.getDefault())

private fun monthLabel(ms: Long): String =
    java.time.Instant.ofEpochMilli(ms).atZone(java.time.ZoneId.systemDefault()).format(monthFormat)

/** Shared between the fast scroller's drawing and its touch handling (on the grid's parent). */
private class FastScrollState {
    var enabled by mutableStateOf(false)
    var shown by mutableStateOf(false)
    var dragging by mutableStateOf(false)
    var dragFraction by androidx.compose.runtime.mutableFloatStateOf(0f)
    // Geometry in px, set by the scroller's layout
    var fraction = 0f
    var trackTop = 0f
    var trackPx = 1f
    var thumbPx = 1f
    var coroutineScope: kotlinx.coroutines.CoroutineScope? = null
    var scrollJob: kotlinx.coroutines.Job? = null
}

/** Which grid position a scroller fraction lands on (the same mapping the handle uses). */
private fun scrollTarget(state: androidx.compose.foundation.lazy.grid.LazyGridState, f: Float): Int {
    val info = state.layoutInfo
    val span = (info.totalItemsCount - info.visibleItemsInfo.size).coerceAtLeast(0)
    return (f * span).roundToInt().coerceIn(0, (info.totalItemsCount - 1).coerceAtLeast(0))
}

/**
 * Grabbing the fast-scroll handle. Runs on the grid's parent before the grid sees the touch,
 * and only takes touches that land on the handle; everything else goes to the grid as usual.
 */
private fun Modifier.fastScrollGestures(
    fs: FastScrollState,
    state: androidx.compose.foundation.lazy.grid.LazyGridState,
): Modifier = pointerInput(fs, state) {
    val touchWidth = 48.dp.toPx()
    val slack = 16.dp.toPx()
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = androidx.compose.ui.input.pointer.PointerEventPass.Initial)
        if (!fs.enabled || !fs.shown) return@awaitEachGesture
        if (down.position.x < size.width - touchWidth) return@awaitEachGesture
        val thumbTop = fs.trackTop + fs.fraction * fs.trackPx
        if (down.position.y < thumbTop - slack || down.position.y > thumbTop + fs.thumbPx + slack) return@awaitEachGesture
        down.consume()
        val grab = (down.position.y - thumbTop).coerceIn(0f, fs.thumbPx)
        try {
            fs.dragFraction = fs.fraction
            fs.dragging = true
            while (true) {
                val event = awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Initial)
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                // Swallow the lift too, so the tile under the handle doesn't get a tap
                change.consume()
                if (!change.pressed) break
                val f = ((change.position.y - grab - fs.trackTop) / fs.trackPx).coerceIn(0f, 1f)
                fs.dragFraction = f
                fs.scrollJob?.cancel()
                fs.scrollJob = fs.coroutineScope?.launch { state.scrollToItem(scrollTarget(state, f)) }
            }
        } finally {
            fs.dragging = false
        }
    }
}

/**
 * Fast scrolling: a handle on the right edge that appears while the grid scrolls. Drag it to
 * fly through the whole library; a bubble shows the month you're at. Touches go to the handle
 * only when they land on it.
 */
@Composable
private fun FastScroller(
    fs: FastScrollState,
    state: androidx.compose.foundation.lazy.grid.LazyGridState,
    labelAt: (Int) -> String?,
    topPadding: androidx.compose.ui.unit.Dp,
    bottomPadding: androidx.compose.ui.unit.Dp,
) {
    fs.coroutineScope = androidx.compose.runtime.rememberCoroutineScope()
    val total by remember(state) { androidx.compose.runtime.derivedStateOf { state.layoutInfo.totalItemsCount } }
    val visible by remember(state) { androidx.compose.runtime.derivedStateOf { state.layoutInfo.visibleItemsInfo.size } }
    // Only worth it for long lists
    fs.enabled = total >= 80 && total >= visible * 4
    val scrolling = state.isScrollInProgress
    LaunchedEffect(scrolling, fs.dragging) {
        if (scrolling || fs.dragging) {
            fs.shown = true
        } else {
            kotlinx.coroutines.delay(1500)
            fs.shown = false
        }
    }
    if (!fs.enabled) return
    val alpha by androidx.compose.animation.core.animateFloatAsState(if (fs.shown) 1f else 0f, label = "scrollerAlpha")
    if (alpha == 0f && !fs.shown) return

    val listFraction by remember(state) {
        androidx.compose.runtime.derivedStateOf {
            val info = state.layoutInfo
            val span = info.totalItemsCount - info.visibleItemsInfo.size
            if (span <= 0) 0f else (state.firstVisibleItemIndex.toFloat() / span).coerceIn(0f, 1f)
        }
    }
    val fraction = if (fs.dragging) fs.dragFraction else listFraction

    androidx.compose.foundation.layout.BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .padding(top = topPadding + 8.dp, bottom = bottomPadding + 8.dp)
            .graphicsLayer { this.alpha = alpha }
    ) {
        val density = androidx.compose.ui.platform.LocalDensity.current
        val thumbH = 52.dp
        val trackPx = with(density) { (maxHeight - thumbH).toPx() }.coerceAtLeast(1f)
        fs.fraction = fraction
        fs.trackTop = with(density) { (topPadding + 8.dp).toPx() }
        fs.trackPx = trackPx
        fs.thumbPx = with(density) { thumbH.toPx() }
        val thumbY = with(density) { (fraction * trackPx).toDp() }

        // Month bubble while dragging
        if (fs.dragging) {
            labelAt(scrollTarget(state, fs.dragFraction))?.let { label ->
                Text(
                    label,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(end = 44.dp)
                        .offset(y = thumbY + 8.dp)
                        .background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(20.dp))
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                )
            }
        }
        // The handle
        Box(
            Modifier
                .align(Alignment.TopEnd)
                .offset(y = thumbY)
                .padding(end = 4.dp)
                .width(if (fs.dragging) 10.dp else 6.dp)
                .height(thumbH)
                .background(
                    if (fs.dragging) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                    RoundedCornerShape(5.dp),
                )
        )
    }
}

/**
 * Arrow keys in a grid: the item next to [current] in that direction (left / right through the list,
 * up / down to the photo above or below on screen). With nothing chosen yet, the first photo on screen.
 * Null when the key isn't an arrow.
 */
fun gridArrowStep(
    items: List<GalleryItem>,
    current: Long?,
    key: androidx.compose.ui.input.key.Key,
    state: androidx.compose.foundation.lazy.grid.LazyGridState,
): Long? {
    val Key = androidx.compose.ui.input.key.Key
    if (key != Key.DirectionLeft && key != Key.DirectionRight && key != Key.DirectionUp && key != Key.DirectionDown) return null
    if (items.isEmpty()) return null
    val ids = items.map { it.id }
    val visible = state.layoutInfo.visibleItemsInfo.filter { it.key is Long && (it.key as Long) in ids }
    val index = current?.let { c -> ids.indexOf(c) } ?: -1
    if (index < 0) return (visible.firstOrNull()?.key as? Long) ?: ids.first()
    return when (key) {
        Key.DirectionLeft -> ids[(index - 1).coerceAtLeast(0)]
        Key.DirectionRight -> ids[(index + 1).coerceAtMost(ids.lastIndex)]
        else -> {
            val down = key == Key.DirectionDown
            val here = visible.firstOrNull { it.key == current }
            // The photo straight above or below on screen (rows can be short at the end of a month)
            val target = here?.let { h ->
                visible
                    .filter { if (down) it.offset.y > h.offset.y else it.offset.y < h.offset.y }
                    .groupBy { it.offset.y }
                    .let { rows -> rows[if (down) rows.keys.minOrNull() else rows.keys.maxOrNull()] }
                    ?.minByOrNull { kotlin.math.abs(it.offset.x - h.offset.x) }
            }
            (target?.key as? Long) ?: run {
                // Off screen: a row's worth along the list
                val columns = (state.layoutInfo.visibleItemsInfo.maxOfOrNull { it.column } ?: 0) + 1
                ids[(index + if (down) columns else -columns).coerceIn(0, ids.lastIndex)]
            }
        }
    }
}
