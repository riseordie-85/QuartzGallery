package com.tim.gallery.data

import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.provider.MediaStore.Files.FileColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MediaRepository(private val context: Context) {

    /** Watched for changes so the grid refreshes when photos are taken, deleted or edited. */
    val collection: Uri = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)

    private val projection = arrayOf(
        FileColumns._ID,
        FileColumns.DISPLAY_NAME,
        FileColumns.MIME_TYPE,
        FileColumns.MEDIA_TYPE,
        MediaStore.MediaColumns.DATE_TAKEN,
        FileColumns.DATE_ADDED,
        FileColumns.DATE_MODIFIED,
        MediaStore.MediaColumns.DURATION,
        MediaStore.MediaColumns.WIDTH,
        MediaStore.MediaColumns.HEIGHT,
        MediaStore.MediaColumns.SIZE,
        MediaStore.MediaColumns.BUCKET_DISPLAY_NAME,
        MediaStore.MediaColumns.RELATIVE_PATH,
        MediaStore.MediaColumns.IS_FAVORITE,
        MediaStore.MediaColumns.DATE_EXPIRES,
    )

    /** Every photo and video, newest first (trashed items excluded). */
    suspend fun loadAll(): List<GalleryItem> = query(MediaStore.MATCH_EXCLUDE)

    @Volatile
    private var lastVersion: String? = null

    @Volatile
    private var lastGeneration = -1L

    /** A loaded list plus the change counters it reflects (see [commit]). */
    class Snapshot(val items: List<GalleryItem>, internal val version: String?, internal val generation: Long)

    /** Call once [snapshot]'s items are shown, so the next load only reads newer changes. */
    fun commit(snapshot: Snapshot) {
        lastVersion = snapshot.version
        lastGeneration = snapshot.generation
    }

    /**
     * Like [loadAll], but after the first load only re-reads what changed (MediaStore keeps a
     * change counter per item), which keeps big libraries quick. Falls back to a full load when
     * the phone has several storage volumes or the media index was rebuilt.
     */
    suspend fun loadAllIncremental(previous: List<GalleryItem>): Snapshot = withContext(Dispatchers.IO) {
        val volumes = MediaStore.getExternalVolumeNames(context)
        val version = MediaStore.getVersion(context)
        val canDiff = previous.isNotEmpty() && volumes.size == 1 && version == lastVersion && lastGeneration >= 0
        // Change counters are per storage volume, so diffing only works with exactly one
        val generation = if (volumes.size == 1) {
            runCatching { MediaStore.getGeneration(context, volumes.first()) }.getOrDefault(-1L)
        } else {
            -1L
        }
        val result = if (!canDiff) {
            query(MediaStore.MATCH_EXCLUDE)
        } else {
            val ids = queryIds()
            val changed = query(MediaStore.MATCH_EXCLUDE, "${MediaStore.MediaColumns.GENERATION_MODIFIED} > ?", lastGeneration.toString())
            val changedIds = changed.mapTo(HashSet()) { it.id }
            val known = previous.mapTo(HashSet()) { it.id }
            if (ids.any { it !in known && it !in changedIds }) {
                // Items we can see now that didn't change (e.g. more photos shared with the app): reload everything
                query(MediaStore.MATCH_EXCLUDE)
            } else {
                (previous.filter { it.id in ids && it.id !in changedIds } + changed).sortedByDescending { it.dateMillis }
            }
        }
        Snapshot(result, version, generation)
    }

    /** Just the ids of every current photo and video (cheap; used to notice deletions). */
    private fun queryIds(): HashSet<Long> {
        val args = Bundle().apply {
            putString(ContentResolver.QUERY_ARG_SQL_SELECTION, "${FileColumns.MEDIA_TYPE} IN (?, ?)")
            putStringArray(
                ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS,
                arrayOf(FileColumns.MEDIA_TYPE_IMAGE.toString(), FileColumns.MEDIA_TYPE_VIDEO.toString()),
            )
            putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_EXCLUDE)
        }
        val out = HashSet<Long>()
        context.contentResolver.query(collection, arrayOf(FileColumns._ID), args, null)?.use { c ->
            while (c.moveToNext()) out += c.getLong(0)
        }
        return out
    }

    /** Items in Android's trash, soonest-to-expire first. */
    suspend fun loadTrash(): List<GalleryItem> =
        query(MediaStore.MATCH_ONLY).sortedBy { it.expiresMillis }

    private suspend fun query(
        matchTrashed: Int,
        extraSelection: String? = null,
        extraArg: String? = null,
    ): List<GalleryItem> = withContext(Dispatchers.IO) {
        val args = Bundle().apply {
            val base = "${FileColumns.MEDIA_TYPE} IN (?, ?)"
            putString(ContentResolver.QUERY_ARG_SQL_SELECTION, if (extraSelection != null) "$base AND $extraSelection" else base)
            putStringArray(
                ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS,
                listOfNotNull(
                    FileColumns.MEDIA_TYPE_IMAGE.toString(),
                    FileColumns.MEDIA_TYPE_VIDEO.toString(),
                    extraArg,
                ).toTypedArray(),
            )
            putString(ContentResolver.QUERY_ARG_SQL_SORT_ORDER, "${FileColumns.DATE_ADDED} DESC")
            putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, matchTrashed)
        }
        val out = ArrayList<GalleryItem>()
        context.contentResolver.query(collection, projection, args, null)?.use { c ->
            val cols = Columns(c)
            while (c.moveToNext()) out += cols.read(c)
        }
        out.sortByDescending { it.dateMillis }
        out
    }

    private class Columns(c: Cursor) {
        val id = c.getColumnIndexOrThrow(FileColumns._ID)
        val name = c.getColumnIndexOrThrow(FileColumns.DISPLAY_NAME)
        val mime = c.getColumnIndexOrThrow(FileColumns.MIME_TYPE)
        val type = c.getColumnIndexOrThrow(FileColumns.MEDIA_TYPE)
        val taken = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_TAKEN)
        val added = c.getColumnIndexOrThrow(FileColumns.DATE_ADDED)
        val modified = c.getColumnIndexOrThrow(FileColumns.DATE_MODIFIED)
        val duration = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DURATION)
        val width = c.getColumnIndexOrThrow(MediaStore.MediaColumns.WIDTH)
        val height = c.getColumnIndexOrThrow(MediaStore.MediaColumns.HEIGHT)
        val size = c.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
        val bucket = c.getColumnIndexOrThrow(MediaStore.MediaColumns.BUCKET_DISPLAY_NAME)
        val path = c.getColumnIndexOrThrow(MediaStore.MediaColumns.RELATIVE_PATH)
        val favorite = c.getColumnIndexOrThrow(MediaStore.MediaColumns.IS_FAVORITE)
        val expires = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_EXPIRES)

        fun read(c: Cursor): GalleryItem {
            val itemId = c.getLong(id)
            val isVideo = c.getInt(type) == FileColumns.MEDIA_TYPE_VIDEO
            val base = if (isVideo) {
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            } else {
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            }
            val takenMs = if (c.isNull(taken)) 0L else c.getLong(taken)
            val addedMs = c.getLong(added) * 1000L
            return GalleryItem(
                id = itemId,
                uri = ContentUris.withAppendedId(base, itemId),
                name = c.getString(name) ?: "",
                mimeType = c.getString(mime) ?: if (isVideo) "video/*" else "image/*",
                isVideo = isVideo,
                dateMillis = if (takenMs > 0) takenMs else addedMs,
                durationMs = if (isVideo && !c.isNull(duration)) c.getLong(duration) else 0L,
                width = if (c.isNull(width)) 0 else c.getInt(width),
                height = if (c.isNull(height)) 0 else c.getInt(height),
                sizeBytes = if (c.isNull(size)) 0L else c.getLong(size),
                album = c.getString(bucket) ?: "Other",
                relativePath = c.getString(path)?.takeIf { it.isNotBlank() } ?: "/",
                isFavorite = !c.isNull(favorite) && c.getInt(favorite) == 1,
                modified = if (c.isNull(modified)) 0L else c.getLong(modified),
                expiresMillis = if (c.isNull(expires)) 0L else c.getLong(expires) * 1000L,
                addedMillis = addedMs,
            )
        }
    }
}
