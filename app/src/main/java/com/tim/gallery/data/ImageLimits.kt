package com.tim.gallery.data

import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.GLES20
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * How big a picture can be here. Long panoramas keep every pixel (up to the GPU's biggest texture
 * side, so they can still be drawn and rendered), while the total stays within [MAX_PIXELS] so
 * memory stays safe on every phone.
 */
object ImageLimits {
    /** Most pixels an edited photo (or a zoomed-in one) is decoded at: 4096 × 4096, about 17 MP. */
    const val MAX_PIXELS = 4096L * 4096L

    /** Most pixels for the editor's live preview (it's redrawn on every change). */
    const val PREVIEW_PIXELS = 3_000_000L

    /** The GPU's biggest texture side (asked once; 4096 when it can't be found out). */
    val maxTexture: Int by lazy { queryMaxTexture().coerceIn(4096, 16384) }

    /** Longest side a picture can be decoded, edited and saved at. */
    val maxSide: Int get() = maxTexture

    /** The scale (at most 1) that brings [w]×[h] within [side] on a side and [pixels] in all. */
    fun fitScale(w: Int, h: Int, side: Int = maxSide, pixels: Long = MAX_PIXELS): Float {
        val ww = max(1, w)
        val hh = max(1, h)
        return min(1f, min(side.toFloat() / max(ww, hh), sqrt(pixels.toDouble() / (ww.toLong() * hh)).toFloat()))
    }

    private fun queryMaxTexture(): Int = runCatching {
        val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val version = IntArray(2)
        if (!EGL14.eglInitialize(display, version, 0, version, 1)) return@runCatching 4096
        try {
            val attribs = intArrayOf(
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
                EGL14.EGL_NONE,
            )
            val configs = arrayOfNulls<EGLConfig>(1)
            val count = IntArray(1)
            if (!EGL14.eglChooseConfig(display, attribs, 0, configs, 0, 1, count, 0) || count[0] == 0) return@runCatching 4096
            val config = configs[0]
            val ctx = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)
            val surface = EGL14.eglCreatePbufferSurface(display, config, intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0)
            try {
                EGL14.eglMakeCurrent(display, surface, surface, ctx)
                val out = IntArray(1)
                GLES20.glGetIntegerv(GLES20.GL_MAX_TEXTURE_SIZE, out, 0)
                if (out[0] > 0) out[0] else 4096
            } finally {
                EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                EGL14.eglDestroySurface(display, surface)
                EGL14.eglDestroyContext(display, ctx)
            }
        } finally {
            EGL14.eglTerminate(display)
        }
    }.getOrDefault(4096)
}
