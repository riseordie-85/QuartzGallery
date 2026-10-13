package com.tim.gallery.ui

import android.net.Uri
import android.text.format.Formatter
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.VolumeOff
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.OpenInFull
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.tim.gallery.data.GalleryItem
import com.tim.gallery.data.MediaDetails
import com.tim.gallery.data.Metadata
import com.tim.gallery.data.thumb
import com.tim.gallery.settings.AppSettings
import kotlinx.coroutines.launch

/**
 * True when the photo grid should get a preview pane beside it: tablets and unfolded
 * foldables held sideways, with the setting on. Never on regular phones.
 */
@Composable
fun rememberPreviewPaneActive(): Boolean {
    val enabled by AppSettings.previewPane.collectAsState()
    val config = LocalConfiguration.current
    return enabled && config.smallestScreenWidthDp >= 600 && config.screenWidthDp > config.screenHeightDp
}

/**
 * The grid, plus (when [active]) a preview of [previewId] on the right. [grid] gets its
 * modifier and padding; tapping a tile should call [onPreviewChange] instead of opening it.
 */
@Composable
fun PreviewPaneLayout(
    active: Boolean,
    items: List<GalleryItem>,
    previewId: Long?,
    onPreviewChange: (Long?) -> Unit,
    onOpenFull: (Long) -> Unit,
    onEdit: (Long) -> Unit,
    actions: MediaActions,
    contentPadding: PaddingValues,
    grid: @Composable (Modifier) -> Unit,
) {
    if (!active) {
        grid(Modifier.fillMaxSize())
        return
    }
    Row(Modifier.fillMaxSize()) {
        Box(Modifier.weight(0.5f).fillMaxHeight()) { grid(Modifier.fillMaxSize()) }
        VerticalDivider(
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
            modifier = Modifier.padding(top = contentPadding.calculateTopPadding(), bottom = contentPadding.calculateBottomPadding()),
        )
        PreviewPane(
            items = items,
            previewId = previewId,
            onPreviewChange = onPreviewChange,
            onOpenFull = onOpenFull,
            onEdit = onEdit,
            actions = actions,
            modifier = Modifier
                .weight(0.5f)
                .fillMaxHeight()
                .padding(top = contentPadding.calculateTopPadding(), bottom = contentPadding.calculateBottomPadding()),
        )
    }
}

@Composable
private fun PreviewPane(
    items: List<GalleryItem>,
    previewId: Long?,
    onPreviewChange: (Long?) -> Unit,
    onOpenFull: (Long) -> Unit,
    onEdit: (Long) -> Unit,
    actions: MediaActions,
    modifier: Modifier,
) {
    val found = items.indexOfFirst { it.id == previewId }
    // Where the previewed item was: if it goes away (trash, move, filtered out by a search),
    // the one now in its place is shown, without forgetting which one you picked
    val lastIndex = remember { intArrayOf(0) }
    if (found >= 0) lastIndex[0] = found
    if (items.isEmpty() || previewId == null) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = modifier.padding(32.dp),
        ) {
            Icon(
                Icons.Outlined.PhotoLibrary,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(48.dp),
            )
            Spacer(Modifier.height(12.dp))
            Text(
                "Tap a photo or video to preview it here",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }
    val index = if (found >= 0) found else lastIndex[0].coerceIn(0, items.lastIndex)

    val pagerState = rememberPagerState(initialPage = index) { items.size }
    // A tile was tapped: jump to it
    LaunchedEffect(previewId, items) {
        val i = items.indexOfFirst { it.id == previewId }
        if (i >= 0 && i != pagerState.currentPage) pagerState.scrollToPage(i)
    }
    // Swiped: tell the grid (skipped on the first run, so a stand-in doesn't replace your pick)
    val firstRun = remember { booleanArrayOf(true) }
    LaunchedEffect(pagerState.settledPage) {
        if (firstRun[0]) {
            firstRun[0] = false
            return@LaunchedEffect
        }
        items.getOrNull(pagerState.settledPage)?.id?.let { if (it != previewId) onPreviewChange(it) }
    }
    val current = items.getOrNull(pagerState.currentPage) ?: items[index]

    Column(modifier.padding(12.dp)) {
        HorizontalPager(
            state = pagerState,
            key = { items.getOrNull(it)?.id ?: it.toLong() },
            pageSpacing = 12.dp,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(Color.Black),
        ) { page ->
            val item = items.getOrNull(page) ?: return@HorizontalPager
            PreviewMedia(
                item = item,
                isCurrent = page == pagerState.settledPage,
                onOpen = { onOpenFull(item.id) },
            )
        }
        PreviewDetails(current)
        Row(horizontalArrangement = Arrangement.SpaceEvenly, modifier = Modifier.fillMaxWidth()) {
            IconButton(onClick = { actions.setFavorite(listOf(current), favorite = !current.isFavorite) }) {
                Icon(
                    if (current.isFavorite) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                    contentDescription = if (current.isFavorite) "Remove from favorites" else "Add to favorites",
                )
            }
            IconButton(onClick = { actions.share(listOf(current)) }) {
                Icon(Icons.Outlined.Share, contentDescription = "Share")
            }
            IconButton(onClick = { onEdit(current.id) }) {
                Icon(Icons.Outlined.Edit, contentDescription = "Edit")
            }
            IconButton(onClick = { actions.trash(listOf(current)) }) {
                Icon(Icons.Outlined.Delete, contentDescription = "Move to trash")
            }
            IconButton(onClick = { onOpenFull(current.id) }) {
                Icon(Icons.Outlined.OpenInFull, contentDescription = "Open full screen")
            }
        }
    }
}

@Composable
private fun PreviewMedia(item: GalleryItem, isCurrent: Boolean, onOpen: () -> Unit) {
    val context = LocalContext.current
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        when {
            item.isVideo && isCurrent -> InlineVideo(item)
            item.isVideo -> AsyncImage(
                model = item.thumb(),
                contentDescription = item.name,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
            else -> {
                val animated = rememberAnimatedImage(item.uri, item.mimeType, item.name, item.modified)
                val tap = Modifier.fillMaxSize().clickable(onClick = onOpen)
                if (animated != null) {
                    Box(tap) { AnimatedDrawableView(animated, playing = isCurrent, modifier = Modifier.fillMaxSize()) }
                } else {
                    val request = remember(item.uri, item.modified) {
                        ImageRequest.Builder(context)
                            .data(item.uri)
                            .memoryCacheKey("preview:${item.uri}#${item.modified}")
                            .crossfade(true)
                            .build()
                    }
                    AsyncImage(
                        model = request,
                        placeholder = null,
                        contentDescription = item.name,
                        contentScale = ContentScale.Fit,
                        modifier = tap,
                    )
                }
            }
        }
    }
}

/**
 * Plays a video right in the pane: muted at first, looping, with the usual controls, plus the
 * full player's frame tools underneath (−1 / +1 frame, hold to keep stepping, and Capture still).
 */
@OptIn(UnstableApi::class)
@Composable
private fun InlineVideo(item: GalleryItem) {
    val context = LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var muted by remember { mutableStateOf(true) }
    var capturing by remember { mutableStateOf(false) }
    // Made and released together, so an abandoned composition can't leak a player
    var player by remember { mutableStateOf<ExoPlayer?>(null) }
    var playerView by remember { mutableStateOf<PlayerView?>(null) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(item.uri, lifecycle) {
        val p = ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(item.uri))
            setSeekParameters(androidx.media3.exoplayer.SeekParameters.EXACT)
            repeatMode = Player.REPEAT_MODE_ONE
            volume = if (muted) 0f else 1f
            prepare()
            playWhenReady = true
        }
        player = p
        var wasPlaying = true
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> {
                    wasPlaying = p.isPlaying
                    p.pause()
                }
                // Only pick up again if it was playing (not if you'd paused on a frame)
                Lifecycle.Event.ON_RESUME -> if (wasPlaying) p.play()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            player = null
            p.release()
        }
    }

    fun captureStill() {
        val p = player ?: return
        if (capturing) return
        p.pause()
        val at = p.currentPosition
        capturing = true
        scope.launch {
            val frame = grabVideoFrame(playerView, p)
            val saved = saveVideoStill(context, item.uri, item.name, at, frame)
            capturing = false
            android.widget.Toast.makeText(
                context,
                if (saved != null) "Still saved to ${com.tim.gallery.data.MediaSaver.FOLDER_LABEL}" else "Couldn't capture this frame",
                android.widget.Toast.LENGTH_SHORT,
            ).show()
        }
    }

    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            AndroidView(
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                        setShutterBackgroundColor(android.graphics.Color.TRANSPARENT)
                        controllerShowTimeoutMs = 2500
                        playerView = this
                    }
                },
                update = { view -> if (view.player !== player) view.player = player },
                onRelease = { view ->
                    view.player = null
                    playerView = null
                },
                modifier = Modifier.fillMaxSize(),
            )
            IconButton(
                onClick = {
                    muted = !muted
                    player?.volume = if (muted) 0f else 1f
                },
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(8.dp)
                    .background(Color.Black.copy(alpha = 0.45f), CircleShape),
            ) {
                Icon(
                    if (muted) Icons.AutoMirrored.Outlined.VolumeOff else Icons.AutoMirrored.Outlined.VolumeUp,
                    contentDescription = if (muted) "Sound on" else "Mute",
                    tint = Color.White,
                )
            }
            if (capturing) {
                androidx.compose.material3.CircularProgressIndicator(color = Color.White, modifier = Modifier.align(Alignment.Center))
            }
        }
        // Frame tools
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally),
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF111111))
                .padding(vertical = 8.dp, horizontal = 12.dp),
        ) {
            FrameStepButton(
                Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                "Previous frame",
                "−1 frame",
                sizeDp = 44,
            ) { player?.let { stepVideoFrame(it, -1) } }
            FrameStepButton(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                "Next frame",
                "+1 frame",
                sizeDp = 44,
            ) { player?.let { stepVideoFrame(it, 1) } }
            Spacer(Modifier.size(8.dp))
            androidx.compose.material3.FilledTonalButton(onClick = { captureStill() }, enabled = !capturing && player != null) {
                Icon(Icons.Filled.PhotoCamera, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.size(8.dp))
                Text("Capture still")
            }
        }
    }
}

@Composable
private fun PreviewDetails(item: GalleryItem) {
    val context = LocalContext.current
    val details by produceState<MediaDetails?>(null, item.id, item.modified) {
        value = runCatching { Metadata.read(context, item) }.getOrNull()
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 10.dp)) {
        Text(item.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(formatDate(item.dateMillis), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        val facts = listOfNotNull(
            if (item.width > 0 && item.height > 0) "${item.width} × ${item.height}" else null,
            Formatter.formatShortFileSize(context, item.sizeBytes),
            if (item.isVideo && item.durationMs > 0) formatDuration(item.durationMs) else null,
            item.album.takeIf { it.isNotBlank() },
        )
        Text(
            facts.joinToString(" · "),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        val d = details
        val camera = listOfNotNull(d?.camera, d?.aperture, d?.shutter, d?.iso, d?.focalLength)
        if (camera.isNotEmpty()) {
            Text(
                camera.joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
