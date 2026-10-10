package com.tim.gallery.personal

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.tim.gallery.data.GalleryItem
import com.tim.gallery.ui.MediaActions
import com.tim.gallery.ui.itemCount
import kotlinx.coroutines.launch

/**
 * "Move to personal folder": confirms (with the can't-be-recovered warning), copies the items in
 * encrypted, then deletes the originals from the gallery. If Android's delete confirmation is
 * declined, the copies are removed again so nothing ends up in two places.
 * If the folder isn't set up yet, offers to set it up ([onSetUp]).
 */
@Composable
fun MoveToPersonalDialog(
    items: List<GalleryItem>,
    actions: MediaActions,
    onDismiss: () -> Unit,
    onSetUp: () -> Unit,
    onFinished: (moved: Int) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val setUp by Vault.setUp.collectAsState()
    var progress by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var waitingForDelete by remember { mutableStateOf(false) }

    if (!setUp) {
        AlertDialog(
            onDismissRequest = onDismiss,
            icon = { Icon(Icons.Outlined.Lock, contentDescription = null) },
            title = { Text("Set up your personal folder") },
            text = { Text("Choose a passcode first. Then you can move photos and videos into it.") },
            confirmButton = { TextButton(onClick = onSetUp) { Text("Set up") } },
            dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        )
        return
    }

    fun start() {
        progress = 0 to items.size
        scope.launch {
            val added = try {
                Vault.add(items) { d, t -> progress = d to t }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                emptyList()
            }
            progress = null
            val failed = items.size - added.size
            if (added.isEmpty()) {
                Toast.makeText(context, "Couldn't move these items", Toast.LENGTH_SHORT).show()
                onFinished(0)
                return@launch
            }
            waitingForDelete = true
            actions.deleteForever(added.map { it.first }) { ok ->
                waitingForDelete = false
                if (ok) {
                    Toast.makeText(
                        context,
                        "Moved ${itemCount(added.size)} to your personal folder" +
                            if (failed > 0) " ($failed couldn't be moved)" else "",
                        Toast.LENGTH_SHORT,
                    ).show()
                    onFinished(added.size)
                } else {
                    // Keep things in one place only: undo the copies
                    scope.launch {
                        runCatching { Vault.delete(added.map { it.second }) }
                        Toast.makeText(context, "Cancelled — nothing was moved", Toast.LENGTH_SHORT).show()
                        onFinished(0)
                    }
                }
            }
        }
    }

    val p = progress
    AlertDialog(
        onDismissRequest = { if (p == null && !waitingForDelete) onDismiss() },
        icon = { Icon(Icons.Outlined.Lock, contentDescription = null) },
        title = { Text("Move to personal folder?") },
        text = {
            when {
                p != null -> Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Moving ${p.first} of ${p.second}…")
                    LinearProgressIndicator(
                        progress = { if (p.second == 0) 0f else p.first.toFloat() / p.second },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                waitingForDelete -> Text("Removing the originals from your gallery…")
                else -> Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        "${itemCount(items.size)} will be removed from your gallery and kept only in your passcode-protected " +
                            "personal folder."
                    )
                    Text(
                        PERSONAL_WARNING,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            if (p == null && !waitingForDelete) TextButton(onClick = { start() }) { Text("Move") }
        },
        dismissButton = {
            if (p == null && !waitingForDelete) TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
