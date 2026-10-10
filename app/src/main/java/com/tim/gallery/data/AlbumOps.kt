package com.tim.gallery.data

import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Albums are real folders on the phone, so other apps (and a PC over USB) see the same
 * organization. Moving an item = changing its folder (RELATIVE_PATH) in MediaStore.
 */
object AlbumOps {

    /** The built-in Favorites album isn't a folder; it's everything with the favorite flag. */
    const val FAVORITES_KEY = "@favorites"

    fun isFavorites(key: String): Boolean = key == FAVORITES_KEY

    /** Trash isn't a folder either; it's Android's system trash. */
    const val TRASH_KEY = "@trash"

    /**
     * Sticker images live in a normal folder so they survive reinstalls and can be added
     * from a PC. They're hidden from the Photos tab but show up as an album.
     */
    val STICKERS_PATH = "${Environment.DIRECTORY_PICTURES}/Stickers/"

    fun isStickers(path: String): Boolean = path.equals(STICKERS_PATH, ignoreCase = true)

    /** New albums go under Pictures/, the one folder Android allows both photos and videos in. */
    val NEW_ALBUM_PARENT = "${Environment.DIRECTORY_PICTURES}/"

    /** Folders that system apps keep saving into even after a rename. */
    private val SYSTEM_FOLDERS = setOf("camera", "screenshots", "screen recordings", "screenrecorder")

    private val ILLEGAL = Regex("""[\\/:*?"<>|]""")

    fun nameFromPath(path: String): String = path.trimEnd('/').substringAfterLast('/')

    /** "DCIM/Camera/" -> "DCIM/". Null for top-level folders like "Pictures/", which can't be renamed. */
    fun parentOf(path: String): String? {
        val trimmed = path.trimEnd('/')
        val i = trimmed.lastIndexOf('/')
        return if (i <= 0) null else trimmed.substring(0, i + 1)
    }

    fun newAlbumPath(name: String): String = "$NEW_ALBUM_PARENT${name.trim()}/"

    fun isSystemFolder(path: String): Boolean = nameFromPath(path).lowercase() in SYSTEM_FOLDERS

    /** Returns an error message, or null if [name] is fine for a folder under [parent]. */
    fun validateName(name: String, parent: String, albums: List<Album>, currentKey: String? = null): String? {
        val n = name.trim()
        val target = "$parent$n/"
        return when {
            n.isEmpty() -> "Enter a name"
            n.length > 80 -> "That name is too long"
            ILLEGAL.containsMatchIn(n) -> "Names can't contain \\ / : * ? \" < > |"
            n.startsWith(".") -> "Names can't start with a period"
            albums.any { it.key.equals(target, ignoreCase = true) && it.key != currentKey } ->
                "There's already an album with that name"
            else -> null
        }
    }

    /** Checks a new file name. Returns an error message, or null if it's fine. */
    fun validateFileName(stem: String): String? {
        val n = stem.trim()
        return when {
            n.isEmpty() -> "Enter a name"
            n.length > 120 -> "That name is too long"
            ILLEGAL.containsMatchIn(n) -> "Names can't contain \\ / : * ? \" < > |"
            n.startsWith(".") -> "Names can't start with a period"
            else -> null
        }
    }

    /** Renames one file. The caller must already have write access. */
    suspend fun rename(context: Context, item: GalleryItem, newName: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val values = ContentValues().apply { put(MediaStore.MediaColumns.DISPLAY_NAME, newName) }
            context.contentResolver.update(item.uri, values, null, null) > 0
        } catch (e: Exception) {
            Log.w("AlbumOps", "Couldn't rename ${item.name}", e)
            false
        }
    }

    /**
     * Copies items into the folder [targetPath] as new files (same name and date; Android adds
     * a number if the name is taken). Returns the new files' uris.
     */
    suspend fun copy(context: Context, items: List<GalleryItem>, targetPath: String): List<android.net.Uri> =
        withContext(Dispatchers.IO) {
            val resolver = context.contentResolver
            val out = mutableListOf<android.net.Uri>()
            for (item in items) {
                val collection = if (item.isVideo) {
                    MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                } else {
                    MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                }
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, item.name)
                    put(MediaStore.MediaColumns.MIME_TYPE, item.mimeType)
                    put(MediaStore.MediaColumns.RELATIVE_PATH, targetPath)
                    put(MediaStore.MediaColumns.DATE_TAKEN, item.dateMillis)
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
                val uri = try {
                    resolver.insert(collection, values)
                } catch (e: Exception) {
                    Log.w("AlbumOps", "Couldn't create a copy of ${item.name} in $targetPath", e)
                    null
                } ?: continue
                try {
                    var copied = 0L
                    resolver.openInputStream(item.uri)?.use { input ->
                        resolver.openOutputStream(uri, "w")?.use { output -> copied = input.copyTo(output) }
                    }
                    if (item.sizeBytes > 0 && copied != item.sizeBytes) throw java.io.IOException("Incomplete copy")
                    values.clear()
                    values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                    resolver.update(uri, values, null, null)
                    out += uri
                } catch (e: Exception) {
                    Log.w("AlbumOps", "Copying ${item.name} failed", e)
                    runCatching { resolver.delete(uri, null, null) }
                }
            }
            out
        }

    /** Deletes files this app created (no confirmation needed for those). */
    suspend fun deleteOwn(context: Context, uris: List<android.net.Uri>) = withContext(Dispatchers.IO) {
        uris.forEach { runCatching { context.contentResolver.delete(it, null, null) } }
    }

    /**
     * Moves items into the folder [targetPath]. The caller must already have write access
     * (MediaStore.createWriteRequest). Returns how many were moved.
     */
    suspend fun move(context: Context, items: List<GalleryItem>, targetPath: String): Int =
        withContext(Dispatchers.IO) {
            val resolver = context.contentResolver
            var moved = 0
            for (item in items) {
                if (item.relativePath.equals(targetPath, ignoreCase = true)) {
                    moved++
                    continue
                }
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.RELATIVE_PATH, targetPath)
                }
                try {
                    if (resolver.update(item.uri, values, null, null) > 0) moved++
                } catch (e: Exception) {
                    // e.g. a photo can't live in Movies/, or a name clash Android refused
                    Log.w("AlbumOps", "Couldn't move ${item.name} to $targetPath", e)
                }
            }
            moved
        }
}
