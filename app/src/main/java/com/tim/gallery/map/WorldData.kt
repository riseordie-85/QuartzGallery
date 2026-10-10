package com.tim.gallery.map

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.DataInputStream
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/** Web Mercator, in 0..1 units for the whole world. */
object Mercator {
    private const val MAX_LAT = 85.0

    fun x(lon: Double): Float = ((lon + 180.0) / 360.0).toFloat()

    fun y(lat: Double): Float {
        val l = Math.toRadians(lat.coerceIn(-MAX_LAT, MAX_LAT))
        return ((1.0 - ln(tan(l) + 1.0 / cos(l)) / PI) / 2.0).toFloat()
    }

    fun lon(x: Float): Double = x * 360.0 - 180.0

    fun lat(y: Float): Double = Math.toDegrees(atan(kotlin.math.sinh(PI * (1.0 - 2.0 * y))))
}

/** A line or ring of the world map, already in Mercator units, with its bounding box. */
class MapLine(val pts: FloatArray, val minX: Float, val minY: Float, val maxX: Float, val maxY: Float)

/**
 * The built-in world map (coastlines, country borders, state and province lines) and city
 * names, from Natural Earth (public domain). Everything is inside the app; nothing is
 * downloaded and no location is ever looked up online.
 */
object WorldData {
    class Layers(val landCoarse: List<MapLine>, val landFine: List<MapLine>, val borders: List<MapLine>, val states: List<MapLine>)

    class Place(val name: String, val region: String, val country: String, val lat: Double, val lon: Double, val population: Int) {
        val label: String
            get() = when {
                region.isNotBlank() && region != name -> "$name, $region"
                country.isNotBlank() -> "$name, $country"
                else -> name
            }
    }

    private val lock = Mutex()
    private var layers: Layers? = null
    private var places: List<Place>? = null

    suspend fun layers(context: Context): Layers = lock.withLock {
        layers ?: withContext(Dispatchers.IO) { load(context) }.also { layers = it }
    }

    private fun load(context: Context): Layers {
        val map = HashMap<String, List<MapLine>>()
        DataInputStream(context.assets.open("map/world.qmap").buffered()).use { inp ->
            val magic = ByteArray(4)
            inp.readFully(magic)
            inp.readUnsignedByte() // version
            val count = inp.readUnsignedByte()
            repeat(count) {
                val nameLen = inp.readUnsignedByte()
                val nameBytes = ByteArray(nameLen)
                inp.readFully(nameBytes)
                val len = inp.readInt()
                val bytes = ByteArray(len)
                inp.readFully(bytes)
                map[String(nameBytes)] = decode(bytes)
            }
        }
        return Layers(
            landCoarse = map["land_coarse"].orEmpty(),
            landFine = map["land_fine"].orEmpty(),
            borders = map["borders"].orEmpty(),
            states = map["states"].orEmpty(),
        )
    }

    private fun decode(b: ByteArray): List<MapLine> {
        var pos = 0
        fun varint(): Long {
            var shift = 0
            var result = 0L
            while (true) {
                val v = b[pos++].toInt() and 0xFF
                result = result or ((v and 0x7F).toLong() shl shift)
                if (v and 0x80 == 0) break
                shift += 7
            }
            return (result ushr 1) xor -(result and 1)
        }
        val n = varint().toInt()
        val out = ArrayList<MapLine>(n)
        repeat(n) {
            val count = varint().toInt()
            val pts = FloatArray(count * 2)
            var qx = 0L
            var qy = 0L
            var minX = 1f; var minY = 1f; var maxX = 0f; var maxY = 0f
            for (i in 0 until count) {
                qx += varint()
                qy += varint()
                val x = Mercator.x(qx / 100.0)
                val y = Mercator.y(qy / 100.0)
                pts[i * 2] = x
                pts[i * 2 + 1] = y
                if (x < minX) minX = x; if (x > maxX) maxX = x
                if (y < minY) minY = y; if (y > maxY) maxY = y
            }
            out += MapLine(pts, minX, minY, maxX, maxY)
        }
        return out
    }

    suspend fun places(context: Context): List<Place> = lock.withLock {
        places ?: withContext(Dispatchers.IO) {
            context.assets.open("map/places.tsv").bufferedReader().useLines { lines ->
                lines.mapNotNull { line ->
                    val p = line.split('\t')
                    if (p.size < 6) null
                    else Place(p[0], p[1], p[2], p[3].toDoubleOrNull() ?: return@mapNotNull null, p[4].toDoubleOrNull() ?: return@mapNotNull null, p[5].toIntOrNull() ?: 0)
                }.toList()
            }
        }.also { places = it }
    }

    /** Distance in km between two points. */
    fun km(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2) * sin(dLon / 2)
        return 2 * r * kotlin.math.asin(sqrt(a.coerceIn(0.0, 1.0)))
    }

    /**
     * The best name for a spot: the nearest town in the built-in list (within 60 km), leaning
     * toward bigger places when they're about as close.
     */
    suspend fun nameFor(context: Context, lat: Double, lon: Double): String? {
        val all = places(context)
        var best: Place? = null
        var bestScore = Double.MAX_VALUE
        for (p in all) {
            if (kotlin.math.abs(p.lat - lat) > 1.0) continue
            val d = km(lat, lon, p.lat, p.lon)
            if (d > 60) continue
            // Bigger cities "reach" a little further
            val score = d / (1.0 + ln(1.0 + p.population / 50_000.0))
            if (score < bestScore) {
                bestScore = score
                best = p
            }
        }
        return best?.label
    }
}
