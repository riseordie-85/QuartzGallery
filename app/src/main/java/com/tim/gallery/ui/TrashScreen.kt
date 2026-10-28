package com.tim.gallery.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.DeleteForever
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.RestoreFromTrash
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material3.AlertDialog
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tim.gallery.GalleryViewModel
import com.tim.gallery.data.GalleryItem
import kotlin.math.ceil
import kotlin.math.max

/** "12 days left" style countdown until Android deletes a trashed item for good. */
fun daysLeft(item: GalleryItem, now: Long = System.currentTimeMillis()): Int =
    max(0, ceil((item.expiresMillis - now) / 86_400_000.0).toInt())

fun daysLeftLabel(item: GalleryItem): String {
    val d = daysLeft(item)
    return when (d) {
        0 -> "Today"
        1 -> "1 day left"
        else -> "$d days left"
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrashScreen(
    vm: GalleryViewModel,
    onBack: () -> Unit,
    onOpenItem: (Long) -> Unit,
) {
    val items by vm.trash.collectAsStateWithLifecycle()
    val actions = rememberMediaActions(vm)
    var selected by remember { mutableStateOf(emptySet<Long>()) }
    var confirmEmpty by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf<List<GalleryItem>?>(null) }
    val selectedItems = { items.filter { it.id in selected } }

    BackHandler(enabled = selected.isNotEmpty()) { selected = emptySet() }

    LaunchedEffect(items) {
        if (selected.isNotEmpty()) {
            val ids = items.mapTo(HashSet()) { it.id }
            selected = selected.filterTo(HashSet()) { it in ids }
        }
    }

    // Keyboard: Ctrl+A select all, Delete deletes the selection for good (after asking), R restores it, Esc
    val keys = Modifier.keyboardShortcuts { k ->
        val Key = androidx.compose.ui.input.key.Key
        when {
            k.isBack -> { if (selected.isNotEmpty()) selected = emptySet() else onBack(); true }
            k.ctrl && k.key == Key.A -> { selected = items.mapTo(HashSet()) { it.id }; true }
            k.plain && k.key == Key.Delete && selected.isNotEmpty() -> { confirmDelete = selectedItems(); true }
            k.plain && k.key == Key.R && selected.isNotEmpty() -> { actions.restore(selectedItems()); selected = emptySet(); true }
            else -> false
        }
    }

    val gridState = androidx.compose.foundation.lazy.grid.rememberLazyGridState()
    com.tim.gallery.quartz.QuartzScaffold(
        modifier = keys,
        scroll = { gridState.firstVisibleItemIndex to gridState.firstVisibleItemScrollOffset },
        topBar = {
            if (selected.isEmpty()) {
                com.tim.gallery.quartz.QuartzTopBar(
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    },
                    title = {
                        Column {
                            Text("Trash")
                            Text(
                                itemCount(items.size),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                    actions = {
                        if (items.isNotEmpty()) {
                            TextButton(onClick = { confirmEmpty = true }) { Text("Empty trash") }
                        }
                    },
                )
            } else {
                com.tim.gallery.quartz.QuartzTopBar(
                    navigationIcon = {
                        IconButton(onClick = { selected = emptySet() }) {
                            Icon(Icons.Filled.Close, contentDescription = "Clear selection")
                        }
                    },
                    title = { Text("${selected.size}") },
                    actions = {
                        IconButton(onClick = { selected = items.mapTo(HashSet()) { it.id } }) {
                            Icon(Icons.Outlined.SelectAll, contentDescription = "Select all")
                        }
                        IconButton(onClick = {
                            actions.restore(selectedItems()) { ok -> if (ok) selected = emptySet() }
                        }) {
                            Icon(Icons.Outlined.RestoreFromTrash, contentDescription = "Restore")
                        }
                        IconButton(onClick = { confirmDelete = selectedItems() }) {
                            Icon(Icons.Outlined.DeleteForever, contentDescription = "Delete forever")
                        }
                    },
                )
            }
        },
    ) { padding ->
        MediaGrid(
            gridState = gridState,
            items = items,
            selected = selected,
            onOpen = { onOpenItem(it.id) },
            onSelectionChange = { selected = it },
            contentPadding = padding,
            groupByMonth = false,
            badge = { daysLeft(it).let { d -> if (d <= 1) "1d" else "${d}d" } },
            header = {
                if (items.isNotEmpty()) {
                    Text(
                        "Items are deleted for good after 30 days. Long-press to select, then restore or delete.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
            },
            empty = {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 64.dp),
                ) {
                    Icon(
                        Icons.Outlined.DeleteOutline,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(64.dp),
                    )
                    Spacer(Modifier.height(12.dp))
                    Text("Trash is empty", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Deleted photos and videos stay here for 30 days before they're gone for good.",
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                    )
                }
            },
        )
    }

    if (confirmEmpty) {
        AlertDialog(
            onDismissRequest = { confirmEmpty = false },
            title = { Text("Empty trash?") },
            text = { Text("All ${itemCount(items.size)} will be deleted forever. This can't be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmEmpty = false
                    actions.deleteForever(items) { ok -> if (ok) selected = emptySet() }
                }) { Text("Delete forever") }
            },
            dismissButton = { TextButton(onClick = { confirmEmpty = false }) { Text("Cancel") } },
        )
    }

    confirmDelete?.let { toDelete ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Delete forever?") },
            text = { Text("${itemCount(toDelete.size)} will be permanently deleted. This can't be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = null
                    actions.deleteForever(toDelete) { ok -> if (ok) selected = emptySet() }
                }) { Text("Delete forever") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Cancel") } },
        )
    }
}
