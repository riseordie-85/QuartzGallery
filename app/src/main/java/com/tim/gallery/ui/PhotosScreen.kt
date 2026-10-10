package com.tim.gallery.ui

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

@Composable
fun MainTabsBar(current: MainTab, onSelect: (MainTab) -> Unit) {
    NavigationBar(containerColor = mainNavBarColor()) {
        MainTab.entries.forEach { tab ->
            NavigationBarItem(
                selected = tab == current,
                onClick = { if (tab != current) onSelect(tab) },
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
) {
    val items by vm.items.collectAsStateWithLifecycle()
    val type by vm.typeFilter.collectAsStateWithLifecycle()
    val loading by vm.loading.collectAsStateWithLifecycle()
    val actions = rememberMediaActions(vm)

    var selected by remember { mutableStateOf(emptySet<Long>()) }
    var moving by remember { mutableStateOf<List<GalleryItem>?>(null) }
    var watermarking by remember { mutableStateOf<List<GalleryItem>?>(null) }
    val selectedItems = { items.filter { it.id in selected } }

    BackHandler(enabled = selected.isNotEmpty()) { selected = emptySet() }

    // Forget selections for items that were moved away or deleted
    LaunchedEffect(items) {
        if (selected.isNotEmpty()) {
            val ids = items.mapTo(HashSet()) { it.id }
            selected = selected.filterTo(HashSet()) { it in ids }
        }
    }

    val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior()

    Scaffold(
        containerColor = mainContainerColor(),
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            if (selected.isEmpty()) {
                TopAppBar(
                    title = { Text("Quartz Gallery") },
                    actions = {
                        IconButton(onClick = onOpenSettings) {
                            Icon(Icons.Outlined.Settings, contentDescription = "Settings")
                        }
                    },
                    colors = mainTopBarColors(),
                    scrollBehavior = scrollBehavior,
                )
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
                )
            }
        },
        bottomBar = { if (selected.isEmpty()) bottomBar() },
    ) { padding ->
        if (loading) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            return@Scaffold
        }
        MediaGrid(
            items = items,
            selected = selected,
            onOpen = { onOpen(it.id) },
            onSelectionChange = { selected = it },
            contentPadding = padding,
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
        Row(
            modifier = Modifier.padding(horizontal = 12.dp),
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
}
