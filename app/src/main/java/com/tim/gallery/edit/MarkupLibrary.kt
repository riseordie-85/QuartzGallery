package com.tim.gallery.edit

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.tim.gallery.GalleryViewModel

/** What the wide editor panel shows under the Markup buttons. */
enum class LibraryTab(val label: String) {
    STICKERS("Sticker"),
    EMOJI("Emoji"),
    SHAPES("Shape"),
    WATERMARKS("Watermark"),
}

/**
 * On wide screens the sticker, emoji, shape and watermark pickers sit right in the editor's
 * side panel (instead of popping up), so you can add several things in a row.
 */
@Composable
fun MarkupLibrary(
    vm: GalleryViewModel,
    tab: LibraryTab,
    onSticker: (String) -> Unit,
    onEmoji: (String) -> Unit,
    onShape: (ShapeKind) -> Unit,
    onWatermark: (WatermarkPreset) -> Unit,
) {
    // The pickers were made for the app's sheets; the editor is always dark
    MaterialTheme(colorScheme = dynamicDarkColorScheme(LocalContext.current)) {
        CompositionLocalProvider(LocalContentColor provides Color.White) {
            Column(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                HorizontalDivider(color = Color.White.copy(alpha = 0.12f), modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                when (tab) {
                    LibraryTab.STICKERS -> StickerPicker(vm = vm, onPick = onSticker, gridMaxHeight = 520.dp)
                    LibraryTab.EMOJI -> EmojiPicker(onPick = onEmoji, gridHeight = 420.dp)
                    LibraryTab.SHAPES -> ShapePicker(onPick = onShape, maxHeight = 520.dp)
                    LibraryTab.WATERMARKS -> WatermarkList(onApply = onWatermark, listMaxHeight = 520.dp)
                }
            }
        }
    }
}
