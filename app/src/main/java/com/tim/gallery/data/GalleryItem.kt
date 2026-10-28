package com.tim.gallery.data

import android.net.Uri

data class GalleryItem(
    val id: Long,
    val uri: Uri,
    val name: String,
    val mimeType: String,
    val isVideo: Boolean,
    val dateMillis: Long,
    val durationMs: Long,
    val width: Int,
    val height: Int,
    val sizeBytes: Long,
    val album: String,
    /** Folder on the phone, e.g. "DCIM/Camera/". An album is one folder. */
    val relativePath: String,
    /** Android's system-wide favorite flag, shared with other gallery apps. */
    val isFavorite: Boolean,
    /** Last-modified time; part of image cache keys so edited files never show stale. */
    val modified: Long,
    /** For items in the trash: when Android deletes them for good. 0 otherwise. */
    val expiresMillis: Long = 0L,
    /** When it was added to the phone (download, copy, edit...). */
    val addedMillis: Long = 0L,
) {
    /** Handed over by another app (shared, "Open with", "Edit with"), not one of the gallery's own items. */
    val isExternal: Boolean get() = id < 0
}

data class Album(
    /** The folder path, e.g. "DCIM/Camera/" — unique per album. */
    val key: String,
    val name: String,
    val count: Int,
    /** Newest item, or null for a freshly created album that has nothing in it yet. */
    val cover: GalleryItem?,
    val latestMillis: Long,
)
