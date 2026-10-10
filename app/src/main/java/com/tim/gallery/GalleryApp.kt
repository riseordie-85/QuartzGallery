package com.tim.gallery

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import com.tim.gallery.data.ThumbFetcher
import com.tim.gallery.data.ThumbKeyer
import com.tim.gallery.edit.ColorRecents
import com.tim.gallery.edit.Fonts
import com.tim.gallery.edit.WatermarkStore
import com.tim.gallery.settings.AppSettings
import com.tim.gallery.personal.Vault

class GalleryApp : Application(), ImageLoaderFactory {
    override fun onCreate() {
        super.onCreate()
        AppSettings.init(this)
        ColorRecents.init(this)
        WatermarkStore.init(this)
        Fonts.refreshImported(this)
        Vault.init(this)
    }

    override fun newImageLoader(): ImageLoader =
        ImageLoader.Builder(this)
            .components {
                // Grid thumbnails come from Android's own thumbnail cache (fast for photos and videos)
                add(ThumbFetcher.Factory())
                add(ThumbKeyer())
            }
            .crossfade(150)
            .build()
}
