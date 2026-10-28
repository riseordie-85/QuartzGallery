package com.tim.gallery.map

import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.tim.gallery.GalleryViewModel
import com.tim.gallery.data.GalleryItem
import com.tim.gallery.data.thumb
import com.tim.gallery.ui.itemCount
import com.tim.gallery.ui.mainContainerColor
import com.tim.gallery.ui.mainTopBarColors
import java.text.NumberFormat
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max

/** A group of nearby photos on the map. */
private class Cluster(val wx: Float, val wy: Float, val items: List<GalleryItem>)

/** The map, prepared once: land as filled paths, borders and state lines as line segments (Mercator units). */
private class MapGeometry(val landCoarse: Path, val landFine: Path, val borders: FloatArray, val states: FloatArray)

private fun buildGeometry(l: WorldData.Layers): MapGeometry {
    fun path(lines: List<MapLine>): Path {
        val p = Path()
        for (line in lines) {
            val pts = line.pts
            p.moveTo(pts[0], pts[1])
            var i = 2
            while (i < pts.size) {
                p.lineTo(pts[i], pts[i + 1])
                i += 2
            }
            p.close()
        }
        return p
    }
    fun segments(lines: List<MapLine>): FloatArray {
        val n = lines.sumOf { maxOf(0, it.pts.size / 2 - 1) }
        val out = FloatArray(n * 4)
        var k = 0
        for (line in lines) {
            val pts = line.pts
            var i = 0
            while (i + 3 < pts.size) {
                out[k++] = pts[i]; out[k++] = pts[i + 1]; out[k++] = pts[i + 2]; out[k++] = pts[i + 3]
                i += 2
            }
        }
        return out
    }
    return MapGeometry(path(l.landCoarse), path(l.landFine), segments(l.borders), segments(l.states))
}

/** Groups photos on a grid fixed to the map, so groups don't reshuffle while you pan. */
private fun clusterAt(located: List<Pair<GalleryItem, Offset>>, cellWorld: Float): List<Cluster> {
    val groups = HashMap<Long, MutableList<Pair<GalleryItem, Offset>>>()
    for (entry in located) {
        val gx = floor(entry.second.x / cellWorld).toLong()
        val gy = floor(entry.second.y / cellWorld).toLong()
        groups.getOrPut((gx shl 32) or (gy and 0xFFFFFFFFL)) { mutableListOf() } += entry
    }
    return groups.values.map { list ->
        Cluster(
            list.sumOf { it.second.x.toDouble() }.toFloat() / list.size,
            list.sumOf { it.second.y.toDouble() }.toFloat() / list.size,
            list.map { it.first },
        )
    }
}

/**
 * Photos on a world map. The map (coastlines, borders, states) and place names are built into
 * the app, so nothing about where your photos were taken ever leaves the phone.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MapScreen(
    vm: GalleryViewModel,
    onBack: () -> Unit,
    onOpenItem: (Long) -> Unit,
    focusLat: Double? = null,
    focusLon: Double? = null,
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val all by vm.allItems.collectAsStateWithLifecycle()
    val progress by LocationIndex.progress.collectAsState()
    val version by LocationIndex.version.collectAsState()
    val geometry by produceState<MapGeometry?>(null) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { buildGeometry(WorldData.layers(context)) }
    }

    LaunchedEffect(all) { if (all.isNotEmpty()) LocationIndex.update(context, all) }

    // Items with a location, as Mercator points
    val located by produceState(emptyList<Pair<GalleryItem, Offset>>(), all, version) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            all.mapNotNull { item ->
                val s = LocationIndex.spot(item.id)
                if (s == null || !s.hasLocation || s.modified != item.modified) null
                else item to Offset(Mercator.x(s.lon.toDouble()), Mercator.y(s.lat.toDouble()))
            }
        }
    }

    // View: map center (Mercator 0..1) and scale (screen px per world unit)
    var centerX by remember { mutableFloatStateOf(focusLon?.let { Mercator.x(it) } ?: 0.5f) }
    var centerY by remember { mutableFloatStateOf(focusLat?.let { Mercator.y(it) } ?: 0.42f) }
    var scale by remember { mutableFloatStateOf(0f) } // set once the size is known
    var selected by remember { mutableStateOf<Cluster?>(null) }

    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val ocean = if (dark) 0xFF0E1822.toInt() else 0xFFCFE2F0.toInt()
    val borderColor = if (dark) 0xFF5B6B7A.toInt() else 0xFF9AA5AF.toInt()
    val stateColor = if (dark) 0xFF3B4855.toInt() else 0xFFC7CDD3.toInt()
    val accent = MaterialTheme.colorScheme.primary.toArgb()
    val onAccent = MaterialTheme.colorScheme.onPrimary.toArgb()

    com.tim.gallery.quartz.QuartzScaffold(
        topBar = {
            com.tim.gallery.quartz.QuartzTopBar(
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
                title = {
                    Column {
                        Text("Places")
                        Text(
                            "${NumberFormat.getIntegerInstance().format(located.size)} with a location",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
            )
        },
    ) { padding ->
        BoxWithConstraints(Modifier.fillMaxSize().padding(padding)) {
            val w = constraints.maxWidth.toFloat()
            val h = constraints.maxHeight.toFloat()
            val minScale = max(w, h * 0.8f)
            val maxScale = minScale * 20000f
            LaunchedEffect(w, h) {
                if (scale == 0f && w > 0) scale = if (focusLat != null) minScale * 1500f else minScale * 1.2f
            }
            val cell = with(density) { 54.dp.toPx() }

            fun clampView() {
                scale = scale.coerceIn(minScale, maxScale)
                val halfW = w / 2f / scale
                val halfH = h / 2f / scale
                centerX = if (halfW >= 0.5f) 0.5f else centerX.coerceIn(halfW, 1f - halfW)
                centerY = if (halfH >= 0.5f) 0.5f else centerY.coerceIn(halfH, 1f - halfH)
            }

            fun toScreen(p: Offset) = Offset((p.x - centerX) * scale + w / 2f, (p.y - centerY) * scale + h / 2f)

            // Zoom steps (each 1.5×) decide the grouping grid, computed in the background
            val zoomStep = if (scale > 0f) floor(ln(scale / minScale) / ln(1.5f)).toInt().coerceAtLeast(0) else 0
            val clusterList by produceState(emptyList<Cluster>(), located, zoomStep, minScale) {
                val cellWorld = cell / (minScale * Math.pow(1.5, zoomStep.toDouble()).toFloat())
                value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { clusterAt(located, cellWorld) }
            }
            fun radiusOf(cl: Cluster) = with(density) { (12f + 5f * ln(cl.items.size.toFloat())).dp.toPx() }
            val paints = remember(dark, accent) {
                object {
                    val land = Paint(Paint.ANTI_ALIAS_FLAG).also { it.color = landColor(dark); it.style = Paint.Style.FILL }
                    val border = Paint(Paint.ANTI_ALIAS_FLAG).also { it.color = borderColor; it.style = Paint.Style.STROKE }
                    val state = Paint(Paint.ANTI_ALIAS_FLAG).also { it.color = stateColor; it.style = Paint.Style.STROKE }
                    val dot = Paint(Paint.ANTI_ALIAS_FLAG).also { it.color = accent }
                    val ring = Paint(Paint.ANTI_ALIAS_FLAG).also {
                        it.color = onAccent
                        it.style = Paint.Style.STROKE
                        it.strokeWidth = with(density) { 2.dp.toPx() }
                    }
                    val text = Paint(Paint.ANTI_ALIAS_FLAG).also {
                        it.color = onAccent
                        it.textAlign = Paint.Align.CENTER
                        it.typeface = Typeface.DEFAULT_BOLD
                        it.textSize = with(density) { 12.dp.toPx() }
                    }
                    val pin = Paint(Paint.ANTI_ALIAS_FLAG).also { it.color = 0xFFE53935.toInt() }
                }
            }

            Canvas(
                Modifier
                    .fillMaxSize()
                    .pointerInput(w, h) {
                        detectTransformGestures { centroid, pan, zoom, _ ->
                            // Zoom around the fingers, then pan
                            val before = Offset((centroid.x - w / 2f) / scale + centerX, (centroid.y - h / 2f) / scale + centerY)
                            scale = (scale * zoom).coerceIn(minScale, maxScale)
                            centerX = before.x - (centroid.x - w / 2f) / scale
                            centerY = before.y - (centroid.y - h / 2f) / scale
                            centerX -= pan.x / scale
                            centerY -= pan.y / scale
                            clampView()
                        }
                    }
                    .pointerInput(w, h, clusterList) {
                        detectTapGestures(
                            onDoubleTap = { p ->
                                val world = Offset((p.x - w / 2f) / scale + centerX, (p.y - h / 2f) / scale + centerY)
                                scale = (scale * 2.5f).coerceIn(minScale, maxScale)
                                centerX = world.x
                                centerY = world.y
                                clampView()
                            },
                            onTap = { p ->
                                val hit = clusterList
                                    .map { it to toScreen(Offset(it.wx, it.wy)) }
                                    .filter { (cl, sp) -> (sp - p).getDistance() <= radiusOf(cl) + with(density) { 6.dp.toPx() } }
                                    .minByOrNull { (_, sp) -> (sp - p).getDistance() }
                                if (hit != null) selected = hit.first
                            },
                        )
                    }
            ) {
                drawIntoCanvas { c ->
                    val nc = c.nativeCanvas
                    nc.drawColor(ocean)
                    if (scale <= 0f) return@drawIntoCanvas
                    val g = geometry ?: return@drawIntoCanvas
                    // The map is drawn in Mercator units through one transform (no per-frame rebuilding)
                    nc.save()
                    nc.translate(w / 2f - centerX * scale, h / 2f - centerY * scale)
                    nc.scale(scale, scale)
                    nc.drawPath(if (scale > minScale * 5f) g.landFine else g.landCoarse, paints.land)
                    if (scale > minScale * 2.5f) {
                        paints.border.strokeWidth = with(density) { 1.dp.toPx() } / scale
                        nc.drawLines(g.borders, paints.border)
                    }
                    if (scale > minScale * 8f) {
                        paints.state.strokeWidth = with(density) { 0.75.dp.toPx() } / scale
                        nc.drawLines(g.states, paints.state)
                    }
                    nc.restore()

                    // Photo groups
                    for (cl in clusterList) {
                        val sp = toScreen(Offset(cl.wx, cl.wy))
                        val r = radiusOf(cl)
                        if (sp.x < -r || sp.y < -r || sp.x > w + r || sp.y > h + r) continue
                        nc.drawCircle(sp.x, sp.y, r, paints.dot)
                        nc.drawCircle(sp.x, sp.y, r, paints.ring)
                        val label = if (cl.items.size > 999) "${cl.items.size / 1000}k" else cl.items.size.toString()
                        nc.drawText(label, sp.x, sp.y - (paints.text.descent() + paints.text.ascent()) / 2f, paints.text)
                    }

                    // The spot from "Show on map"
                    if (focusLat != null && focusLon != null) {
                        val sp = toScreen(Offset(Mercator.x(focusLon), Mercator.y(focusLat)))
                        nc.drawCircle(sp.x, sp.y, with(density) { 7.dp.toPx() }, paints.pin)
                        nc.drawCircle(sp.x, sp.y, with(density) { 7.dp.toPx() }, paints.ring)
                    }
                }
            }

            // Zoom buttons (for one-handed use)
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
            ) {
                SmallFloatingActionButton(onClick = { scale *= 2f; clampView() }) { Icon(Icons.Outlined.Add, contentDescription = "Zoom in") }
                SmallFloatingActionButton(onClick = { scale /= 2f; clampView() }) { Icon(Icons.Outlined.Remove, contentDescription = "Zoom out") }
            }

            progress?.let { (done, total) ->
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f)),
                    modifier = Modifier.align(Alignment.TopCenter).padding(12.dp).fillMaxWidth(),
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Text(
                            "Finding where your photos were taken… $done of $total",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            "First time only. Read from the photos on your phone — nothing is looked up online.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        LinearProgressIndicator(
                            progress = { if (total == 0) 0f else done.toFloat() / total },
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        )
                    }
                }
            }
            if (progress == null && located.isEmpty() && all.isNotEmpty()) {
                Text(
                    "None of your photos have location info yet. Turn on location in your camera app's settings to see new photos here.",
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(32.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.9f))
                        .padding(16.dp),
                )
            }
        }
    }

    selected?.let { cl ->
        val items = remember(cl) { cl.items.sortedByDescending { it.dateMillis } }
        val first = items.first()
        val spot = LocationIndex.spot(first.id)
        val place by produceState<String?>(null, cl) {
            value = spot?.let { WorldData.nameFor(context, it.lat.toDouble(), it.lon.toDouble()) }
        }
        ModalBottomSheet(onDismissRequest = { selected = null }) {
            Text(
                place ?: "Photos here",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 20.dp),
            )
            Text(
                itemCount(items.size),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 2.dp),
            )
            LazyVerticalGrid(
                columns = GridCells.Adaptive(96.dp),
                contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 32.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
                modifier = Modifier.heightIn(max = 520.dp),
            ) {
                items(items, key = { it.id }) { item ->
                    AsyncImage(
                        model = item.thumb(),
                        contentDescription = item.name,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .aspectRatio(1f)
                            .clickable {
                                selected = null
                                onOpenItem(item.id)
                            },
                    )
                }
            }
        }
    }
}

private fun landColor(dark: Boolean): Int = if (dark) 0xFF26313C.toInt() else 0xFFF3F0EA.toInt()
