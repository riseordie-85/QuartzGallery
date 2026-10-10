package com.tim.gallery.ui

import android.content.Context
import android.content.pm.ActivityInfo
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.util.LruCache
import androidx.annotation.OptIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.tim.gallery.data.GalleryItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer

/**
 * Motion Photos: a photo with a short video tucked inside the same file (Pixel "Motion",
 * Samsung "Motion photo"). This finds the video and plays it.
 */
object MotionPhotos {
    private val offsets = LruCache<String, Long>(200)
    private const val NONE = -1L

    private fun key(item: GalleryItem) = "${item.id}#${item.modified}"

    /** Where the video starts inside the file, or null if this isn't a motion photo. */
    suspend fun videoOffset(context: Context, item: GalleryItem): Long? {
        if (item.isVideo || !item.mimeType.lowercase().let { it.contains("jpeg") || it.contains("jpg") || it.contains("heic") || it.contains("heif") }) return null
        offsets.get(key(item))?.let { return it.takeIf { o -> o != NONE } }
        val found = withContext(Dispatchers.IO) { runCatching { find(context, item) }.getOrNull() }
        offsets.put(key(item), found ?: NONE)
        return found
    }

    private fun find(context: Context, item: GalleryItem): Long? {
        context.contentResolver.openFileDescriptor(item.uri, "r")?.use { pfd ->
            val size = pfd.statSize
            if (size <= 0) return null
            FileInputStream(pfd.fileDescriptor).channel.use { ch ->
                fun readAt(pos: Long, len: Int): ByteArray {
                    val buf = ByteBuffer.allocate(len)
                    var p = pos
                    while (buf.hasRemaining()) {
                        val n = ch.read(buf, p)
                        if (n <= 0) break
                        p += n
                    }
                    return buf.array().copyOf(buf.position())
                }
                fun looksLikeVideo(offset: Long): Boolean {
                    if (offset < 0 || offset + 8 > size) return false
                    val b = readAt(offset, 8)
                    return b.size == 8 && String(b, 4, 4, Charsets.ISO_8859_1) == "ftyp"
                }
                // XMP lives near the start of the file
                val head = readAt(0, minOf(size, 256L * 1024).toInt())
                val text = String(head, Charsets.ISO_8859_1)
                // Newer Google format: a "MotionPhoto" item in the XMP container, stored at the end
                val itemRegex = Regex("""Item:Semantic="MotionPhoto"[^>]*?Item:Length="(\d+)"|Item:Length="(\d+)"[^>]*?Item:Semantic="MotionPhoto"""")
                itemRegex.find(text)?.let { m ->
                    val len = (m.groupValues[1].ifEmpty { m.groupValues[2] }).toLongOrNull()
                    if (len != null && len in 1 until size && looksLikeVideo(size - len)) return size - len
                }
                // Older Google format: offset from the end of the file
                Regex("""MicroVideoOffset="(\d+)"""").find(text)?.groupValues?.get(1)?.toLongOrNull()?.let { off ->
                    if (off in 1 until size && looksLikeVideo(size - off)) return size - off
                }
                // Samsung: video follows a "MotionPhoto_Data" marker near the end. Only look on
                // Samsung photos (reading the end of every file would be slow)
                val mentionsMotion = text.contains("MotionPhoto") || text.contains("MicroVideo")
                if (!mentionsMotion && !text.contains("samsung", ignoreCase = true)) return null
                val tailLen = minOf(size, 16L * 1024 * 1024).toInt()
                val tailStart = size - tailLen
                val tail = readAt(tailStart, tailLen)
                val marker = "MotionPhoto_Data".toByteArray()
                val idx = indexOf(tail, marker)
                if (idx >= 0) {
                    val start = tailStart + idx + marker.size
                    if (looksLikeVideo(start)) return start
                }
                // Last resort: an MP4 header appended after the image data
                if (mentionsMotion) {
                    val ftyp = indexOf(tail, "ftyp".toByteArray())
                    if (ftyp >= 4 && looksLikeVideo(tailStart + ftyp - 4)) return tailStart + ftyp - 4
                }
            }
        }
        return null
    }

    private fun indexOf(hay: ByteArray, needle: ByteArray): Int {
        outer@ for (i in 0..hay.size - needle.size) {
            for (j in needle.indices) if (hay[i + j] != needle[j]) continue@outer
            return i
        }
        return -1
    }

    /** Copies the embedded video into the app's cache so the player can open it. */
    suspend fun extract(context: Context, item: GalleryItem, offset: Long): File? = withContext(Dispatchers.IO) {
        val out = File(context.cacheDir, "motion_${item.id}_${item.modified}.mp4")
        if (out.exists() && out.length() > 0) return@withContext out
        runCatching {
            context.contentResolver.openInputStream(item.uri)?.use { input ->
                var skip = offset
                while (skip > 0) {
                    val s = input.skip(skip)
                    if (s <= 0) break
                    skip -= s
                }
                out.outputStream().use { input.copyTo(it) }
            }
            // Keep the cache small: only the most recent few
            context.cacheDir.listFiles { f -> f.name.startsWith("motion_") && f != out }
                ?.sortedByDescending { it.lastModified() }?.drop(5)?.forEach { it.delete() }
            out
        }.getOrNull()
    }
}

/** Plays a motion photo's video once over the photo, muted. */
@OptIn(UnstableApi::class)
@Composable
fun MotionPlayer(file: File, onEnded: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val player = remember(file) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
            volume = 0f
            prepare()
            playWhenReady = true
        }
    }
    DisposableEffect(player) {
        val l = object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) onEnded()
            }
        }
        player.addListener(l)
        onDispose {
            player.removeListener(l)
            player.release()
        }
    }
    AndroidView(
        factory = { ctx ->
            PlayerView(ctx).apply {
                useController = false
                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                setShutterBackgroundColor(android.graphics.Color.TRANSPARENT)
                this.player = player
            }
        },
        modifier = modifier,
    )
}

/** Ultra HDR photos: detects the HDR "gain map" and switches the screen into HDR mode to show it. */
object UltraHdr {
    private val cache = LruCache<String, Boolean>(300)

    suspend fun isUltraHdr(context: Context, item: GalleryItem): Boolean {
        if (item.isVideo || Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return false
        val key = "${item.id}#${item.modified}"
        cache.get(key)?.let { return it }
        val result = withContext(Dispatchers.IO) {
            runCatching {
                context.contentResolver.openInputStream(item.uri)?.use { s ->
                    val opts = BitmapFactory.Options().apply { inSampleSize = 16 }
                    BitmapFactory.decodeStream(s, null, opts)?.let { b ->
                        val has = b.hasGainmap()
                        b.recycle()
                        has
                    }
                } ?: false
            }.getOrDefault(false)
        }
        cache.put(key, result)
        return result
    }
}

/** Puts the window in HDR mode while [enabled] (Android 14+ screens that support it). */
@Composable
fun HdrWindowMode(enabled: Boolean) {
    val context = LocalContext.current
    DisposableEffect(enabled) {
        val window = context.findActivity()?.window
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            window?.colorMode = if (enabled) ActivityInfo.COLOR_MODE_HDR else ActivityInfo.COLOR_MODE_DEFAULT
        }
        onDispose {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                window?.colorMode = ActivityInfo.COLOR_MODE_DEFAULT
            }
        }
    }
}
