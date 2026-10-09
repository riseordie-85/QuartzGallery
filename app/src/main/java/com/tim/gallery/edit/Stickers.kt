package com.tim.gallery.edit

import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AddPhotoAlternate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.tim.gallery.GalleryViewModel
import com.tim.gallery.data.AlbumOps
import com.tim.gallery.data.Cutout
import com.tim.gallery.data.CutoutException
import com.tim.gallery.data.MediaSaver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Saves a bitmap into the sticker folder as a trimmed PNG. Returns its uri. */
suspend fun saveSticker(context: android.content.Context, bitmap: Bitmap): Uri? {
    val trimmed = withContext(Dispatchers.Default) { Cutout.trimForSticker(bitmap) }
    return MediaSaver.saveBitmap(
        context = context,
        bitmap = trimmed,
        displayName = "sticker_${System.currentTimeMillis()}.png",
        format = Bitmap.CompressFormat.PNG,
        relativePath = AlbumOps.STICKERS_PATH,
    )
}

/**
 * Bottom sheet listing everything in the sticker folder, plus "Add from photos"
 * (which goes through the sticker maker so you can cut out the subject).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StickerSheet(
    vm: GalleryViewModel,
    onDismiss: () -> Unit,
    onPick: (Uri) -> Unit,
) {
    val stickers by vm.stickers.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var making by remember { mutableStateOf<Uri?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) making = uri
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp),
        ) {
            Text("Stickers", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            TextButton(onClick = {
                picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            }) {
                Icon(Icons.Outlined.AddPhotoAlternate, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.size(6.dp))
                Text("Add from photos")
            }
        }
        if (stickers.isEmpty()) {
            Text(
                "No stickers yet. Add one from your photos — you can cut out the subject so it has no background. " +
                    "Any image you put in the Pictures/Stickers folder shows up here too.",
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 32.dp),
            )
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(84.dp),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 32.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.heightIn(max = 480.dp),
            ) {
                items(stickers, key = { it.id }) { item ->
                    // Decode the file itself (not the system thumbnail) so transparency is kept
                    val request = remember(item.uri, item.modified) {
                        ImageRequest.Builder(context)
                            .data(item.uri)
                            .memoryCacheKey("sticker:${item.uri}#${item.modified}")
                            .size(256)
                            .build()
                    }
                    Box(
                        Modifier
                            .aspectRatio(1f)
                            .clip(RoundedCornerShape(14.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .clickable { onPick(item.uri) }
                            .padding(8.dp)
                    ) {
                        AsyncImage(
                            model = request,
                            contentDescription = item.name,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }
        }
    }

    making?.let { uri ->
        StickerMakerDialog(
            source = uri,
            onDismiss = { making = null },
            onSaved = { saved ->
                making = null
                vm.refresh()
                onPick(saved)
            },
        )
    }
}

/**
 * Turns a photo into a sticker: keep the whole image, or cut out the subject
 * (on-device background removal). Saves a PNG into Pictures/Stickers.
 */
@Composable
fun StickerMakerDialog(
    source: Uri,
    onDismiss: () -> Unit,
    onSaved: (Uri) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var original by remember { mutableStateOf<Bitmap?>(null) }
    var cutout by remember { mutableStateOf<Bitmap?>(null) }
    var useCutout by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(source) {
        original = withContext(Dispatchers.IO) {
            runCatching { MediaSaver.decodeUpright(context, source, 2048) }.getOrNull()
        }
        if (original == null) error = "Couldn't open this image."
    }

    fun runCutout() {
        val src = original ?: return
        if (cutout != null) {
            useCutout = true
            return
        }
        busy = true
        error = null
        scope.launch {
            try {
                cutout = Cutout.removeBackground(src)
                useCutout = true
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: CutoutException) {
                error = e.message
            } catch (e: Exception) {
                error = "Background removal failed."
            }
            busy = false
        }
    }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("New sticker") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(260.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                ) {
                    val shown = if (useCutout) cutout else original
                    if (shown != null) {
                        Image(
                            bitmap = shown.asImageBitmap(),
                            contentDescription = null,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxSize().padding(8.dp),
                        )
                    }
                    if (busy || original == null && error == null) CircularProgressIndicator()
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = !useCutout,
                        onClick = { useCutout = false },
                        label = { Text("Whole image") },
                        enabled = !busy,
                    )
                    FilterChip(
                        selected = useCutout,
                        onClick = { runCutout() },
                        label = { Text("Cut out subject") },
                        enabled = !busy && original != null,
                    )
                }
                if (error != null) {
                    Text(error ?: "", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !busy && original != null,
                onClick = {
                    val chosen = (if (useCutout) cutout else original) ?: return@TextButton
                    busy = true
                    scope.launch {
                        val uri = saveSticker(context, chosen)
                        busy = false
                        if (uri != null) onSaved(uri) else error = "Couldn't save the sticker."
                    }
                },
            ) { Text("Save sticker") }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text("Cancel") } },
    )
}
