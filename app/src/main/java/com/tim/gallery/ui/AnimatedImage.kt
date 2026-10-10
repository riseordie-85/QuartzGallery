package com.tim.gallery.ui

import android.graphics.ImageDecoder
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import android.widget.ImageView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.roundToInt

/** GIFs and animated WebP may move; other formats are always still. */
fun mayBeAnimated(mimeType: String?, name: String?): Boolean {
    val m = mimeType?.lowercase().orEmpty()
    val n = name?.lowercase().orEmpty()
    return m == "image/gif" || m == "image/webp" || n.endsWith(".gif") || n.endsWith(".webp")
}

/**
 * Loads an animated GIF / WebP as a playable drawable (Android's own decoder, on the phone).
 * Null while loading, or when the image doesn't actually move.
 */
@Composable
fun rememberAnimatedImage(uri: Uri, mimeType: String?, name: String?, version: Long = 0L): AnimatedImageDrawable? {
    val context = LocalContext.current
    val drawable by produceState<AnimatedImageDrawable?>(null, uri, version) {
        if (!mayBeAnimated(mimeType, name)) return@produceState
        value = withContext(Dispatchers.IO) {
            runCatching {
                val source = ImageDecoder.createSource(context.contentResolver, uri)
                ImageDecoder.decodeDrawable(source) { decoder, info, _ ->
                    // Keep huge GIFs reasonable in memory
                    val longest = max(info.size.width, info.size.height)
                    if (longest > 2048) {
                        val k = 2048f / longest
                        decoder.setTargetSize((info.size.width * k).roundToInt(), (info.size.height * k).roundToInt())
                    }
                } as? AnimatedImageDrawable
            }.getOrNull()?.also { it.repeatCount = AnimatedImageDrawable.REPEAT_INFINITE }
        }
    }
    return drawable
}

/** Shows an animated drawable, playing while [playing] is true. */
@Composable
fun AnimatedDrawableView(drawable: Drawable, playing: Boolean, modifier: Modifier = Modifier) {
    AndroidView(
        factory = { ctx ->
            ImageView(ctx).apply {
                scaleType = ImageView.ScaleType.FIT_CENTER
                adjustViewBounds = false
            }
        },
        update = { view ->
            if (view.drawable !== drawable) view.setImageDrawable(drawable)
            val anim = drawable as? AnimatedImageDrawable
            if (playing) anim?.start() else anim?.stop()
        },
        modifier = modifier,
    )
    DisposableEffect(drawable) {
        onDispose { (drawable as? AnimatedImageDrawable)?.stop() }
    }
}
