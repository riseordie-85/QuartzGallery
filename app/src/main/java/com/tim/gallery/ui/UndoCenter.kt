package com.tim.gallery.ui

import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.collectLatest

/**
 * "Moved 12 items · Undo" messages. Anything can post one; the app shows them in one place at
 * the bottom of the screen. The undo runs with app-wide [MediaActions], so it still works after
 * you've moved on to another screen.
 */
object UndoCenter {
    class Entry(val message: String, val undo: ((MediaActions) -> Unit)?)

    private val _events = MutableSharedFlow<Entry>(extraBufferCapacity = 8)
    val events = _events.asSharedFlow()

    fun post(message: String, undo: ((MediaActions) -> Unit)? = null) {
        _events.tryEmit(Entry(message, undo))
    }
}

@Composable
fun UndoHost(actions: MediaActions, modifier: Modifier = Modifier) {
    val host = remember { SnackbarHostState() }
    LaunchedEffect(Unit) {
        UndoCenter.events.collectLatest { e ->
            val result = host.showSnackbar(
                message = e.message,
                actionLabel = if (e.undo != null) "Undo" else null,
                duration = if (e.undo != null) SnackbarDuration.Long else SnackbarDuration.Short,
            )
            if (result == SnackbarResult.ActionPerformed) e.undo?.invoke(actions)
        }
    }
    SnackbarHost(host, modifier)
}
