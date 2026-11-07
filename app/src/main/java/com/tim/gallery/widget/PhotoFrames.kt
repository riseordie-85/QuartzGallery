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
        prefs(context).edit().putLong("pending_id", photoId).putString("pending_uri", uri).apply()
    }

    internal fun clearPending(context: Context) {
        prefs(context).edit().remove("pending_id").remove("pending_uri").apply()
    }

    internal fun pending(context: Context): Pair<Long, String>? {
        val p = prefs(context)
        val id = p.getLong("pending_id", -1L)
        val uri = p.getString("pending_uri", null)
        return if (id > 0 && uri != null) id to uri else null
    }

    /** The widget's size on screen in pixels (as it's laid out on a phone held upright). */
    fun sizePx(context: Context, widgetId: Int): Pair<Int, Int> {
        val opts: Bundle = AppWidgetManager.getInstance(context).getAppWidgetOptions(widgetId)
        val wDp = opts.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH).takeIf { it > 0 } ?: 180
        val hDp = opts.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT).takeIf { it > 0 } ?: 180
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

    /** Draws one widget and hands it to the home screen. Slow-ish (decodes the photo): call off the main thread. */
    fun update(context: Context, widgetId: Int) {
        val mgr = AppWidgetManager.getInstance(context)
        val views = RemoteViews(context.packageName, R.layout.widget_photo_frame)
        val config = load(context, widgetId)
        val (w, h) = sizePx(context, widgetId)
        val photo = config?.let { FrameRenderer.loadPhoto(context, Uri.parse(it.uri), max(w, h)) }
        if (config == null || photo == null) {
            views.setViewVisibility(R.id.frame, View.GONE)
            views.setViewVisibility(R.id.empty, View.VISIBLE)
            views.setTextViewText(
                R.id.empty,
                if (config == null) "Tap to choose a photo" else "Photo not found.\nTap to choose another",
            )
            views.setOnClickPendingIntent(R.id.root, settingsIntent(context, widgetId))
        } else {
            val framed = FrameRenderer.render(photo, w, h, config.style, config.shadow, config.fill)
            photo.recycle()
            views.setViewVisibility(R.id.empty, View.GONE)
            views.setViewVisibility(R.id.frame, View.VISIBLE)
            views.setImageViewBitmap(R.id.frame, framed)
            views.setContentDescription(R.id.frame, "Photo frame")
            views.setOnClickPendingIntent(R.id.root, openPhotoIntent(context, widgetId, config.photoId))
        }
        mgr.updateAppWidget(widgetId, views)
    }

    fun updateAll(context: Context) {
        val mgr = AppWidgetManager.getInstance(context)
        mgr.getAppWidgetIds(ComponentName(context, PhotoFrameWidget::class.java)).forEach { update(context, it) }
    }

    /** Tapping the frame opens its photo in the gallery. */
    private fun openPhotoIntent(context: Context, widgetId: Int, photoId: Long): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .setAction(ACTION_OPEN_PHOTO)
            .setData(Uri.parse("quartzgallery://frame/$widgetId"))
            .putExtra(EXTRA_PHOTO_ID, photoId)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        return PendingIntent.getActivity(context, widgetId, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    /** A frame without a photo opens its settings. */
    private fun settingsIntent(context: Context, widgetId: Int): PendingIntent {
        val intent = Intent(context, PhotoFrameConfigActivity::class.java)
            .setData(Uri.parse("quartzgallery://frame-settings/$widgetId"))
            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return PendingIntent.getActivity(context, widgetId, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

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
