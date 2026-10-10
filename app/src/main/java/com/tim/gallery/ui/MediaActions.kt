package com.tim.gallery.ui

import android.app.Activity
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewModelScope
import com.tim.gallery.GalleryViewModel
import com.tim.gallery.data.Album
import com.tim.gallery.data.AlbumOps
import com.tim.gallery.data.GalleryItem
import com.tim.gallery.data.MediaSaver
import kotlinx.coroutines.launch

fun itemCount(n: Int): String = if (n == 1) "1 item" else "$n items"

/**
 * Move / rename / favorite / delete / restore / share, including Android's confirmation
 * prompts for changing files that other apps (like the camera) created. With "Media
 * management" access turned on, Android skips those prompts.
 */
class MediaActions internal constructor(
    private val context: Context,
    private val vm: GalleryViewModel,
) {
    internal lateinit var launcher: ActivityResultLauncher<IntentSenderRequest>
    private var pending: ((Boolean) -> Unit)? = null

    /** Moves [items] into the album folder [target]. [onFinished] gets how many moved (0 if cancelled). */
    fun move(items: List<GalleryItem>, target: String, undoable: Boolean = true, onFinished: (Int) -> Unit = {}) {
        val toMove = items.filter { !it.relativePath.equals(target, ignoreCase = true) }
        if (toMove.isEmpty()) {
            toast("Already in that album")
            onFinished(0)
            return
        }
        withWriteAccess(toMove) { granted ->
            if (!granted) {
                onFinished(0)
                return@withWriteAccess
            }
            val moved = AlbumOps.move(context, toMove, target)
            if (moved > 0) vm.removeEmptyAlbum(target)
            val name = AlbumOps.nameFromPath(target)
            val message = if (moved == toMove.size) "Moved ${itemCount(moved)} to $name"
            else "Moved $moved of ${toMove.size} to $name. Android refused the rest."
            if (undoable && moved > 0) {
                UndoCenter.post(message) { a -> a.moveBack(toMove, target) }
            } else {
                toast(message)
            }
            vm.refresh()
            onFinished(moved)
        }
    }

    /** Undo for [move]: one permission request, then each item goes back to the album it came from. */
    fun moveBack(original: List<GalleryItem>, movedTo: String) {
        val nowIn = original.map { it.copy(relativePath = movedTo) }
        withWriteAccess(nowIn) { granted ->
            if (!granted) return@withWriteAccess
            var back = 0
            original.groupBy { it.relativePath }.forEach { (from, group) ->
                back += AlbumOps.move(context, group.map { it.copy(relativePath = movedTo) }, from)
            }
            toast("Moved ${itemCount(back)} back")
            vm.refresh()
        }
    }

    /** Renames an album by moving everything into a sibling folder. [onFinished] gets the new key, or null. */
    fun renameAlbum(album: Album, newName: String, onFinished: (String?) -> Unit) {
        val parent = AlbumOps.parentOf(album.key)
        if (parent == null) {
            toast("This folder can't be renamed")
            onFinished(null)
            return
        }
        val newKey = parent + newName.trim() + "/"
        val items = vm.albumItems(album.key).value
        if (items.isEmpty()) {
            vm.renameEmptyAlbum(album.key, newKey)
            onFinished(newKey)
            return
        }
        withWriteAccess(items) { granted ->
            if (!granted) {
                onFinished(null)
                return@withWriteAccess
            }
            val moved = AlbumOps.move(context, items, newKey)
            if (moved < items.size) toast("Renamed, but ${items.size - moved} item(s) couldn't be moved")
            vm.refresh()
            onFinished(if (moved > 0) newKey else null)
        }
    }

    /** Moves items to Android's trash (restorable for about 30 days). */
    fun trash(items: List<GalleryItem>, onFinished: (Boolean) -> Unit = {}) {
        if (items.isEmpty()) return
        request({ MediaStore.createTrashRequest(context.contentResolver, items.map { it.uri }, true) }) { ok ->
            if (ok) {
                vm.refresh()
                UndoCenter.post("Moved ${itemCount(items.size)} to trash") { a -> a.restore(items) }
            }
            onFinished(ok)
        }
    }

    /** Takes items back out of the trash. */
    fun restore(items: List<GalleryItem>, onFinished: (Boolean) -> Unit = {}) {
        if (items.isEmpty()) return
        request({ MediaStore.createTrashRequest(context.contentResolver, items.map { it.uri }, false) }) { ok ->
            if (ok) {
                vm.refresh()
                toast("Restored ${itemCount(items.size)}")
            }
            onFinished(ok)
        }
    }

    /** Deletes items permanently. */
    fun deleteForever(items: List<GalleryItem>, onFinished: (Boolean) -> Unit = {}) {
        if (items.isEmpty()) return
        request({ MediaStore.createDeleteRequest(context.contentResolver, items.map { it.uri }) }) { ok ->
            if (ok) vm.refresh()
            onFinished(ok)
        }
    }

    /**
     * Copies [items] into the album folder [target] (the originals stay where they are).
     * New files belong to this app, so no confirmation is needed, and undo just deletes them.
     */
    fun copy(items: List<GalleryItem>, target: String, onFinished: (Int) -> Unit = {}) {
        if (items.isEmpty()) return
        vm.viewModelScope.launch {
            val copies = AlbumOps.copy(context, items, target)
            vm.removeEmptyAlbum(target)
            vm.refresh()
            val name = AlbumOps.nameFromPath(target)
            val message = if (copies.size == items.size) "Copied ${itemCount(copies.size)} to $name"
            else "Copied ${copies.size} of ${items.size} to $name"
            if (copies.isNotEmpty()) {
                UndoCenter.post(message) { _ ->
                    vm.viewModelScope.launch {
                        AlbumOps.deleteOwn(context, copies)
                        vm.refresh()
                    }
                }
            } else {
                toast("Couldn't copy to $name")
            }
            onFinished(copies.size)
        }
    }

    /** Renames a photo or video file (the extension is kept). */
    fun rename(item: GalleryItem, newStem: String, onFinished: (Boolean) -> Unit = {}) {
        val ext = item.name.substringAfterLast('.', "")
        val newName = if (ext.isNotEmpty()) "${newStem.trim()}.$ext" else newStem.trim()
        if (newName == item.name) {
            onFinished(true)
            return
        }
        withWriteAccess(listOf(item)) { granted ->
            if (!granted) {
                onFinished(false)
                return@withWriteAccess
            }
            val ok = AlbumOps.rename(context, item, newName)
            toast(if (ok) "Renamed to $newName" else "Android didn't allow that name")
            if (ok) vm.refresh()
            onFinished(ok)
        }
    }

    fun refreshGallery() = vm.refresh()

    /** Adds or removes items from Favorites (Android's shared favorite flag). */
    fun setFavorite(items: List<GalleryItem>, favorite: Boolean, onFinished: (Boolean) -> Unit = {}) {
        val toChange = items.filter { it.isFavorite != favorite }
        if (toChange.isEmpty()) {
            onFinished(true)
            return
        }
        request({
            MediaStore.createFavoriteRequest(context.contentResolver, toChange.map { it.uri }, favorite)
        }) { granted ->
            if (granted) vm.refresh()
            onFinished(granted)
        }
    }

    /** [which] is WallpaperManager.FLAG_SYSTEM (home), FLAG_LOCK, or both OR'd together. */
    fun setWallpaper(item: GalleryItem, which: Int) {
        vm.viewModelScope.launch {
            val ok = MediaSaver.setWallpaper(context, item.uri, which)
            toast(if (ok) "Wallpaper set" else "Couldn't set this as wallpaper")
        }
    }

    fun share(items: List<GalleryItem>) {
        if (items.isEmpty()) return
        if (items.size == 1) {
            shareMedia(context, items[0].uri, items[0].mimeType)
            return
        }
        val type = when {
            items.all { !it.isVideo } -> "image/*"
            items.all { it.isVideo } -> "video/*"
            else -> "*/*"
        }
        val send = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            this.type = type
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList<Uri>(items.map { it.uri }))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(send, null))
    }

    /** Asks Android for permission to modify [items], then runs [block] with the answer. */
    fun withWriteAccess(items: List<GalleryItem>, block: suspend (granted: Boolean) -> Unit) {
        request({ MediaStore.createWriteRequest(context.contentResolver, items.map { it.uri }) }) { granted ->
            vm.viewModelScope.launch { block(granted) }
        }
    }

    /** Shows Android's confirmation (skipped if the app has Media management access). */
    private fun request(build: () -> PendingIntent, onResult: (Boolean) -> Unit) {
        try {
            val intent = build()
            pending = onResult
            launcher.launch(IntentSenderRequest.Builder(intent.intentSender).build())
        } catch (e: Exception) {
            // Android caps how many items one request can carry
            pending = null
            toast(TOO_MANY)
            onResult(false)
        }
    }

    internal fun onResult(ok: Boolean) {
        val done = pending ?: return
        pending = null
        done(ok)
    }

    /** Called when the screen that owns these actions goes away, so stale callbacks never run. */
    internal fun dispose() {
        pending = null
    }

    fun toast(message: String) {
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }

    private companion object {
        const val TOO_MANY = "Too many items for one request. Try a smaller selection."
    }
}

@Composable
fun rememberMediaActions(vm: GalleryViewModel): MediaActions {
    val context = LocalContext.current
    val actions = remember(vm) { MediaActions(context, vm) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) {
        actions.onResult(it.resultCode == Activity.RESULT_OK)
    }
    actions.launcher = launcher
    DisposableEffect(actions) { onDispose { actions.dispose() } }
    return actions
}

/**
 * "Media management" special access: with it, moves, renames, favorites and deletes
 * happen without a confirmation dialog each time. Re-checked whenever the app resumes.
 */
@Composable
fun rememberCanManageMedia(): Boolean {
    val context = LocalContext.current
    var granted by remember { mutableStateOf(MediaStore.canManageMedia(context)) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) granted = MediaStore.canManageMedia(context)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    return granted
}

fun openManageMediaSettings(context: Context) {
    context.startActivity(
        Intent(Settings.ACTION_REQUEST_MANAGE_MEDIA, Uri.parse("package:${context.packageName}"))
    )
}
