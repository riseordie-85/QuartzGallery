package com.tim.gallery.ui

import kotlinx.coroutines.launch




import androidx.compose.foundation.layout.fillMaxHeight

import androidx.compose.foundation.layout.Spacer

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TextField
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Photo
import androidx.compose.material.icons.outlined.PhotoAlbum
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import android.widget.Toast
import com.tim.gallery.edit.BatchWatermarkDialog
import com.tim.gallery.personal.MoveToPersonalDialog
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tim.gallery.GalleryViewModel
import com.tim.gallery.TypeFilter
import com.tim.gallery.data.GalleryItem

enum class MainTab(val route: String, val label: String, val icon: ImageVector) {
    PHOTOS("photos", "Photos", Icons.Outlined.Photo),
    ALBUMS("albums", "Albums", Icons.Outlined.PhotoAlbum),
}

/** Tapping the tab you're already on: that screen jumps back to the top. */
object MainTabEvents {
    val reselected = kotlinx.coroutines.flow.MutableSharedFlow<MainTab>(extraBufferCapacity = 1)
}

/**
 * The Photos / Albums switcher. [content] gets the bottom bar to place (with the preview pane
 * on wide screens, Photos puts it under the grid only).
 */
@Composable
fun MainTabsLayout(current: MainTab, onSelect: (MainTab) -> Unit, content: @Composable (bottomBar: @Composable () -> Unit) -> Unit) {
    content { MainTabsBar(current, onSelect) }
}

@Composable
fun MainTabsBar(current: MainTab, onSelect: (MainTab) -> Unit) {
    NavigationBar(containerColor = mainNavBarColor()) {
        MainTab.entries.forEach { tab ->
            NavigationBarItem(
                selected = tab == current,
                onClick = { if (tab != current) onSelect(tab) else MainTabEvents.reselected.tryEmit(tab) },
                icon = { Icon(tab.icon, contentDescription = null) },
                label = { Text(tab.label) },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PhotosScreen(
    vm: GalleryViewModel,
    access: Access,
    onRequestAccess: () -> Unit,
    onOpen: (Long) -> Unit,
    onOpenSettings: () -> Unit,
    bottomBar: @Composable () -> Unit,
    onOpenPersonal: () -> Unit,
    onEdit: (Long) -> Unit = {},
) {
    val items by vm.items.collectAsStateWithLifecycle()
    val type by vm.typeFilter.collectAsStateWithLifecycle()
    val loading by vm.loading.collectAsStateWithLifecycle()
    val query by vm.query.collectAsStateWithLifecycle()
    var searching by rememberSaveable { mutableStateOf(vm.query.value.isNotEmpty()) }
    val searchFocus = remember { FocusRequester() }
    // Typed text lives here; the grid filters a moment after typing pauses
    var searchText by rememberSaveable { mutableStateOf(vm.query.value) }
    LaunchedEffect(searchText) {
        if (searchText.isNotEmpty()) kotlinx.coroutines.delay(250)
        vm.query.value = searchText
    }
    val actions = rememberMediaActions(vm)
    val paneActive = rememberPreviewPaneActive()
    var previewId by rememberSaveable { mutableStateOf<Long?>(null) }
    val gridState = androidx.compose.foundation.lazy.grid.rememberLazyGridState()

    var selected by remember { mutableStateOf(emptySet<Long>()) }
    var moving by remember { mutableStateOf<List<GalleryItem>?>(null) }
    var watermarking by remember { mutableStateOf<List<GalleryItem>?>(null) }
    var hiding by remember { mutableStateOf<List<GalleryItem>?>(null) }
    var copying by remember { mutableStateOf<List<GalleryItem>?>(null) }
    val selectedItems = { items.filter { it.id in selected } }

    BackHandler(enabled = searching && selected.isEmpty()) {
        searching = false
        searchText = ""
    }
    BackHandler(enabled = selected.isNotEmpty()) { selected = emptySet() }

    // Forget selections for items that were moved away or deleted
    LaunchedEffect(items) {
        if (selected.isNotEmpty()) {
            val ids = items.mapTo(HashSet()) { it.id }
            selected = selected.filterTo(HashSet()) { it in ids }
        }
    }

    val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior()
    // Tap "Photos" again: back to the top
    LaunchedEffect(Unit) {
        MainTabEvents.reselected.collect { tab ->
            if (tab == MainTab.PHOTOS) {
                scrollBehavior.state.heightOffset = 0f
                // Its own job: touching the grid mid-scroll cancels just this scroll, not the listener
                launch {
                    if (gridState.firstVisibleItemIndex > 60) gridState.scrollToItem(30)
                    gridState.animateScrollToItem(0)
                }
            }
        }
    }

    Scaffold(
        containerColor = mainContainerColor(),
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            if (selected.isEmpty()) {
                if (searching) {
                    TopAppBar(
                        navigationIcon = {
                            IconButton(onClick = {
                                searching = false
                                searchText = ""
                            }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Close search") }
                        },
                        title = {
                            TextField(
                                value = searchText,
                                onValueChange = { searchText = it },
                                placeholder = { Text("Name, album, month, year, video…") },
                                singleLine = true,
                                colors = TextFieldDefaults.colors(
                                    focusedContainerColor = Color.Transparent,
                                    unfocusedContainerColor = Color.Transparent,
                                    focusedIndicatorColor = Color.Transparent,
                                    unfocusedIndicatorColor = Color.Transparent,
                                ),
                                modifier = Modifier.fillMaxWidth().focusRequester(searchFocus),
                            )
                            LaunchedEffect(Unit) { runCatching { searchFocus.requestFocus() } }
                        },
                        actions = {
                            if (searchText.isNotEmpty()) {
                                IconButton(onClick = { searchText = "" }) {
                                    Icon(Icons.Filled.Close, contentDescription = "Clear")
                                }
                            }
                        },
                        colors = mainTopBarColors(),
                    )
                } else {
                    TopAppBar(
                        title = { Text("Quartz Gallery") },
                        actions = {
                            IconButton(onClick = { searching = true }) {
                                Icon(Icons.Outlined.Search, contentDescription = "Search")
                            }
                            SortButton()
                            IconButton(onClick = onOpenSettings) {
                                Icon(Icons.Outlined.Settings, contentDescription = "Settings")
                            }
                        },
                        colors = mainTopBarColors(),
                        scrollBehavior = scrollBehavior,
                    )
                }
            } else {
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
            }
        },
        // With the preview pane, the Photos / Albums bar sits under the grid only (left half)
        bottomBar = { if (selected.isEmpty() && !paneActive) bottomBar() },
    ) { padding ->
        if (loading) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            return@Scaffold
        }
        PreviewPaneLayout(
            active = paneActive,
            items = items,
            previewId = previewId,
            onPreviewChange = { previewId = it },
            onOpenFull = onOpen,
            onEdit = onEdit,
            actions = actions,
            contentPadding = padding,
            bottomBar = if (selected.isEmpty()) bottomBar else null,
        ) { gridModifier, gridPadding ->
            MediaGrid(
                items = items,
                selected = selected,
                onOpen = { if (paneActive) previewId = it.id else onOpen(it.id) },
                onSelectionChange = { selected = it },
                contentPadding = gridPadding,
                modifier = gridModifier,
                focusId = if (paneActive) previewId else null,
                gridState = gridState,
                empty = {
                    Text(
                        if (query.isNotBlank()) "Nothing matches “$query”." else "Nothing to show here yet.",
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.fillMaxWidth().padding(48.dp),
                    )
                },
                header = {
                    FiltersHeader(
                        access = access,
                        onRequestAccess = onRequestAccess,
                        type = type,
                        onType = { vm.typeFilter.value = it },
                    )
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
        excludeKey = null,
        onDismiss = { moving = null },
        onMoved = { if (it > 0) selected = emptySet() },
    )
}

@Composable
private fun FiltersHeader(
    access: Access,
    onRequestAccess: () -> Unit,
    type: TypeFilter,
    onType: (TypeFilter) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
        if (access == Access.PARTIAL) {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
            ) {
                Row(Modifier.padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "You've only allowed access to some photos.",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f).padding(vertical = 12.dp),
                    )
                    TextButton(onClick = onRequestAccess) { Text("Change") }
                }
            }
        }
        TypeFilterChips(type, onType)
    }
}

/** All / Photos / Videos. */
@Composable
fun TypeFilterChips(type: TypeFilter, onType: (TypeFilter) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        TypeFilter.entries.forEach { t ->
            val icon: ImageVector = when (t) {
                TypeFilter.ALL -> Icons.Outlined.PhotoLibrary
                TypeFilter.PHOTOS -> Icons.Outlined.Image
                TypeFilter.VIDEOS -> Icons.Outlined.Videocam
            }
            FilterChip(
                selected = t == type,
                onClick = { onType(t) },
                label = { Text(t.label) },
                leadingIcon = { Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp)) },
            )
        }
    }
}
