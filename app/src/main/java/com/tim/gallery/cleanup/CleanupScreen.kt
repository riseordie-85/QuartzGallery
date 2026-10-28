package com.tim.gallery.cleanup

import android.text.format.Formatter
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.tim.gallery.GalleryViewModel
import com.tim.gallery.data.GalleryItem
import com.tim.gallery.data.thumb
import com.tim.gallery.ui.formatDuration
import com.tim.gallery.ui.itemCount
import com.tim.gallery.ui.mainContainerColor
import com.tim.gallery.ui.mainTopBarColors
import com.tim.gallery.ui.rememberMediaActions

private enum class CleanTab(val label: String) {
    LARGE("Biggest files"),
    DUPLICATES("Duplicates"),
    SIMILAR("Similar photos"),
    SCREENSHOTS("Screenshots"),
}

/**
 * Free up space: biggest files, exact duplicates, look-alike photos and screenshots.
 * Tap to select, long-press to look closer; selected items go to the trash (restorable).
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun CleanupScreen(vm: GalleryViewModel, onBack: () -> Unit, onOpenItem: (Long) -> Unit) {
    val context = LocalContext.current
    val actions = rememberMediaActions(vm)
    val all by vm.allItems.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableStateOf(CleanTab.LARGE) }
    var selected by remember { mutableStateOf(emptySet<Long>()) }
    var videosOnly by rememberSaveable { mutableStateOf(false) }

    // Scans run when their tab is first opened (results kept while you're on this screen)
    var dupGroups by remember { mutableStateOf<List<List<GalleryItem>>?>(null) }
    var simGroups by remember { mutableStateOf<List<List<GalleryItem>>?>(null) }
    var scanProgress by remember { mutableStateOf<Pair<Int, Int>?>(null) }

    // Each tab has its own selection, so nothing hidden on another tab gets trashed
    LaunchedEffect(tab) { selected = emptySet() }
    LaunchedEffect(tab, all) {
        if (all.isEmpty()) return@LaunchedEffect
        val ids = all.mapTo(HashSet()) { it.id }
        selected = selected.filterTo(HashSet()) { it in ids }
        when (tab) {
            CleanTab.DUPLICATES -> {
                scanProgress = 0 to 0
                val firstScanHere = selected.isEmpty()
                val groups = Cleanup.duplicates(context, all) { d, t -> scanProgress = d to t }
                dupGroups = groups
                scanProgress = null
                // Pre-select the extra copies (the first in each group is kept)
                if (firstScanHere) selected = groups.flatMap { g -> g.drop(1).map { it.id } }.toSet()
            }
            CleanTab.SIMILAR -> {
                scanProgress = 0 to 0
                simGroups = Cleanup.similar(context, all) { d, t -> scanProgress = d to t }
                scanProgress = null
            }
            else -> Unit
        }
    }

    val large = remember(all, videosOnly) {
        all.filter { !videosOnly || it.isVideo }.sortedByDescending { it.sizeBytes }.take(300)
    }
    val screenshots = remember(all) {
        all.filter { it.relativePath.contains("screenshot", ignoreCase = true) }
    }

    val selectedItems = all.filter { it.id in selected }
    val selectedBytes = selectedItems.sumOf { it.sizeBytes }

    com.tim.gallery.quartz.QuartzScaffold(
        topBar = {
            com.tim.gallery.quartz.QuartzTopBar(
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
                title = { Text("Free up space") },
            )
        },
        bottomBar = {
            if (selected.isNotEmpty()) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceContainer)
                        .navigationBarsPadding()
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("${itemCount(selected.size)} selected", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "Frees ${Formatter.formatShortFileSize(context, selectedBytes)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(onClick = { selected = emptySet() }) { Text("Clear") }
                    Button(onClick = { actions.trash(selectedItems) { ok -> if (ok) selected = emptySet() } }) { Text("Move to trash") }
                }
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(CleanTab.entries) { t ->
                    FilterChip(selected = tab == t, onClick = { tab = t }, label = { Text(t.label) })
                }
            }
            scanProgress?.let { (d, t) ->
                Column(Modifier.fillMaxWidth().padding(16.dp)) {
                    Text(
                        if (tab == CleanTab.SIMILAR) "Comparing photos… $d of $t" else "Checking files… $d of $t",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    LinearProgressIndicator(
                        progress = { if (t == 0) 0f else d.toFloat() / t },
                        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                    )
                }
            }

            fun toggle(id: Long) {
                selected = if (id in selected) selected - id else selected + id
            }

            LazyVerticalGrid(
                columns = GridCells.Adaptive(104.dp),
                contentPadding = PaddingValues(start = 2.dp, end = 2.dp, top = 8.dp, bottom = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                fun header(text: String, sub: String? = null, key: String) {
                    item(key = key, span = { GridItemSpan(maxLineSpan) }) {
                        Column(Modifier.padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 6.dp)) {
                            Text(text, style = MaterialTheme.typography.titleSmall)
                            if (sub != null) Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                fun tiles(list: List<GalleryItem>, keyPrefix: String, keepFirst: Boolean = false) {
                    items(list, key = { "$keyPrefix${it.id}" }) { item ->
                        Tile(
                            item = item,
                            selected = item.id in selected,
                            label = if (keepFirst && item == list.first()) "Keep" else Formatter.formatShortFileSize(context, item.sizeBytes),
                            onToggle = { toggle(item.id) },
                            onOpen = { onOpenItem(item.id) },
                        )
                    }
                }
                when (tab) {
                    CleanTab.LARGE -> {
                        item(key = "large-filter", span = { GridItemSpan(maxLineSpan) }) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(horizontal = 12.dp)) {
                                FilterChip(selected = !videosOnly, onClick = { videosOnly = false }, label = { Text("Everything") })
                                FilterChip(selected = videosOnly, onClick = { videosOnly = true }, label = { Text("Videos") })
                            }
                        }
                        tiles(large, "l")
                    }
                    CleanTab.DUPLICATES -> {
                        val groups = dupGroups
                        if (groups != null && groups.isEmpty()) {
                            item(span = { GridItemSpan(maxLineSpan) }) { Empty("No exact duplicates found.") }
                        }
                        groups?.forEachIndexed { i, g ->
                            header(
                                "${g.size} identical copies",
                                "Extra copies are selected — the one marked Keep stays. Saves ${Formatter.formatShortFileSize(context, g.drop(1).sumOf { it.sizeBytes })}",
                                "dh$i",
                            )
                            tiles(g, "d$i-", keepFirst = true)
                        }
                    }
                    CleanTab.SIMILAR -> {
                        val groups = simGroups
                        if (groups != null && groups.isEmpty()) {
                            item(span = { GridItemSpan(maxLineSpan) }) { Empty("No look-alike photos found.") }
                        }
                        groups?.forEachIndexed { i, g ->
                            header("${g.size} similar photos", "Tap the ones you don't need. Long-press to look closer.", "sh$i")
                            tiles(g, "s$i-")
                        }
                    }
                    CleanTab.SCREENSHOTS -> {
                        if (screenshots.isEmpty()) {
                            item(span = { GridItemSpan(maxLineSpan) }) { Empty("No screenshots.") }
                        } else {
                            item(key = "ss-all", span = { GridItemSpan(maxLineSpan) }) {
                                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 12.dp)) {
                                    Text(
                                        "${itemCount(screenshots.size)} · ${Formatter.formatShortFileSize(context, screenshots.sumOf { it.sizeBytes })}",
                                        style = MaterialTheme.typography.titleSmall,
                                        modifier = Modifier.weight(1f),
                                    )
                                    TextButton(onClick = { selected = selected + screenshots.map { it.id } }) { Text("Select all") }
                                }
                            }
                            tiles(screenshots, "ss")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Empty(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyLarge,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(48.dp),
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Tile(item: GalleryItem, selected: Boolean, label: String, onToggle: () -> Unit, onOpen: () -> Unit) {
    Box(
        Modifier
            .aspectRatio(1f)
            .combinedClickable(onClick = onToggle, onLongClick = onOpen)
    ) {
        AsyncImage(model = item.thumb(), contentDescription = item.name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        Text(
            if (item.isVideo) "$label · ${formatDuration(item.durationMs)}" else label,
            color = Color.White,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(4.dp)
                .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(4.dp))
                .padding(horizontal = 4.dp, vertical = 1.dp),
        )
        Icon(
            if (selected) Icons.Filled.CheckCircle else Icons.Outlined.RadioButtonUnchecked,
            contentDescription = if (selected) "Selected" else "Not selected",
            tint = if (selected) MaterialTheme.colorScheme.primary else Color.White,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(6.dp)
                .size(24.dp)
                .background(if (selected) MaterialTheme.colorScheme.surface else Color.Black.copy(alpha = 0.25f), CircleShape),
        )
    }
}
