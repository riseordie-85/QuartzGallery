package com.tim.gallery

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import com.tim.gallery.data.ThumbFetcher
import com.tim.gallery.data.ThumbKeyer

class GalleryApp : Application(), ImageLoaderFactory {
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
