package com.tim.gallery.edit

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FormatAlignCenter
import androidx.compose.material.icons.filled.FormatAlignLeft
import androidx.compose.material.icons.filled.FormatAlignRight
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.Typeface
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/**
 * Full-screen text entry with font, color, style and alignment. [initial] is null for new text.
 * [onDone] receives a TextLayer with the chosen look (position comes from [initial] if editing).
 */
@Composable
fun TextEditorDialog(
    initial: TextLayer?,
    onDismiss: () -> Unit,
    onDone: (TextLayer) -> Unit,
) {
    val start = initial ?: TextLayer(id = 0L, text = "")
    var text by remember { mutableStateOf(start.text) }
    var color by remember { mutableStateOf(start.color) }
    var font by remember { mutableStateOf(start.font) }
    var style by remember { mutableStateOf(start.style) }
    var align by remember { mutableStateOf(start.align) }
    val focus = remember { FocusRequester() }

    val chipColors = FilterChipDefaults.filterChipColors(
        labelColor = Color.White,
        selectedContainerColor = Color.White,
        selectedLabelColor = Color.Black,
    )

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        // Inside the dialog, so the text field is attached before focus is requested
        LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
        Column(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.88f))
                .safeDrawingPadding()
                .imePadding(),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(4.dp)) {
                IconButton(onClick = onDismiss) { Icon(Icons.Filled.Close, contentDescription = "Cancel", tint = Color.White) }
                Box(Modifier.weight(1f))
                TextButton(
                    enabled = text.isNotBlank(),
                    onClick = {
                        onDone(start.copy(text = text.trimEnd(), color = color, font = font, style = style, align = align))
                    },
                ) { Text("Done") }
            }

            // Preview of the look, editable in place
            val bubble = style == TextStyleOption.BUBBLE
            val textColor = if (bubble) Color(EditRenderer.contrastColor(color)) else Color(color)
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp),
            ) {
                TextField(
                    value = text,
                    onValueChange = { text = it },
                    placeholder = { Text("Type something", color = Color.White.copy(alpha = 0.5f), style = MaterialTheme.typography.headlineMedium) },
                    textStyle = TextStyle(
                        color = textColor,
                        fontSize = 34.sp,
                        fontFamily = FontFamily(Typeface(font.typeface)),
                        textAlign = when (align) {
                            TextAlignOption.LEFT -> TextAlign.Start
                            TextAlignOption.CENTER -> TextAlign.Center
                            TextAlignOption.RIGHT -> TextAlign.End
                        },
                        shadow = if (style == TextStyleOption.OUTLINE) {
                            Shadow(Color(EditRenderer.contrastColor(color)), blurRadius = 6f)
                        } else {
                            null
                        },
                    ),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = if (bubble) Color(color) else Color.Transparent,
                        unfocusedContainerColor = if (bubble) Color(color) else Color.Transparent,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                        cursorColor = Color.White,
                    ),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                )
            }

            Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(vertical = 12.dp)) {
                ColorDots(PALETTE, color, onPick = { color = it })

                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(TextFont.entries) { f ->
                        FilterChip(
                            selected = f == font,
                            onClick = { font = f },
                            label = { Text(f.label, fontFamily = FontFamily(Typeface(f.typeface))) },
                            colors = chipColors,
                        )
                    }
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(horizontal = 16.dp),
                ) {
                    TextStyleOption.entries.forEach { s ->
                        FilterChip(
                            selected = s == style,
                            onClick = { style = s },
                            label = { Text(s.label) },
                            colors = chipColors,
                        )
                    }
                    Box(Modifier.weight(1f))
                    IconButton(onClick = {
                        align = when (align) {
                            TextAlignOption.LEFT -> TextAlignOption.CENTER
                            TextAlignOption.CENTER -> TextAlignOption.RIGHT
                            TextAlignOption.RIGHT -> TextAlignOption.LEFT
                        }
                    }) {
                        Icon(
                            when (align) {
                                TextAlignOption.LEFT -> Icons.Filled.FormatAlignLeft
                                TextAlignOption.CENTER -> Icons.Filled.FormatAlignCenter
                                TextAlignOption.RIGHT -> Icons.Filled.FormatAlignRight
                            },
                            contentDescription = "Alignment",
                            tint = Color.White,
                        )
                    }
                }
            }
        }
    }
}
