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
    /** Delay between frames, in milliseconds (each frame can override it). */
    private val delayMs: Int,
    /**
     * Keep see-through pixels (alpha below half) see-through. Every frame is then cleared
     * before the next one is drawn, so see-through areas don't show older frames.
     */
    private val keepTransparency: Boolean = false,
) {
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
    fun addFrame(bitmap: Bitmap, frameDelayMs: Int = delayMs) {
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        addFrame(pixels, frameDelayMs)
    }

    /** Adds one frame from ARGB pixels (row by row, [width] × [height]). */
    fun addFrame(pixels: IntArray, frameDelayMs: Int = delayMs) {
        if (!started) start()
        // When keeping transparency, the see-through slot is always declared (even on frames
        // without see-through pixels) so every viewer clears frames to see-through, not a color
        val seeThrough = keepTransparency
        // One palette slot is kept free for "see-through"
        val palette = medianCut(pixels, if (seeThrough) 255 else 256, seeThrough)
        val indices = ditherToPalette(pixels, palette, if (seeThrough) TRANSPARENT_INDEX else -1)

        // Graphic control extension: delay, what happens before the next frame, see-through color
        val disposal = if (keepTransparency) 2 else 1 // 2 = clear, 1 = leave in place
        out.write(byteArrayOf(0x21, 0xF9.toByte(), 0x04, ((disposal shl 2) or (if (seeThrough) 1 else 0)).toByte()))
        write16((frameDelayMs / 10).coerceAtLeast(2))
        out.write(if (seeThrough) TRANSPARENT_INDEX else 0)
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

    private companion object {
        const val TRANSPARENT_INDEX = 255
    }

    // ------------------------------------------------------------ color reduction

    private fun medianCut(pixels: IntArray, maxColors: Int, skipSeeThrough: Boolean = false): IntArray {
        val step = maxOf(1, pixels.size / 40_000)
        var samples = IntArray((pixels.size + step - 1) / step) { pixels[it * step] }
        if (skipSeeThrough) samples = samples.filter { (it ushr 24) >= 128 }.toIntArray()
        for (i in samples.indices) samples[i] = samples[i] and 0xFFFFFF
        // Each box remembers its widest channel and score, so a split only measures the two new halves
        class Box(val colors: IntArray) {
            val channel: Int
            val score: Long

            init {
                var rMin = 255; var rMax = 0; var gMin = 255; var gMax = 0; var bMin = 255; var bMax = 0
                for (c in colors) {
                    val r = (c shr 16) and 0xFF; val g = (c shr 8) and 0xFF; val b = c and 0xFF
                    if (r < rMin) rMin = r; if (r > rMax) rMax = r
                    if (g < gMin) gMin = g; if (g > gMax) gMax = g
                    if (b < bMin) bMin = b; if (b > bMax) bMax = b
                }
                val rr = rMax - rMin; val gr = gMax - gMin; val br = bMax - bMin
                val range = maxOf(rr, gr, br, 0)
                // Weighted by how many pixels it holds; boxes that can't split score 0
                score = if (colors.size < 2 || range == 0) 0L else range.toLong() * colors.size
                channel = if (rr >= gr && rr >= br) 16 else if (gr >= br) 8 else 0
            }
        }
        val boxes = ArrayList<Box>()
        boxes += Box(samples)
        while (boxes.size < maxColors) {
            var best = -1
            var bestScore = 0L
            for (i in boxes.indices) {
                if (boxes[i].score > bestScore) {
                    bestScore = boxes[i].score
                    best = i
                }
            }
            if (best < 0) break
            val box = boxes[best]
            val shift = box.channel
            // Counting sort on the chosen channel (stable, no boxing)
            val colors = box.colors
            val start = IntArray(257)
            for (c in colors) start[((c shr shift) and 0xFF) + 1]++
            for (k in 1..256) start[k] += start[k - 1]
            val sorted = IntArray(colors.size)
            for (c in colors) sorted[start[(c shr shift) and 0xFF]++] = c
            val mid = sorted.size / 2
            boxes[best] = Box(sorted.copyOfRange(0, mid))
            boxes += Box(sorted.copyOfRange(mid, sorted.size))
        }
        return IntArray(boxes.size) { i ->
            val box = boxes[i].colors
            var r = 0L; var g = 0L; var b = 0L
            for (c in box) {
                r += (c shr 16) and 0xFF; g += (c shr 8) and 0xFF; b += c and 0xFF
            }
            val k = maxOf(1, box.size)
            ((r / k).toInt() shl 16) or ((g / k).toInt() shl 8) or (b / k).toInt()
        }
    }

    private fun ditherToPalette(pixels: IntArray, palette: IntArray, seeThroughIndex: Int = -1): ByteArray {
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
                if (seeThroughIndex >= 0 && (c ushr 24) < 128) {
                    // See-through: no color, and no error passed on to neighbours
                    out[y * width + x] = seeThroughIndex.toByte()
                    continue
                }
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
