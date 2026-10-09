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

    /** Items in Android's trash, soonest-to-expire first. */
    suspend fun loadTrash(): List<GalleryItem> =
        query(MediaStore.MATCH_ONLY).sortedBy { it.expiresMillis }

    private suspend fun query(matchTrashed: Int): List<GalleryItem> = withContext(Dispatchers.IO) {
        val args = Bundle().apply {
            putString(ContentResolver.QUERY_ARG_SQL_SELECTION, "${FileColumns.MEDIA_TYPE} IN (?, ?)")
            putStringArray(
                ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS,
                arrayOf(
                    FileColumns.MEDIA_TYPE_IMAGE.toString(),
                    FileColumns.MEDIA_TYPE_VIDEO.toString(),
                ),
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
            )
        }
    }
}
