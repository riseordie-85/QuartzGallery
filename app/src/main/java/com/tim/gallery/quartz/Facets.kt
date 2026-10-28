package com.tim.gallery.quartz

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Quartz patterns: cut-crystal surfaces lit from the top left. Each facet is a flat triangle tilted in 3D; facets
 * facing the light get a white veil, facets facing away a dark one, so the pattern takes on whatever color or
 * gradient is underneath (gold stays gold, amethyst stays purple).
 *
 * One instance per thread that draws (its buffers are reused between draws).
 *
 * Everything is placed on a grid with fixed "random" numbers per grid point, so the cover screen's half lines up with
 * the unfolded screen, and the same wallpaper always looks the same.
 */
internal class Facets {

    // Light from the upper left, a little in front of the screen
    private val LX = -0.33f
    private val LY = -0.68f
    private val LZ = 0.65f

    private var verts = FloatArray(0)
    private var colors = IntArray(0)
    private var n = 0
    private var maxAlpha = 0f
    private var seed = 0

    // White, so the facet colors come through unchanged even where the paint color is mixed in with them
    private val paint = Paint().apply { isDither = true; color = Color.WHITE }
    private val sparkle = Paint(Paint.ANTI_ALIAS_FLAG)

    /** Draws [style] over a [w]×[h] virtual canvas, only where [clip] is on screen. */
    fun draw(canvas: Canvas, w: Float, h: Float, clip: Rect, c: WallConfig, cellScale: Float = 1f) {
        val cell = h * (0.03f + c.patternSize.coerceIn(0f, 1f) * 0.09f) * cellScale
        if (cell < 4f) return
        n = 0
        maxAlpha = c.patternStrength.coerceIn(0f, 1f)
        seed = c.patternSeed
        val x0 = clip.left - cell * 2
        val x1 = clip.right + cell * 2
        val y0 = clip.top - cell * 2
        val y1 = clip.bottom + cell * 2
        when (c.pattern) {
            PatternStyle.CRYSTAL -> crystal(cell, x0, x1, y0, y1)
            PatternStyle.RIPPLE -> ripple(cell, h, x0, x1, y0, y1)
            PatternStyle.DIAMONDS -> diamonds(cell, x0, x1, y0, y1)
            PatternStyle.PYRAMIDS -> pyramids(cell, x0, x1, y0, y1)
            PatternStyle.SHARDS -> shards(cell, x0, x1, y0, y1)
            else -> return
        }
        if (n > 0) {
            canvas.drawVertices(Canvas.VertexMode.TRIANGLES, n * 2, verts, 0, null, 0, colors, 0, null, 0, 0, paint)
        }
        if (c.sparkles) sparkles(canvas, cell, x0, x1, y0, y1)
    }

    // ---- Patterns ----

    /** Low-poly crystal: a jittered grid with random heights and diagonal ridges, like crumpled gold foil. */
    private fun crystal(cell: Float, x0: Float, x1: Float, y0: Float, y1: Float) {
        val i0 = floor(x0 / cell).toInt()
        val i1 = ceil(x1 / cell).toInt()
        val j0 = floor(y0 / cell).toInt()
        val j1 = ceil(y1 / cell).toInt()
        fun px(i: Int, j: Int) = (i + (rand(i, j, 1) - 0.5f) * 0.7f) * cell
        fun py(i: Int, j: Int) = (j + (rand(i, j, 2) - 0.5f) * 0.7f) * cell
        fun pz(i: Int, j: Int) = cell * (0.5f * rand(i, j, 3) + 0.7f * tri((i + 2f * j) / 5f))
        for (j in j0 until j1) for (i in i0 until i1) {
            val ax = px(i, j); val ay = py(i, j); val az = pz(i, j)
            val bx = px(i + 1, j); val by = py(i + 1, j); val bz = pz(i + 1, j)
            val cx = px(i, j + 1); val cy = py(i, j + 1); val cz = pz(i, j + 1)
            val dx = px(i + 1, j + 1); val dy = py(i + 1, j + 1); val dz = pz(i + 1, j + 1)
            if (rand(i, j, 4) < 0.5f) {
                tri3(ax, ay, az, bx, by, bz, dx, dy, dz)
                tri3(ax, ay, az, dx, dy, dz, cx, cy, cz)
            } else {
                tri3(ax, ay, az, bx, by, bz, cx, cy, cz)
                tri3(bx, by, bz, dx, dy, dz, cx, cy, cz)
            }
        }
    }

    /** Rows of small facets bent into one long wave, like light rippling through blue quartz. */
    private fun ripple(cell: Float, h: Float, x0: Float, x1: Float, y0: Float, y1: Float) {
        val rowH = cell * 0.866f
        val amp = h * 0.08f
        val length = h * 0.9f
        val i0 = floor(x0 / cell).toInt() - 1
        val i1 = ceil(x1 / cell).toInt() + 1
        // The wave moves rows up and down, so look a little further above and below
        val j0 = floor((y0 - amp) / rowH).toInt() - 1
        val j1 = ceil((y1 + amp) / rowH).toInt() + 1
        fun px(i: Int, j: Int) = (i + if (j % 2 != 0) 0.5f else 0f) * cell
        fun wave(x: Float) = amp * sin(2f * PI.toFloat() * x / length)
        fun py(i: Int, j: Int) = j * rowH + wave(px(i, j))
        // Alternate heights in a checkerboard, so neighboring facets tilt different ways
        fun pz(i: Int, j: Int) = cell * 0.32f * ((i + j) and 1) + cell * 0.22f * rand(i, j, 5)
        for (j in j0 until j1) for (i in i0 until i1) {
            val even = j % 2 == 0
            // Two triangles per grid step: one pointing up, one pointing down
            val (bi, ci) = if (even) i to i else i + 1 to i + 1
            tri3(px(i, j), py(i, j), pz(i, j), px(i + 1, j), py(i + 1, j), pz(i + 1, j), px(bi, j + 1), py(bi, j + 1), pz(bi, j + 1))
            if (even) {
                tri3(px(i + 1, j), py(i + 1, j), pz(i + 1, j), px(i + 1, j + 1), py(i + 1, j + 1), pz(i + 1, j + 1), px(i, j + 1), py(i, j + 1), pz(i, j + 1))
            } else {
                tri3(px(i, j), py(i, j), pz(i, j), px(ci, j + 1), py(ci, j + 1), pz(ci, j + 1), px(ci - 1, j + 1), py(ci - 1, j + 1), pz(ci - 1, j + 1))
            }
        }
    }

    /** Diamonds: a lattice of low four-sided pyramids, like a cut gem's crown. */
    private fun diamonds(cell: Float, x0: Float, x1: Float, y0: Float, y1: Float) {
        val half = cell / 2f
        val i0 = floor(x0 / cell).toInt() - 1
        val i1 = ceil(x1 / cell).toInt() + 1
        val j0 = floor(y0 / half).toInt() - 1
        val j1 = ceil(y1 / half).toInt() + 1
        val peak = cell * 0.32f
        for (j in j0..j1) for (i in i0..i1) {
            val cx = (i + if (j % 2 != 0) 0.5f else 0f) * cell
            val cy = j * half
            val top = cy - half
            val bottom = cy + half
            val left = cx - half
            val right = cx + half
            tri3(cx, top, 0f, right, cy, 0f, cx, cy, peak)
            tri3(right, cy, 0f, cx, bottom, 0f, cx, cy, peak)
            tri3(cx, bottom, 0f, left, cy, 0f, cx, cy, peak)
            tri3(left, cy, 0f, cx, top, 0f, cx, cy, peak)
        }
    }

    /** Triangular pyramids on a triangle grid: three facets each, so they catch the light like a geodesic sphere. */
    private fun pyramids(cell: Float, x0: Float, x1: Float, y0: Float, y1: Float) {
        val rowH = cell * 0.866f
        val i0 = floor(x0 / cell).toInt() - 1
        val i1 = ceil(x1 / cell).toInt() + 1
        val j0 = floor(y0 / rowH).toInt() - 1
        val j1 = ceil(y1 / rowH).toInt() + 1
        val peak = cell * 0.42f
        fun px(i: Int, j: Int) = (i + if (j % 2 != 0) 0.5f else 0f) * cell
        fun py(j: Int) = j * rowH
        fun pyramid(ax: Float, ay: Float, bx: Float, by: Float, cx: Float, cy: Float) {
            val mx = (ax + bx + cx) / 3f
            val my = (ay + by + cy) / 3f
            tri3(ax, ay, 0f, bx, by, 0f, mx, my, peak)
            tri3(bx, by, 0f, cx, cy, 0f, mx, my, peak)
            tri3(cx, cy, 0f, ax, ay, 0f, mx, my, peak)
        }
        for (j in j0..j1) for (i in i0..i1) {
            val odd = j % 2 != 0
            // Up-pointing and down-pointing triangle for this step
            if (!odd) {
                pyramid(px(i, j), py(j), px(i + 1, j), py(j), px(i, j + 1), py(j + 1))
                pyramid(px(i + 1, j), py(j), px(i + 1, j + 1), py(j + 1), px(i, j + 1), py(j + 1))
            } else {
                pyramid(px(i, j), py(j), px(i + 1, j + 1), py(j + 1), px(i, j + 1), py(j + 1))
                pyramid(px(i, j), py(j), px(i + 1, j), py(j), px(i + 1, j + 1), py(j + 1))
            }
        }
    }

    /** Smoky quartz shards: tall columns of stacked chevrons, each a slightly different shade. */
    private fun shards(cell: Float, x0: Float, x1: Float, y0: Float, y1: Float) {
        val colW = cell
        val band = cell * 1.4f
        val dip = cell * 0.55f
        val i0 = floor(x0 / colW).toInt() - 1
        val i1 = ceil(x1 / colW).toInt() + 1
        for (i in i0..i1) {
            val left = i * colW
            val right = left + colW
            val mid = left + colW / 2f
            val shift = rand(i, 0, 6) * band
            val k0 = floor((y0 - shift - dip) / band).toInt() - 1
            val k1 = ceil((y1 - shift) / band).toInt() + 1
            for (k in k0..k1) {
                val top = shift + k * band
                val bottom = top + band
                val shade = (rand(i, k, 7) - 0.5f) * 1.2f + (rand(i, 0, 8) - 0.5f) * 0.5f
                val color = veil(shade * 0.7f)
                // A chevron band: the middle sits higher than the edges
                flat(left, top + dip, mid, top, mid, bottom, color)
                flat(left, top + dip, mid, bottom, left, bottom + dip, color)
                // The right half turns a little away from the light
                val shaded = veil(shade * 0.7f - 0.25f)
                flat(mid, top, right, top + dip, right, bottom + dip, shaded)
                flat(mid, top, right, bottom + dip, mid, bottom, shaded)
            }
        }
    }

    /** Small glints scattered over the pattern, like flecks in the stone. */
    private fun sparkles(canvas: Canvas, cell: Float, x0: Float, x1: Float, y0: Float, y1: Float) {
        val i0 = floor(x0 / cell).toInt()
        val i1 = ceil(x1 / cell).toInt()
        val j0 = floor(y0 / cell).toInt()
        val j1 = ceil(y1 / cell).toInt()
        val base = max(1f, cell / 70f)
        for (j in j0..j1) for (i in i0..i1) {
            if (rand(i, j, 9) > 0.3f) continue
            val x = (i + rand(i, j, 10)) * cell
            val y = (j + rand(i, j, 11)) * cell
            val r = base * (0.6f + 1.8f * rand(i, j, 12) * rand(i, j, 13))
            sparkle.color = (((0.25f + 0.6f * rand(i, j, 14)) * 255).toInt() shl 24) or 0xFFFFFF
            canvas.drawCircle(x, y, r, sparkle)
        }
    }

    // ---- Geometry ----

    /** One triangle in 3D (z toward you), shaded by how much it faces the light. */
    private fun tri3(ax: Float, ay: Float, az: Float, bx: Float, by: Float, bz: Float, cx: Float, cy: Float, cz: Float) {
        // Normal = (b - a) × (c - a), flipped to face the viewer
        val ux = bx - ax; val uy = by - ay; val uz = bz - az
        val vx = cx - ax; val vy = cy - ay; val vz = cz - az
        var nx = uy * vz - uz * vy
        var ny = uz * vx - ux * vz
        var nz = ux * vy - uy * vx
        if (nz < 0f) { nx = -nx; ny = -ny; nz = -nz }
        val len = sqrt(nx * nx + ny * ny + nz * nz)
        if (len < 1e-4f) return
        // Compared with a flat facet, so flat areas keep the color underneath
        val lit = (nx * LX + ny * LY + nz * LZ) / len - LZ
        flat(ax, ay, bx, by, cx, cy, veil(lit * 2.2f))
    }

    /** A white veil for lit facets, a dark one for shaded ones. [shade] runs about −1 to 1. */
    private fun veil(shade: Float): Int {
        val s = shade.coerceIn(-1f, 1f)
        val a = (abs(s) * maxAlpha * 255).toInt().coerceIn(0, 255)
        return if (s >= 0) (a shl 24) or 0xFFFFFF else (a shl 24) or 0x05040A
    }

    private fun flat(ax: Float, ay: Float, bx: Float, by: Float, cx: Float, cy: Float, color: Int) {
        ensure(n + 3)
        var v = n * 2
        verts[v++] = ax; verts[v++] = ay
        verts[v++] = bx; verts[v++] = by
        verts[v++] = cx; verts[v] = cy
        colors[n] = color
        colors[n + 1] = color
        colors[n + 2] = color
        n += 3
    }

    private fun ensure(count: Int) {
        if (colors.size >= count) return
        val size = max(count, colors.size * 2).coerceAtLeast(3000)
        verts = verts.copyOf(size * 2)
        colors = colors.copyOf(size)
    }

    /** A fixed "random" number 0–1 for a grid point, so patterns never shimmer between redraws or screens. */
    private fun rand(i: Int, j: Int, k: Int): Float {
        var x = i * 374761393 + j * 668265263 + k * 1274126177 + seed * 1442695041
        x = (x xor (x ushr 13)) * 1274126177
        x = x xor (x ushr 16)
        return (x and 0xFFFFFF) / 16777215f
    }

    /** A triangle wave 0–1–0 with period 1. */
    private fun tri(t: Float): Float {
        val f = t - floor(t)
        return 1f - abs(f * 2f - 1f)
    }
}
