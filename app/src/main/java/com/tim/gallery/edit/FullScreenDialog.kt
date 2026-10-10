package com.tim.gallery.edit

import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat

/**
 * A dialog that really fills the screen, edge to edge, with its content kept clear of the status
 * bar, navigation bar and keyboard.
 *
 * A plain full-width Compose dialog is placed below the status bar but sized to the whole screen,
 * so its bottom slid off the screen (cutting off the font and style rows). This tells the window
 * not to inset itself, so the system bar padding is applied once, inside, where it belongs.
 */
@Composable
fun FullScreenDialog(
    onDismissRequest: () -> Unit,
    background: Color = Color(0xFF121212),
    content: @Composable () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        val view = LocalView.current
        val window = (view.parent as? DialogWindowProvider)?.window
        SideEffect {
            if (window != null) {
                window.attributes = window.attributes.apply {
                    fitInsetsTypes = 0
                    fitInsetsSides = 0
                    layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                }
                window.isNavigationBarContrastEnforced = false
                WindowCompat.getInsetsController(window, view).apply {
                    isAppearanceLightStatusBars = false
                    isAppearanceLightNavigationBars = false
                }
            }
        }
        Box(
            Modifier
                .fillMaxSize()
                .background(background)
                .safeDrawingPadding()
                .imePadding()
        ) {
            content()
        }
    }
}
