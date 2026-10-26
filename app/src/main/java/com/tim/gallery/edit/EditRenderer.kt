package com.tim.gallery.edit

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorSpace
import android.graphics.HardwareRenderer
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.RenderEffect
import android.graphics.RenderNode
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.graphics.Typeface
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

/** Where the renderer gets sticker pictures and fonts from. */
interface LayerAssets {
    fun bitmap(uri: String): Bitmap?
    fun typeface(key: String): Typeface = Fonts.resolve(key)

    companion object {
        val NONE = object : LayerAssets {
            override fun bitmap(uri: String): Bitmap? = null
        }
    }
}

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

    /** Longest side of a saved edit: the GPU's biggest texture (so a panorama keeps its pixels). */
    val MAX_OUTPUT_PX: Int get() = com.tim.gallery.data.ImageLimits.maxSide

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


    /** Smallest box with the given pixel ratio that holds the whole frame (Background mode adds space). */
    fun fitCrop(ratio: Float?, frame: FSize): NRect {
        if (ratio == null || frame.width <= 0f || frame.height <= 0f) return NRect.FULL
        val rn = ratio * frame.height / frame.width
        val (cw, ch) = if (rn >= 1f) rn to 1f else 1f to 1f / rn
        val l = (1f - cw) / 2f
        val t = (1f - ch) / 2f
        return NRect(l, t, l + cw, t + ch)
    }

    /**
     * Where the crop box may go, in 0..1 frame units. Crop-in mode stays inside the photo;
     * Background mode can reach past it to add space around the picture.
     */
    fun cropBounds(g: Geometry, w: Float, h: Float): NRect {
        if (g.fill == FillMode.CROP_IN) return NRect.FULL
        val fit = fitCrop(resolvedRatio(g, w, h), frameSize(g, w, h))
        val u = NRect.FULL.union(fit).union(g.crop)
        val mx = u.width * 0.15f
        val my = u.height * 0.15f
        // The extra room is capped, but never cuts into the photo, the fitted box or the crop itself
        return NRect(
            min(u.left, max(u.left - mx, -2f)),
            min(u.top, max(u.top - my, -2f)),
            max(u.right, min(u.right + mx, 3f)),
            max(u.bottom, min(u.bottom + my, 3f)),
        )
    }

    // ------------------------------------------------------------ drawing

    // Paints are created per draw: the preview (main thread) and saving (background) may draw at once

    /**
     * Draws the straightened, color-adjusted photo in frame coordinates
     * (0..frameWidth, 0..frameHeight). The caller handles any background fill and clipping.
     * [src] may be a smaller copy of the full photo ([fullW] × [fullH]).
     */
    fun drawFrame(canvas: Canvas, state: EditState, src: Bitmap, fullW: Float, fullH: Float, color: ColorShader) {
        val g = state.geometry
        canvas.save()
        canvas.concat(sourceToFrame(g, fullW, fullH))
        canvas.scale(fullW / src.width, fullH / src.height)
        color.bind(state, src)
        canvas.drawRect(0f, 0f, src.width.toFloat(), src.height.toFloat(), color.paint)
        canvas.restore()
    }

    /** Draws the finished picture (cropped, with vignette, stickers, text and shapes) in output coordinates. */
    fun drawOutput(
        canvas: Canvas,
        state: EditState,
        src: Bitmap,
        fullW: Float,
        fullH: Float,
        color: ColorShader,
        assets: LayerAssets,
    ) {
        val g = state.geometry
        val f = frameSize(g, fullW, fullH)
        val out = outputSize(g, fullW, fullH)
        canvas.saveLayer(0f, 0f, out.width, out.height, null)

        if (g.fill == FillMode.BACKGROUND && Color.alpha(g.fillColor) > 0) {
            val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).also { it.color = g.fillColor }
            canvas.drawRect(0f, 0f, out.width, out.height, fillPaint)
        }

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

        for (layer in state.layers) drawLayer(canvas, layer, out, assets)
        canvas.restore()
    }

    /** Just the color treatment on a bitmap at its own size (filter thumbnails). */
    fun drawColorOnly(canvas: Canvas, state: EditState, src: Bitmap, color: ColorShader) {
        color.bind(state, src)
        canvas.drawRect(0f, 0f, src.width.toFloat(), src.height.toFloat(), color.paint)
    }

    // ------------------------------------------------------------ layers

    /** Width and height of a layer in output pixels (before rotation). */
    fun layerSize(layer: Layer, out: FSize, assets: LayerAssets): FSize = when (layer) {
        is StickerLayer -> {
            val w = layer.scale * out.shortSide
            val bmp = assets.bitmap(layer.uri)
            val aspect = if (bmp != null && bmp.height > 0) bmp.width.toFloat() / bmp.height else 1f
            FSize(w, w / (aspect * layer.stretch.coerceIn(0.1f, 10f)))
        }
        is TextLayer -> buildText(layer, out.shortSide, assets.typeface(layer.fontKey)).let { FSize(it.width, it.height) }
        is ShapeLayer -> shapeSize(layer, out)
    }

    /** Draws one layer centered at the origin (used by the markup canvas and watermark previews too). */
    fun drawLayer(canvas: Canvas, layer: Layer, out: FSize, assets: LayerAssets) {
        canvas.save()
        canvas.translate(layer.cx * out.width, layer.cy * out.height)
        canvas.rotate(layer.rotation)
        val fade = layer.opacity < 0.999f
        if (fade) {
            val s = layerSize(layer, out, assets)
            val r = max(s.width, s.height) * 1.5f + out.shortSide * 0.1f
            canvas.saveLayerAlpha(-r, -r, r, r, (layer.opacity.coerceIn(0f, 1f) * 255).roundToInt())
        }
        when (layer) {
            is StickerLayer -> {
                val bmp = assets.bitmap(layer.uri)
                if (bmp != null) {
                    val s = layerSize(layer, out, assets)
                    canvas.drawBitmap(
                        bmp,
                        null,
                        RectF(-s.width / 2f, -s.height / 2f, s.width / 2f, s.height / 2f),
                        Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG),
                    )
                }
            }
            is TextLayer -> drawText(canvas, layer, out.shortSide, assets.typeface(layer.fontKey))
            is ShapeLayer -> drawShape(canvas, layer, out)
        }
        if (fade) canvas.restore()
        canvas.restore()
    }

    // ------------------------------------------------------------ shapes

    private fun strokePx(shape: ShapeLayer, out: FSize) = shape.strokeWidth / 100f * out.shortSide

    fun shapeSize(shape: ShapeLayer, out: FSize): FSize {
        val w = shape.scale * out.shortSide
        val sp = strokePx(shape, out)
        return when (shape.kind) {
            ShapeKind.LINE -> FSize(w, max(sp * 3f, out.shortSide * 0.04f))
            ShapeKind.ARROW -> FSize(w, max(sp * 5f, out.shortSide * 0.05f))
            else -> FSize(w, w / shape.aspect.coerceIn(0.1f, 10f))
        }
    }

    private fun drawShape(canvas: Canvas, shape: ShapeLayer, out: FSize) {
        val s = shapeSize(shape, out)
        val w = s.width
        val h = s.height
        val sp = strokePx(shape, out)
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).also { it.style = Paint.Style.FILL; it.color = shape.fill }
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).also {
            it.style = Paint.Style.STROKE
            it.color = shape.stroke
            it.strokeWidth = sp
            it.strokeJoin = Paint.Join.ROUND
            it.strokeCap = Paint.Cap.ROUND
        }
        val hasStroke = Color.alpha(shape.stroke) > 0 && sp > 0f

        if (shape.kind == ShapeKind.LINE) {
            if (hasStroke) canvas.drawLine(-w / 2f + sp / 2f, 0f, w / 2f - sp / 2f, 0f, stroke)
            return
        }
        if (shape.kind == ShapeKind.ARROW) {
            if (!hasStroke) return
            val headLen = min(sp * 4.5f, w * 0.45f)
            val headW = sp * 4f
            canvas.drawLine(-w / 2f + sp / 2f, 0f, w / 2f - headLen * 0.8f, 0f, stroke)
            val head = Path().apply {
                moveTo(w / 2f, 0f)
                lineTo(w / 2f - headLen, -headW / 2f)
                lineTo(w / 2f - headLen, headW / 2f)
                close()
            }
            val headPaint = Paint(Paint.ANTI_ALIAS_FLAG).also { it.color = shape.stroke; it.style = Paint.Style.FILL }
            canvas.drawPath(head, headPaint)
            return
        }

        val path = shapePath(shape, w, h)
        if (Color.alpha(shape.fill) > 0) canvas.drawPath(path, fill)
        if (hasStroke) canvas.drawPath(path, stroke)
    }

    private fun shapePath(shape: ShapeLayer, w: Float, h: Float): Path {
        val hw = w / 2f
        val hh = h / 2f
        return when (shape.kind) {
            ShapeKind.RECT -> Path().apply { addRect(-hw, -hh, hw, hh, Path.Direction.CW) }
            ShapeKind.ROUNDED -> Path().apply {
                val r = shape.cornerRadius.coerceIn(0f, 0.5f) * min(w, h)
                addRoundRect(-hw, -hh, hw, hh, r, r, Path.Direction.CW)
            }
            ShapeKind.OVAL -> Path().apply { addOval(-hw, -hh, hw, hh, Path.Direction.CW) }
            ShapeKind.TRIANGLE -> Path().apply {
                moveTo(0f, -hh); lineTo(hw, hh); lineTo(-hw, hh); close()
            }
            ShapeKind.STAR -> Path().apply {
                for (i in 0 until 10) {
                    val r = if (i % 2 == 0) 1f else 0.42f
                    val a = Math.toRadians((-90 + i * 36).toDouble())
                    val x = (cos(a) * r).toFloat() * hw
                    val y = (sin(a) * r).toFloat() * hh + hh * 0.08f
                    if (i == 0) moveTo(x, y) else lineTo(x, y)
                }
                close()
            }
            ShapeKind.HEART -> Path().apply {
                fun X(v: Float) = v * w
                fun Y(v: Float) = v * h
                moveTo(X(0f), Y(0.45f))
                cubicTo(X(-0.1f), Y(0.35f), X(-0.5f), Y(0.1f), X(-0.5f), Y(-0.15f))
                cubicTo(X(-0.5f), Y(-0.4f), X(-0.2f), Y(-0.5f), X(0f), Y(-0.28f))
                cubicTo(X(0.2f), Y(-0.5f), X(0.5f), Y(-0.4f), X(0.5f), Y(-0.15f))
                cubicTo(X(0.5f), Y(0.1f), X(0.1f), Y(0.35f), X(0f), Y(0.45f))
                close()
            }
            ShapeKind.BUBBLE -> {
                val bodyBottom = hh - h * 0.22f
                val r = min(w, bodyBottom + hh) * 0.3f
                val body = Path().apply { addRoundRect(-hw, -hh, hw, bodyBottom, r, r, Path.Direction.CW) }
                val tail = Path().apply {
                    moveTo(-w * 0.28f, bodyBottom - r * 0.2f)
                    lineTo(-w * 0.04f, bodyBottom - r * 0.2f)
                    lineTo(-w * 0.34f, hh)
                    close()
                }
                body.apply { op(tail, Path.Op.UNION) }
            }
            ShapeKind.LINE, ShapeKind.ARROW -> Path()
        }
    }

    // ------------------------------------------------------------ text

    class TextRender(
        val text: String,
        val layout: StaticLayout?,
        /** Set for curved text instead of [layout]. */
        val path: Path?,
        val paint: TextPaint,
        val size: Float,
        /** Where the glyphs start inside the box. */
        val offsetX: Float,
        val offsetY: Float,
        val width: Float,
        val height: Float,
    )

    fun buildText(layer: TextLayer, shortSide: Float, typeface: Typeface): TextRender {
        val size = (layer.scale * shortSide * 0.1f).coerceAtLeast(4f)
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = size
            this.typeface = typeface
            letterSpacing = layer.letterSpacing
            color = layer.color
        }
        val text = layer.text.ifEmpty { " " }
        // Room for the box padding, outline and glow around the letters
        val effectPad = max(
            layer.stroke?.let { size * it.width } ?: 0f,
            layer.glow?.let { size * it.radius * 0.6f } ?: 0f,
        )
        val pad = (layer.box?.let { size * it.padding } ?: (size * 0.2f)) + effectPad

        val curve = layer.curve.coerceIn(-1f, 1f)
        if (abs(curve) >= 0.02f && !text.contains('\n')) {
            val tw = max(1f, paint.measureText(text))
            val theta = abs(curve) * Math.PI.toFloat()
            val r = tw / theta
            val fm = paint.fontMetrics
            val asc = -fm.ascent
            val desc = fm.descent
            val deg = Math.toDegrees(theta.toDouble()).toFloat()
            val path = Path()
            var minX = Float.MAX_VALUE
            var minY = Float.MAX_VALUE
            var maxX = -Float.MAX_VALUE
            var maxY = -Float.MAX_VALUE
            fun include(x: Float, y: Float) {
                minX = min(minX, x); maxX = max(maxX, x); minY = min(minY, y); maxY = max(maxY, y)
            }
            val steps = 24
            if (curve > 0f) {
                // Rainbow: circle center below the text
                path.addArc(RectF(-r, 0f, r, 2 * r), 270f - deg / 2f, deg)
                for (i in 0..steps) {
                    val phi = -theta / 2f + theta * i / steps
                    for (rr in floatArrayOf(r - desc, r + asc)) include(rr * sin(phi), r - rr * cos(phi))
                }
            } else {
                // Smile: circle center above the text
                path.addArc(RectF(-r, -2 * r, r, 0f), 90f + deg / 2f, -deg)
                for (i in 0..steps) {
                    val phi = -theta / 2f + theta * i / steps
                    for (rr in floatArrayOf(r - asc, r + desc)) include(rr * sin(phi), -r + rr * cos(phi))
                }
            }
            return TextRender(
                text, null, path, paint, size,
                offsetX = pad - minX,
                offsetY = pad - minY,
                width = (maxX - minX) + 2 * pad,
                height = (maxY - minY) + 2 * pad,
            )
        }

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
            .setLineSpacing(0f, layer.lineSpacing.coerceIn(0.5f, 3f))
            .build()
        return TextRender(text, layout, null, paint, size, pad, pad, layoutWidth + 2 * pad, layout.height + 2 * pad)
    }

    /** Black or white, whichever reads better on [color]. */
    fun contrastColor(color: Int): Int = if (Color.luminance(color) > 0.5f) Color.BLACK else Color.WHITE

    /** Draws text (with its effects) centered at the canvas origin. */
    fun drawText(canvas: Canvas, layer: TextLayer, shortSide: Float, typeface: Typeface) {
        val t = buildText(layer, shortSide, typeface)
        canvas.save()
        canvas.translate(-t.width / 2f, -t.height / 2f)

        layer.box?.let { box ->
            val alpha = (Color.alpha(box.color) * box.opacity.coerceIn(0f, 1f)).roundToInt()
            val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).also {
                it.color = box.color
                it.alpha = alpha
            }
            val r = t.size * box.radius.coerceIn(0f, 2f)
            canvas.drawRoundRect(0f, 0f, t.width, t.height, r, r, boxPaint)
        }

        canvas.translate(t.offsetX, t.offsetY)
        val paint = t.paint
        fun glyphs() {
            if (t.path != null) canvas.drawTextOnPath(t.text, t.path, 0f, 0f, paint) else t.layout?.draw(canvas)
        }
        val stroke = layer.stroke
        val shadow = layer.shadow
        fun applyShadow() {
            if (shadow == null) return
            val a = Math.toRadians(shadow.angle.toDouble())
            val d = t.size * shadow.distance
            paint.setShadowLayer(max(0.5f, t.size * shadow.blur), (cos(a) * d).toFloat(), (sin(a) * d).toFloat(), shadow.color)
        }

        layer.glow?.let { glow ->
            paint.style = if (stroke != null) Paint.Style.FILL_AND_STROKE else Paint.Style.FILL
            paint.strokeWidth = stroke?.let { t.size * it.width } ?: 0f
            paint.color = glow.color
            paint.setShadowLayer(max(1f, t.size * glow.radius), 0f, 0f, glow.color)
            glyphs()
            glyphs()
            paint.clearShadowLayer()
        }
        if (stroke != null) {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = t.size * stroke.width
            paint.strokeJoin = Paint.Join.ROUND
            paint.color = stroke.color
            applyShadow()
            glyphs()
            paint.clearShadowLayer()
        }
        paint.style = Paint.Style.FILL
        paint.color = layer.color
        if (stroke == null) applyShadow()
        glyphs()
        paint.clearShadowLayer()
        canvas.restore()
    }

    // ------------------------------------------------------------ off-screen rendering

    /** Renders the edit at full size on the GPU and returns a normal bitmap. Call off the main thread. */
    fun export(state: EditState, src: Bitmap, assets: LayerAssets): Bitmap {
        val fullW = src.width.toFloat()
        val fullH = src.height.toFloat()
        val out = outputSize(state.geometry, fullW, fullH)
        // (Within the GPU's texture side and the pixel limit: memory stays safe)
        val k = min(
            min(1f, MAX_OUTPUT_PX / max(out.width, out.height)),
            kotlin.math.sqrt(com.tim.gallery.data.ImageLimits.MAX_PIXELS / (out.width.toDouble() * out.height)).toFloat(),
        )
        val w = (out.width * k).toInt().coerceAtLeast(1)
        val h = (out.height * k).toInt().coerceAtLeast(1)
        return renderOffscreen(w, h) { canvas ->
            canvas.scale(w / out.width, h / out.height)
            drawOutput(canvas, state, src, fullW, fullH, ColorShader(), assets)
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
        val duo = state.filter.duotone
        shader.setFloatUniform("duo", if (duo) 1f else 0f)
        shader.setFloatUniform("duoA", Color.red(state.duoShadow) / 255f, Color.green(state.duoShadow) / 255f, Color.blue(state.duoShadow) / 255f)
        shader.setFloatUniform("duoB", Color.red(state.duoHighlight) / 255f, Color.green(state.duoHighlight) / 255f, Color.blue(state.duoHighlight) / 255f)
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
uniform float duo;
uniform float3 duoA;
uniform float3 duoB;

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
    if (duo > 0.5) {
        f = mix(duoA, duoB, smoothstep(0.0, 1.0, clamp(luma(c), 0.0, 1.0)));
    }
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
