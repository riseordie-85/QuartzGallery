package com.tim.gallery.video

import android.graphics.Bitmap
import java.io.OutputStream

/**
 * A small animated GIF writer. Each frame gets its own 256-color palette (median cut) and
 * Floyd–Steinberg dithering, which keeps video gradients looking smooth.
 */
class GifEncoder(
    private val out: OutputStream,
    private val width: Int,
    private val height: Int,
    /** Delay between frames, in milliseconds. */
    delayMs: Int,
) {
    private val delayCs = (delayMs / 10).coerceAtLeast(2)
    private var started = false

    private fun write16(v: Int) {
        out.write(v and 0xFF)
        out.write((v shr 8) and 0xFF)
    }

    private fun start() {
        out.write("GIF89a".toByteArray())
        write16(width)
        write16(height)
        out.write(0x70) // no global color table, 8-bit color resolution
        out.write(0)
        out.write(0)
        // Loop forever
        out.write(byteArrayOf(0x21, 0xFF.toByte(), 0x0B))
        out.write("NETSCAPE2.0".toByteArray())
        out.write(byteArrayOf(0x03, 0x01, 0x00, 0x00, 0x00))
        started = true
    }

    /** Adds one frame. [bitmap] must be [width] × [height]. */
    fun addFrame(bitmap: Bitmap) {
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        addFrame(pixels)
    }

    /** Adds one frame from ARGB pixels (row by row, [width] × [height]). */
    fun addFrame(pixels: IntArray) {
        if (!started) start()
        val palette = medianCut(pixels, 256)
        val indices = ditherToPalette(pixels, palette)

        // Graphic control extension: delay, "leave in place"
        out.write(byteArrayOf(0x21, 0xF9.toByte(), 0x04, 0x04))
        write16(delayCs)
        out.write(0)
        out.write(0)
        // Image descriptor with a 256-entry local color table
        out.write(0x2C)
        write16(0)
        write16(0)
        write16(width)
        write16(height)
        out.write(0x87)
        val table = ByteArray(256 * 3)
        for (i in palette.indices) {
            table[i * 3] = ((palette[i] shr 16) and 0xFF).toByte()
            table[i * 3 + 1] = ((palette[i] shr 8) and 0xFF).toByte()
            table[i * 3 + 2] = (palette[i] and 0xFF).toByte()
        }
        out.write(table)
        lzw(indices)
    }

    fun finish() {
        if (!started) start()
        out.write(0x3B)
        out.flush()
    }

    // ------------------------------------------------------------ color reduction

    private fun medianCut(pixels: IntArray, maxColors: Int): IntArray {
        val step = maxOf(1, pixels.size / 40_000)
        val samples = IntArray((pixels.size + step - 1) / step) { pixels[it * step] and 0xFFFFFF }
        val boxes = ArrayList<IntArray>()
        boxes += samples
        while (boxes.size < maxColors) {
            // Split the box with the widest color range (weighted by how many pixels it holds)
            var best = -1
            var bestScore = 0L
            var bestChannel = 0
            for ((i, box) in boxes.withIndex()) {
                if (box.size < 2) continue
                var rMin = 255; var rMax = 0; var gMin = 255; var gMax = 0; var bMin = 255; var bMax = 0
                for (c in box) {
                    val r = (c shr 16) and 0xFF; val g = (c shr 8) and 0xFF; val b = c and 0xFF
                    if (r < rMin) rMin = r; if (r > rMax) rMax = r
                    if (g < gMin) gMin = g; if (g > gMax) gMax = g
                    if (b < bMin) bMin = b; if (b > bMax) bMax = b
                }
                val rr = rMax - rMin; val gr = gMax - gMin; val br = bMax - bMin
                val range = maxOf(rr, gr, br)
                if (range == 0) continue
                val score = range.toLong() * box.size
                if (score > bestScore) {
                    bestScore = score
                    best = i
                    bestChannel = if (rr >= gr && rr >= br) 16 else if (gr >= br) 8 else 0
                }
            }
            if (best < 0) break
            val box = boxes[best]
            val shift = bestChannel
            val sorted = box.sortedBy { (it shr shift) and 0xFF }
            val mid = sorted.size / 2
            boxes[best] = sorted.subList(0, mid).toIntArray()
            boxes += sorted.subList(mid, sorted.size).toIntArray()
        }
        return IntArray(boxes.size) { i ->
            val box = boxes[i]
            var r = 0L; var g = 0L; var b = 0L
            for (c in box) {
                r += (c shr 16) and 0xFF; g += (c shr 8) and 0xFF; b += c and 0xFF
            }
            val k = maxOf(1, box.size)
            ((r / k).toInt() shl 16) or ((g / k).toInt() shl 8) or (b / k).toInt()
        }
    }

    private fun ditherToPalette(pixels: IntArray, palette: IntArray): ByteArray {
        val cache = IntArray(32768) { -1 }
        fun nearest(r: Int, g: Int, b: Int): Int {
            val key = ((r shr 3) shl 10) or ((g shr 3) shl 5) or (b shr 3)
            val hit = cache[key]
            if (hit >= 0) return hit
            var best = 0
            var bestD = Int.MAX_VALUE
            for (i in palette.indices) {
                val p = palette[i]
                val dr = r - ((p shr 16) and 0xFF)
                val dg = g - ((p shr 8) and 0xFF)
                val db = b - (p and 0xFF)
                val d = 2 * dr * dr + 4 * dg * dg + 3 * db * db
                if (d < bestD) {
                    bestD = d
                    best = i
                }
            }
            cache[key] = best
            return best
        }

        val out = ByteArray(pixels.size)
        // Error carried to the current and next row (r, g, b per pixel)
        var cur = IntArray((width + 2) * 3)
        var next = IntArray((width + 2) * 3)
        for (y in 0 until height) {
            java.util.Arrays.fill(next, 0)
            for (x in 0 until width) {
                val c = pixels[y * width + x]
                val e = (x + 1) * 3
                val r = (((c shr 16) and 0xFF) + cur[e] / 16).coerceIn(0, 255)
                val g = (((c shr 8) and 0xFF) + cur[e + 1] / 16).coerceIn(0, 255)
                val b = ((c and 0xFF) + cur[e + 2] / 16).coerceIn(0, 255)
                val idx = nearest(r, g, b)
                out[y * width + x] = idx.toByte()
                val p = palette[idx]
                val er = r - ((p shr 16) and 0xFF)
                val eg = g - ((p shr 8) and 0xFF)
                val eb = b - (p and 0xFF)
                // Floyd–Steinberg: 7 right, 3 down-left, 5 down, 1 down-right (all /16)
                cur[e + 3] += er * 7; cur[e + 4] += eg * 7; cur[e + 5] += eb * 7
                next[e - 3] += er * 3; next[e - 2] += eg * 3; next[e - 1] += eb * 3
                next[e] += er * 5; next[e + 1] += eg * 5; next[e + 2] += eb * 5
                next[e + 3] += er; next[e + 4] += eg; next[e + 5] += eb
            }
            val t = cur
            cur = next
            next = t
        }
        return out
    }

    // ------------------------------------------------------------ LZW

    private fun lzw(indices: ByteArray) {
        val minCodeSize = 8
        val clearCode = 1 shl minCodeSize
        val eoiCode = clearCode + 1
        out.write(minCodeSize)

        val block = ByteArray(255)
        var blockLen = 0
        var bitBuf = 0L
        var bitCount = 0
        fun flushByte(b: Int) {
            block[blockLen++] = b.toByte()
            if (blockLen == 255) {
                out.write(255)
                out.write(block, 0, 255)
                blockLen = 0
            }
        }
        var codeSize = minCodeSize + 1
        fun emit(code: Int) {
            bitBuf = bitBuf or (code.toLong() shl bitCount)
            bitCount += codeSize
            while (bitCount >= 8) {
                flushByte((bitBuf and 0xFF).toInt())
                bitBuf = bitBuf ushr 8
                bitCount -= 8
            }
        }

        val table = HashMap<Int, Int>(8192)
        var nextCode = eoiCode + 1
        emit(clearCode)
        if (indices.isNotEmpty()) {
            var cur = indices[0].toInt() and 0xFF
            for (i in 1 until indices.size) {
                val k = indices[i].toInt() and 0xFF
                val key = (cur shl 8) or k
                val found = table[key]
                if (found != null) {
                    cur = found
                } else {
                    emit(cur)
                    if (nextCode == 4096) {
                        emit(clearCode)
                        nextCode = eoiCode + 1
                        codeSize = minCodeSize + 1
                        table.clear()
                    } else {
                        if (nextCode >= (1 shl codeSize)) codeSize++
                        table[key] = nextCode++
                    }
                    cur = k
                }
            }
            emit(cur)
        }
        emit(eoiCode)
        if (bitCount > 0) flushByte((bitBuf and 0xFF).toInt())
        if (blockLen > 0) {
            out.write(blockLen)
            out.write(block, 0, blockLen)
        }
        out.write(0) // block terminator
    }
}
