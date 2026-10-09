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
