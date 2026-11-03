package com.tim.gallery.ui

import android.text.format.Formatter
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.material.icons.automirrored.outlined.DriveFileMove
import androidx.compose.material.icons.automirrored.outlined.VolumeOff
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Screenshot
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
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.shape.RoundedCornerShape
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.material.icons.outlined.MotionPhotosOn
import androidx.compose.material.icons.outlined.ContentCut
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
import androidx.compose.runtime.mutableLongStateOf
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
    onEditAgain: (Long) -> Unit = {},
    onSetWallpaper: (Long) -> Unit = {},
    /** In an album: page through only its photos or only its videos. */
    typeFilter: com.tim.gallery.TypeFilter = com.tim.gallery.TypeFilter.ALL,
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
    val sourceItems by source.collectAsStateWithLifecycle()
    val items = remember(sourceItems, typeFilter) {
        if (typeFilter == com.tim.gallery.TypeFilter.ALL || albumKey == null) sourceItems else sourceItems.filter { typeFilter.matches(it) }
    }
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
    var zoomKey by remember { mutableStateOf<ZoomKey?>(null) }
    var infoFor by remember { mutableStateOf<GalleryItem?>(null) }
    var moving by remember { mutableStateOf<List<GalleryItem>?>(null) }
    var stickerFrom by remember { mutableStateOf<GalleryItem?>(null) }
    var hiding by remember { mutableStateOf<GalleryItem?>(null) }
    var copying by remember { mutableStateOf<List<GalleryItem>?>(null) }
    var renaming by remember { mutableStateOf<GalleryItem?>(null) }
    var editChoice by remember { mutableStateOf<GalleryItem?>(null) }
    var confirmDeleteForever by remember { mutableStateOf<GalleryItem?>(null) }
    var menuOpen by remember { mutableStateOf(false) }
    var motionFile by remember { mutableStateOf<Pair<Long, java.io.File>?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(pagerState.currentPage) {
        zoomed = false
        motionFile = null
        zoomKey = null
    }

    // Stay on the same photo if the list changes order (sorting, renames, items arriving)
    // Saved, so coming back from the video player (or any other screen) returns to the same item
    // even if the list shifted meanwhile (e.g. a new photo arrived at the top)
    var currentId by rememberSaveable { mutableLongStateOf(startId) }
    val pageSynced = remember { booleanArrayOf(false) }
    LaunchedEffect(pagerState.currentPage) {
        // On return, the restored page number may point at a different item now: let the
        // list check below move to the remembered item first
        if (!pageSynced[0]) {
            pageSynced[0] = true
            return@LaunchedEffect
        }
        items.getOrNull(pagerState.currentPage)?.id?.let { currentId = it }
    }
    LaunchedEffect(items) {
        val idx = items.indexOfFirst { it.id == currentId }
        if (idx >= 0 && idx != pagerState.currentPage) {
            pagerState.scrollToPage(idx)
        } else if (idx < 0) {
            items.getOrNull(pagerState.currentPage)?.id?.let { currentId = it }
        }
    }
    val current = items.getOrNull(pagerState.currentPage)

    // Videos play right in the viewer: one player, moved to the video page you're on
    val video = rememberInlineVideo()
    val settledItem = items.getOrNull(pagerState.settledPage)
    LaunchedEffect(settledItem?.id, settledItem?.uri) {
        if (settledItem?.isVideo == true) video.load(settledItem) else video.clear()
        Playback.videoShowing = settledItem?.isVideo == true
    }
    // While a video plays, everything on top fades away after a moment (tap to bring it back)
    LaunchedEffect(chromeVisible, video.isPlaying, video.scrubbing, current?.id) {
        if (current?.isVideo == true && chromeVisible && video.isPlaying && !video.scrubbing) {
            kotlinx.coroutines.delay(3000)
            chromeVisible = false
        }
    }
    LaunchedEffect(video.ended) { if (video.ended) chromeVisible = true }
    if (!chromeVisible) ImmersiveMode()
    // Ultra HDR photos: switch the screen into HDR mode while one is showing
    val hdr by produceState(false, current?.id, current?.modified) {
        value = current?.let { UltraHdr.isUltraHdr(context, it) } ?: false
    }
    HdrWindowMode(hdr)

    fun captureStill() {
        val item = current?.takeIf { it.isVideo && video.itemId == it.id } ?: return
        video.player.pause()
        val at = video.player.currentPosition
        val frame = video.grabFrame()
        scope.launch {
            val saved = saveVideoStill(context, item.uri, item.name, at, frame)
            actions.toast(if (saved != null) "Still saved to ${com.tim.gallery.data.MediaSaver.FOLDER_LABEL}" else "Couldn't capture this frame")
            if (saved != null) vm.refresh()
        }
    }

    // Keyboard: arrows/Page keys go between photos (or pan when zoomed in), +/-/0 zoom, F favorite,
    // Delete to trash, E edit, I details, Enter/Space plays a video, Esc back
    fun goTo(page: Int) {
        if (items.isEmpty()) return
        val p = page.coerceIn(0, items.lastIndex)
        if (p != pagerState.currentPage) scope.launch { pagerState.animateScrollToPage(p) }
    }
    val keys = Modifier.keyboardShortcuts { k ->
        val item = current ?: return@keyboardShortcuts false
        val Key = androidx.compose.ui.input.key.Key
        val dx = when (k.key) { Key.DirectionLeft -> -1f; Key.DirectionRight -> 1f; else -> 0f }
        val dy = when (k.key) { Key.DirectionUp -> -1f; Key.DirectionDown -> 1f; else -> 0f }
        when {
            k.isBack -> { onBack(); true }
            !k.plain -> false
            zoomed && (dx != 0f || dy != 0f) -> { zoomKey = ZoomKey(ZoomKey.Action.PAN, dx, dy, item.id); true }
            k.isAny(Key.DirectionLeft, Key.PageUp) -> { goTo(pagerState.currentPage - 1); true }
            k.isAny(Key.DirectionRight, Key.PageDown) -> { goTo(pagerState.currentPage + 1); true }
            k.key == Key.MoveHome -> { goTo(0); true }
            k.key == Key.MoveEnd -> { goTo(items.lastIndex); true }
            !item.isVideo && k.isZoomIn -> { zoomKey = ZoomKey(ZoomKey.Action.IN, itemId = item.id); true }
            !item.isVideo && k.isZoomOut -> { zoomKey = ZoomKey(ZoomKey.Action.OUT, itemId = item.id); true }
            !item.isVideo && k.isZoomReset -> { zoomKey = ZoomKey(ZoomKey.Action.RESET, itemId = item.id); true }
            item.isVideo && (k.isEnter || k.key == Key.Spacebar || k.key == Key.K) -> { video.togglePlay(); chromeVisible = true; true }
            item.isVideo && k.key == Key.J -> { video.jump(-10_000); true }
            item.isVideo && k.key == Key.L -> { video.jump(10_000); true }
            item.isVideo && k.key == Key.Comma -> { video.stepFrame(-1); true }
            item.isVideo && k.key == Key.Period -> { video.stepFrame(1); true }
            item.isVideo && k.key == Key.M -> { Playback.muted = !Playback.muted; true }
            item.isVideo && k.key == Key.C -> { captureStill(); true }
            k.key == Key.Spacebar -> { chromeVisible = !chromeVisible; true }
            k.key == Key.I -> { infoFor = item; true }
            k.key == Key.Delete -> {
                if (inTrash) confirmDeleteForever = item else actions.trash(listOf(item))
                true
            }
            inTrash -> if (k.key == Key.R) { actions.restore(listOf(item)); true } else false
            k.key == Key.F -> { actions.setFavorite(listOf(item), favorite = !item.isFavorite); true }
            k.key == Key.E -> {
                if (item.isVideo) onEdit(item.id)
                else if (com.tim.gallery.edit.EditRecipes.has(context, item.id)) editChoice = item else onEdit(item.id)
                true
            }
            k.key == Key.S -> { actions.share(listOf(item)); true }
            k.key == Key.M -> { moving = listOf(item); true }
            k.key == Key.F2 -> { renaming = item; true }
            else -> false
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black).then(keys)) {
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
                    video = video,
                    onTap = { chromeVisible = !chromeVisible },
                )
            } else {
                val request = remember(item.uri, item.modified) {
                    ImageRequest.Builder(context)
                        .data(item.uri)
                        .memoryCacheKey("${item.uri}#${item.modified}")
                        .size(2560)
                        .build()
                }
                val motionOffset by produceState<Long?>(null, item.id, item.modified) {
                    value = MotionPhotos.videoOffset(context, item)
                }
                val animated = rememberAnimatedImage(item.uri, item.mimeType, item.name, item.modified)
                Box(Modifier.fillMaxSize()) {
                    ZoomableImage(
                        model = request,
                        content = animated?.let { d ->
                            { m -> AnimatedDrawableView(d, playing = page == pagerState.currentPage, modifier = m) }
                        },
                        contentDescription = item.name,
                        isCurrent = page == pagerState.currentPage,
                        onTap = { chromeVisible = !chromeVisible },
                        onZoomChange = { if (page == pagerState.currentPage) zoomed = it },
                        zoomKey = zoomKey?.takeIf { page == pagerState.currentPage && it.itemId == item.id },
                        onLongPress = motionOffset?.let { off ->
                            {
                                scope.launch { motionFile = MotionPhotos.extract(context, item, off)?.let { item.id to it } }
                            }
                        },
                    )
                    val playing = motionFile?.takeIf { it.first == item.id && page == pagerState.currentPage }
                    if (playing != null) {
                        MotionPlayer(playing.second, onEnded = { motionFile = null }, modifier = Modifier.fillMaxSize())
                    }
                    if (motionOffset != null && chromeVisible && playing == null) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .statusBarsPadding()
                                .padding(top = 64.dp, end = 12.dp)
                                .clip(RoundedCornerShape(16.dp))
                                .background(Color.Black.copy(alpha = 0.5f))
                                .clickable {
                                    motionOffset?.let { off ->
                                        scope.launch { motionFile = MotionPhotos.extract(context, item, off)?.let { item.id to it } }
                                    }
                                }
                                .padding(horizontal = 10.dp, vertical = 6.dp),
                        ) {
                            Icon(Icons.Outlined.MotionPhotosOn, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Play motion", color = Color.White, style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }
        }

        if (current != null) {
            AnimatedVisibility(
                visible = chromeVisible,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.align(Alignment.TopCenter),
            ) {
                // Back on the left, details and more on the right: small floating pills, nothing across the picture
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                ) {
                    Box(Modifier.size(48.dp).viewerPill(CircleShape), contentAlignment = Alignment.Center) {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
                        }
                    }
                    Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                        Text(
                            formatDate(current.dateMillis),
                            color = Color.White,
                            style = MaterialTheme.typography.titleSmall.copy(shadow = TextShadow),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            if (inTrash) "In trash · ${daysLeftLabel(current)}"
                            else "${current.album} · ${pagerState.currentPage + 1} of ${items.size}",
                            color = Color.White.copy(alpha = 0.85f),
                            style = MaterialTheme.typography.bodySmall.copy(shadow = TextShadow),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Row(Modifier.viewerPill(RoundedCornerShape(50)).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { infoFor = current }) {
                            Icon(Icons.Outlined.Info, contentDescription = "Details", tint = Color.White)
                        }
                        if (!inTrash) {
                            Box {
                                IconButton(onClick = { menuOpen = true }) {
                                    Icon(Icons.Filled.MoreVert, contentDescription = "More", tint = Color.White)
                                }
                            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                                if (current.isVideo) {
                                    DropdownMenuItem(
                                        text = { Text("Open in player") },
                                        leadingIcon = { Icon(Icons.Filled.PlayArrow, contentDescription = null) },
                                        onClick = {
                                            menuOpen = false
                                            onPlay(current.id)
                                        },
                                    )
                                }
                                if (!current.isVideo) {
                                    DropdownMenuItem(
                                        text = { Text("Set as wallpaper") },
                                        leadingIcon = { Icon(Icons.Outlined.Wallpaper, contentDescription = null) },
                                        onClick = {
                                            menuOpen = false
                                            onSetWallpaper(current.id)
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
            }

            AnimatedVisibility(
                visible = chromeVisible,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.align(Alignment.BottomCenter),
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 12.dp),
                ) {
                    if (current.isVideo) {
                        // Capture still · play and time · sound
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                        ) {
                            Box(Modifier.size(44.dp).viewerPill(CircleShape), contentAlignment = Alignment.Center) {
                                IconButton(onClick = { captureStill() }) {
                                    Icon(Icons.Outlined.Screenshot, contentDescription = "Capture still", tint = Color.White)
                                }
                            }
                            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier
                                        .viewerPill(RoundedCornerShape(50))
                                        .clickable { video.togglePlay() }
                                        .padding(start = 12.dp, end = 18.dp, top = 6.dp, bottom = 6.dp),
                                ) {
                                    Icon(
                                        if (video.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                                        contentDescription = if (video.isPlaying) "Pause" else "Play",
                                        tint = Color.White,
                                        modifier = Modifier.size(28.dp),
                                    )
                                    Spacer(Modifier.width(10.dp))
                                    Text(
                                        "${formatClock(video.position)}  /  ${formatClock(video.duration.takeIf { it > 0 } ?: current.durationMs)}",
                                        color = Color.White,
                                        style = MaterialTheme.typography.titleSmall,
                                    )
                                }
                            }
                            Box(Modifier.size(44.dp).viewerPill(CircleShape), contentAlignment = Alignment.Center) {
                                IconButton(onClick = { Playback.muted = !Playback.muted }) {
                                    Icon(
                                        if (Playback.muted) Icons.AutoMirrored.Outlined.VolumeOff else Icons.AutoMirrored.Outlined.VolumeUp,
                                        contentDescription = if (Playback.muted) "Turn sound on" else "Mute",
                                        tint = Color.White,
                                    )
                                }
                            }
                        }
                    }

                    // Every item, with the video's frames opened out for scrubbing
                    Filmstrip(
                        items = items,
                        current = pagerState.currentPage,
                        video = video,
                        onSelect = { i -> scope.launch { pagerState.scrollToPage(i) } },
                    )

                    // The actions, in a floating pill
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.viewerPill(RoundedCornerShape(50)).padding(horizontal = 14.dp, vertical = 4.dp),
                    ) {
                        if (inTrash) {
                            PillButton(Icons.Outlined.RestoreFromTrash, "Restore") { actions.restore(listOf(current)) }
                            PillButton(Icons.Outlined.DeleteForever, "Delete forever") { confirmDeleteForever = current }
                        } else {
                            PillButton(
                                if (current.isFavorite) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                                if (current.isFavorite) "Remove from favorites" else "Favorite",
                            ) { actions.setFavorite(listOf(current), favorite = !current.isFavorite) }
                            if (current.isVideo) {
                                PillButton(Icons.Outlined.ContentCut, "Trim and video tools") { onEdit(current.id) }
                            } else {
                                PillButton(Icons.Outlined.Edit, "Edit") {
                                    if (com.tim.gallery.edit.EditRecipes.has(context, current.id)) editChoice = current else onEdit(current.id)
                                }
                            }
                            PillButton(Icons.Outlined.Share, "Share") { actions.share(listOf(current)) }
                            PillButton(Icons.AutoMirrored.Outlined.DriveFileMove, "Move") { moving = listOf(current) }
                            PillButton(Icons.Outlined.Delete, "Delete") { actions.trash(listOf(current)) }
                        }
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

    editChoice?.let { item ->
        AlertDialog(
            onDismissRequest = { editChoice = null },
            title = { Text("Edit") },
            text = { Text("This is an edited copy. Reopen the edit to change its crop, filters, text and stickers — or edit this picture as it is now.") },
            confirmButton = {
                TextButton(onClick = {
                    editChoice = null
                    onEditAgain(item.id)
                }) { Text("Reopen edit") }
            },
            dismissButton = {
                TextButton(onClick = {
                    editChoice = null
                    onEdit(item.id)
                }) { Text("Edit as is") }
            },
        )
    }

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

}

/**
 * A video page: its thumbnail, with the video playing over it once it's the page you're on.
 * Tap shows or hides the controls; double-tap the left or right side to jump 10 seconds.
 */
@Composable
private fun VideoPage(item: GalleryItem, video: InlineVideo, onTap: () -> Unit) {
    val playingHere = video.itemId == item.id
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.fillMaxSize(),
    ) {
        AsyncImage(
            model = item.thumb(),
            contentDescription = item.name,
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize(),
        )
        if (playingHere) InlineVideoSurface(video, item.id)
        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(playingHere) {
                    detectTapGestures(
                        onTap = { onTap() },
                        onDoubleTap = { p -> if (playingHere) video.jump(if (p.x < size.width / 2f) -10_000L else 10_000L) },
                    )
                },
        )
    }
}

/** Dark see-through floating pill or circle, with a faint rim, for controls over photos and videos. */
private fun Modifier.viewerPill(shape: androidx.compose.ui.graphics.Shape): Modifier = this
    .clip(shape)
    .background(Color(0xCC1E1E22))
    .border(androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.12f)), shape)

private val TextShadow = androidx.compose.ui.graphics.Shadow(Color.Black.copy(alpha = 0.7f), androidx.compose.ui.geometry.Offset(0f, 1f), 6f)

@Composable
private fun PillButton(icon: ImageVector, label: String, onClick: () -> Unit) {
    IconButton(onClick = onClick) { Icon(icon, contentDescription = label, tint = Color.White) }
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
