package com.tim.gallery.ui

import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/**
 * Long-press a tile and keep your finger down, then drag to select everything between the
 * first tile and the one under your finger. Starting on an already-selected tile deselects
 * instead. The grid scrolls by itself when you drag near the top or bottom edge.
 */
@Composable
fun Modifier.dragToSelect(
    state: LazyGridState,
    ids: List<Long>,
    selected: Set<Long>,
    contentPadding: PaddingValues,
    drag: DragSelectSession,
    onSelectionChange: (Set<Long>) -> Unit,
): Modifier {
    val haptics = LocalHapticFeedback.current
    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current

    val currentIds by rememberUpdatedState(ids)
    val currentSelected by rememberUpdatedState(selected)
    val onChange by rememberUpdatedState(onSelectionChange)
    val indexOf = remember(ids) { HashMap<Long, Int>(ids.size * 2).apply { ids.forEachIndexed { i, id -> put(id, i) } } }
    val currentIndexOf by rememberUpdatedState(indexOf)

    val startPadPx = with(density) { contentPadding.calculateStartPadding(layoutDirection).toPx() }
    val topPadPx = with(density) { contentPadding.calculateTopPadding().toPx() }
    val edgePx = with(density) { 72.dp.toPx() }
    val maxSpeedPx = with(density) { 22.dp.toPx() }

    var autoScrollSpeed by remember { mutableFloatStateOf(0f) }

    fun idAt(p: Offset): Long? {
        val local = Offset(p.x - startPadPx, p.y - topPadPx)
        val hit = state.layoutInfo.visibleItemsInfo.firstOrNull { info ->
            local.x >= info.offset.x && local.x < info.offset.x + info.size.width &&
                local.y >= info.offset.y && local.y < info.offset.y + info.size.height
        }
        return hit?.key as? Long
    }

    fun applyTo(endId: Long) {
        val start = currentIndexOf[drag.startId] ?: return
        val end = currentIndexOf[endId] ?: return
        val lo = minOf(start, end)
        val hi = maxOf(start, end)
        val range = currentIds.subList(lo, hi + 1)
        onChange(if (drag.deselecting) drag.base - range.toSet() else drag.base + range)
    }

    // Keep scrolling (and extending the selection) while the finger rests near an edge
    LaunchedEffect(autoScrollSpeed) {
        if (autoScrollSpeed == 0f) return@LaunchedEffect
        while (isActive) {
            state.scrollBy(autoScrollSpeed)
            idAt(drag.lastPointer)?.let { applyTo(it) }
            delay(16)
        }
    }

    return this.pointerInput(state, startPadPx, topPadPx) {
        detectDragGesturesAfterLongPress(
            onDragStart = { p ->
                val id = idAt(p) ?: return@detectDragGesturesAfterLongPress
                drag.active = true
                drag.startId = id
                drag.base = currentSelected
                drag.deselecting = id in currentSelected
                drag.lastPointer = p
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                applyTo(id)
            },
            onDrag = { change, _ ->
                if (!drag.active) return@detectDragGesturesAfterLongPress
                change.consume()
                drag.lastPointer = change.position
                idAt(change.position)?.let { applyTo(it) }
                val y = change.position.y
                val h = size.height.toFloat()
                autoScrollSpeed = when {
                    y < edgePx -> -maxSpeedPx * ((edgePx - y) / edgePx).coerceIn(0f, 1f)
                    y > h - edgePx -> maxSpeedPx * ((y - (h - edgePx)) / edgePx).coerceIn(0f, 1f)
                    else -> 0f
                }
            },
            onDragEnd = {
                drag.active = false
                autoScrollSpeed = 0f
            },
            onDragCancel = {
                drag.active = false
                autoScrollSpeed = 0f
            },
        )
    }
}

/**
 * Shared between the grid and its tiles: while a long-press/drag selection is active, the
 * tile's own tap (which fires when the finger lifts) must be ignored.
 */
class DragSelectSession {
    var active = false
    internal var startId = 0L
    internal var base: Set<Long> = emptySet()
    internal var deselecting = false
    internal var lastPointer = Offset.Zero
}
