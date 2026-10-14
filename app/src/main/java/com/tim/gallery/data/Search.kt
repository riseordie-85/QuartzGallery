package com.tim.gallery.data

import com.tim.gallery.settings.SortField
import com.tim.gallery.settings.SortSettings
import java.text.DateFormatSymbols
import java.util.Calendar
import java.util.Locale

/**
 * Orders items for the grid. Items that tie (e.g. hundreds of downloads added in the same
 * second, which share a date) are ordered by name, then by id, so every item always has the
 * same place; otherwise an update to one item (like favoriting it) could move it within its tie.
 */
fun List<GalleryItem>.sortedFor(s: SortSettings): List<GalleryItem> = sortedWith(sortComparator(s))

fun sortComparator(s: SortSettings): Comparator<GalleryItem> {
    val main: Comparator<GalleryItem> = when (s.field) {
        SortField.TAKEN -> compareBy { it.dateMillis }
        SortField.ADDED -> compareBy { it.addedMillis }
        SortField.SIZE -> compareBy { it.sizeBytes }
        SortField.NAME -> compareBy(String.CASE_INSENSITIVE_ORDER) { it.name }
    }
    val cmp = main
        .thenBy(String.CASE_INSENSITIVE_ORDER) { it.name }
        .thenBy { it.id }
    return if (s.descending) cmp.reversed() else cmp
}

/**
 * Simple on-device search. Every word you type must match something about the item:
 * its file name, album, month or year ("october", "oct", "2024"), or a type word
 * ("video", "photo", "favorite", "screenshot", "gif", "png"...).
 */
class Search private constructor(private val words: List<String>) {

    private val months = DateFormatSymbols(Locale.getDefault()).months.map { it.lowercase(Locale.getDefault()) }
    private val shortMonths = DateFormatSymbols(Locale.getDefault()).shortMonths.map { it.lowercase(Locale.getDefault()).trimEnd('.') }
    private val cal = Calendar.getInstance()

    fun matches(item: GalleryItem): Boolean {
        cal.timeInMillis = item.dateMillis
        val m = cal.get(Calendar.MONTH)
        val year = cal.get(Calendar.YEAR).toString()
        val name = item.name.lowercase(Locale.getDefault())
        val album = item.album.lowercase(Locale.getDefault())
        val path = item.relativePath.lowercase(Locale.getDefault())
        val ext = name.substringAfterLast('.', "")
        return words.all { w ->
            name.contains(w) || album.contains(w) || path.contains(w) ||
                year == w ||
                months.getOrNull(m)?.startsWith(w) == true ||
                (w.length >= 3 && shortMonths.getOrNull(m)?.startsWith(w) == true) ||
                ext == w ||
                when (w) {
                    "video", "videos" -> item.isVideo
                    "photo", "photos", "picture", "pictures", "image", "images" -> !item.isVideo
                    "favorite", "favorites", "favourite", "fav", "favs" -> item.isFavorite
                    "screenshot", "screenshots" -> path.contains("screenshot")
                    else -> false
                }
        }
    }

    companion object {
        /** Null when there's nothing to search for. */
        fun compile(query: String): Search? {
            val words = query.lowercase(Locale.getDefault()).split(Regex("\\s+")).map { it.trim(',', '.') }.filter { it.isNotEmpty() }
            return if (words.isEmpty()) null else Search(words)
        }
    }
}
