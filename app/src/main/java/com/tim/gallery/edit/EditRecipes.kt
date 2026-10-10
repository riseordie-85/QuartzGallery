package com.tim.gallery.edit

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Saved copies remember how they were made, so "Edit again" can reopen the original with every
 * crop, filter, sticker and text still adjustable. Stored inside the app, keyed by the copy's id.
 */
data class EditRecipe(
    /** The photo the edit started from. */
    val originalId: Long,
    val originalUri: String,
    val state: EditState,
    /** The original's last-modified time and size when edited (to notice if it changed since). */
    val originalModified: Long = 0L,
    val originalSize: Long = 0L,
)

object EditRecipes {
    private fun dir(context: Context) = File(context.filesDir, "edits").apply { mkdirs() }
    private fun file(context: Context, copyId: Long) = File(dir(context), "$copyId.json")

    fun has(context: Context, copyId: Long): Boolean = file(context, copyId).exists()

    suspend fun load(context: Context, copyId: Long): EditRecipe? = withContext(Dispatchers.IO) {
        runCatching {
            val o = JSONObject(file(context, copyId).readText())
            EditRecipe(
                originalId = o.getLong("originalId"),
                originalUri = o.getString("originalUri"),
                state = EditJson.stateFromJson(o.getJSONObject("state")),
                originalModified = o.optLong("originalModified", 0L),
                originalSize = o.optLong("originalSize", 0L),
            )
        }.getOrNull()
    }

    suspend fun save(context: Context, copyId: Long, recipe: EditRecipe) = withContext(Dispatchers.IO) {
        runCatching {
            val o = JSONObject()
                .put("originalId", recipe.originalId)
                .put("originalUri", recipe.originalUri)
                .put("state", EditJson.stateToJson(recipe.state))
                .put("originalModified", recipe.originalModified)
                .put("originalSize", recipe.originalSize)
            // Write then swap in, so a crash never leaves a half-written file
            val tmp = File(dir(context), "$copyId.tmp")
            tmp.writeText(o.toString())
            if (!tmp.renameTo(file(context, copyId))) {
                tmp.copyTo(file(context, copyId), overwrite = true)
                tmp.delete()
            }
        }
    }

    fun delete(context: Context, copyId: Long) {
        file(context, copyId).delete()
    }
}

/** A saved "look": adjustments + filter, reusable on any photo. */
data class Look(
    val name: String,
    val adjust: Map<Adjust, Float>,
    val filter: FilterPreset,
    val filterStrength: Float,
    val duoShadow: Int,
    val duoHighlight: Int,
) {
    fun applyTo(s: EditState) = s.copy(
        adjust = adjust,
        filter = filter,
        filterStrength = filterStrength,
        duoShadow = duoShadow,
        duoHighlight = duoHighlight,
    )

    companion object {
        fun from(name: String, s: EditState) = Look(name, s.adjust, s.filter, s.filterStrength, s.duoShadow, s.duoHighlight)
    }
}

object Looks {
    private val _looks = MutableStateFlow<List<Look>>(emptyList())
    val looks: StateFlow<List<Look>> = _looks.asStateFlow()
    private var loaded = false

    private fun file(context: Context) = File(context.filesDir, "looks.json")

    fun init(context: Context) {
        if (loaded) return
        loaded = true
        _looks.value = runCatching {
            val arr = JSONArray(file(context).readText())
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                Look(
                    name = o.getString("name"),
                    adjust = EditJson.adjustFromJson(o.optJSONObject("adjust")),
                    filter = runCatching { FilterPreset.valueOf(o.optString("filter")) }.getOrDefault(FilterPreset.NONE),
                    filterStrength = o.optDouble("strength", 1.0).toFloat(),
                    duoShadow = o.optInt("duoA", DUOTONE_PRESETS[0].first),
                    duoHighlight = o.optInt("duoB", DUOTONE_PRESETS[0].second),
                )
            }
        }.getOrDefault(emptyList())
    }

    fun add(context: Context, look: Look) {
        _looks.value = _looks.value.filterNot { it.name.equals(look.name, ignoreCase = true) } + look
        write(context)
    }

    fun remove(context: Context, look: Look) {
        _looks.value = _looks.value - look
        write(context)
    }

    private fun write(context: Context) {
        val arr = JSONArray()
        _looks.value.forEach { l ->
            arr.put(
                JSONObject()
                    .put("name", l.name)
                    .put("adjust", EditJson.adjustToJson(l.adjust))
                    .put("filter", l.filter.name)
                    .put("strength", l.filterStrength.toDouble())
                    .put("duoA", l.duoShadow)
                    .put("duoB", l.duoHighlight)
            )
        }
        runCatching { file(context).writeText(arr.toString()) }
    }
}

/** JSON for edit states (recipes) — every part of an edit. */
object EditJson {
    private fun JSONObject.f(name: String, def: Float) = optDouble(name, def.toDouble()).toFloat()

    fun adjustToJson(a: Map<Adjust, Float>) = JSONObject().apply { a.forEach { (k, v) -> put(k.name, v.toDouble()) } }

    fun adjustFromJson(o: JSONObject?): Map<Adjust, Float> {
        if (o == null) return emptyMap()
        return Adjust.entries.mapNotNull { a -> if (o.has(a.name)) a to o.f(a.name, 0f) else null }.toMap()
    }

    fun stateToJson(s: EditState): JSONObject = JSONObject().apply {
        val g = s.geometry
        put(
            "geometry",
            JSONObject()
                .put("turns", g.quarterTurns)
                .put("flipped", g.flipped)
                .put("angle", g.angle.toDouble())
                .put("fill", g.fill.name)
                .put("fillColor", g.fillColor)
                .put("crop", JSONArray().put(g.crop.left.toDouble()).put(g.crop.top.toDouble()).put(g.crop.right.toDouble()).put(g.crop.bottom.toDouble()))
                .put("aspect", g.aspect.name),
        )
        put("adjust", adjustToJson(s.adjust))
        put("filter", s.filter.name)
        put("strength", s.filterStrength.toDouble())
        put("duoA", s.duoShadow)
        put("duoB", s.duoHighlight)
        put("cutout", s.cutout.name)
        put("cutoutColor", s.cutoutColor)
        val layers = JSONArray()
        s.layers.forEach { layers.put(layerToJson(it)) }
        put("layers", layers)
    }

    fun stateFromJson(o: JSONObject): EditState {
        val g = o.optJSONObject("geometry")
        val crop = g?.optJSONArray("crop")
        val geometry = if (g == null) Geometry() else Geometry(
            quarterTurns = g.optInt("turns", 0),
            flipped = g.optBoolean("flipped", false),
            angle = g.f("angle", 0f),
            fill = runCatching { FillMode.valueOf(g.optString("fill")) }.getOrDefault(FillMode.CROP_IN),
            fillColor = g.optInt("fillColor", android.graphics.Color.BLACK),
            crop = if (crop != null && crop.length() == 4) {
                NRect(crop.getDouble(0).toFloat(), crop.getDouble(1).toFloat(), crop.getDouble(2).toFloat(), crop.getDouble(3).toFloat())
            } else {
                NRect.FULL
            },
            aspect = runCatching { AspectOption.valueOf(g.optString("aspect")) }.getOrDefault(AspectOption.FREE),
        )
        val arr = o.optJSONArray("layers")
        val layers = if (arr == null) emptyList() else (0 until arr.length()).mapNotNull { layerFromJson(arr.getJSONObject(it)) }
        return EditState(
            geometry = geometry,
            adjust = adjustFromJson(o.optJSONObject("adjust")),
            filter = runCatching { FilterPreset.valueOf(o.optString("filter")) }.getOrDefault(FilterPreset.NONE),
            filterStrength = o.f("strength", 1f),
            duoShadow = o.optInt("duoA", DUOTONE_PRESETS[0].first),
            duoHighlight = o.optInt("duoB", DUOTONE_PRESETS[0].second),
            cutout = runCatching { CutoutMode.valueOf(o.optString("cutout")) }.getOrDefault(CutoutMode.OFF),
            cutoutColor = o.optInt("cutoutColor", android.graphics.Color.WHITE),
            layers = layers,
        )
    }

    private fun JSONObject.putPlacement(l: Layer) = this
        .put("id", l.id)
        .put("cx", l.cx.toDouble())
        .put("cy", l.cy.toDouble())
        .put("scale", l.scale.toDouble())
        .put("rotation", l.rotation.toDouble())
        .put("opacity", l.opacity.toDouble())

    private fun layerToJson(l: Layer): JSONObject = when (l) {
        is StickerLayer -> JSONObject().put("type", "sticker").putPlacement(l).put("uri", l.uri).put("stretch", l.stretch.toDouble())
        is TextLayer -> WatermarkStore.textToJson(l).put("type", "text").putPlacement(l)
        is ShapeLayer -> JSONObject().put("type", "shape").putPlacement(l)
            .put("kind", l.kind.name)
            .put("fill", l.fill)
            .put("stroke", l.stroke)
            .put("strokeWidth", l.strokeWidth.toDouble())
            .put("aspect", l.aspect.toDouble())
            .put("corner", l.cornerRadius.toDouble())
    }

    private fun layerFromJson(o: JSONObject): Layer? = runCatching {
        val id = o.getLong("id")
        val cx = o.f("cx", 0.5f)
        val cy = o.f("cy", 0.5f)
        val scale = o.f("scale", 0.4f)
        val rotation = o.f("rotation", 0f)
        val opacity = o.f("opacity", 1f)
        when (o.optString("type")) {
            "sticker" -> StickerLayer(id, o.getString("uri"), cx, cy, scale, rotation, opacity, o.f("stretch", 1f))
            "text" -> WatermarkStore.textFromJson(o).copy(id = id, cx = cx, cy = cy, scale = scale, rotation = rotation, opacity = opacity)
            "shape" -> ShapeLayer(
                id = id,
                kind = ShapeKind.valueOf(o.getString("kind")),
                fill = o.optInt("fill"),
                stroke = o.optInt("stroke"),
                strokeWidth = o.f("strokeWidth", 1f),
                aspect = o.f("aspect", 1f),
                cornerRadius = o.f("corner", 0.2f),
                cx = cx, cy = cy, scale = scale, rotation = rotation, opacity = opacity,
            )
            else -> null
        }
    }.getOrNull()
}
