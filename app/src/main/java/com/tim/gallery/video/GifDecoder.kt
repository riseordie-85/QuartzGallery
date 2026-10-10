package com.tim.gallery.video

/**
 * Reads a GIF frame by frame. Each frame comes out as the full picture you'd see at that
 * moment (frames stacked and cleared the way browsers and Android show them), so it can
 * be edited like a normal photo and written back with [GifEncoder].
 *
 * Android has no public way to get at a GIF's individual frames, hence this small reader.
 */
class GifDecoder private constructor(private val data: ByteArray) {

    private class FrameInfo(
        /** Where the frame's image descriptor (0x2C) starts. */
        val at: Int,
        /** What happens to this frame before the next one: 0/1 leave, 2 clear, 3 restore. */
        val disposal: Int,
        val delayCs: Int,
        /** Palette index that's see-through, or -1. */
        val transparent: Int,
    )

    val width: Int
    val height: Int
    private val globalTable: IntArray?
    private val frames = ArrayList<FrameInfo>()

    /** Number of frames (1 for a still GIF). */
    val frameCount: Int get() = frames.size

    /** True if any frame has see-through pixels or clears part of the picture. */
    val mayHaveTransparency: Boolean

    /** Total play time of one loop, in milliseconds. */
    val durationMs: Long get() = frames.sumOf { delayMs(it.delayCs).toLong() }

    init {
        require(data.size >= 13 && data[0] == 'G'.code.toByte() && data[1] == 'I'.code.toByte() && data[2] == 'F'.code.toByte()) { "Not a GIF" }
        width = u16(6)
        height = u16(8)
        require(width > 0 && height > 0 && width.toLong() * height <= MAX_PIXELS) { "Bad GIF size" }
        val packed = u8(10)
        var p = 13
        globalTable = if (packed and 0x80 != 0) {
            val n = 2 shl (packed and 7)
            readTable(p, n).also { p += n * 3 }
        } else {
            null
        }
        var disposal = 0
        var delay = 0
        var transparent = -1
        var seeThrough = false
        scan@ while (p < data.size) {
            when (u8(p)) {
                0x21 -> { // extension
                    if (p + 1 >= data.size) break@scan
                    val label = u8(p + 1)
                    p += 2
                    if (label == 0xF9 && p + 4 < data.size && u8(p) >= 4) {
                        val pk = u8(p + 1)
                        disposal = (pk shr 2) and 7
                        delay = u16(p + 2)
                        transparent = if (pk and 1 != 0) u8(p + 4) else -1
                    }
                    p = skipSubBlocks(p)
                }
                0x2C -> { // image
                    if (p + 10 > data.size) break@scan
                    frames += FrameInfo(p, disposal, delay, transparent)
                    if (transparent >= 0 || disposal == 2 || disposal == 3) seeThrough = true
                    // A frame that doesn't cover the whole picture leaves see-through gaps at first
                    if (frames.size == 1 && (u16(p + 1) != 0 || u16(p + 3) != 0 || u16(p + 5) < width || u16(p + 7) < height)) seeThrough = true
                    val pk = u8(p + 9)
                    p += 10
                    if (pk and 0x80 != 0) p += (2 shl (pk and 7)) * 3
                    p += 1 // LZW minimum code size
                    p = skipSubBlocks(p)
                    disposal = 0
                    delay = 0
                    transparent = -1
                }
                0x3B -> break@scan // end
                else -> break@scan // trailing junk
            }
        }
        mayHaveTransparency = seeThrough
    }

    /** Steps through the frames in order. [pixels] is reused for every frame. */
    inner class Reader {
        /** The current frame, ARGB, [width] × [height], row by row. */
        val pixels = IntArray(width * height)

        /** How long the current frame shows, in milliseconds. */
        var delayMs = 0
            private set

        /** Index of the current frame (-1 before the first [next]). */
        var index = -1
            private set

        private var saved: IntArray? = null
        private var indices = ByteArray(0)
        private val prefix = ShortArray(MAX_CODES)
        private val suffix = ByteArray(MAX_CODES)
        private val stack = ByteArray(MAX_CODES + 1)

        /** Moves to the next frame. False when there are no more. */
        fun next(): Boolean {
            if (index + 1 >= frames.size) return false
            // Tidy up after the previous frame
            if (index >= 0) {
                val prev = frames[index]
                when (prev.disposal) {
                    2 -> clearRect(prev.at)
                    3 -> saved?.copyInto(pixels)
                }
            }
            index++
            val f = frames[index]
            if (f.disposal == 3) {
                val s = saved ?: IntArray(pixels.size).also { saved = it }
                pixels.copyInto(s)
            }
            draw(f)
            delayMs = delayMs(f.delayCs)
            return true
        }

        private fun clearRect(at: Int) {
            val fx = u16(at + 1)
            val fy = u16(at + 3)
            val x1 = minOf(width, fx + u16(at + 5))
            val y1 = minOf(height, fy + u16(at + 7))
            for (y in fy until y1) {
                val row = y * width
                for (x in fx until x1) pixels[row + x] = 0
            }
        }

        private fun draw(f: FrameInfo) {
            val at = f.at
            val fx = u16(at + 1)
            val fy = u16(at + 3)
            val fw = u16(at + 5)
            val fh = u16(at + 7)
            val pk = u8(at + 9)
            var p = at + 10
            val table = if (pk and 0x80 != 0) {
                val n = 2 shl (pk and 7)
                readTable(p, n).also { p += n * 3 }
            } else {
                globalTable ?: DEFAULT_TABLE
            }
            val count = fw * fh
            if (count <= 0) return
            if (indices.size < count) indices = ByteArray(count)
            val decoded = lzw(p, indices, count)
            val interlaced = pk and 0x40 != 0
            val trans = f.transparent
            var k = 0
            for (r in 0 until fh) {
                val y = fy + (if (interlaced) interlacedRow(r, fh) else r)
                if (y >= height) {
                    k += fw
                    continue
                }
                val row = y * width
                for (c in 0 until fw) {
                    if (k >= decoded) return // the rest of a damaged frame stays as it was
                    val idx = indices[k++].toInt() and 0xFF
                    val x = fx + c
                    if (x >= width || idx == trans) continue
                    if (idx < table.size) pixels[row + x] = table[idx]
                }
            }
        }

        /** Decodes up to [count] palette indices into [out]. Returns how many it got. */
        private fun lzw(start: Int, out: ByteArray, count: Int): Int {
            var p = start
            if (p >= data.size) return 0
            val minSize = u8(p++)
            if (minSize < 1 || minSize > 11) return 0
            val clear = 1 shl minSize
            val eoi = clear + 1
            for (i in 0 until clear) {
                prefix[i] = 0
                suffix[i] = i.toByte()
            }
            var codeSize = minSize + 1
            var codeMask = (1 shl codeSize) - 1
            var avail = clear + 2
            var old = -1
            var first = 0
            var datum = 0
            var bits = 0
            var blockLeft = 0
            var n = 0
            loop@ while (n < count) {
                while (bits < codeSize) {
                    if (blockLeft == 0) {
                        if (p >= data.size) break@loop
                        blockLeft = u8(p++)
                        if (blockLeft == 0) break@loop
                    }
                    if (p >= data.size) break@loop
                    datum = datum or (u8(p++) shl bits)
                    bits += 8
                    blockLeft--
                }
                val code = datum and codeMask
                datum = datum ushr codeSize
                bits -= codeSize
                if (code == clear) {
                    codeSize = minSize + 1
                    codeMask = (1 shl codeSize) - 1
                    avail = clear + 2
                    old = -1
                    continue
                }
                if (code == eoi) break
                if (old == -1) {
                    if (code >= clear) break // damaged
                    out[n++] = suffix[code]
                    old = code
                    first = code
                    continue
                }
                if (code > avail) break // damaged
                var top = 0
                var c = code
                if (code == avail) {
                    stack[top++] = first.toByte()
                    c = old
                }
                while (c >= clear) {
                    if (top >= MAX_CODES) break@loop
                    stack[top++] = suffix[c]
                    c = prefix[c].toInt()
                }
                first = suffix[c].toInt() and 0xFF
                stack[top++] = first.toByte()
                if (avail < MAX_CODES) {
                    prefix[avail] = old.toShort()
                    suffix[avail] = first.toByte()
                    avail++
                    if (avail and codeMask == 0 && avail < MAX_CODES) {
                        codeSize++
                        codeMask += avail
                    }
                }
                old = code
                while (top > 0 && n < count) out[n++] = stack[--top]
            }
            return n
        }
    }

    fun reader(): Reader = Reader()

    // ------------------------------------------------------------ helpers

    private fun u8(i: Int): Int = if (i < data.size) data[i].toInt() and 0xFF else 0
    private fun u16(i: Int): Int = u8(i) or (u8(i + 1) shl 8)

    private fun readTable(at: Int, n: Int): IntArray = IntArray(n) { i ->
        val o = at + i * 3
        (0xFF shl 24) or (u8(o) shl 16) or (u8(o + 1) shl 8) or u8(o + 2)
    }

    private fun skipSubBlocks(start: Int): Int {
        var q = start
        while (q < data.size) {
            val n = u8(q)
            q += 1 + n
            if (n == 0) break
        }
        return q
    }

    companion object {
        private const val MAX_CODES = 4096
        private const val MAX_PIXELS = 40_000_000L
        private val DEFAULT_TABLE = IntArray(256) { (0xFF shl 24) or (it shl 16) or (it shl 8) or it }

        /** Opens a GIF, or null if it isn't one / can't be read. */
        fun open(data: ByteArray): GifDecoder? =
            runCatching { GifDecoder(data) }.getOrNull()?.takeIf { it.frameCount > 0 }

        /** Browsers show very short delays (0 or 10 ms) as 100 ms; so does this. */
        fun delayMs(delayCs: Int): Int = if (delayCs <= 1) 100 else delayCs * 10

        /** Which picture row the [r]-th stored row of an interlaced frame belongs to. */
        internal fun interlacedRow(r: Int, h: Int): Int {
            val pass1 = (h + 7) / 8
            val pass2 = (h + 3) / 8
            val pass3 = (h + 1) / 4
            return when {
                r < pass1 -> r * 8
                r < pass1 + pass2 -> (r - pass1) * 8 + 4
                r < pass1 + pass2 + pass3 -> (r - pass1 - pass2) * 4 + 2
                else -> (r - pass1 - pass2 - pass3) * 2 + 1
            }
        }
    }
}
