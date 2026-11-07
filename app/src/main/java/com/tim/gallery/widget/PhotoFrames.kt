package com.tim.gallery.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.RemoteViews
import com.tim.gallery.MainActivity
import com.tim.gallery.R
import org.json.JSONObject
import kotlin.math.max
import kotlin.math.roundToInt

/** One photo frame on the home screen: which photo, and how it's framed. */
data class FrameConfig(
    val photoId: Long,
    val uri: String,
    val style: FrameStyle = FrameStyle.GALLERY,
    /** Drop shadow, 0 none – 1 strong. */
    val shadow: Float = 0.6f,
    /** Crop the photo to fill the widget, rather than framing the whole photo. */
    val fill: Boolean = false,
) {
    fun toJson(): String = JSONObject().apply {
        put("photoId", photoId)
        put("uri", uri)
        put("style", style.name)
        put("shadow", shadow.toDouble())
        put("fill", fill)
    }.toString()

    companion object {
        fun fromJson(text: String?): FrameConfig? = runCatching {
            val j = JSONObject(text ?: return null)
            FrameConfig(
                photoId = j.getLong("photoId"),
                uri = j.getString("uri"),
                style = FrameStyle.entries.firstOrNull { it.name == j.optString("style") } ?: FrameStyle.GALLERY,
                shadow = j.optDouble("shadow", 0.6).toFloat(),
                fill = j.optBoolean("fill", false),
            )
        }.getOrNull()
    }
}

/** The photo frame widgets: their settings, drawing them, and putting a new one on the home screen. */
object PhotoFrames {
    const val ACTION_OPEN_PHOTO = "com.tim.gallery.action.OPEN_PHOTO"
    const val EXTRA_PHOTO_ID = "photo_id"
    internal const val ACTION_PINNED = "com.tim.gallery.action.PHOTO_FRAME_PINNED"
    internal const val EXTRA_PHOTO_URI = "photo_uri"

    /** Longest side of the picture sent to the home screen (Android limits how big it can be). */
    private const val MAX_SIDE = 960

    private fun prefs(context: Context) = context.getSharedPreferences("photo_frames", Context.MODE_PRIVATE)

    fun load(context: Context, widgetId: Int): FrameConfig? = FrameConfig.fromJson(prefs(context).getString("w$widgetId", null))

    fun save(context: Context, widgetId: Int, config: FrameConfig) {
        prefs(context).edit().putString("w$widgetId", config.toJson()).apply()
    }

    fun remove(context: Context, widgetIds: IntArray) {
        prefs(context).edit().apply { widgetIds.forEach { remove("w$it") } }.apply()
    }

    /** The photo chosen with "Put on home screen", for the frame that's about to appear. */
    internal fun setPending(context: Context, photoId: Long, uri: String) {
        prefs(context).edit().putLong("pending_id", photoId).putString("pending_uri", uri)
            .putLong("pending_at", System.currentTimeMillis()).apply()
    }

    internal fun clearPending(context: Context) {
        prefs(context).edit().remove("pending_id").remove("pending_uri").apply()
    }

    internal fun pending(context: Context): Pair<Long, String>? {
        val p = prefs(context)
        val id = p.getLong("pending_id", -1L)
        val uri = p.getString("pending_uri", null)
        // Only for a frame being placed right now (not one added from the widget list much later)
        val fresh = System.currentTimeMillis() - p.getLong("pending_at", 0L) < 3 * 60_000L
        return if (id > 0 && uri != null && fresh) id to uri else null
    }

    /** The widget's size on screen in pixels, upright (portrait) or sideways (landscape). */
    fun sizePx(context: Context, widgetId: Int, landscape: Boolean = false): Pair<Int, Int> {
        val opts: Bundle = AppWidgetManager.getInstance(context).getAppWidgetOptions(widgetId)
        // Android's convention: upright uses the narrowest width and tallest height, sideways the reverse
        val wDp = opts.getInt(if (landscape) AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH else AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH).takeIf { it > 0 } ?: 180
        val hDp = opts.getInt(if (landscape) AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT else AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT).takeIf { it > 0 } ?: 180
        val d = context.resources.displayMetrics.density
        var w = wDp * d
        var h = hDp * d
        val k = MAX_SIDE / max(w, h)
        if (k < 1f) {
            w *= k
            h *= k
        }
        return w.roundToInt().coerceAtLeast(64) to h.roundToInt().coerceAtLeast(64)
    }

    // One drawing at a time (photos are big), and for each widget only its newest request is drawn
    private val worker = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "photo-frames") }
    private val requests = java.util.concurrent.ConcurrentHashMap<Int, Int>()
    private val lastDrawn = java.util.concurrent.ConcurrentHashMap<Int, String>()

    /** Redraws [widgetIds] in the background; [done] runs afterwards (to finish a broadcast). */
    fun queue(context: Context, widgetIds: IntArray, force: Boolean = false, done: () -> Unit = {}) {
        val app = context.applicationContext
        val tickets = widgetIds.map { id -> id to requests.merge(id, 1, Int::plus)!! }
        worker.execute {
            try {
                for ((id, ticket) in tickets) {
                    if (requests[id] != ticket) continue
                    runCatching { update(app, id, force) }
                }
            } finally {
                done()
            }
        }
    }

    /** Draws one widget (upright and sideways) and hands it to the home screen. Runs on the worker. */
    private fun update(context: Context, widgetId: Int, force: Boolean) {
        val mgr = AppWidgetManager.getInstance(context)
        val config = load(context, widgetId)
        val portrait = sizePx(context, widgetId, landscape = false)
        val landscape = sizePx(context, widgetId, landscape = true)
        val key = "$config|$portrait|$landscape"
        if (!force && config != null && lastDrawn[widgetId] == key) return
        val longest = maxOf(portrait.first, portrait.second, landscape.first, landscape.second)
        val photo = config?.let { FrameRenderer.loadPhoto(context, Uri.parse(it.uri), longest) }
        val views = if (config == null || photo == null) {
            RemoteViews(context.packageName, R.layout.widget_photo_frame).apply {
                setViewVisibility(R.id.frame, View.GONE)
                setViewVisibility(R.id.empty, View.VISIBLE)
                setTextViewText(R.id.empty, if (config == null) "Tap to choose a photo" else "Photo not found.\nTap to choose another")
                setOnClickPendingIntent(R.id.root, settingsIntent(context, widgetId))
            }
        } else {
            fun framed(size: Pair<Int, Int>) = RemoteViews(context.packageName, R.layout.widget_photo_frame).apply {
                setViewVisibility(R.id.empty, View.GONE)
                setViewVisibility(R.id.frame, View.VISIBLE)
                setImageViewBitmap(R.id.frame, FrameRenderer.render(photo, size.first, size.second, config.style, config.shadow, config.fill))
                setOnClickPendingIntent(R.id.root, openPhotoIntent(context, widgetId, config.photoId))
            }
            // Same shape both ways (most phones): one picture; otherwise one for each (folds, tablets)
            val pa = portrait.first.toFloat() / portrait.second
            val la = landscape.first.toFloat() / landscape.second
            val result = if (kotlin.math.abs(pa / la - 1f) < 0.08f) framed(portrait) else RemoteViews(framed(landscape), framed(portrait))
            photo.recycle()
            result
        }
        mgr.updateAppWidget(widgetId, views)
        if (config != null && photo != null) lastDrawn[widgetId] = key else lastDrawn.remove(widgetId)
    }

    fun forget(widgetIds: IntArray) {
        widgetIds.forEach {
            lastDrawn.remove(it)
            requests.remove(it)
        }
    }

    /** After a backup is restored the widgets get new ids: carry each frame's settings over. */
    fun moveIds(context: Context, oldIds: IntArray, newIds: IntArray) {
        val p = prefs(context)
        val e = p.edit()
        oldIds.zip(newIds).forEach { (old, new) ->
            p.getString("w$old", null)?.let { e.putString("w$new", it) }
            e.remove("w$old")
        }
        e.apply()
    }

    /** Is [widgetId] one of this app's frames? (A placed-frame message must be about a real one.) */
    internal fun isOurs(context: Context, widgetId: Int): Boolean =
        AppWidgetManager.getInstance(context).getAppWidgetIds(ComponentName(context, PhotoFrameWidget::class.java)).contains(widgetId)

    /** Tapping the frame opens its photo in the gallery. */
    private fun openPhotoIntent(context: Context, widgetId: Int, photoId: Long): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .setAction(ACTION_OPEN_PHOTO)
            .setData(Uri.parse("quartzgallery://frame/$widgetId"))
            .putExtra(EXTRA_PHOTO_ID, photoId)
            // Back to the gallery as it was (an edit in progress stays), with the photo opened on top
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(context, widgetId, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    /** The frames on the home screen right now. */
    fun frameIds(context: Context): List<Int> =
        AppWidgetManager.getInstance(context).getAppWidgetIds(ComponentName(context, PhotoFrameWidget::class.java)).toList()

    /** Opens a frame's settings screen. */
    fun settingsActivityIntent(context: Context, widgetId: Int): Intent =
        Intent(context, PhotoFrameConfigActivity::class.java)
            .setData(Uri.parse("quartzgallery://frame-settings/$widgetId"))
            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** A frame without a photo opens its settings. */
    private fun settingsIntent(context: Context, widgetId: Int): PendingIntent =
        PendingIntent.getActivity(context, widgetId, settingsActivityIntent(context, widgetId), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    /**
     * Asks the home screen to place a new frame showing this photo. False when the home screen
     * can't do that (then the widget can still be added from its widget list).
     */
    fun pin(context: Context, photoId: Long, uri: Uri): Boolean {
        val mgr = AppWidgetManager.getInstance(context)
        if (!mgr.isRequestPinAppWidgetSupported) return false
        setPending(context, photoId, uri.toString())
        // Android adds the new widget's id to this when it's placed
        val callback = PendingIntent.getBroadcast(
            context,
            photoId.hashCode(),
            Intent(context, PhotoFrameWidget::class.java)
                .setAction(ACTION_PINNED)
                .putExtra(EXTRA_PHOTO_ID, photoId)
                .putExtra(EXTRA_PHOTO_URI, uri.toString()),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )
        return runCatching { mgr.requestPinAppWidget(ComponentName(context, PhotoFrameWidget::class.java), null, callback) }.getOrDefault(false)
    }
}
