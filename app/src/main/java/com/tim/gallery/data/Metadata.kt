package com.tim.gallery.data

import android.content.Context
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.util.Locale
import kotlin.math.roundToInt

/** Camera and file details for the info panel. Everything is read on the phone. */
data class MediaDetails(
    val camera: String? = null,
    val lens: String? = null,
    val iso: String? = null,
    val shutter: String? = null,
    val aperture: String? = null,
    val focalLength: String? = null,
    val flash: String? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val altitude: String? = null,
    val frameRate: String? = null,
    val bitrate: String? = null,
    val hdr: String? = null,
    val codec: String? = null,
)

object Metadata {

    /** Opens the original file (with location info, when the app may see it). */
    fun openOriginal(context: Context, uri: Uri): InputStream? =
        try {
            context.contentResolver.openInputStream(MediaStore.setRequireOriginal(uri))
        } catch (e: Exception) {
            null
        } ?: try {
            context.contentResolver.openInputStream(uri)
        } catch (e: Exception) {
            null
        }

    suspend fun read(context: Context, item: GalleryItem): MediaDetails = withContext(Dispatchers.IO) {
        runCatching { if (item.isVideo) readVideo(context, item.uri) else readPhoto(context, item.uri) }
            .getOrDefault(MediaDetails())
    }

    /** Just the location (used to build the map). */
    fun location(context: Context, item: GalleryItem): Pair<Double, Double>? = runCatching {
        if (item.isVideo) {
            val r = MediaMetadataRetriever()
            try {
                // The original (unredacted) file, so the location is included
                runCatching { r.setDataSource(context, MediaStore.setRequireOriginal(item.uri)) }
                    .onFailure { r.setDataSource(context, item.uri) }
                parseIso6709(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_LOCATION))
            } finally {
                runCatching { r.release() }
            }
        } else {
            openOriginal(context, item.uri)?.use { s ->
                ExifInterface(s).latLong?.let { it[0] to it[1] }
            }
        }
    }.getOrNull()?.takeIf { (lat, lon) -> !(lat == 0.0 && lon == 0.0) && lat in -90.0..90.0 && lon in -180.0..180.0 }

    private fun readPhoto(context: Context, uri: Uri): MediaDetails {
        val exif = openOriginal(context, uri)?.use { ExifInterface(it) } ?: return MediaDetails()
        val make = exif.getAttribute(ExifInterface.TAG_MAKE)?.trim().orEmpty()
        val model = exif.getAttribute(ExifInterface.TAG_MODEL)?.trim().orEmpty()
        val camera = when {
            model.isEmpty() -> make.ifEmpty { null }
            make.isEmpty() || model.startsWith(make, ignoreCase = true) -> model
            else -> "$make $model"
        }
        val exposure = exif.getAttributeDouble(ExifInterface.TAG_EXPOSURE_TIME, 0.0)
        val shutter = when {
            exposure <= 0.0 -> null
            exposure >= 1.0 -> String.format(Locale.US, "%.1f s", exposure)
            else -> "1/${(1.0 / exposure).roundToInt()} s"
        }
        val fNumber = exif.getAttributeDouble(ExifInterface.TAG_F_NUMBER, 0.0)
        val focal = exif.getAttributeDouble(ExifInterface.TAG_FOCAL_LENGTH, 0.0)
        val focal35 = exif.getAttributeInt(ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM, 0)
        val focalText = when {
            focal <= 0.0 -> null
            focal35 > 0 -> String.format(Locale.US, "%.2f mm (%d mm equiv.)", focal, focal35)
            else -> String.format(Locale.US, "%.2f mm", focal)
        }
        val iso = exif.getAttributeInt(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY, 0)
        val flashAttr = exif.getAttribute(ExifInterface.TAG_FLASH)
        val flash = flashAttr?.toIntOrNull()?.let { if (it and 1 == 1) "Fired" else "Off" }
        val latLong = exif.latLong
        val alt = exif.getAltitude(Double.NaN)
        return MediaDetails(
            camera = camera,
            lens = exif.getAttribute(ExifInterface.TAG_LENS_MODEL)?.trim()?.ifEmpty { null },
            iso = if (iso > 0) "ISO $iso" else null,
            shutter = shutter,
            aperture = if (fNumber > 0) String.format(Locale.US, "f/%.1f", fNumber) else null,
            focalLength = focalText,
            flash = flash,
            latitude = latLong?.get(0),
            longitude = latLong?.get(1),
            altitude = if (!alt.isNaN()) "${alt.roundToInt()} m" else null,
        )
    }

    private fun readVideo(context: Context, uri: Uri): MediaDetails {
        val r = MediaMetadataRetriever()
        try {
            runCatching { r.setDataSource(context, MediaStore.setRequireOriginal(uri)) }
                .onFailure { r.setDataSource(context, uri) }
            val durationMs = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            val frames = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT)?.toLongOrNull() ?: 0L
            val capture = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE)?.toFloatOrNull()
            val fps = when {
                frames > 0 && durationMs > 0 -> frames * 1000f / durationMs
                capture != null && capture > 0 -> capture
                else -> 0f
            }
            val bitrate = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toLongOrNull() ?: 0L
            val transfer = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_COLOR_TRANSFER)?.toIntOrNull()
            val hdr = when (transfer) {
                MediaFormat.COLOR_TRANSFER_ST2084 -> "HDR (HDR10 / PQ)"
                MediaFormat.COLOR_TRANSFER_HLG -> "HDR (HLG)"
                else -> null
            }
            val loc = parseIso6709(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_LOCATION))
            return MediaDetails(
                frameRate = if (fps > 0) String.format(Locale.US, "%.2f fps", fps).replace(".00 fps", " fps") else null,
                bitrate = if (bitrate > 0) String.format(Locale.US, "%.1f Mbps", bitrate / 1_000_000.0) else null,
                hdr = hdr,
                codec = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE),
                latitude = loc?.first,
                longitude = loc?.second,
            )
        } finally {
            runCatching { r.release() }
        }
    }

    /** "+39.8680-104.9719/" → (39.868, -104.9719). */
    fun parseIso6709(s: String?): Pair<Double, Double>? {
        if (s.isNullOrBlank()) return null
        val m = Regex("""([+-]\d+(?:\.\d+)?)([+-]\d+(?:\.\d+)?)""").find(s) ?: return null
        val lat = m.groupValues[1].toDoubleOrNull() ?: return null
        val lon = m.groupValues[2].toDoubleOrNull() ?: return null
        return lat to lon
    }
}
