package com.tim.gallery.ui

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.BrandingWatermark
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.FileUpload
import androidx.compose.material.icons.outlined.FontDownload
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.PermMedia
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.tim.gallery.edit.ColorPickerDialog
import com.tim.gallery.edit.Fonts
import com.tim.gallery.edit.WatermarkSheet
import com.tim.gallery.settings.AppSettings
import com.tim.gallery.settings.GRADIENT_PRESETS
import com.tim.gallery.settings.GradientType
import com.tim.gallery.settings.ThemeMode
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import androidx.compose.ui.text.font.Typeface as ComposeTypeface

/** Theme, gradient background, watermarks, fonts and media permissions. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit, onOpenPersonal: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val theme by AppSettings.theme.collectAsState()
    val gradient by AppSettings.gradient.collectAsState()
    val importedFonts by Fonts.imported.collectAsState()
    val fontVersion by Fonts.version.collectAsState()
    val canManageMedia = rememberCanManageMedia()

    var picking by remember { mutableStateOf<Boolean?>(null) } // true = start color, false = end color
    var watermarks by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { Fonts.refreshImported(context) }
    LaunchedEffect(importedFonts) { importedFonts.forEach { Fonts.load(context, it.key) } }

    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val key = Fonts.import(context, uri)
            Toast.makeText(
                context,
                if (key != null) "Font added" else "That file isn't a font Android can use (.ttf or .otf).",
                Toast.LENGTH_SHORT,
            ).show()
        }
    }

    Scaffold(
        containerColor = mainContainerColor(),
        topBar = {
            TopAppBar(
                colors = mainTopBarColors(),
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
                title = { Text("Settings") },
            )
        },
    ) { padding ->
        LazyColumn(
            contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding() + 24.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            // ------------------------------------------------ appearance
            item { SectionTitle("Appearance") }
            item {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                ) {
                    Text("Theme", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    ThemeMode.entries.forEach { m ->
                        FilterChip(selected = theme == m, onClick = { AppSettings.setTheme(m) }, label = { Text(m.label) })
                    }
                }
            }
            item {
                ListItem(
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    headlineContent = { Text("Gradient background") },
                    supportingContent = { Text("Photos, Albums, Trash and Settings. Text switches to light or dark to stay readable.") },
                    trailingContent = {
                        Switch(checked = gradient.enabled, onCheckedChange = { AppSettings.setGradient(gradient.copy(enabled = it)) })
                    },
                )
            }
            if (gradient.enabled) {
                item {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.6f)),
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    ) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .height(110.dp)
                                    .clip(RoundedCornerShape(14.dp))
                                    .drawBehind { drawRect(gradientBrush(gradient, size)) }
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                GradientType.entries.forEach { t ->
                                    FilterChip(
                                        selected = gradient.type == t,
                                        onClick = { AppSettings.setGradient(gradient.copy(type = t)) },
                                        label = { Text(t.label) },
                                    )
                                }
                            }
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                ColorSwatch("Start", gradient.start) { picking = true }
                                IconButton(onClick = { AppSettings.setGradient(gradient.copy(start = gradient.end, end = gradient.start)) }) {
                                    Icon(Icons.Outlined.SwapHoriz, contentDescription = "Swap colors")
                                }
                                ColorSwatch("End", gradient.end) { picking = false }
                            }
                            if (gradient.type != GradientType.RADIAL) {
                                Column {
                                    Text(
                                        "Direction  ${directionLabel(gradient.angle)}",
                                        style = MaterialTheme.typography.labelLarge,
                                    )
                                    Slider(
                                        value = gradient.angle,
                                        onValueChange = { v ->
                                            // Snap to the common directions
                                            val snapped = listOf(0f, 45f, 90f, 135f, 180f, 225f, 270f, 315f, 360f)
                                                .firstOrNull { kotlin.math.abs(it - v) < 4f } ?: v
                                            AppSettings.setGradient(gradient.copy(angle = snapped))
                                        },
                                        valueRange = 0f..360f,
                                    )
                                }
                            }
                            Text("Presets", style = MaterialTheme.typography.labelLarge)
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                items(GRADIENT_PRESETS) { (name, pair) ->
                                    Column(
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(10.dp))
                                            .clickable { AppSettings.setGradient(gradient.copy(start = pair.first, end = pair.second)) }
                                            .padding(4.dp),
                                    ) {
                                        Box(
                                            Modifier
                                                .size(48.dp)
                                                .clip(CircleShape)
                                                .drawBehind {
                                                    drawRect(gradientBrush(gradient.copy(start = pair.first, end = pair.second), size))
                                                }
                                                .border(BorderStroke(1.dp, MaterialTheme.colorScheme.outline), CircleShape)
                                        )
                                        Text(name, style = MaterialTheme.typography.labelSmall)
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // ------------------------------------------------ editing
            item { SectionTitle("Editing") }
            item {
                ListItem(
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    leadingContent = { Icon(Icons.AutoMirrored.Outlined.BrandingWatermark, contentDescription = null) },
                    headlineContent = { Text("Watermarks") },
                    supportingContent = { Text("Create and edit your saved watermarks") },
                    modifier = Modifier.clickable { watermarks = true },
                )
            }
            item {
                ListItem(
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    leadingContent = { Icon(Icons.Outlined.FontDownload, contentDescription = null) },
                    headlineContent = { Text("Your fonts") },
                    supportingContent = {
                        Text(
                            if (importedFonts.isEmpty()) "Add .ttf or .otf fonts to use in text and watermarks"
                            else "${importedFonts.size} imported · tap the trash can to remove one"
                        )
                    },
                    trailingContent = {
                        TextButton(onClick = {
                            importer.launch(
                                arrayOf(
                                    "font/ttf", "font/otf", "font/sfnt", "application/x-font-ttf", "application/x-font-otf",
                                    "application/font-sfnt", "application/vnd.ms-opentype", "application/octet-stream",
                                )
                            )
                        }) {
                            Icon(Icons.Outlined.FileUpload, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Import")
                        }
                    },
                )
            }
            items(importedFonts, key = { it.key }) { f ->
                if (fontVersion < 0) return@items
                val tf = Fonts.cached(f.key)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().padding(start = 72.dp, end = 8.dp),
                ) {
                    Text(
                        f.label,
                        fontFamily = tf?.let { FontFamily(ComposeTypeface(it)) },
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = { Fonts.deleteImported(context, f.key) }) {
                        Icon(Icons.Outlined.Delete, contentDescription = "Remove font")
                    }
                }
            }

            // ------------------------------------------------ media
            item { SectionTitle("Photos & videos") }
            item {
                ListItem(
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    leadingContent = { Icon(Icons.Outlined.Lock, contentDescription = null) },
                    headlineContent = { Text("Personal folder") },
                    supportingContent = { Text("Passcode, fingerprint/face and wrong-passcode limit are set inside the folder") },
                    modifier = Modifier.clickable(onClick = onOpenPersonal),
                )
            }
            item {
                ListItem(
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    leadingContent = { Icon(Icons.Outlined.PermMedia, contentDescription = null) },
                    headlineContent = { Text("Move & delete without asking") },
                    supportingContent = {
                        Text(
                            if (canManageMedia) "On — Android won't ask before moving or deleting"
                            else "Off — Android asks each time. Tap to change."
                        )
                    },
                    modifier = Modifier.clickable { openManageMediaSettings(context) },
                )
            }

            item {
                HorizontalDivider(Modifier.padding(horizontal = 16.dp, vertical = 12.dp))
                Text(
                    "Private by design: no ads, no accounts, no tracking. Quartz Gallery has no internet access — " +
                        "your photos, edits, map and searches never leave this phone.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
                Text(
                    "Quartz Gallery ${appVersion(context)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
        }
    }

    picking?.let { start ->
        ColorPickerDialog(
            initial = if (start) gradient.start else gradient.end,
            allowAlpha = false,
            title = if (start) "Start color" else "End color",
            onDismiss = { picking = null },
            onPick = { c ->
                AppSettings.setGradient(if (start) gradient.copy(start = c) else gradient.copy(end = c))
                picking = null
            },
        )
    }

    if (watermarks) {
        WatermarkSheet(onDismiss = { watermarks = false }, onApply = null)
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 4.dp),
    )
}

@Composable
private fun ColorSwatch(label: String, color: Int, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .border(BorderStroke(1.dp, MaterialTheme.colorScheme.outline), RoundedCornerShape(20.dp))
            .clickable(onClick = onClick)
            .padding(start = 6.dp, end = 14.dp, top = 6.dp, bottom = 6.dp),
    ) {
        Box(
            Modifier
                .size(26.dp)
                .clip(CircleShape)
                .background(Color(color))
                .border(BorderStroke(1.dp, MaterialTheme.colorScheme.outline), CircleShape)
        )
        Spacer(Modifier.width(8.dp))
        Text(label, style = MaterialTheme.typography.labelLarge)
    }
}

private fun directionLabel(angle: Float): String = when (((angle.roundToInt() % 360) + 360) % 360) {
    0 -> "→ left to right"
    45 -> "↘ diagonal"
    90 -> "↓ top to bottom"
    135 -> "↙ diagonal"
    180 -> "← right to left"
    225 -> "↖ diagonal"
    270 -> "↑ bottom to top"
    315 -> "↗ diagonal"
    else -> "${angle.roundToInt()}°"
}

private fun appVersion(context: android.content.Context): String =
    runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: ""
