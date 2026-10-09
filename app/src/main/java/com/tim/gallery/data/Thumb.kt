package com.tim.gallery.data

import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import android.util.Size
import coil.ImageLoader
import coil.decode.DataSource
import coil.fetch.DrawableResult
import coil.fetch.FetchResult
import coil.fetch.Fetcher
import coil.key.Keyer
import coil.request.Options
import coil.size.pxOrElse

/**
 * Request model for a system-generated thumbnail of a photo or video.
 * [version] (the file's modified time) makes edited files get fresh thumbnails.
 */
data class Thumb(val uri: Uri, val version: Long = 0L)

fun GalleryItem.thumb(): Thumb = Thumb(uri, modified)

class ThumbFetcher(
    private val data: Thumb,
    private val options: Options,
) : Fetcher {

    override suspend fun fetch(): FetchResult {
        val w = options.size.width.pxOrElse { DEFAULT_SIZE }
        val h = options.size.height.pxOrElse { DEFAULT_SIZE }
        val bitmap = options.context.contentResolver.loadThumbnail(data.uri, Size(w, h), null)
        return DrawableResult(
            drawable = BitmapDrawable(options.context.resources, bitmap),
            isSampled = true,
            dataSource = DataSource.DISK,
        )
    }

    class Factory : Fetcher.Factory<Thumb> {
        override fun create(data: Thumb, options: Options, imageLoader: ImageLoader): Fetcher =
            ThumbFetcher(data, options)
    }

    private companion object {
        const val DEFAULT_SIZE = 512
    }
}

class ThumbKeyer : Keyer<Thumb> {
    override fun key(data: Thumb, options: Options): String =
        "thumb:${data.uri}:${data.version}:${options.size}"
}
