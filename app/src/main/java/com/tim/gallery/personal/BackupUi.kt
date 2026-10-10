package com.tim.gallery.personal

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import java.time.LocalDate

/** True while a file picker we opened is showing, so the folder doesn't lock behind it. */
internal object VaultPicker {
    @Volatile
    var open = false
        set(value) {
            field = value
            if (value) openedAt = System.currentTimeMillis()
        }

    @Volatile
    var openedAt = 0L

    /** Away longer than this (e.g. left the picker for the home screen): lock anyway. */
    const val MAX_AWAY_MS = 3 * 60 * 1000L
}

/**
 * Back up the personal folder to one password-protected file (to keep on a PC or USB drive),
 * or restore one. Shown from the folder's ⋮ menu.
 */
@Composable
fun BackupFlows(exportRequested: Boolean, importRequested: Boolean, onDone: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var exportPassword by remember { mutableStateOf<String?>(null) }
    var importUri by remember { mutableStateOf<android.net.Uri?>(null) }
    var progress by remember { mutableStateOf<String?>(null) }
    var importError by remember { mutableStateOf<String?>(null) }

    val createDoc = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        VaultPicker.open = false
        val pw = exportPassword
        exportPassword = null
        if (uri == null || pw == null) {
            onDone()
            return@rememberLauncherForActivityResult
        }
        progress = "Backing up…"
        // The work runs outside this screen, so leaving the app doesn't cut it short
        val work = Vault.runInBackground {
            runCatching {
                context.contentResolver.openOutputStream(uri, "w")?.use { out ->
                    Vault.exportBackup(out, pw) { d, t -> progress = "Backing up… $d of $t" }
                } ?: error("Couldn't write the file")
            }.onFailure {
                // Don't leave a half-written backup behind
                runCatching { android.provider.DocumentsContract.deleteDocument(context.contentResolver, uri) }
            }
        }
        scope.launch {
            val result = work.await()
            progress = null
            Toast.makeText(
                context,
                result.fold({ "Backed up ${it} item${if (it == 1) "" else "s"}. Keep the file and its password somewhere safe." }, { "Backup failed: ${it.message}" }),
                Toast.LENGTH_LONG,
            ).show()
            onDone()
        }
    }
    val openDoc = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        VaultPicker.open = false
        if (uri == null) onDone() else importUri = uri
    }

    if (exportRequested && exportPassword == null && progress == null) {
        NewPasswordDialog(
            onCancel = onDone,
            onOk = { pw ->
                exportPassword = pw
                VaultPicker.open = true
                createDoc.launch("Quartz Personal Backup ${LocalDate.now()}.qgbackup")
            },
        )
    }
    if (importRequested && importUri == null && progress == null) {
        androidx.compose.runtime.LaunchedEffect(Unit) {
            VaultPicker.open = true
            openDoc.launch(arrayOf("*/*"))
        }
    }
    importUri?.let { uri ->
        if (progress == null) {
            PasswordDialog(
                error = importError,
                onCancel = {
                    importUri = null
                    importError = null
                    onDone()
                },
                onOk = { pw ->
                    progress = "Opening backup…"
                    val work = Vault.runInBackground {
                        runCatching {
                            context.contentResolver.openInputStream(uri)?.use { input ->
                                Vault.importBackup(input, pw) { d, t -> progress = "Restoring… $d of $t" }
                            } ?: error("Couldn't open the file")
                        }
                    }
                    scope.launch {
                        val result = work.await()
                        progress = null
                        val e = result.exceptionOrNull()
                        if (e is Vault.WrongPasswordException) {
                            importError = e.message
                        } else {
                            importUri = null
                            importError = null
                            Toast.makeText(
                                context,
                                result.fold({ "Restored $it item${if (it == 1) "" else "s"}" }, { "Restore failed: ${it.message}" }),
                                Toast.LENGTH_LONG,
                            ).show()
                            onDone()
                        }
                    }
                },
            )
        }
    }
    progress?.let {
        AlertDialog(
            onDismissRequest = {},
            title = { Text(it) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text("Keep Quartz Gallery open until this finishes.", style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {},
        )
    }
}

@Composable
private fun NewPasswordDialog(onCancel: () -> Unit, onOk: (String) -> Unit) {
    var a by remember { mutableStateOf("") }
    var b by remember { mutableStateOf("") }
    val error = when {
        a.isNotEmpty() && a.length < 8 -> "Use at least 8 characters"
        b.isNotEmpty() && a != b -> "The passwords don't match"
        else -> null
    }
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Back up personal folder") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "Everything is saved into one encrypted file you can keep on your computer or a USB drive. " +
                        "Choose a password for it — it's needed to restore, and it can't be recovered.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                OutlinedTextField(
                    value = a,
                    onValueChange = { a = it },
                    label = { Text("Backup password") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                )
                OutlinedTextField(
                    value = b,
                    onValueChange = { b = it },
                    label = { Text("Type it again") },
                    singleLine = true,
                    isError = error != null,
                    supportingText = { error?.let { Text(it) } },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                )
            }
        },
        confirmButton = {
            TextButton(enabled = a.length >= 8 && a == b, onClick = { onOk(a) }) { Text("Choose where to save") }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } },
    )
}

@Composable
private fun PasswordDialog(error: String?, onCancel: () -> Unit, onOk: (String) -> Unit) {
    var pw by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Restore from backup") },
        text = {
            OutlinedTextField(
                value = pw,
                onValueChange = { pw = it },
                label = { Text("Backup password") },
                singleLine = true,
                isError = error != null,
                supportingText = { error?.let { Text(it) } },
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            )
        },
        confirmButton = { TextButton(enabled = pw.isNotEmpty(), onClick = { onOk(pw) }) { Text("Restore") } },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } },
    )
}
