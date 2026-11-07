package com.tim.gallery.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.os.Bundle
import kotlin.concurrent.thread

/** The photo frame on the home screen. Drawing happens on a background thread. */
class PhotoFrameWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        inBackground { ids.forEach { PhotoFrames.update(context, it) } }
    }

    /** Resized: draw it again at the new size. */
    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, newOptions: Bundle) {
        inBackground { PhotoFrames.update(context, id) }
    }

    override fun onDeleted(context: Context, ids: IntArray) {
        PhotoFrames.remove(context, ids)
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == PhotoFrames.ACTION_PINNED) {
            // Placed with "Put on home screen": show the photo it was asked for
            val id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
            val photoId = intent.getLongExtra(PhotoFrames.EXTRA_PHOTO_ID, -1L)
            val uri = intent.getStringExtra(PhotoFrames.EXTRA_PHOTO_URI)
            if (id != AppWidgetManager.INVALID_APPWIDGET_ID && photoId > 0 && uri != null) {
                // Unless its settings screen already saved a choice
                if (PhotoFrames.load(context, id) == null) PhotoFrames.save(context, id, FrameConfig(photoId, uri))
                PhotoFrames.clearPending(context)
                inBackground { PhotoFrames.update(context, id) }
            }
            return
        }
        super.onReceive(context, intent)
    }

    private fun inBackground(block: () -> Unit) {
        val pending = goAsync()
        thread(name = "photo-frame") {
            try {
                runCatching { block() }
            } finally {
                pending.finish()
            }
        }
    }
}
