package com.tim.gallery.edit

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorSpace
import android.graphics.HardwareRenderer
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.RenderEffect
import android.graphics.RenderNode
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.hardware.HardwareBuffer
import android.media.ImageReader
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

data class FSize(val width: Float, val height: Float) {
    val shortSide get() = min(width, height)
}

/**
 * Turns an [EditState] into pixels. The same drawing code runs for the on-screen preview
 * (on a smaller copy of the photo) and for saving (full size, rendered off-screen on the GPU),
 * so what you see is what you get.
 *
 * Coordinates: "full units" are pixels of the full-size source photo. Geometry places the photo
 * in a "frame" (its upright, straightened size); the crop box picks a part of the frame; the
 * result is the "output", which stickers and text are positioned in.
 */
object EditRenderer {

    /** Longest side of a saved edit. Keeps memory and GPU texture sizes safe on every phone. */
    const val MAX_OUTPUT_PX = 4096

    // ------------------------------------------------------------ geometry

    fun orientedSize(g: Geometry, w: Float, h: Float): FSize =
        if (g.quarterTurns % 2 != 0) FSize(h, w) else FSize(w, h)

    fun frameSize(g: Geometry, w: Float, h: Float): FSize {
        val o = orientedSize(g, w, h)
        if (g.fill == FillMode.CROP_IN || g.angle == 0f) return o
        val rad = Math.toRadians(abs(g.angle).toDouble())
        val c = cos(rad).toFloat()
        val s = sin(rad).toFloat()
        return FSize(o.width * c + o.height * s, o.width * s + o.height * c)
    }

    /** In CROP_IN mode the tilted photo is enlarged just enough to cover its frame. */
    private fun coverScale(g: Geometry, o: FSize): Float {
        if (g.fill != FillMode.CROP_IN || g.angle == 0f) return 1f
        val rad = Math.toRadians(abs(g.angle).toDouble())
        val c = cos(rad).toFloat()
        val s = sin(rad).toFloat()
        val ratio = max(o.width / o.height, o.height / o.width)
        return c + ratio * s
    }

    /** Maps full-size source pixels into the frame. */
    fun sourceToFrame(g: Geometry, w: Float, h: Float): Matrix {
        val o = orientedSize(g, w, h)
        val f = frameSize(g, w, h)
        val k = coverScale(g, o)
        return Matrix().apply {
            postTranslate(-w / 2f, -h / 2f)
            postRotate(90f * g.quarterTurns)
            if (g.flipped) postScale(-1f, 1f)
            postScale(k, k)
            postRotate(g.angle)
            postTranslate(f.width / 2f, f.height / 2f)
        }
    }

    fun outputSize(g: Geometry, w: Float, h: Float): FSize {
        val f = frameSize(g, w, h)
        return FSize(
            max(1f, g.crop.width * f.width),
            max(1f, g.crop.height * f.height),
        )
    }

    /** The chosen aspect ratio in pixels, or null for free cropping. */
    fun resolvedRatio(g: Geometry, w: Float, h: Float): Float? = when (g.aspect.ratio) {
        null -> null
        -1f -> orientedSize(g, w, h).let { it.width / it.height }
        else -> g.aspect.ratio
    }

    /** Largest centered crop with the given pixel ratio (or everything when free). */
    fun centeredCrop(ratio: Float?, frame: FSize): NRect {
        if (ratio == null || frame.width <= 0f || frame.height <= 0f) return NRect.FULL
        val rn = ratio * frame.height / frame.width
        val (cw, ch) = if (rn >= 1f) 1f to 1f / rn else rn to 1f
        val l = (1f - cw) / 2f
        val t = (1f - ch) / 2f
        return NRect(l, t, l + cw, t + ch)
    }

    // ------------------------------------------------------------ drawing

    // Paints are created per draw: the preview (main thread) and saving (background) may draw at once

    /**
     * Draws the straightened, color-adjusted photo in frame coordinates
     * (0..frameWidth, 0..frameHeight). The caller should clip to the frame.
     * [src] may be a smaller copy of the full photo ([fullW] × [fullH]).
     */
    fun drawFrame(canvas: Canvas, state: EditState, src: Bitmap, fullW: Float, fullH: Float, color: ColorShader) {
        val g = state.geometry
        val f = frameSize(g, fullW, fullH)
        if (g.fill == FillMode.BACKGROUND && Color.alpha(g.fillColor) > 0) {
            val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).also { it.color = g.fillColor }
            canvas.drawRect(0f, 0f, f.width, f.height, fillPaint)
        }
        canvas.save()
        canvas.concat(sourceToFrame(g, fullW, fullH))
        canvas.scale(fullW / src.width, fullH / src.height)
        color.bind(state, src)
        canvas.drawRect(0f, 0f, src.width.toFloat(), src.height.toFloat(), color.paint)
        canvas.restore()
    }

    /** Draws the finished picture (cropped, with vignette, stickers and text) in output coordinates. */
    fun drawOutput(
        canvas: Canvas,
        state: EditState,
        src: Bitmap,
        fullW: Float,
        fullH: Float,
        color: ColorShader,
        stickers: (String) -> Bitmap?,
    ) {
        val g = state.geometry
        val f = frameSize(g, fullW, fullH)
        val out = outputSize(g, fullW, fullH)
        canvas.saveLayer(0f, 0f, out.width, out.height, null)

        canvas.save()
        canvas.translate(-g.crop.left * f.width, -g.crop.top * f.height)
        drawFrame(canvas, state, src, fullW, fullH, color)
        canvas.restore()

        val v = (state.adjust(Adjust.VIGNETTE) + state.filter.vignette * state.filterStrength).coerceIn(0f, 1f)
        if (v > 0f) {
            val vignettePaint = Paint(Paint.ANTI_ALIAS_FLAG)
            vignettePaint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_ATOP)
            vignettePaint.shader = RadialGradient(
                out.width / 2f,
                out.height / 2f,
                hypot(out.width, out.height) / 2f,
                intArrayOf(0, 0, Color.argb((v * 235).roundToInt(), 0, 0, 0)),
                floatArrayOf(0f, 0.45f, 1f),
                Shader.TileMode.CLAMP,
            )
            canvas.drawRect(0f, 0f, out.width, out.height, vignettePaint)
        }

        for (layer in state.layers) drawLayer(canvas, layer, out, stickers)
        canvas.restore()
    }

    /** Just the color treatment on a bitmap at its own size (filter thumbnails). */
    fun drawColorOnly(canvas: Canvas, state: EditState, src: Bitmap, color: ColorShader) {
        color.bind(state, src)
        canvas.drawRect(0f, 0f, src.width.toFloat(), src.height.toFloat(), color.paint)
    }

    // ------------------------------------------------------------ layers

    /** Width and height of a layer in output pixels (before rotation). */
    fun layerSize(layer: Layer, out: FSize, stickers: (String) -> Bitmap?): FSize = when (layer) {
        is StickerLayer -> {
            val w = layer.scale * out.shortSide
            val bmp = stickers(layer.uri)
            val aspect = if (bmp != null && bmp.height > 0) bmp.width.toFloat() / bmp.height else 1f
            FSize(w, w / aspect)
        }
        is TextLayer -> buildText(layer, out.shortSide).let { FSize(it.width, it.height) }
    }

    private fun drawLayer(canvas: Canvas, layer: Layer, out: FSize, stickers: (String) -> Bitmap?) {
        canvas.save()
        canvas.translate(layer.cx * out.width, layer.cy * out.height)
        canvas.rotate(layer.rotation)
        when (layer) {
            is StickerLayer -> {
                val bmp = stickers(layer.uri)
                if (bmp != null) {
                    val s = layerSize(layer, out, stickers)
                    canvas.drawBitmap(
                        bmp,
                        null,
                        RectF(-s.width / 2f, -s.height / 2f, s.width / 2f, s.height / 2f),
                        Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG),
                    )
                }
            }
            is TextLayer -> drawText(canvas, layer, out.shortSide)
        }
        canvas.restore()
    }

    class TextRender(
        val layout: StaticLayout,
        val paint: TextPaint,
        val size: Float,
        val pad: Float,
        val width: Float,
        val height: Float,
    )

    fun buildText(layer: TextLayer, shortSide: Float): TextRender {
        val size = (layer.scale * shortSide * 0.1f).coerceAtLeast(4f)
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = size
            typeface = layer.font.typeface
            color = layer.color
        }
        val text = layer.text.ifEmpty { " " }
        val widest = text.split('\n').maxOf { paint.measureText(it) }
        val layoutWidth = ceil(widest.toDouble()).toInt().coerceAtLeast(1) + 1
        val alignment = when (layer.align) {
            TextAlignOption.LEFT -> Layout.Alignment.ALIGN_NORMAL
            TextAlignOption.CENTER -> Layout.Alignment.ALIGN_CENTER
            TextAlignOption.RIGHT -> Layout.Alignment.ALIGN_OPPOSITE
        }
        val layout = StaticLayout.Builder.obtain(text, 0, text.length, paint, layoutWidth)
            .setAlignment(alignment)
            .setIncludePad(false)
            .build()
        val pad = size * 0.35f
        return TextRender(layout, paint, size, pad, layoutWidth + 2 * pad, layout.height + 2 * pad)
    }

    /** Black or white, whichever reads better on [color]. */
    fun contrastColor(color: Int): Int = if (Color.luminance(color) > 0.5f) Color.BLACK else Color.WHITE

    private fun drawText(canvas: Canvas, layer: TextLayer, shortSide: Float) {
        val t = buildText(layer, shortSide)
        canvas.translate(-t.width / 2f, -t.height / 2f)
        val paint = t.paint
        when (layer.style) {
            TextStyleOption.PLAIN -> {
                canvas.translate(t.pad, t.pad)
                paint.setShadowLayer(t.size * 0.08f, 0f, t.size * 0.03f, 0x99000000.toInt())
                t.layout.draw(canvas)
            }
            TextStyleOption.OUTLINE -> {
                canvas.translate(t.pad, t.pad)
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = t.size * 0.14f
                paint.strokeJoin = Paint.Join.ROUND
                paint.color = contrastColor(layer.color)
                t.layout.draw(canvas)
                paint.style = Paint.Style.FILL
                paint.color = layer.color
                t.layout.draw(canvas)
            }
            TextStyleOption.BUBBLE -> {
                val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).also { it.color = layer.color }
                val r = t.size * 0.45f
                canvas.drawRoundRect(0f, 0f, t.width, t.height, r, r, fillPaint)
                canvas.translate(t.pad, t.pad)
                paint.color = contrastColor(layer.color)
                t.layout.draw(canvas)
            }
        }
    }

    // ------------------------------------------------------------ off-screen rendering

    /** Renders the edit at full size on the GPU and returns a normal bitmap. Call off the main thread. */
    fun export(state: EditState, src: Bitmap, stickers: (String) -> Bitmap?): Bitmap {
        val fullW = src.width.toFloat()
        val fullH = src.height.toFloat()
        val out = outputSize(state.geometry, fullW, fullH)
        val k = min(1f, MAX_OUTPUT_PX / max(out.width, out.height))
        val w = (out.width * k).roundToInt().coerceAtLeast(1)
        val h = (out.height * k).roundToInt().coerceAtLeast(1)
        return renderOffscreen(w, h) { canvas ->
            canvas.scale(w / out.width, h / out.height)
            drawOutput(canvas, state, src, fullW, fullH, ColorShader(), stickers)
        }
    }

    /** A blurred copy, for the "blur background" cutout option. */
    fun blurred(src: Bitmap): Bitmap {
        val r = max(src.width, src.height) / 45f
        return renderOffscreen(src.width, src.height, RenderEffect.createBlurEffect(r, r, Shader.TileMode.CLAMP)) {
            it.drawBitmap(src, 0f, 0f, null)
        }
    }

    private fun renderOffscreen(
        width: Int,
        height: Int,
        effect: RenderEffect? = null,
        draw: (Canvas) -> Unit,
    ): Bitmap {
        val reader = ImageReader.newInstance(
            width,
            height,
            PixelFormat.RGBA_8888,
            1,
            HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE or HardwareBuffer.USAGE_GPU_COLOR_OUTPUT,
        )
        val renderer = HardwareRenderer()
        try {
            renderer.setSurface(reader.surface)
            renderer.isOpaque = false
            val node = RenderNode("edit")
            node.setPosition(0, 0, width, height)
            if (effect != null) node.setRenderEffect(effect)
            val canvas = node.beginRecording()
            draw(canvas)
            node.endRecording()
            renderer.setContentRoot(node)
            renderer.createRenderRequest().setWaitForPresent(true).syncAndDraw()

            var image = reader.acquireLatestImage()
            var tries = 0
            while (image == null && tries < 50) {
                Thread.sleep(10)
                image = reader.acquireLatestImage()
                tries++
            }
            if (image == null) throw IllegalStateException("GPU render produced no image")
            image.use { img ->
                val buffer = img.hardwareBuffer ?: throw IllegalStateException("No hardware buffer")
                buffer.use { hb ->
                    val hw = Bitmap.wrapHardwareBuffer(hb, ColorSpace.get(ColorSpace.Named.SRGB))
                        ?: throw IllegalStateException("Couldn't wrap GPU buffer")
                    val copy = hw.copy(Bitmap.Config.ARGB_8888, false)
                    hw.recycle()
                    return copy
                }
            }
        } finally {
            renderer.destroy()
            reader.close()
        }
    }
}

/**
 * Color adjustments and filters as an Android 13+ GPU shader (AGSL). One instance per
 * place that draws (preview, each filter thumbnail, export).
 */
class ColorShader {
    private val shader = RuntimeShader(AGSL)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private var bound: Bitmap? = null

    fun bind(state: EditState, src: Bitmap) {
        if (src !== bound) {
            val input = BitmapShader(src, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
            input.filterMode = BitmapShader.FILTER_MODE_LINEAR
            shader.setInputShader("image", input)
            bound = src
        }
        val strength = if (state.filter == FilterPreset.NONE) 0f else state.filterStrength
        // Sample spacing that scales with the image, so preview and full size look the same
        shader.setFloatUniform("unit", max(1f, max(src.width, src.height) / 1500f))
        shader.setFloatUniform("exposure", state.adjust(Adjust.EXPOSURE))
        shader.setFloatUniform("brightness", state.adjust(Adjust.BRIGHTNESS))
        shader.setFloatUniform("contrastAmt", state.adjust(Adjust.CONTRAST))
        shader.setFloatUniform("highlights", state.adjust(Adjust.HIGHLIGHTS))
        shader.setFloatUniform("shadows", state.adjust(Adjust.SHADOWS))
        shader.setFloatUniform("saturationAmt", state.adjust(Adjust.SATURATION))
        shader.setFloatUniform("vibrance", state.adjust(Adjust.VIBRANCE))
        shader.setFloatUniform("warmth", state.adjust(Adjust.WARMTH))
        shader.setFloatUniform("tintAmt", state.adjust(Adjust.TINT))
        shader.setFloatUniform("sharpness", state.adjust(Adjust.SHARPNESS))
        shader.setFloatUniform(
            "grain",
            (state.adjust(Adjust.GRAIN) + state.filter.grain * strength).coerceIn(0f, 1f),
        )
        val m = state.filter.matrix
        shader.setFloatUniform("fr", m[0], m[1], m[2], m[3])
        shader.setFloatUniform("fg", m[4], m[5], m[6], m[7])
        shader.setFloatUniform("fb", m[8], m[9], m[10], m[11])
        shader.setFloatUniform("fstrength", strength)
        paint.shader = shader
    }

    companion object {
        private const val AGSL = """
uniform shader image;
uniform float unit;
uniform float exposure;
uniform float brightness;
uniform float contrastAmt;
uniform float highlights;
uniform float shadows;
uniform float saturationAmt;
uniform float vibrance;
uniform float warmth;
uniform float tintAmt;
uniform float sharpness;
uniform float grain;
uniform float4 fr;
uniform float4 fg;
uniform float4 fb;
uniform float fstrength;

float3 straight(float4 c) {
    return c.a > 0.0 ? c.rgb / c.a : float3(0.0);
}

float luma(float3 c) {
    return dot(c, float3(0.2126, 0.7152, 0.0722));
}

float hash(float2 p) {
    return fract(sin(dot(p, float2(12.9898, 78.233))) * 43758.5453);
}

half4 main(float2 p) {
    float4 src = float4(image.eval(p));
    float a = src.a;
    float3 c = straight(src);

    if (sharpness > 0.0) {
        float3 n = straight(float4(image.eval(p + float2(unit, 0.0))))
                 + straight(float4(image.eval(p - float2(unit, 0.0))))
                 + straight(float4(image.eval(p + float2(0.0, unit))))
                 + straight(float4(image.eval(p - float2(0.0, unit))));
        c = c + (c - n * 0.25) * sharpness * 1.5;
    }

    c = c * exp2(exposure * 2.0);
    c = c + brightness * 0.25;

    float k = contrastAmt >= 0.0 ? 1.0 + contrastAmt : 1.0 + contrastAmt * 0.7;
    c = (c - 0.5) * k + 0.5;

    float l = clamp(luma(c), 0.0, 1.0);
    float ws = 1.0 - smoothstep(0.0, 0.6, l);
    float wh = smoothstep(0.4, 1.0, l);
    c = c + shadows * 0.35 * ws + highlights * 0.35 * wh;

    c.r = c.r + warmth * 0.08;
    c.b = c.b - warmth * 0.08;
    c.g = c.g - tintAmt * 0.06;
    c.r = c.r + tintAmt * 0.03;
    c.b = c.b + tintAmt * 0.03;

    float l2 = luma(c);
    float sat = max(c.r, max(c.g, c.b)) - min(c.r, min(c.g, c.b));
    float vib = vibrance * (1.0 - clamp(sat, 0.0, 1.0));
    c = mix(float3(l2), c, 1.0 + saturationAmt + vib);
    c = clamp(c, 0.0, 1.0);

    float3 f = float3(dot(fr.rgb, c) + fr.a, dot(fg.rgb, c) + fg.a, dot(fb.rgb, c) + fb.a);
    c = mix(c, f, fstrength);

    if (grain > 0.0) {
        float g = hash(floor(p / unit)) - 0.5;
        c = c + g * grain * 0.18;
    }

    c = clamp(c, 0.0, 1.0);
    return half4(half3(c * a), half(a));
}
"""
    }
}
