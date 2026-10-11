package com.tim.gallery.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.util.Base64
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

fun formatDuration(ms: Long): String {
    val totalSec = (ms / 1000).coerceAtLeast(0)
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s)
    else String.format(Locale.US, "%d:%02d", m, s)
}

/** Like [formatDuration] but with tenths of a second, handy when lining up a still frame. */
fun formatPrecise(ms: Long): String {
    val tenths = (ms.coerceAtLeast(0) % 1000) / 100
    return "${formatDuration(ms)}.$tenths"
}

private val dateFormatter = DateTimeFormatter.ofPattern("EEE, MMM d, yyyy · h:mm a", Locale.getDefault())

fun formatDate(ms: Long): String =
    Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).format(dateFormatter)

/** Album keys are folder paths with slashes, so they're Base64-encoded for navigation routes. */
fun encodeKey(key: String): String =
    Base64.encodeToString(key.toByteArray(Charsets.UTF_8), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)

fun decodeKey(encoded: String): String =
    String(Base64.decode(encoded, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING), Charsets.UTF_8)

fun Context.findActivity(): Activity? {
    var c: Context = this
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}

fun shareMedia(context: Context, uri: Uri, mimeType: String) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = mimeType
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(send, null))
}

/** Hides the status and navigation bars while this composable is on screen. */
@Composable
fun ImmersiveMode() {
    val view = LocalView.current
    DisposableEffect(view) {
        val window = view.context.findActivity()?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }
        controller?.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller?.hide(WindowInsetsCompat.Type.systemBars())
        onDispose { controller?.show(WindowInsetsCompat.Type.systemBars()) }
    }
}

/**
 * Keeps the screen upright while this composable is shown, on phones only (including a
 * foldable's outer screen). Tablets and unfolded foldables can still turn.
 */
@Composable
fun PortraitOnPhones() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val isPhone = androidx.compose.ui.platform.LocalConfiguration.current.smallestScreenWidthDp < 600
    DisposableEffect(isPhone) {
        val activity = context.findActivity()
        if (isPhone && activity != null) PortraitLock.acquire(activity)
        onDispose {
            if (isPhone && activity != null) PortraitLock.release(activity)
        }
    }
}

/**
 * Counts the screens asking for portrait, so overlapping ones (an editor and its dialog, or two
 * screens during a transition) can't leave the app stuck upright or unlock too early.
 */
private object PortraitLock {
    private class Hold(val before: Int) {
        var count = 0
    }

    private val holds = java.util.WeakHashMap<android.app.Activity, Hold>()

    fun acquire(activity: android.app.Activity) {
        val hold = holds.getOrPut(activity) { Hold(activity.requestedOrientation) }
        hold.count++
        if (hold.count == 1) activity.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT
    }

    fun release(activity: android.app.Activity) {
        val hold = holds[activity] ?: return
        hold.count--
        if (hold.count <= 0) {
            holds.remove(activity)
            activity.requestedOrientation = hold.before
        }
    }
}
