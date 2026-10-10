package com.tim.gallery.personal

import android.net.Uri
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.DeleteForever
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.tim.gallery.ui.SystemBarIcons
import com.tim.gallery.ui.VideoPlayerScreen
import com.tim.gallery.ui.ZoomableImage
import com.tim.gallery.ui.findActivity
import com.tim.gallery.ui.formatDuration
import com.tim.gallery.ui.itemCount
import kotlinx.coroutines.launch
import java.io.File

/** What the warning says everywhere it appears. */
const val PERSONAL_WARNING =
    "Photos and videos in your personal folder are encrypted and stored only inside Quartz Gallery on this phone. " +
        "They are not backed up anywhere and CAN'T BE RECOVERED if you forget your passcode, if too many wrong " +
        "passcodes are entered, if Quartz Gallery is uninstalled or its data is cleared, or if this phone is reset or lost — " +
        "unless you've made a backup file (⋮ → Back up to a file) inside the folder."

/**
 * The personal folder: first-time setup, the lock screen, and (once unlocked) its contents.
 * It locks again as soon as you leave it or the app goes to the background, and its screens
 * can't be screenshotted or shown in the recent-apps preview.
 */
@Composable
fun PersonalScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val setUp by Vault.setUp.collectAsState()
    val unlocked by Vault.unlocked.collectAsState()

    // No screenshots / recents previews while this screen is up
    DisposableEffect(Unit) {
        val window = context.findActivity()?.window
        window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
            Vault.lock()
        }
    }
    // Lock when the app goes to the background
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            // Don't lock behind a file picker we opened for a backup
            if (event == Lifecycle.Event.ON_STOP && !VaultPicker.open) Vault.lock()
            if (event == Lifecycle.Event.ON_RESUME) {
                // Gone a long time while "in the file picker"? Lock now to be safe
                if (VaultPicker.open && System.currentTimeMillis() - VaultPicker.openedAt > VaultPicker.MAX_AWAY_MS) Vault.lock()
                VaultPicker.open = false
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        when {
            !setUp -> SetupFlow(onBack)
            !unlocked -> LockScreen(onBack)
            else -> VaultBrowser(onBack)
        }
    }
}

// ===================================================================== setup

private enum class SetupStep { WARNING, CREATE, CONFIRM, OPTIONS }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SetupFlow(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var step by remember { mutableStateOf(SetupStep.WARNING) }
    var understood by remember { mutableStateOf(false) }
    var firstPin by remember { mutableStateOf("") }
    var pin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    BackHandler {
        when (step) {
            SetupStep.WARNING -> onBack()
            SetupStep.CREATE -> step = SetupStep.WARNING
            SetupStep.CONFIRM -> step = SetupStep.CREATE
            SetupStep.OPTIONS -> step = SetupStep.CREATE
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
                title = { Text("Set up personal folder") },
            )
        },
    ) { padding ->
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(vertical = 16.dp),
        ) {
            when (step) {
                SetupStep.WARNING -> {
                    Icon(Icons.Outlined.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(56.dp))
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "Hide photos and videos behind a passcode. They're moved out of your gallery, so other apps " +
                            "can't see them either.",
                        style = MaterialTheme.typography.bodyLarge,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 24.dp),
                    )
                    Spacer(Modifier.height(20.dp))
                    WarningCard()
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { understood = !understood }
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                    ) {
                        Checkbox(checked = understood, onCheckedChange = { understood = it })
                        Text("I understand that items in the personal folder can't be recovered.", style = MaterialTheme.typography.bodyMedium)
                    }
                    Spacer(Modifier.height(8.dp))
                    Button(enabled = understood, onClick = { step = SetupStep.CREATE }) { Text("Continue") }
                }
                SetupStep.CREATE -> PinPad(
                    title = "Create a passcode",
                    subtitle = "6 digits, just for the personal folder — it's separate from your phone's lock screen.",
                    error = error,
                    busy = false,
                    onComplete = {
                        firstPin = it
                        error = null
                        step = SetupStep.CONFIRM
                    },
                )
                SetupStep.CONFIRM -> PinPad(
                    title = "Enter it again",
                    subtitle = null,
                    error = null,
                    busy = false,
                    onComplete = {
                        if (it == firstPin) {
                            pin = it
                            step = SetupStep.OPTIONS
                        } else {
                            error = "Those didn't match. Try again."
                            step = SetupStep.CREATE
                        }
                    },
                )
                SetupStep.OPTIONS -> UnlockOptions(
                    initialBio = false,
                    initialCred = false,
                    initialAttempts = 5,
                    buttonLabel = if (busy) "Setting up…" else "Finish",
                    enabled = !busy,
                    onSave = { bio, cred, attempts ->
                        busy = true
                        scope.launch {
                            try {
                                Vault.setUp(pin, bio, cred, attempts)
                            } catch (e: kotlinx.coroutines.CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                Toast.makeText(context, "Couldn't set up the personal folder: ${e.message}", Toast.LENGTH_LONG).show()
                            }
                            busy = false
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun WarningCard() {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
    ) {
        Row(Modifier.padding(16.dp)) {
            Icon(Icons.Outlined.WarningAmber, contentDescription = null, tint = MaterialTheme.colorScheme.onErrorContainer)
            Spacer(Modifier.width(12.dp))
            Text(PERSONAL_WARNING, color = MaterialTheme.colorScheme.onErrorContainer, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/** Fingerprint/face, screen lock, and how many wrong passcodes empty the folder. */
@Composable
private fun UnlockOptions(
    initialBio: Boolean,
    initialCred: Boolean,
    initialAttempts: Int,
    buttonLabel: String,
    enabled: Boolean,
    onSave: (Boolean, Boolean, Int) -> Unit,
) {
    val context = LocalContext.current
    val bioAvailable = remember { VaultAuth.biometricAvailable(context) }
    val credAvailable = remember { VaultAuth.screenLockAvailable(context) }
    var bio by remember { mutableStateOf(initialBio && bioAvailable) }
    var cred by remember { mutableStateOf(initialCred && credAvailable) }
    var attempts by remember { mutableFloatStateOf(initialAttempts.toFloat()) }
    val n = attempts.toInt()

    Column(Modifier.fillMaxWidth()) {
        Text("Other ways to unlock", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 16.dp))
        Text(
            "Your passcode always works. You can also allow:",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )
        ListItem(
            headlineContent = { Text("Fingerprint or face") },
            supportingContent = {
                Text(
                    if (bioAvailable) "Whichever your phone has set up — Android picks which to show"
                    else "Not set up on this phone (add one in your phone's Settings → Security)"
                )
            },
            trailingContent = { Switch(checked = bio, enabled = bioAvailable, onCheckedChange = { bio = it }) },
        )
        ListItem(
            headlineContent = { Text("Phone's screen lock") },
            supportingContent = {
                Text(if (credAvailable) "Your pattern, PIN or password" else "This phone has no screen lock")
            },
            trailingContent = { Switch(checked = cred, enabled = credAvailable, onCheckedChange = { cred = it }) },
        )
        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        Text("Wrong passcodes allowed", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 16.dp))
        Text(
            "After $n wrong passcode${if (n == 1) "" else "s"} in a row, everything in the personal folder is deleted for good. " +
                "Fingerprint, face and screen-lock attempts don't count.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 16.dp)) {
            Text("$n", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.width(40.dp))
            Slider(
                value = attempts,
                onValueChange = { attempts = it },
                valueRange = 1f..Vault.MAX_ATTEMPTS_LIMIT.toFloat(),
                steps = Vault.MAX_ATTEMPTS_LIMIT - 2,
                modifier = Modifier.weight(1f),
            )
        }
        Spacer(Modifier.height(16.dp))
        Button(
            enabled = enabled,
            onClick = { onSave(bio, cred, n) },
            modifier = Modifier.align(Alignment.CenterHorizontally),
        ) { Text(buttonLabel) }
    }
}

// ===================================================================== lock screen

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LockScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var wiped by remember { mutableStateOf(false) }
    var forgot by remember { mutableStateOf(false) }
    var confirmReset by remember { mutableStateOf(false) }
    val systemUnlock = remember { VaultAuth.canUseSystemUnlock(context) }

    fun promptSystem() {
        VaultAuth.prompt(
            context,
            onSuccess = {
                scope.launch {
                    runCatching { Vault.unlockWithSystemAuth() }
                        .onFailure { Toast.makeText(context, "Couldn't unlock: ${it.message}", Toast.LENGTH_LONG).show() }
                }
            },
            onError = { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() },
        )
    }

    LaunchedEffect(Unit) { if (systemUnlock) promptSystem() }

    val failed = Vault.failedAttempts
    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
                title = { Text("Personal folder") },
            )
        },
    ) { padding ->
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(vertical = 16.dp),
        ) {
            PinPad(
                title = "Enter passcode",
                subtitle = if (failed > 0 && error == null) {
                    "${Vault.maxAttempts - failed} tries left before the folder is emptied"
                } else {
                    null
                },
                error = error,
                busy = busy,
                onSystemUnlock = if (systemUnlock) ({ promptSystem() }) else null,
                onComplete = { pin ->
                    busy = true
                    scope.launch {
                        val r = try {
                            Vault.checkPin(pin)
                        } catch (e: kotlinx.coroutines.CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            error = "Couldn't check the passcode: ${e.message}"
                            busy = false
                            return@launch
                        }
                        when (r) {
                            PinResult.Ok -> error = null
                            is PinResult.Wrong -> error =
                                "Wrong passcode. ${r.attemptsLeft} ${if (r.attemptsLeft == 1) "try" else "tries"} left before everything is deleted."
                            PinResult.Wiped -> {
                                error = null
                                wiped = true
                            }
                        }
                        busy = false
                    }
                },
            )
            Spacer(Modifier.height(16.dp))
            TextButton(onClick = { forgot = true }) { Text("Forgot passcode?") }
        }
    }

    if (wiped) {
        AlertDialog(
            onDismissRequest = { wiped = false },
            title = { Text("Personal folder emptied") },
            text = { Text("Too many wrong passcodes were entered, so everything in the personal folder was deleted.") },
            confirmButton = { TextButton(onClick = { wiped = false }) { Text("OK") } },
        )
    }
    if (forgot) {
        AlertDialog(
            onDismissRequest = { forgot = false },
            title = { Text("Forgot your passcode?") },
            text = {
                Text(
                    "There's no way to recover it — not even through your phone or Google account. " +
                        "You can reset the personal folder, which permanently deletes everything in it."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    forgot = false
                    confirmReset = true
                }) { Text("Reset folder…") }
            },
            dismissButton = { TextButton(onClick = { forgot = false }) { Text("Cancel") } },
        )
    }
    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            icon = { Icon(Icons.Outlined.WarningAmber, contentDescription = null) },
            title = { Text("Delete everything?") },
            text = { Text("Every photo and video in the personal folder will be deleted for good, and the passcode removed.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmReset = false
                    Vault.resetInBackground()
                }) { Text("Delete everything", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmReset = false }) { Text("Cancel") } },
        )
    }
}

// ===================================================================== contents

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun VaultBrowser(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val items by Vault.items.collectAsState()
    var selected by remember { mutableStateOf(emptySet<String>()) }
    var viewing by remember { mutableStateOf<Int?>(null) }
    var playing by remember { mutableStateOf<Pair<VaultItem, File>?>(null) }
    var progress by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf<List<VaultItem>?>(null) }
    var menuOpen by remember { mutableStateOf(false) }
    var settingsOpen by remember { mutableStateOf(false) }
    var exporting by remember { mutableStateOf(false) }
    var importing by remember { mutableStateOf(false) }

    BackHandler(enabled = selected.isNotEmpty() && viewing == null) { selected = emptySet() }

    fun restore(list: List<VaultItem>) {
        if (list.isEmpty()) return
        progress = "Moving back to your gallery…"
        scope.launch {
            val n = runCatching {
                Vault.restore(list) { d, t -> progress = "Moving back to your gallery… $d of $t" }
            }.getOrDefault(0)
            progress = null
            selected = emptySet()
            val failed = list.size - n
            Toast.makeText(
                context,
                "Moved ${itemCount(n)} back to your gallery" + if (failed > 0) " ($failed couldn't be moved and are still here)" else "",
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    Scaffold(
        topBar = {
            if (selected.isEmpty()) {
                TopAppBar(
                    navigationIcon = {
                        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                    },
                    title = {
                        Column {
                            Text("Personal")
                            Text(itemCount(items.size), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    },
                    actions = {
                        IconButton(onClick = { Vault.lock() }) { Icon(Icons.Outlined.Lock, contentDescription = "Lock now") }
                        Box {
                            IconButton(onClick = { menuOpen = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "More") }
                            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                                DropdownMenuItem(
                                    text = { Text("Back up to a file…") },
                                    enabled = items.isNotEmpty(),
                                    onClick = {
                                        menuOpen = false
                                        exporting = true
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text("Restore from a backup…") },
                                    onClick = {
                                        menuOpen = false
                                        importing = true
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text("Passcode & unlock options") },
                                    onClick = {
                                        menuOpen = false
                                        settingsOpen = true
                                    },
                                )
                            }
                        }
                    },
                )
            } else {
                TopAppBar(
                    navigationIcon = {
                        IconButton(onClick = { selected = emptySet() }) { Icon(Icons.Filled.Close, contentDescription = "Clear selection") }
                    },
                    title = { Text("${selected.size}") },
                    actions = {
                        IconButton(onClick = { selected = items.mapTo(HashSet()) { it.id } }) {
                            Icon(Icons.Outlined.SelectAll, contentDescription = "Select all")
                        }
                        IconButton(onClick = { restore(items.filter { it.id in selected }) }) {
                            Icon(Icons.Outlined.LockOpen, contentDescription = "Move back to gallery")
                        }
                        IconButton(onClick = { confirmDelete = items.filter { it.id in selected } }) {
                            Icon(Icons.Outlined.DeleteForever, contentDescription = "Delete forever")
                        }
                    },
                )
            }
        },
    ) { padding ->
        if (items.isEmpty()) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxSize().padding(padding).padding(32.dp),
            ) {
                Spacer(Modifier.height(48.dp))
                Icon(Icons.Outlined.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(64.dp))
                Spacer(Modifier.height(12.dp))
                Text("Nothing here yet", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(4.dp))
                Text(
                    "Select photos or videos in your gallery, then choose ⋮ → Move to personal folder. " +
                        "You can also do it from the ⋮ menu while viewing one.",
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                )
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(104.dp),
                contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding() + 8.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(items, key = { it.id }) { item ->
                    val isSel = item.id in selected
                    Box(
                        Modifier
                            .aspectRatio(1f)
                            .combinedClickable(
                                onClick = {
                                    if (selected.isNotEmpty()) {
                                        selected = if (isSel) selected - item.id else selected + item.id
                                    } else {
                                        viewing = items.indexOf(item)
                                    }
                                },
                                onLongClick = { selected = if (isSel) selected - item.id else selected + item.id },
                            )
                    ) {
                        VaultThumb(item, Modifier.fillMaxSize())
                        if (item.isVideo) {
                            Text(
                                formatDuration(item.durationMs),
                                color = Color.White,
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier
                                    .align(Alignment.BottomEnd)
                                    .padding(4.dp)
                                    .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(4.dp))
                                    .padding(horizontal = 4.dp, vertical = 1.dp),
                            )
                        }
                        if (selected.isNotEmpty()) {
                            Icon(
                                if (isSel) Icons.Filled.CheckCircle else Icons.Outlined.RadioButtonUnchecked,
                                contentDescription = if (isSel) "Selected" else "Not selected",
                                tint = if (isSel) MaterialTheme.colorScheme.primary else Color.White,
                                modifier = Modifier
                                    .align(Alignment.TopStart)
                                    .padding(6.dp)
                                    .size(24.dp)
                                    .background(if (isSel) MaterialTheme.colorScheme.surface else Color.Black.copy(alpha = 0.25f), CircleShape),
                            )
                        }
                    }
                }
            }
        }
    }

    viewing?.let { start ->
        if (items.isEmpty()) {
            LaunchedEffect(Unit) { viewing = null }
        } else {
            VaultViewer(
                items = items,
                startIndex = start.coerceIn(0, items.size - 1),
                onClose = { viewing = null },
                onPlay = { item, file -> playing = item to file },
                onRestore = { restore(listOf(it)) },
                onDelete = { confirmDelete = listOf(it) },
            )
        }
    }

    playing?.let { (item, file) ->
        BackHandler { playing = null }
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            VideoPlayerScreen(uri = Uri.fromFile(file), title = item.name, onBack = { playing = null }, allowCapture = false)
        }
    }

    progress?.let { text ->
        AlertDialog(
            onDismissRequest = {},
            title = { Text("Please wait") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(text)
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
            },
            confirmButton = {},
        )
    }

    confirmDelete?.let { list ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Delete forever?") },
            text = { Text("${itemCount(list.size)} will be permanently deleted. They aren't in the trash and can't be recovered.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = null
                    scope.launch {
                        runCatching { Vault.delete(list.map { it.id }) }
                        selected = emptySet()
                    }
                }) { Text("Delete forever", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Cancel") } },
        )
    }

    if (settingsOpen) {
        VaultSettingsSheet(onDismiss = { settingsOpen = false })
    }

    if (exporting || importing) {
        BackupFlows(
            exportRequested = exporting,
            importRequested = importing,
            onDone = {
                exporting = false
                importing = false
            },
        )
    }
}

@Composable
private fun VaultThumb(item: VaultItem, modifier: Modifier) {
    val bmp by produceState<android.graphics.Bitmap?>(null, item.id) { value = Vault.thumbnail(item.id) }
    Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant)) {
        bmp?.let {
            Image(it.asImageBitmap(), contentDescription = item.name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
    }
}

// ===================================================================== viewer

@Composable
private fun VaultViewer(
    items: List<VaultItem>,
    startIndex: Int,
    onClose: () -> Unit,
    onPlay: (VaultItem, File) -> Unit,
    onRestore: (VaultItem) -> Unit,
    onDelete: (VaultItem) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val pager = rememberPagerState(initialPage = startIndex) { items.size }
    var chrome by remember { mutableStateOf(true) }
    var zoomed by remember { mutableStateOf(false) }
    var decrypting by remember { mutableStateOf<Float?>(null) }
    var menuOpen by remember { mutableStateOf(false) }

    BackHandler { onClose() }
    SystemBarIcons(darkBackground = true)

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        HorizontalPager(state = pager, userScrollEnabled = !zoomed, key = { items.getOrNull(it)?.id ?: it }) { page ->
            val item = items.getOrNull(page) ?: return@HorizontalPager
            if (item.isVideo) {
                Box(Modifier.fillMaxSize().clickable { chrome = !chrome }, contentAlignment = Alignment.Center) {
                    VaultThumb(item, Modifier.fillMaxWidth().aspectRatio(if (item.height > 0) item.width.toFloat() / item.height else 1f))
                    IconButton(
                        onClick = {
                            if (decrypting != null) return@IconButton
                            decrypting = 0f
                            scope.launch {
                                val f = Vault.decryptToTemp(item) { decrypting = it }
                                decrypting = null
                                if (f != null) onPlay(item, f) else Toast.makeText(context, "Couldn't open this video", Toast.LENGTH_SHORT).show()
                            }
                        },
                        modifier = Modifier.size(80.dp).background(Color.Black.copy(alpha = 0.5f), CircleShape),
                    ) {
                        Icon(Icons.Filled.PlayArrow, contentDescription = "Play", tint = Color.White, modifier = Modifier.size(52.dp))
                    }
                }
            } else {
                val bmp by produceState<android.graphics.Bitmap?>(null, item.id) { value = Vault.decodeImage(item) }
                if (bmp == null) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        VaultThumb(item, Modifier.fillMaxWidth().aspectRatio(if (item.height > 0) item.width.toFloat() / item.height else 1f))
                    }
                } else {
                    ZoomableImage(
                        model = bmp,
                        contentDescription = item.name,
                        isCurrent = page == pager.currentPage,
                        onTap = { chrome = !chrome },
                        onZoomChange = { zoomed = it },
                    )
                }
            }
        }

        val current = items.getOrNull(pager.currentPage)
        if (chrome && current != null) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.5f))
                    .statusBarsPadding()
                    .padding(4.dp),
            ) {
                IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White) }
                Text(
                    current.name,
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Box {
                    IconButton(onClick = { menuOpen = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "More", tint = Color.White) }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("Move back to gallery") },
                            leadingIcon = { Icon(Icons.Outlined.LockOpen, contentDescription = null) },
                            onClick = {
                                menuOpen = false
                                onRestore(current)
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("Delete forever") },
                            leadingIcon = { Icon(Icons.Outlined.DeleteForever, contentDescription = null) },
                            onClick = {
                                menuOpen = false
                                onDelete(current)
                            },
                        )
                    }
                }
            }
            Text(
                "${pager.currentPage + 1} of ${items.size}",
                color = Color.White.copy(alpha = 0.8f),
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(16.dp),
            )
        }

        decrypting?.let { p ->
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .align(Alignment.Center)
                    .background(Color.Black.copy(alpha = 0.75f), RoundedCornerShape(16.dp))
                    .padding(24.dp),
            ) {
                CircularProgressIndicator(progress = { p }, color = Color.White)
                Spacer(Modifier.height(8.dp))
                Text("Unlocking video…", color = Color.White)
            }
        }
    }
}

// ===================================================================== settings (while unlocked)

private enum class PinChange { NONE, OLD, NEW, CONFIRM }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VaultSettingsSheet(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var changing by remember { mutableStateOf(PinChange.NONE) }
    var newPin by remember { mutableStateOf("") }
    var pinError by remember { mutableStateOf<String?>(null) }
    var confirmReset by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var version by remember { mutableIntStateOf(0) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 24.dp),
        ) {
            if (changing == PinChange.NONE) {
                ListItem(
                    headlineContent = { Text("Change passcode") },
                    leadingContent = { Icon(Icons.Outlined.Lock, contentDescription = null) },
                    modifier = Modifier.clickable {
                        pinError = null
                        changing = PinChange.OLD
                    },
                )
                HorizontalDivider()
                Spacer(Modifier.height(8.dp))
                androidx.compose.runtime.key(version) {
                    UnlockOptions(
                        initialBio = Vault.allowBiometric,
                        initialCred = Vault.allowDeviceCredential,
                        initialAttempts = Vault.maxAttempts,
                        buttonLabel = "Save",
                        enabled = true,
                        onSave = { bio, cred, attempts ->
                            Vault.setOptions(bio, cred, attempts)
                            version++
                            Toast.makeText(context, "Saved", Toast.LENGTH_SHORT).show()
                        },
                    )
                }
                Spacer(Modifier.height(16.dp))
                HorizontalDivider()
                ListItem(
                    headlineContent = { Text("Delete everything and turn off", color = MaterialTheme.colorScheme.error) },
                    supportingContent = { Text("Permanently deletes every item and removes the passcode") },
                    leadingContent = { Icon(Icons.Outlined.DeleteForever, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
                    modifier = Modifier.clickable { confirmReset = true },
                )
            } else {
                PinPad(
                    title = when (changing) {
                        PinChange.OLD -> "Enter current passcode"
                        PinChange.NEW -> "New passcode"
                        else -> "Enter new passcode again"
                    },
                    subtitle = null,
                    error = pinError,
                    busy = busy,
                    onComplete = { pin ->
                        when (changing) {
                            PinChange.OLD -> {
                                busy = true
                                scope.launch {
                                    if (Vault.verifyPin(pin)) {
                                        pinError = null
                                        changing = PinChange.NEW
                                    } else {
                                        pinError = "That's not your current passcode"
                                    }
                                    busy = false
                                }
                            }
                            PinChange.NEW -> {
                                newPin = pin
                                pinError = null
                                changing = PinChange.CONFIRM
                            }
                            PinChange.CONFIRM -> {
                                if (pin != newPin) {
                                    pinError = "Those didn't match. Try again."
                                    changing = PinChange.NEW
                                } else {
                                    busy = true
                                    scope.launch {
                                        Vault.changePin(pin)
                                        busy = false
                                        changing = PinChange.NONE
                                        Toast.makeText(context, "Passcode changed", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            }
                            PinChange.NONE -> Unit
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                TextButton(onClick = { changing = PinChange.NONE }, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                    Text("Cancel")
                }
            }
        }
    }

    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            icon = { Icon(Icons.Outlined.WarningAmber, contentDescription = null) },
            title = { Text("Delete everything?") },
            text = { Text("Every photo and video in the personal folder will be deleted for good, and the passcode removed.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmReset = false
                    onDismiss()
                    Vault.resetInBackground()
                }) { Text("Delete everything", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmReset = false }) { Text("Cancel") } },
        )
    }
}
