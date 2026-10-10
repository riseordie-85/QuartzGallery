package com.tim.gallery.map

import android.content.Context
import com.tim.gallery.data.GalleryItem
import com.tim.gallery.data.Metadata
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Where each photo and video was taken, read from the files themselves (on the phone) and
 * remembered so only new or changed items are read next time.
 */
object LocationIndex {
    data class Spot(val modified: Long, val lat: Float, val lon: Float) {
        val hasLocation get() = !lat.isNaN()
    }

    private val cache = ConcurrentHashMap<Long, Spot>()
    private var loaded = false
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    /** (done, total) while reading new items; null when up to date. */
    private val _progress = MutableStateFlow<Pair<Int, Int>?>(null)
    val progress: StateFlow<Pair<Int, Int>?> = _progress.asStateFlow()

    /** Bumps when new locations are known, so the map redraws. */
    private val _version = MutableStateFlow(0)
    val version: StateFlow<Int> = _version.asStateFlow()

    private fun file(context: Context) = File(context.noBackupFilesDir, "locations.bin")

    fun spot(id: Long): Spot? = cache[id]

    /** Reads locations for any items not seen before (in the background). */
    fun update(context: Context, items: List<GalleryItem>) {
        if (job?.isActive == true) return
        job = scope.launch {
            if (!loaded) {
                load(context)
                loaded = true
                _version.value++
            }
            val todo = items.filter { cache[it.id]?.modified != it.modified }
            if (todo.isEmpty()) return@launch
            _progress.value = 0 to todo.size
            var done = 0
            todo.chunked(64).forEach { chunk ->
                // A few files at a time, in parallel
                val results = chunk.chunked(16).map { part ->
                    async {
                        part.map { item ->
                            val loc = Metadata.location(context, item)
                            item.id to Spot(item.modified, loc?.first?.toFloat() ?: Float.NaN, loc?.second?.toFloat() ?: Float.NaN)
                        }
                    }
                }.awaitAll().flatten()
                results.forEach { (id, spot) -> cache[id] = spot }
                done += chunk.size
                _progress.value = done to todo.size
                if (done % 512 < 64) {
                    _version.value++
                    save(context)
                }
            }
            // Forget items that no longer exist
            val ids = items.mapTo(HashSet()) { it.id }
            cache.keys.retainAll(ids)
            save(context)
            _progress.value = null
            _version.value++
        }
    }

    private suspend fun load(context: Context) = withContext(Dispatchers.IO) {
        runCatching {
            DataInputStream(file(context).inputStream().buffered()).use { inp ->
                val n = inp.readInt()
                repeat(n) {
                    val id = inp.readLong()
                    cache[id] = Spot(inp.readLong(), inp.readFloat(), inp.readFloat())
                }
            }
        }
    }

    private fun save(context: Context) {
        runCatching {
            val tmp = File(file(context).parentFile, "locations.tmp")
            DataOutputStream(tmp.outputStream().buffered()).use { out ->
                val snapshot = cache.entries.toList()
                out.writeInt(snapshot.size)
                snapshot.forEach { (id, s) ->
                    out.writeLong(id)
                    out.writeLong(s.modified)
                    out.writeFloat(s.lat)
                    out.writeFloat(s.lon)
                }
            }
            tmp.renameTo(file(context))
        }
    }
}
