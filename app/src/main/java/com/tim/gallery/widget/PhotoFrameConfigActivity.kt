package com.tim.gallery.widget

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tim.gallery.GalleryViewModel
import com.tim.gallery.quartz.QuartzScaffold
import com.tim.gallery.quartz.QuartzTopBar
import com.tim.gallery.ui.GalleryTheme
import com.tim.gallery.ui.MainBackground
import com.tim.gallery.ui.MediaGrid
import com.tim.gallery.ui.PermissionGate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * A photo frame's settings: the photo, the frame, the shadow, and whether the photo is shown whole or
 * fills the widget. Opens when the widget is added, and again from the widget's settings.
 */
class PhotoFrameConfigActivity : ComponentActivity() {

    private var widgetId = AppWidgetManager.INVALID_APPWIDGET_ID

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        widgetId = intent?.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
            ?: AppWidgetManager.INVALID_APPWIDGET_ID
        // Backing out of a new frame doesn't add it
        setResult(RESULT_CANCELED, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId))
        if (widgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }
        // This frame's settings, or the photo picked with "Put on home screen", or nothing yet
        val start = PhotoFrames.load(this, widgetId)
            ?: PhotoFrames.pending(this)?.let { (id, uri) -> FrameConfig(id, uri) }
        val size = PhotoFrames.sizePx(this, widgetId)

        setContent {
            GalleryTheme {
                MainBackground {
                    FrameSettings(
                        start = start,
                        widgetSize = size,
                        onCancel = { finish() },
                        onDone = { config -> done(config) },
                    )
                }
            }
        }
    }

    private fun done(config: FrameConfig) {
        PhotoFrames.save(this, widgetId, config)
        PhotoFrames.clearPending(this)
        // Redraw through the widget itself, which keeps the app awake until it's drawn
        sendBroadcast(
            Intent(this, PhotoFrameWidget::class.java)
                .setAction(AppWidgetManager.ACTION_APPWIDGET_UPDATE)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, intArrayOf(widgetId)),
        )
        setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId))
        finish()
    }
}

@Composable
private fun FrameSettings(
    start: FrameConfig?,
    widgetSize: Pair<Int, Int>,
    onCancel: () -> Unit,
    onDone: (FrameConfig) -> Unit,
) {
    val context = LocalContext.current
    var photoId by remember { mutableStateOf(start?.photoId) }
    var photoUri by remember { mutableStateOf(start?.uri) }
    var style by remember { mutableStateOf(start?.style ?: FrameStyle.GALLERY) }
    var shadow by remember { mutableStateOf(start?.shadow ?: 0.6f) }
    var fill by remember { mutableStateOf(start?.fill ?: false) }
    var picking by remember { mutableStateOf(start == null) }

    // The photo, decoded once at preview size
    val photo by produceState<Bitmap?>(null, photoUri) {
        value = photoUri?.let { u -> withContext(Dispatchers.IO) { FrameRenderer.loadPhoto(context, Uri.parse(u), 900) } }
    }

    if (picking) {
        PhotoPicker(
            onPick = { id, uri ->
                photoId = id
                photoUri = uri.toString()
                picking = false
            },
            onCancel = { if (photoUri == null) onCancel() else picking = false },
        )
        return
    }

    // The widget as it will look, at the widget's own shape
    val (ww, wh) = widgetSize
    val preview by produceState<Bitmap?>(null, photo, style, shadow, fill, ww, wh) {
        val p = photo ?: return@produceState
        // Wait for the slider to pause: a new value cancels this before the (uncancellable) drawing starts
        kotlinx.coroutines.delay(60)
        value = withContext(Dispatchers.Default) { FrameRenderer.render(p, ww, wh, style, shadow, fill) }
    }

    QuartzScaffold(
        topBar = {
            QuartzTopBar(
                navigationIcon = { IconButton(onClick = onCancel) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Cancel") } },
                title = { Text("Photo frame") },
                actions = {
                    TextButton(
                        onClick = {
                            val id = photoId
                            val uri = photoUri
                            if (id != null && uri != null) onDone(FrameConfig(id, uri, style, shadow, fill))
                        },
                        enabled = photoId != null,
                    ) { Text("Done") }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            contentPadding = PaddingValues(top = padding.calculateTopPadding() + 8.dp, bottom = padding.calculateBottomPadding() + 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            item {
                Box(Modifier.fillMaxWidth().height(300.dp).padding(horizontal = 24.dp), contentAlignment = Alignment.Center) {
                    preview?.let { Image(it.asImageBitmap(), contentDescription = "Preview", contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize()) }
                }
            }
            item {
                Panel {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Image, contentDescription = null)
                        Spacer(Modifier.width(12.dp))
                        Text("Photo", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        OutlinedButton(onClick = { picking = true }) { Text("Choose another") }
                    }
                }
            }
            item {
                Panel {
                    Text("Frame", style = MaterialTheme.typography.titleMedium)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        items(FrameStyle.entries) { s ->
                            StyleTile(s, photo, selected = s == style) { style = s }
                        }
                    }
                }
            }
            item {
                Panel {
                    Row {
                        Text("Drop shadow", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        Text(if (shadow < 0.02f) "None" else "${(shadow * 100).roundToInt()}%", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Slider(value = shadow, onValueChange = { shadow = it }, valueRange = 0f..1f)
                }
            }
            item {
                Panel {
                    Text("Photo", style = MaterialTheme.typography.titleMedium)
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        listOf(false to "Whole photo", true to "Fill the widget").forEachIndexed { i, (value, label) ->
                            SegmentedButton(
                                selected = fill == value,
                                onClick = { fill = value },
                                shape = SegmentedButtonDefaults.itemShape(i, 2),
                                icon = {},
                                label = { Text(label) },
                            )
                        }
                    }
                    Text(
                        if (fill) "The photo is cropped to the widget's shape." else "The frame takes the photo's shape; resize the widget to make it bigger.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** A small framed version of the photo for each style. */
@Composable
private fun StyleTile(style: FrameStyle, photo: Bitmap?, selected: Boolean, onClick: () -> Unit) {
    val thumb by produceState<Bitmap?>(null, photo, style) {
        val p = photo ?: return@produceState
        value = withContext(Dispatchers.Default) { FrameRenderer.render(p, 240, 240, style, 0.45f, false) }
    }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .width(96.dp)
            .clip(RoundedCornerShape(16.dp))
            .then(if (selected) Modifier.border(BorderStroke(2.dp, MaterialTheme.colorScheme.primary), RoundedCornerShape(16.dp)) else Modifier)
            .clickable(onClick = onClick)
            .padding(6.dp),
    ) {
        Box(Modifier.size(84.dp), contentAlignment = Alignment.Center) {
            thumb?.let { Image(it.asImageBitmap(), contentDescription = style.label, modifier = Modifier.fillMaxSize()) }
        }
        Text(style.label, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun Panel(content: @Composable () -> Unit) {
    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f)),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) { content() }
    }
}

/** All photos (newest first, as in the Photos tab, without videos): tap one to frame it. */
@Composable
private fun PhotoPicker(onPick: (Long, Uri) -> Unit, onCancel: () -> Unit) {
    val vm: GalleryViewModel = viewModel()
    BackHandler { onCancel() }
    PermissionGate(onRefresh = { vm.refresh() }) { _, _ ->
        // Every photo, including albums hidden from the Photos tab
        val all by vm.allItems.collectAsState()
        val photos = remember(all) { all.filter { !it.isVideo } }
        QuartzScaffold(
            topBar = {
                QuartzTopBar(
                    navigationIcon = { IconButton(onClick = onCancel) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
                    title = { Text("Choose a photo") },
                )
            },
        ) { padding ->
            MediaGrid(
                items = photos,
                selected = emptySet(),
                onOpen = { onPick(it.id, it.uri) },
                onSelectionChange = {},
                contentPadding = padding,
            )
        }
    }
}

