package com.tim.gallery.video

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import com.tim.gallery.data.GalleryItem
import com.tim.gallery.data.MediaSaver
import com.tim.gallery.data.Metadata
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import kotlin.coroutines.coroutineContext
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/** Video basics needed by the tools. */
data class VideoInfo(
    val durationMs: Long,
    val width: Int,
    val height: Int,
    val rotation: Int,
    val frameCount: Long,
) {
    /** Size as shown (after rotation). */
    val shownWidth get() = if (rotation % 180 != 0) height else width
    val shownHeight get() = if (rotation % 180 != 0) width else height
    val fps: Float get() = if (frameCount > 0 && durationMs > 0) frameCount * 1000f / durationMs else 30f
}

/**
 * Trim, mute, GIF and frame export. Trimming copies the compressed video as-is (no quality loss,
 * very fast); the cut starts at the nearest keyframe at or before the start you picked.
 */
object VideoTools {
    private const val TAG = "VideoTools"

    fun info(context: Context, uri: Uri): VideoInfo? = runCatching {
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(context, uri)
            VideoInfo(
                durationMs = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L,
                width = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0,
                height = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0,
                rotation = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0,
                frameCount = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT)?.toLongOrNull() ?: 0L,
            )
        } finally {
            runCatching { r.release() }
        }
    }.getOrNull()

    /** Small preview frames for the trim filmstrip. */
    suspend fun filmstrip(context: Context, uri: Uri, count: Int, heightPx: Int): List<Bitmap?> = withContext(Dispatchers.IO) {
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(context, uri)
            val dur = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            (0 until count).map { i ->
                coroutineContext.ensureActive()
                val t = dur * (i + 0.5) / count
                runCatching {
                    r.getScaledFrameAtTime((t * 1000).toLong(), MediaMetadataRetriever.OPTION_CLOSEST_SYNC, heightPx * 2, heightPx)
                }.getOrNull()
            }
        } catch (e: Exception) {
            emptyList()
        } finally {
            runCatching { r.release() }
        }
    }

    // ------------------------------------------------------------ trim

    /**
     * Saves [startMs]..[endMs] as a new video next to the original. Returns the new file's uri,
     * or throws with a readable message.
     */
    suspend fun trim(
        context: Context,
        item: GalleryItem,
        startMs: Long,
        endMs: Long,
        mute: Boolean,
        onProgress: (Float) -> Unit,
    ): Uri = withContext(Dispatchers.IO) {
        val extractor = MediaExtractor()
        val tmp = File(context.cacheDir, "trim_${System.currentTimeMillis()}.tmp")
        var muxer: MediaMuxer? = null
        try {
            extractor.setDataSource(context, item.uri, null)
            var videoMime: String? = null
            var videoTrack = -1
            val tracks = mutableListOf<Int>()
            for (i in 0 until extractor.trackCount) {
                val mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("video/") && videoMime == null) {
                    videoMime = mime
                    videoTrack = i
                    tracks += i
                } else if (mime.startsWith("audio/") && !mute && tracks.none { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }) {
                    tracks += i
                }
            }
            if (videoMime == null) throw IOException("This file has no video track")
            val webm = videoMime == MediaFormat.MIMETYPE_VIDEO_VP8 || videoMime == MediaFormat.MIMETYPE_VIDEO_VP9
            val outFormat = if (webm) MediaMuxer.OutputFormat.MUXER_OUTPUT_WEBM else MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
            val m = MediaMuxer(tmp.absolutePath, outFormat)
            muxer = m
            val map = HashMap<Int, Int>()
            var maxInput = 1 shl 20
            for (t in tracks) {
                val f = extractor.getTrackFormat(t)
                try {
                    map[t] = m.addTrack(f)
                } catch (e: Exception) {
                    // e.g. an audio format MP4 can't hold: keep the video, drop that track
                    if (f.getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true) {
                        throw IOException("This video's format can't be trimmed")
                    }
                    continue
                }
                if (f.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) maxInput = max(maxInput, f.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE))
            }
            val info = info(context, item.uri)
            if (info != null && info.rotation != 0) m.setOrientationHint(info.rotation)
            Metadata.parseIso6709(
                runCatching {
                    MediaMetadataRetriever().let { r ->
                        try {
                            runCatching { r.setDataSource(context, MediaStore.setRequireOriginal(item.uri)) }
                                .onFailure { r.setDataSource(context, item.uri) }
                            r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_LOCATION)
                        } finally {
                            r.release()
                        }
                    }
                }.getOrNull()
            )?.let { (lat, lon) -> runCatching { m.setLocation(lat.toFloat(), lon.toFloat()) } }
            m.start()

            val startUs = startMs * 1000
            val endUs = endMs * 1000
            // Find the keyframe at or before the start using the video alone, then line the
            // sound up with that same moment (so the clip doesn't begin silent)
            extractor.selectTrack(videoTrack)
            extractor.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            val keyUs = extractor.sampleTime.takeIf { it >= 0 } ?: startUs
            map.keys.filter { it != videoTrack }.forEach { extractor.selectTrack(it) }
            extractor.seekTo(keyUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            // Where the cut really starts (the keyframe at or before the chosen start)
            var firstUs = -1L
            val buffer = ByteBuffer.allocate(max(maxInput, 4 shl 20))
            val bufInfo = MediaCodec.BufferInfo()
            while (true) {
                coroutineContext.ensureActive()
                val track = extractor.sampleTrackIndex
                if (track < 0) break
                val time = extractor.sampleTime
                if (time > endUs) {
                    if (track == videoTrack) break
                    if (!extractor.advance()) break
                    continue
                }
                // The clip starts at the first video keyframe; earlier audio is skipped
                if (firstUs < 0 && track == videoTrack) firstUs = time
                val outTrack = map[track]
                if (outTrack != null && firstUs >= 0) {
                    if (time >= firstUs) {
                        val size = extractor.readSampleData(buffer, 0)
                        if (size >= 0) {
                            bufInfo.offset = 0
                            bufInfo.size = size
                            bufInfo.presentationTimeUs = time - firstUs
                            bufInfo.flags = if (extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                            m.writeSampleData(outTrack, buffer, bufInfo)
                        }
                    }
                }
                if (endUs > startUs) onProgress(((time - startUs).toFloat() / (endUs - startUs)).coerceIn(0f, 1f))
                if (!extractor.advance()) break
            }
            m.stop()
            m.release()
            muxer = null

            val ext = if (webm) "webm" else "mp4"
            val mime = if (webm) "video/webm" else "video/mp4"
            val stem = item.name.substringBeforeLast('.').ifBlank { "video" }
            saveVideoFile(
                context = context,
                file = tmp,
                displayName = "${stem}_clip_${System.currentTimeMillis() % 100000}.$ext",
                mime = mime,
                relativePath = item.relativePath,
                dateTaken = item.dateMillis + max(0L, (if (firstUs >= 0) firstUs else startUs) / 1000),
            ) ?: throw IOException("Couldn't save the clip")
        } finally {
            runCatching { muxer?.release() }
            runCatching { extractor.release() }
            tmp.delete()
        }
    }

    /** Copies a finished video file into the gallery. */
    private fun saveVideoFile(
        context: Context,
        file: File,
        displayName: String,
        mime: String,
        relativePath: String,
        dateTaken: Long,
    ): Uri? {
        val resolver = context.contentResolver
        val collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        for (path in listOf(relativePath, MediaSaver.FOLDER_LABEL + "/").distinct()) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, path)
                put(MediaStore.MediaColumns.DATE_TAKEN, dateTaken)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = runCatching { resolver.insert(collection, values) }.getOrNull() ?: continue
            try {
                resolver.openOutputStream(uri, "w")?.use { out -> file.inputStream().use { it.copyTo(out) } }
                    ?: throw IOException("No output")
                values.clear()
                values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
                return uri
            } catch (e: Exception) {
                Log.w(TAG, "Saving to $path failed", e)
                runCatching { resolver.delete(uri, null, null) }
            }
        }
        return null
    }

    // ------------------------------------------------------------ frames

    /**
     * Calls [onFrame] with frames from [startMs]..[endMs], [stepFrames] apart (1 = every frame),
     * scaled so the longest side is at most [maxPx] (0 = full size). Decodes in order, which is
     * far faster than seeking for each frame. Returns how many frames were delivered.
     */
    suspend fun frames(
        context: Context,
        uri: Uri,
        startMs: Long,
        endMs: Long,
        stepFrames: Int,
        maxPx: Int,
        onFrame: suspend (index: Int, timeMs: Long, frame: Bitmap) -> Unit,
    ): Int = withContext(Dispatchers.IO) {
        val info = info(context, uri) ?: throw IOException("Can't read this video")
        val r = MediaMetadataRetriever()
        var delivered = 0
        try {
            r.setDataSource(context, uri)
            val fps = info.fps
            val step = max(1, stepFrames)
            fun scaled(b: Bitmap): Bitmap {
                if (maxPx <= 0) return b
                val longest = max(b.width, b.height)
                if (longest <= maxPx) return b
                val k = maxPx.toFloat() / longest
                val s = Bitmap.createScaledBitmap(b, (b.width * k).roundToInt().coerceAtLeast(1), (b.height * k).roundToInt().coerceAtLeast(1), true)
                if (s !== b) b.recycle()
                return s
            }
            if (info.frameCount > 0) {
                val first = (startMs / 1000f * fps).roundToInt().coerceIn(0, (info.frameCount - 1).toInt())
                val last = (endMs / 1000f * fps).roundToInt().coerceIn(first, (info.frameCount - 1).toInt())
                // Decode in small batches so 4K frames don't run the phone out of memory
                val perFrameBytes = max(1L, info.width.toLong() * info.height * 4)
                val batch = (64L * 1024 * 1024 / perFrameBytes).toInt().coerceIn(1, 8)
                if (step >= 8) {
                    // Far-apart frames: fetch just the ones needed
                    var i = first
                    while (i <= last) {
                        coroutineContext.ensureActive()
                        val f = runCatching { r.getFramesAtIndex(i, 1) }.getOrNull()?.firstOrNull() ?: break
                        onFrame(delivered, (i * 1000f / fps).roundToLong(), scaled(f))
                        delivered++
                        i += step
                    }
                    if (delivered > 0) return@withContext delivered
                }
                var idx = first
                while (idx <= last) {
                    coroutineContext.ensureActive()
                    val n = min(batch, last - idx + 1)
                    val list = runCatching { r.getFramesAtIndex(idx, n) }.getOrNull()
                    if (list.isNullOrEmpty()) break
                    for ((j, f) in list.withIndex()) {
                        val frameIndex = idx + j
                        if ((frameIndex - first) % step == 0) {
                            onFrame(delivered, (frameIndex * 1000f / fps).roundToLong(), scaled(f))
                            delivered++
                        } else {
                            f.recycle()
                        }
                    }
                    idx += n
                }
                if (delivered > 0) return@withContext delivered
            }
            // Fallback (files that don't report a frame count): seek to each time
            val stepMs = max(1L, (step * 1000f / fps).roundToLong())
            var t = startMs
            while (t <= endMs) {
                coroutineContext.ensureActive()
                val f = runCatching {
                    r.getFrameAtTime(t * 1000, MediaMetadataRetriever.OPTION_CLOSEST)
                }.getOrNull()
                if (f != null) {
                    onFrame(delivered, t, scaled(f))
                    delivered++
                }
                t += stepMs
            }
            delivered
        } finally {
            runCatching { r.release() }
        }
    }

    /** Saves [startMs]..[endMs] as an animated GIF. [widthPx] is the GIF's width. */
    suspend fun saveGif(
        context: Context,
        item: GalleryItem,
        startMs: Long,
        endMs: Long,
        widthPx: Int,
        fps: Int,
        onProgress: (Float) -> Unit,
    ): Uri = withContext(Dispatchers.IO) {
        val info = info(context, item.uri) ?: throw IOException("Can't read this video")
        // Some files don't report their size; fall back to the gallery's numbers, then 16:9
        val srcW = info.shownWidth.takeIf { it > 0 } ?: item.width.takeIf { it > 0 } ?: 1920
        val srcH = info.shownHeight.takeIf { it > 0 } ?: item.height.takeIf { it > 0 } ?: 1080
        val w = min(widthPx, max(16, srcW))
        val h = max(2, (w.toFloat() * srcH / max(1, srcW)).roundToInt()) and 1.inv()
        val sourceStep = max(1, (info.fps / fps).roundToInt())
        val delayMs = (sourceStep * 1000f / info.fps).roundToInt()
        val expected = max(1, ((endMs - startMs) / 1000f * info.fps / sourceStep).roundToInt())
        val tmp = File(context.cacheDir, "gif_${System.currentTimeMillis()}.gif")
        try {
            tmp.outputStream().buffered().use { out ->
                val gif = GifEncoder(out, w, h, delayMs)
                frames(context, item.uri, startMs, endMs, sourceStep, 0) { i, _, frame ->
                    val fitted = Bitmap.createScaledBitmap(frame, w, h, true)
                    gif.addFrame(fitted)
                    if (fitted !== frame) fitted.recycle()
                    frame.recycle()
                    onProgress(((i + 1).toFloat() / expected).coerceIn(0f, 1f))
                }
                gif.finish()
            }
            val stem = item.name.substringBeforeLast('.').ifBlank { "video" }
            saveImageFile(context, tmp, "${stem}_${System.currentTimeMillis() % 100000}.gif", "image/gif", item.dateMillis + startMs)
                ?: throw IOException("Couldn't save the GIF")
        } finally {
            tmp.delete()
        }
    }

    private fun saveImageFile(context: Context, file: File, displayName: String, mime: String, dateTaken: Long): Uri? {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, MediaSaver.FOLDER_LABEL + "/")
            put(MediaStore.MediaColumns.DATE_TAKEN, dateTaken)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = runCatching {
            resolver.insert(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values)
        }.getOrNull() ?: return null
        return try {
            resolver.openOutputStream(uri, "w")?.use { out -> file.inputStream().use { it.copyTo(out) } } ?: throw IOException()
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            uri
        } catch (e: Exception) {
            runCatching { resolver.delete(uri, null, null) }
            null
        }
    }

    /** Saves frames as JPEGs into a new album named after the video. Returns how many were saved. */
    suspend fun saveFrames(
        context: Context,
        item: GalleryItem,
        startMs: Long,
        endMs: Long,
        stepFrames: Int,
        expected: Int,
        onProgress: (Float) -> Unit,
    ): Int {
        val stem = item.name.substringBeforeLast('.').ifBlank { "video" }
        val folder = "Pictures/${stem.take(60)} frames/"
        var saved = 0
        frames(context, item.uri, startMs, endMs, stepFrames, 0) { i, timeMs, frame ->
            val uri = MediaSaver.saveBitmap(
                context = context,
                bitmap = frame,
                displayName = "${stem}_${String.format(java.util.Locale.US, "%06d", timeMs)}ms.jpg",
                format = Bitmap.CompressFormat.JPEG,
                relativePath = folder,
                dateTaken = item.dateMillis + timeMs,
            )
            frame.recycle()
            if (uri != null) saved++
            onProgress(((i + 1).toFloat() / max(1, expected)).coerceIn(0f, 1f))
        }
        return saved
    }
}
