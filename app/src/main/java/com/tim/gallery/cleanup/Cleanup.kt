package com.tim.gallery.cleanup

import android.content.Context
import android.graphics.Bitmap
import android.util.Size
import com.tim.gallery.data.GalleryItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.coroutineContext

/** Finds space to free up: exact duplicates and look-alike photos. All on the phone. */
object Cleanup {

    /**
     * Exact duplicates: same size and type first (cheap), then the same content. Each group is
     * sorted so the one to keep (camera folder, then oldest) comes first.
     */
    suspend fun duplicates(
        context: Context,
        items: List<GalleryItem>,
        onProgress: (Int, Int) -> Unit,
    ): List<List<GalleryItem>> = withContext(Dispatchers.IO) {
        val candidates = items.filter { it.sizeBytes > 0 }
            .groupBy { it.sizeBytes to it.mimeType }
            .values.filter { it.size > 1 }
        val total = candidates.sumOf { it.size }
        var done = 0
        val groups = mutableListOf<List<GalleryItem>>()
        for (group in candidates) {
            coroutineContext.ensureActive()
            val byHash = group.groupBy { item ->
                val key = "${item.id}#${item.modified}"
                val h = contentHashes[key] ?: runCatching { contentHash(context, item) }.getOrNull()?.also { contentHashes[key] = it }
                done++
                onProgress(done, total)
                h
            }
            byHash.forEach { (hash, same) -> if (hash != null && same.size > 1) groups += keepFirst(same) }
        }
        groups.sortedByDescending { g -> g.drop(1).sumOf { it.sizeBytes } }
    }

    private val contentHashes = ConcurrentHashMap<String, String>()

    private fun keepFirst(list: List<GalleryItem>): List<GalleryItem> =
        list.sortedWith(compareBy<GalleryItem> { if (it.relativePath.contains("DCIM/Camera", ignoreCase = true)) 0 else 1 }.thenBy { it.addedMillis })

    /** Hash of the whole file (small files) or of its start, middle and end (big ones). */
    private fun contentHash(context: Context, item: GalleryItem): String? {
        val md = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(64 * 1024)
        context.contentResolver.openInputStream(item.uri)?.use { input ->
            if (item.sizeBytes <= 48L * 1024 * 1024) {
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    md.update(buf, 0, n)
                }
            } else {
                val positions = listOf(0L, item.sizeBytes / 2, item.sizeBytes - buf.size)
                var at = 0L
                for (p in positions) {
                    var skip = p - at
                    while (skip > 0) {
                        val s = input.skip(skip)
                        if (s <= 0) break
                        skip -= s
                        at += s
                    }
                    var got = 0
                    while (got < buf.size) {
                        val n = input.read(buf, got, buf.size - got)
                        if (n < 0) break
                        got += n
                    }
                    at += got
                    md.update(buf, 0, got)
                }
                md.update(item.sizeBytes.toString().toByteArray())
            }
        } ?: return null
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    // ------------------------------------------------------------ similar photos

    private val hashes = ConcurrentHashMap<Long, Pair<Long, Long>>() // id -> (modified, dHash)
    private var hashesLoaded = false
    private fun hashFile(context: Context) = File(context.noBackupFilesDir, "similar.bin")

    /**
     * Look-alike photos (bursts, near-identical shots): photos taken within 10 minutes of each
     * other whose small "fingerprints" nearly match. In each group the largest file (usually
     * the most detailed) comes first, as the one to keep.
     */
    suspend fun similar(
        context: Context,
        items: List<GalleryItem>,
        onProgress: (Int, Int) -> Unit,
    ): List<List<GalleryItem>> = withContext(Dispatchers.IO) {
        if (!hashesLoaded) {
            loadHashes(context)
            hashesLoaded = true
        }
        val photos = items.filter { !it.isVideo && !it.mimeType.contains("gif") }.sortedBy { it.dateMillis }
        // Only photos with a neighbor close in time can be in a group
        val window = 10 * 60 * 1000L
        val need = photos.filterIndexed { i, p ->
            (i > 0 && p.dateMillis - photos[i - 1].dateMillis <= window) ||
                (i < photos.size - 1 && photos[i + 1].dateMillis - p.dateMillis <= window)
        }
        val todo = need.filter { hashes[it.id]?.first != it.modified }
        var done = 0
        onProgress(0, todo.size)
        todo.chunked(32).forEach { chunk ->
            coroutineContext.ensureActive()
            coroutineScope {
                chunk.chunked(8).map { part ->
                    async {
                        part.forEach { item ->
                            dHash(context, item)?.let { hashes[item.id] = item.modified to it }
                        }
                    }
                }.awaitAll()
            }
            done += chunk.size
            onProgress(done, todo.size)
        }
        saveHashes(context, items)

        // Group neighbors in time with near-identical fingerprints
        val groups = mutableListOf<MutableList<GalleryItem>>()
        var current = mutableListOf<GalleryItem>()
        var last: GalleryItem? = null
        for (p in need) {
            val h = hashes[p.id]?.second ?: continue
            val prev = last
            val prevHash = prev?.let { hashes[it.id]?.second }
            if (prev != null && prevHash != null && p.dateMillis - prev.dateMillis <= window &&
                java.lang.Long.bitCount(h xor prevHash) <= 8
            ) {
                if (current.isEmpty()) current += prev
                current += p
            } else {
                if (current.size > 1) groups += current
                current = mutableListOf()
            }
            last = p
        }
        if (current.size > 1) groups += current
        groups.map { g -> g.sortedByDescending { it.sizeBytes } }
            .sortedByDescending { it.first().dateMillis }
    }

    /** 64-bit "difference hash" of a 9×8 grayscale thumbnail. */
    private fun dHash(context: Context, item: GalleryItem): Long? = runCatching {
        val thumb = context.contentResolver.loadThumbnail(item.uri, Size(96, 96), null)
        val small = Bitmap.createScaledBitmap(thumb, 9, 8, true)
        val px = IntArray(72)
        small.getPixels(px, 0, 9, 0, 0, 9, 8)
        if (small !== thumb) small.recycle()
        thumb.recycle()
        fun lum(c: Int) = ((c shr 16) and 0xFF) * 299 + ((c shr 8) and 0xFF) * 587 + (c and 0xFF) * 114
        var h = 0L
        var bit = 0
        for (y in 0 until 8) for (x in 0 until 8) {
            if (lum(px[y * 9 + x]) > lum(px[y * 9 + x + 1])) h = h or (1L shl bit)
            bit++
        }
        h
    }.getOrNull()

    private fun loadHashes(context: Context) {
        runCatching {
            DataInputStream(hashFile(context).inputStream().buffered()).use { inp ->
                repeat(inp.readInt()) { hashes[inp.readLong()] = inp.readLong() to inp.readLong() }
            }
        }
    }

    private fun saveHashes(context: Context, items: List<GalleryItem>) {
        runCatching {
            val ids = items.mapTo(HashSet()) { it.id }
            hashes.keys.retainAll(ids)
            DataOutputStream(hashFile(context).outputStream().buffered()).use { out ->
                val snap = hashes.entries.toList()
                out.writeInt(snap.size)
                snap.forEach { (id, v) ->
                    out.writeLong(id)
                    out.writeLong(v.first)
                    out.writeLong(v.second)
                }
            }
        }
    }
}
