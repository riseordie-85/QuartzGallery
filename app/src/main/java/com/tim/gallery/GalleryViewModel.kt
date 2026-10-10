package com.tim.gallery

import android.app.Application
import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.tim.gallery.data.Album
import com.tim.gallery.data.AlbumOps
import com.tim.gallery.data.GalleryItem
import com.tim.gallery.data.MediaRepository
import com.tim.gallery.data.Search
import com.tim.gallery.data.sortedFor
import com.tim.gallery.settings.AppSettings
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class TypeFilter(val label: String) { ALL("All"), PHOTOS("Photos"), VIDEOS("Videos") }

class GalleryViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = MediaRepository(app)
    private val all = MutableStateFlow<List<GalleryItem>>(emptyList())

    /** Every photo and video, newest first. */
    val allItems: StateFlow<List<GalleryItem>> = all.asStateFlow()

    val typeFilter = MutableStateFlow(TypeFilter.ALL)

    private val _loading = MutableStateFlow(true)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    /** Search text for the Photos tab ("" = no search). */
    val query = MutableStateFlow("")

    /** What the Photos tab shows: All / Photos / Videos filter, search, and sort. Stickers are left out. */
    val items: StateFlow<List<GalleryItem>> =
        combine(all, typeFilter, query, AppSettings.sort) { list, type, q, sort ->
            val search = Search.compile(q)
            list.filter { item ->
                !AlbumOps.isStickers(item.relativePath) && when (type) {
                    TypeFilter.ALL -> true
                    TypeFilter.PHOTOS -> !item.isVideo
                    TypeFilter.VIDEOS -> item.isVideo
                } && (search == null || search.matches(item))
            }.sortedFor(sort)
        }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Images in the sticker folder, newest first. */
    val stickers: StateFlow<List<GalleryItem>> =
        all.map { list -> list.filter { !it.isVideo && AlbumOps.isStickers(it.relativePath) } }
            .flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _trash = MutableStateFlow<List<GalleryItem>>(emptyList())

    /** Items in Android's trash, soonest-to-expire first. */
    val trash: StateFlow<List<GalleryItem>> = _trash.asStateFlow()

    // Albums created in the app that don't have anything in them yet. A folder with no
    // photos is invisible to Android's media index, so the app remembers them itself.
    private val prefs = app.getSharedPreferences("gallery_albums", Context.MODE_PRIVATE)
    private val emptyAlbums = MutableStateFlow(prefs.getStringSet(KEY_EMPTY, emptySet())?.toSet() ?: emptySet())

    val albums: StateFlow<List<Album>> =
        combine(all, emptyAlbums) { list, empties ->
            val real = list.groupBy { it.relativePath }.map { (path, items) ->
                Album(
                    key = path,
                    name = AlbumOps.nameFromPath(path).ifBlank { items.first().album },
                    count = items.size,
                    cover = items.first(),
                    latestMillis = items.first().dateMillis,
                )
            }
            val realKeys = real.map { it.key.lowercase() }.toSet()
            val pending = empties
                .filter { it.lowercase() !in realKeys }
                .map { Album(it, AlbumOps.nameFromPath(it), 0, null, Long.MAX_VALUE) }
            val favs = list.filter { it.isFavorite }
            val favorites = Album(
                key = AlbumOps.FAVORITES_KEY,
                name = "Favorites",
                count = favs.size,
                cover = favs.firstOrNull(),
                latestMillis = Long.MAX_VALUE,
            )
            listOf(favorites) + (pending + real).sortedByDescending { it.latestMillis }
        }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val albumFlows = HashMap<String, StateFlow<List<GalleryItem>>>()

    /** Items in one album (a folder, or the Favorites album), newest first. */
    fun albumItems(key: String): StateFlow<List<GalleryItem>> = albumFlows.getOrPut(key) {
        val favorites = AlbumOps.isFavorites(key)
        val everything = key == AlbumOps.ALL_KEY
        val belongs: (GalleryItem) -> Boolean = { item ->
            everything || if (favorites) item.isFavorite else item.relativePath == key
        }
        combine(all, AppSettings.sort) { list, sort -> list.filter(belongs).sortedFor(sort) }
            .flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.Eagerly, all.value.filter(belongs))
    }

    fun addEmptyAlbum(key: String) = saveEmpty(emptyAlbums.value + key)

    fun removeEmptyAlbum(key: String) {
        if (key in emptyAlbums.value) saveEmpty(emptyAlbums.value - key)
    }

    fun renameEmptyAlbum(oldKey: String, newKey: String) = saveEmpty(emptyAlbums.value - oldKey + newKey)

    private fun saveEmpty(set: Set<String>) {
        emptyAlbums.value = set
        prefs.edit().putStringSet(KEY_EMPTY, HashSet(set)).apply()
    }

    private var loadJob: Job? = null
    private var observing = false
    private val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) {
            refresh(debounce = true)
        }
    }

    fun refresh(debounce: Boolean = false) {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            if (debounce) delay(400)
            val fresh = try {
                repo.loadAllIncremental(all.value)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                emptyList()
            }
            if (fresh != all.value) all.value = fresh
            _loading.value = false
            val trash = try {
                repo.loadTrash()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                emptyList()
            }
            if (trash != _trash.value) _trash.value = trash
            if (!observing) {
                getApplication<Application>().contentResolver
                    .registerContentObserver(repo.collection, true, observer)
                observing = true
            }
        }
    }

    fun find(id: Long): GalleryItem? =
        all.value.firstOrNull { it.id == id } ?: _trash.value.firstOrNull { it.id == id }

    override fun onCleared() {
        if (observing) {
            getApplication<Application>().contentResolver.unregisterContentObserver(observer)
        }
    }

    private companion object {
        const val KEY_EMPTY = "empty_albums"
    }
}
