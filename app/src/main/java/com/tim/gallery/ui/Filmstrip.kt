package com.tim.gallery.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.ScrollableDefaults
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.tim.gallery.data.GalleryItem
import com.tim.gallery.data.thumb
import com.tim.gallery.video.VideoTools
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToInt

private val TileW = 34.dp
private val TileH = 46.dp
private val CurrentW = 46.dp
private val CurrentH = 54.dp

/** How wide the current video's frames run: longer videos get more room, so scrubbing stays fine-grained. */
private fun blockWidth(durationMs: Long): Dp = (durationMs / 1000f * 9f).coerceIn(136f, 1400f).dp

/**
 * Every item in a strip along the bottom, the current one in the middle. On a video, its part of the
 * strip opens out into its frames with a playhead line: drag the strip to scrub through the video,
 * and keep dragging to go on to the items around it.
 */
@Composable
fun Filmstrip(
    items: List<GalleryItem>,
    current: Int,
    video: InlineVideo,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state = rememberLazyListState()
    val density = LocalDensity.current
    val dragged by state.interactionSource.collectIsDraggedAsState()
    // A drag (and the fling after it) by the user, rather than the strip following playback
    var userScroll by remember { mutableStateOf(false) }
    // While the user drags, the video that was open stays open (so the strip doesn't jump under the finger)
    var frozenExpanded by remember { mutableStateOf<Long?>(null) }
    val liveExpanded = items.getOrNull(current)?.takeIf { it.isVideo }?.id
    val expandedId = if (userScroll) frozenExpanded else liveExpanded

    val currentItem = items.getOrNull(current)
    // The drag handler runs for the whole drag: read these fresh each time
    val latestCurrent by androidx.compose.runtime.rememberUpdatedState(current)
    val latestItems by androidx.compose.runtime.rememberUpdatedState(items)
    val select by androidx.compose.runtime.rememberUpdatedState(onSelect)
    fun durationOf(item: GalleryItem): Long =
        if (video.itemId == item.id && video.duration > 0) video.duration else item.durationMs

    fun centerHit(): Pair<Int, Float>? {
        val info = state.layoutInfo
        val center = (info.viewportStartOffset + info.viewportEndOffset) / 2
        val hit = info.visibleItemsInfo.firstOrNull { center >= it.offset && center < it.offset + it.size }
            ?: info.visibleItemsInfo.minByOrNull { abs(it.offset + it.size / 2 - center) }
            ?: return null
        return hit.index to ((center - hit.offset).toFloat() / hit.size.coerceAtLeast(1)).coerceIn(0f, 1f)
    }

    // Scrolls the strip makes itself while following playback (anything else is the user: finger, wheel, trackpad)
    val ownScrolls = remember { intArrayOf(0) }
    LaunchedEffect(dragged, state.isScrollInProgress) {
        if ((dragged || state.isScrollInProgress && ownScrolls[0] == 0) && !userScroll) {
            frozenExpanded = liveExpanded
            userScroll = true
        }
    }
    // Never leave a video paused mid-scrub when the strip goes away (controls hidden, viewer closed)
    androidx.compose.runtime.DisposableEffect(Unit) { onDispose { video.endScrub() } }

    // The user moves the strip: scrub the open video, or step to whatever passes the middle
    LaunchedEffect(userScroll) {
        if (!userScroll) return@LaunchedEffect
        snapshotFlow { centerHit() }.collect { hit ->
            val (index, fraction) = hit ?: return@collect
            val item = latestItems.getOrNull(index) ?: return@collect
            if (item.id == frozenExpanded) {
                if (index != latestCurrent) select(index)
                if (video.itemId == item.id) {
                    video.startScrub()
                    video.scrub((fraction * durationOf(item)).toLong())
                }
            } else {
                if (video.scrubbing) video.endScrub()
                if (index != latestCurrent) select(index)
            }
        }
    }
    // The drag (and its fling) is over
    LaunchedEffect(state.isScrollInProgress, dragged) {
        if (userScroll && !state.isScrollInProgress && !dragged) {
            video.endScrub()
            userScroll = false
        }
    }

    // The strip follows: the current item in the middle, and on a video the playhead follows playback
    var lastIndex by remember { mutableStateOf(-1) }
    LaunchedEffect(current, expandedId, userScroll) {
        if (userScroll || currentItem == null) return@LaunchedEffect
        val far = lastIndex < 0 || abs(lastIndex - current) > 4
        lastIndex = current
        suspend fun own(block: suspend () -> Unit) {
            ownScrolls[0]++
            try {
                block()
            } finally {
                ownScrolls[0]--
            }
        }
        if (expandedId == currentItem.id) {
            var shown = -1
            while (true) {
                // Re-read each step: the real duration (and so the frames' width) can arrive after loading
                val d = durationOf(currentItem).coerceAtLeast(1L)
                val blockPx = with(density) { blockWidth(d).toPx() }
                val pos = if (video.itemId == currentItem.id) video.position else 0L
                val px = ((pos.toFloat() / d).coerceIn(0f, 1f) * blockPx).roundToInt()
                if (px != shown) {
                    own { if (shown < 0 && !far) state.animateScrollToItem(current, px) else state.scrollToItem(current, px) }
                    shown = px
                }
                delay(33)
            }
        } else {
            val half = with(density) { (CurrentW / 2).toPx() }.roundToInt()
            own { if (far) state.scrollToItem(current, half) else state.animateScrollToItem(current, half) }
        }
    }

    BoxWithConstraints(modifier.fillMaxWidth().height(CurrentH + 8.dp), contentAlignment = Alignment.Center) {
        val half = maxWidth / 2
        LazyRow(
            state = state,
            contentPadding = PaddingValues(horizontal = half),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.CenterVertically,
            flingBehavior = ScrollableDefaults.flingBehavior(),
            modifier = Modifier.fillMaxSize(),
        ) {
            itemsIndexed(items, key = { _, it -> it.id }, contentType = { _, it -> if (it.id == expandedId) "video" else "tile" }) { index, item ->
                when {
                    item.id == expandedId -> VideoFrames(item, blockWidth(durationOf(item)))
                    else -> {
                        val isCurrent = index == current
                        Box(
                            Modifier
                                .size(if (isCurrent) CurrentW else TileW, if (isCurrent) CurrentH else TileH)
                                .clip(RoundedCornerShape(4.dp))
                                .background(Color.White.copy(alpha = 0.08f))
                                .then(if (isCurrent) Modifier.border(2.dp, Color.White, RoundedCornerShape(4.dp)) else Modifier)
                                .clickable { onSelect(index) },
                        ) {
                            AsyncImage(
                                model = item.thumb(),
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize(),
                            )
                            if (item.isVideo) {
                                Icon(
                                    Icons.Filled.PlayArrow,
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier.align(Alignment.BottomEnd).padding(1.dp).size(13.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
        // The playhead, on a video
        if (expandedId != null && expandedId == currentItem?.id || userScroll && frozenExpanded != null) {
            Box(
                Modifier
                    .align(Alignment.Center)
                    .width(3.dp)
                    .height(CurrentH + 6.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(Color.White),
            )
        }
    }
}

/** A video's frames, laid end to end across [width]. */
@Composable
private fun VideoFrames(item: GalleryItem, width: Dp) {
    val context = LocalContext.current
    val count = ceil(width.value / TileW.value).toInt().coerceIn(2, 48)
    val heightPx = with(LocalDensity.current) { CurrentH.roundToPx() }
    val key = "${item.id}:${item.modified}:$count"
    val frames by produceState(FrameCache.get(key) ?: emptyList(), key) {
        FrameCache.get(key)?.let {
            value = it
            return@produceState
        }
        val got = VideoTools.filmstrip(context, item.uri, count, heightPx)
        if (got.isNotEmpty()) FrameCache.put(key, got)
        value = got
    }
    Box(
        Modifier
            .width(width)
            .height(CurrentH)
            .clip(RoundedCornerShape(6.dp))
            .background(Color.White.copy(alpha = 0.08f)),
    ) {
        Row(Modifier.fillMaxSize()) {
            repeat(count) { i ->
                val f = frames.getOrNull(i)
                Box(Modifier.weight(1f).fillMaxHeight()) {
                    if (f != null) {
                        Image(f.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                    } else {
                        AsyncImage(item.thumb(), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                    }
                }
            }
        }
    }
}

/** Recent videos' filmstrip frames, so showing the controls again doesn't decode them all again. */
private object FrameCache : android.util.LruCache<String, List<Bitmap?>>(24 * 1024 * 1024) {
    override fun sizeOf(key: String, value: List<Bitmap?>): Int = value.sumOf { it?.allocationByteCount ?: 0 }.coerceAtLeast(1)
}
