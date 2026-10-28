package com.tim.gallery.data

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import kotlin.math.max
import kotlin.math.roundToInt

object MediaSaver {

    const val FOLDER = "Gallery"
    val FOLDER_LABEL = "${Environment.DIRECTORY_PICTURES}/$FOLDER"

    /** Formats this app can write back over an original. */
    private val OVERWRITABLE = setOf("image/jpeg", "image/png", "image/webp")

    fun canOverwrite(mimeType: String): Boolean = mimeType.lowercase() in OVERWRITABLE

    fun mimeFor(format: Bitmap.CompressFormat): String = when (format) {
        Bitmap.CompressFormat.PNG -> "image/png"
        Bitmap.CompressFormat.WEBP_LOSSY, Bitmap.CompressFormat.WEBP_LOSSLESS -> "image/webp"
        else -> "image/jpeg"
    }

    fun extensionFor(format: Bitmap.CompressFormat): String = when (format) {
        Bitmap.CompressFormat.PNG -> "png"
        Bitmap.CompressFormat.WEBP_LOSSY, Bitmap.CompressFormat.WEBP_LOSSLESS -> "webp"
        else -> "jpg"
    }

    /** Matching encoder for an original's mime type (used when overwriting). */
    fun formatFor(mimeType: String): Bitmap.CompressFormat = when (mimeType.lowercase()) {
        "image/png" -> Bitmap.CompressFormat.PNG
        "image/webp" -> Bitmap.CompressFormat.WEBP_LOSSY
        else -> Bitmap.CompressFormat.JPEG
    }

    /**
     * Saves a bitmap as a new image. Tries [relativePath] first (e.g. the original's album)
     * and falls back to Pictures/Gallery if Android won't put an image there.
     * [exifFrom] copies date, camera and location info from another photo (JPEG only).
     */
    suspend fun saveBitmap(
        context: Context,
        bitmap: Bitmap,
        displayName: String,
        format: Bitmap.CompressFormat = Bitmap.CompressFormat.JPEG,
        relativePath: String = FOLDER_LABEL,
        dateTaken: Long = System.currentTimeMillis(),
        exifFrom: Uri? = null,
    ): Uri? = withContext(Dispatchers.IO) {
        val bytes = try {
            encode(context, bitmap, format, exifFrom)
        } catch (e: Exception) {
            Log.w(TAG, "Encoding failed", e)
            return@withContext null
        }
        val write: (java.io.OutputStream) -> Unit = { it.write(bytes) }
        insert(context, write, displayName, mimeFor(format), relativePath, dateTaken)
            ?: if (relativePath != FOLDER_LABEL) {
                insert(context, write, displayName, mimeFor(format), FOLDER_LABEL, dateTaken)
            } else {
                null
            }
    }

    /**
     * Saves a finished image file (e.g. a GIF this app made) as a new image. Same folder rules
     * as [saveBitmap].
     */
    suspend fun saveFile(
        context: Context,
        file: File,
        displayName: String,
        mime: String,
        relativePath: String = FOLDER_LABEL,
        dateTaken: Long = System.currentTimeMillis(),
    ): Uri? = withContext(Dispatchers.IO) {
        val write: (java.io.OutputStream) -> Unit = { out -> file.inputStream().use { it.copyTo(out) } }
        insert(context, write, displayName, mime, relativePath, dateTaken)
            ?: if (relativePath.trimEnd('/') != FOLDER_LABEL) {
                insert(context, write, displayName, mime, FOLDER_LABEL, dateTaken)
            } else {
                null
            }
    }

    /**
     * Copies a photo or video another app handed us (shared, or "Open with") into the gallery as
     * a new item in [relativePath] (Pictures/Gallery if that album won't take it).
     */
    suspend fun saveFromUri(
        context: Context,
        source: Uri,
        displayName: String,
        mime: String,
        relativePath: String = FOLDER_LABEL,
    ): Uri? = withContext(Dispatchers.IO) {
        val video = mime.startsWith("video/")
        val collection = if (video) {
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        }
        val write: (java.io.OutputStream) -> Unit = { out ->
            context.contentResolver.openInputStream(source)?.use { it.copyTo(out) } ?: throw IOException("Can't read the shared file")
        }
        insert(context, write, displayName, mime, relativePath, System.currentTimeMillis(), collection)
            ?: if (relativePath.trimEnd('/') != FOLDER_LABEL) {
                insert(context, write, displayName, mime, FOLDER_LABEL, System.currentTimeMillis(), collection)
            } else {
                null
            }
    }

    /** Replaces an existing image with a finished file. The caller must hold write access. */
    suspend fun overwriteWithFile(context: Context, uri: Uri, file: File): Boolean = withContext(Dispatchers.IO) {
        try {
            val stream = context.contentResolver.openOutputStream(uri, "wt") ?: throw IOException("No output stream")
            stream.use { out -> file.inputStream().use { it.copyTo(out) } }
            true
        } catch (e: Exception) {
            Log.w(TAG, "Overwrite failed", e)
            false
        }
    }

    private fun insert(
        context: Context,
        write: (java.io.OutputStream) -> Unit,
        displayName: String,
        mime: String,
        relativePath: String,
        dateTaken: Long,
        collection: Uri = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
    ): Uri? {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Images.Media.MIME_TYPE, mime)
            put(MediaStore.Images.Media.RELATIVE_PATH, relativePath)
            put(MediaStore.Images.Media.DATE_TAKEN, dateTaken)
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = try {
            resolver.insert(collection, values)
        } catch (e: Exception) {
            Log.w(TAG, "Can't create an image in $relativePath", e)
            null
        } ?: return null
        return try {
            val stream = resolver.openOutputStream(uri) ?: throw IOException("No output stream")
            stream.use { write(it) }
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            uri
        } catch (e: Exception) {
            Log.w(TAG, "Writing $displayName failed", e)
            resolver.delete(uri, null, null)
            null
        }
    }

    /**
     * Replaces an existing image's contents. The caller must already hold write access
     * (MediaStore.createWriteRequest). Keeps the original's date/camera/location info.
     */
    suspend fun overwrite(
        context: Context,
        uri: Uri,
        bitmap: Bitmap,
        format: Bitmap.CompressFormat,
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            // Encode first: it reads EXIF from the original before we replace it
            val bytes = encode(context, bitmap, format, exifFrom = uri)
            val stream = context.contentResolver.openOutputStream(uri, "wt")
                ?: throw IOException("No output stream")
            stream.use { it.write(bytes) }
            true
        } catch (e: Exception) {
            Log.w(TAG, "Overwrite failed", e)
            false
        }
    }

    private fun encode(
        context: Context,
        bitmap: Bitmap,
        format: Bitmap.CompressFormat,
        exifFrom: Uri?,
    ): ByteArray {
        val raw = ByteArrayOutputStream().also { out ->
            if (!bitmap.compress(format, if (format == Bitmap.CompressFormat.PNG) 100 else 95, out)) {
                throw IOException("Compress failed")
            }
        }.toByteArray()
        if (exifFrom == null || format != Bitmap.CompressFormat.JPEG) return raw
        return try {
            copyExif(context, exifFrom, raw)
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't copy photo info", e)
            raw
        }
    }

    private val EXIF_TAGS = listOf(
        ExifInterface.TAG_DATETIME,
        ExifInterface.TAG_DATETIME_ORIGINAL,
        ExifInterface.TAG_DATETIME_DIGITIZED,
        ExifInterface.TAG_OFFSET_TIME,
        ExifInterface.TAG_OFFSET_TIME_ORIGINAL,
        ExifInterface.TAG_OFFSET_TIME_DIGITIZED,
        ExifInterface.TAG_SUBSEC_TIME,
        ExifInterface.TAG_SUBSEC_TIME_ORIGINAL,
        ExifInterface.TAG_MAKE,
        ExifInterface.TAG_MODEL,
        ExifInterface.TAG_GPS_LATITUDE,
        ExifInterface.TAG_GPS_LATITUDE_REF,
        ExifInterface.TAG_GPS_LONGITUDE,
        ExifInterface.TAG_GPS_LONGITUDE_REF,
        ExifInterface.TAG_GPS_ALTITUDE,
        ExifInterface.TAG_GPS_ALTITUDE_REF,
        ExifInterface.TAG_GPS_TIMESTAMP,
        ExifInterface.TAG_GPS_DATESTAMP,
    )

    private fun openOriginal(context: Context, uri: Uri): InputStream? = try {
        // Unredacted file, so location info survives (needs ACCESS_MEDIA_LOCATION)
        context.contentResolver.openInputStream(MediaStore.setRequireOriginal(uri))
    } catch (e: Exception) {
        context.contentResolver.openInputStream(uri)
    }

    private fun copyExif(context: Context, from: Uri, jpeg: ByteArray): ByteArray {
        val source = openOriginal(context, from)?.use { ExifInterface(it) } ?: return jpeg
        val temp = File.createTempFile("edit", ".jpg", context.cacheDir)
        try {
            temp.writeBytes(jpeg)
            val target = ExifInterface(temp.absolutePath)
            for (tag in EXIF_TAGS) {
                source.getAttribute(tag)?.let { target.setAttribute(tag, it) }
            }
            // Pixels are already upright
            target.setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL.toString())
            target.saveAttributes()
            return temp.readBytes()
        } finally {
            temp.delete()
        }
    }

    /**
     * Decodes an image upright (EXIF rotation applied), capped at [maxPx] on the longest side and
     * [maxPixels] in all (a long panorama keeps its full height).
     */
    fun decodeUpright(context: Context, uri: Uri, maxPx: Int = 4096, maxPixels: Long = Long.MAX_VALUE): Bitmap {
        val source = ImageDecoder.createSource(context.contentResolver, uri)
        return ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val w = info.size.width
            val h = info.size.height
            val s = ImageLimits.fitScale(w, h, maxPx, maxPixels)
            if (s < 1f) {
                decoder.setTargetSize(
                    (w * s).toInt().coerceAtLeast(1),
                    (h * s).toInt().coerceAtLeast(1),
                )
            }
        }
    }

    /** A photo for editing: as big as can be edited and saved here (see [ImageLimits]). */
    fun decodeForEditing(context: Context, uri: Uri): Bitmap =
        decodeUpright(context, uri, ImageLimits.maxSide, ImageLimits.MAX_PIXELS)

    /** Grabs the exact frame at [positionMs] from a video and saves it as a photo. */
    suspend fun saveVideoFrame(
        context: Context,
        videoUri: Uri,
        positionMs: Long,
        baseName: String,
    ): Uri? = withContext(Dispatchers.IO) {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, videoUri)
            val frame = retriever.getFrameAtTime(
                positionMs * 1000L,
                MediaMetadataRetriever.OPTION_CLOSEST,
            ) ?: return@withContext null
            val stem = baseName.substringBeforeLast('.').ifBlank { "video" }
            saveBitmap(context, frame, "${stem}_still_${positionMs}ms.jpg")
        } catch (e: Exception) {
            null
        } finally {
            try {
                retriever.release()
            } catch (_: Exception) {
            }
        }
    }

    private const val TAG = "MediaSaver"
}
