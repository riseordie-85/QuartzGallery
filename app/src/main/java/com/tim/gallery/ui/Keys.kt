package com.tim.gallery.ui

import androidx.compose.foundation.focusable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type

/**
 * Keyboard shortcuts for a whole screen. The screen takes keyboard focus when it opens, and gets
 * every key press first (even while a button inside it has focus), so shortcuts always work.
 * [onKey] returns true when it used the key.
 */
@Composable
fun Modifier.keyboardShortcuts(enabled: Boolean = true, onKey: (Shortcut) -> Boolean): Modifier {
    val focus = remember { FocusRequester() }
    val handler by rememberUpdatedState(onKey)
    val on by rememberUpdatedState(enabled)
    LaunchedEffect(enabled) { if (enabled) runCatching { focus.requestFocus() } }
    return this
        .focusRequester(focus)
        .onPreviewKeyEvent { e -> on && e.type == KeyEventType.KeyDown && handler(Shortcut(e)) }
        .focusable()
}

/** A key press, with the modifier keys spelled out. */
class Shortcut(event: KeyEvent) {
    val key: Key = event.key
    val ctrl: Boolean = event.isCtrlPressed || event.isMetaPressed
    val shift: Boolean = event.isShiftPressed
    val alt: Boolean = event.isAltPressed
    val plain: Boolean get() = !ctrl && !alt

    fun isAny(a: Key, b: Key, c: Key = a, d: Key = a): Boolean = key == a || key == b || key == c || key == d

    // (The phone's own Back is left to the screen's back handling)
    val isBack: Boolean get() = plain && key == Key.Escape
    val isZoomIn: Boolean get() = isAny(Key.Plus, Key.NumPadAdd, Key.ZoomIn, Key.Equals)
    val isZoomOut: Boolean get() = isAny(Key.Minus, Key.NumPadSubtract, Key.ZoomOut)
    val isZoomReset: Boolean get() = isAny(Key.Zero, Key.NumPad0)
    val isEnter: Boolean get() = isAny(Key.Enter, Key.NumPadEnter, Key.DirectionCenter)
}
