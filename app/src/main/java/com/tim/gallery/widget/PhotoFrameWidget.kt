package com.tim.gallery.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.net.Uri
import android.provider.MediaStore

/** The photo frame on the home screen. Drawing happens on a background thread. */
class PhotoFrameWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        // A redraw asked for by name (e.g. right after the settings are saved) always draws
        val pending = goAsync()
        PhotoFrames.queue(context, ids, force = true) { pending.finish() }
    }

    /** Resized: draw it again at the new size. */
    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, newOptions: Bundle) {
        val pending = goAsync()
        PhotoFrames.queue(context, intArrayOf(id)) { pending.finish() }
    }

    override fun onDeleted(context: Context, ids: IntArray) {
        PhotoFrames.remove(context, ids)
        PhotoFrames.forget(ids)
    }

    /** Restored from a backup (e.g. a new phone): keep each frame's photo and style. */
    override fun onRestored(context: Context, oldWidgetIds: IntArray, newWidgetIds: IntArray) {
        PhotoFrames.moveIds(context, oldWidgetIds, newWidgetIds)
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == PhotoFrames.ACTION_PINNED) {
            // Placed with "Put on home screen": show the photo it was asked for
            val id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
            val photoId = intent.getLongExtra(PhotoFrames.EXTRA_PHOTO_ID, -1L)
            val uri = intent.getStringExtra(PhotoFrames.EXTRA_PHOTO_URI)
            val real = id != AppWidgetManager.INVALID_APPWIDGET_ID && photoId > 0 && uri != null &&
                Uri.parse(uri).authority == MediaStore.AUTHORITY && PhotoFrames.isOurs(context, id)
            if (real) {
                // Unless its settings screen already saved a choice
                if (PhotoFrames.load(context, id) == null) PhotoFrames.save(context, id, FrameConfig(photoId, uri!!))
                PhotoFrames.clearPending(context)
                val pending = goAsync()
                PhotoFrames.queue(context, intArrayOf(id), force = true) { pending.finish() }
            }
            return
        }
        super.onReceive(context, intent)
    }
}
