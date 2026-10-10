package com.tim.gallery.personal

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.provider.MediaStore
import android.util.Base64
import android.util.LruCache
import android.util.Size
import com.tim.gallery.data.GalleryItem
import com.tim.gallery.data.MediaSaver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import kotlin.math.max
import kotlin.math.roundToInt

/** Something stored in the personal folder. */
data class VaultItem(
    val id: String,
    val name: String,
    val mimeType: String,
    val isVideo: Boolean,
    val dateMillis: Long,
    val durationMs: Long,
    val width: Int,
    val height: Int,
    val sizeBytes: Long,
    /** Where it came from, so "Move back" can put it in the same album. */
    val relativePath: String,
    val addedMillis: Long,
)

sealed interface PinResult {
    data object Ok : PinResult
    data class Wrong(val attemptsLeft: Int) : PinResult
    /** Too many wrong passcodes: everything in the folder was deleted. */
    data object Wiped : PinResult
}

/**
 * The personal folder: photos and videos moved out of the phone's shared storage into the app's
 * own private storage, encrypted (see [VaultCrypto]), behind a 6-digit passcode that's separate
 * from the phone's lock screen.
 *
 * Nothing here is backed up, and it's gone for good if the app is uninstalled, its data is
 * cleared, or too many wrong passcodes are entered.
 */
object Vault {
    const val PIN_LENGTH = 6
    const val MAX_ATTEMPTS_LIMIT = 10
    private const val PBKDF2_ROUNDS = 120_000

    private lateinit var app: Context
    /** For work that must finish even if the screen that started it closes. */
    private val background = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val writeLock = Mutex()
    private val thumbs = LruCache<String, Bitmap>(120)

    private val _unlocked = MutableStateFlow(false)
    val unlocked: StateFlow<Boolean> = _unlocked.asStateFlow()

    private val _items = MutableStateFlow<List<VaultItem>>(emptyList())
    /** Contents, newest first. Only filled in while unlocked. */
    val items: StateFlow<List<VaultItem>> = _items.asStateFlow()

    private val _setUp = MutableStateFlow(false)
    val setUp: StateFlow<Boolean> = _setUp.asStateFlow()

    fun init(context: Context) {
        app = context.applicationContext
        _setUp.value = prefs().contains("pin_hash")
        clearTemp()
    }

    private fun prefs() = app.getSharedPreferences("vault", Context.MODE_PRIVATE)
    private fun dir() = File(app.noBackupFilesDir, "vault").apply { mkdirs() }
    private fun tempDir() = File(app.cacheDir, "vault_tmp").apply { mkdirs() }
    private fun dataFile(id: String) = File(dir(), "$id.qv")
    private fun thumbFile(id: String) = File(dir(), "$id.qt")
    /** Per-item details (name, date, album...), encrypted. Written last, so an item only shows up once complete. */
    private fun metaFile(id: String) = File(dir(), "$id.qm")

    // ------------------------------------------------------------ settings

    val isSetUp: Boolean get() = _setUp.value
    val allowBiometric: Boolean get() = prefs().getBoolean("allow_bio", false)
    val allowDeviceCredential: Boolean get() = prefs().getBoolean("allow_cred", false)
    val maxAttempts: Int get() = prefs().getInt("max_attempts", MAX_ATTEMPTS_LIMIT)
    val failedAttempts: Int get() = prefs().getInt("failed", 0)

    /** Bumped on every lock, so an unlock that was still checking when you left doesn't stick. */
    @Volatile
    private var lockGeneration = 0

    suspend fun setUp(pin: String, allowBio: Boolean, allowCred: Boolean, attempts: Int) = withContext(Dispatchers.Default) {
        val gen = lockGeneration
        ensureKey()
        savePin(pin)
        prefs().edit()
            .putBoolean("allow_bio", allowBio)
            .putBoolean("allow_cred", allowCred)
            .putInt("max_attempts", attempts.coerceIn(1, MAX_ATTEMPTS_LIMIT))
            .putInt("failed", 0)
            .commit()
        _setUp.value = true
        unlockIfStill(gen)
    }

    fun setOptions(allowBio: Boolean, allowCred: Boolean, attempts: Int) {
        prefs().edit()
            .putBoolean("allow_bio", allowBio)
            .putBoolean("allow_cred", allowCred)
            .putInt("max_attempts", attempts.coerceIn(1, MAX_ATTEMPTS_LIMIT))
            .apply()
    }

    suspend fun changePin(newPin: String) = withContext(Dispatchers.Default) { savePin(newPin) }

    private fun savePin(pin: String) {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        prefs().edit()
            .putString("pin_salt", Base64.encodeToString(salt, Base64.NO_WRAP))
            .putString("pin_hash", Base64.encodeToString(hash(pin, salt), Base64.NO_WRAP))
            .commit()
    }

    private fun hash(pin: String, salt: ByteArray): ByteArray =
        SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(PBEKeySpec(pin.toCharArray(), salt, PBKDF2_ROUNDS, 256))
            .encoded

    private fun pinMatches(pin: String): Boolean {
        val p = prefs()
        val salt = p.getString("pin_salt", null)?.let { Base64.decode(it, Base64.NO_WRAP) } ?: return false
        val stored = p.getString("pin_hash", null)?.let { Base64.decode(it, Base64.NO_WRAP) } ?: return false
        return MessageDigest.isEqual(stored, hash(pin, salt))
    }

    /** Checks a passcode without unlocking or counting attempts (for changing settings while unlocked). */
    suspend fun verifyPin(pin: String): Boolean = withContext(Dispatchers.Default) { pinMatches(pin) }

    /** Checks a passcode to unlock. Wrong ones count toward the limit; reaching it empties the folder. */
    suspend fun checkPin(pin: String): PinResult = withContext(Dispatchers.Default) {
        val gen = lockGeneration
        // Count the attempt before checking, so closing the app mid-check can't skip it
        val failed = failedAttempts + 1
        prefs().edit().putInt("failed", failed).commit()
        if (pinMatches(pin)) {
            prefs().edit().putInt("failed", 0).commit()
            unlockIfStill(gen)
            PinResult.Ok
        } else if (failed >= maxAttempts) {
            wipeContents()
            prefs().edit().putInt("failed", 0).commit()
            PinResult.Wiped
        } else {
            PinResult.Wrong(maxAttempts - failed)
        }
    }

    /** After fingerprint/face/screen-lock confirmation. */
    suspend fun unlockWithSystemAuth() = withContext(Dispatchers.Default) {
        val gen = lockGeneration
        prefs().edit().putInt("failed", 0).commit()
        unlockIfStill(gen)
    }

    private suspend fun unlockIfStill(gen: Int) {
        ensureKey()
        val list = scanItems()
        cleanUpLeftovers()
        if (gen != lockGeneration) return // locked again while checking
        _items.value = list
        _unlocked.value = true
    }

    /** Loads the key; if the old one was lost for good, clears files nothing can read anymore. */
    private suspend fun ensureKey() {
        VaultCrypto.key(app)
        if (VaultCrypto.consumeKeyReset()) wipeContents()
    }

    fun lock() {
        lockGeneration++
        _unlocked.value = false
        _items.value = emptyList()
        thumbs.evictAll()
        clearTemp()
    }

    fun resetInBackground() {
        background.launch { reset() }
    }

    /** Deletes everything and forgets the passcode. */
    suspend fun reset() = withContext(Dispatchers.IO) {
        wipeContents()
        prefs().edit().clear().commit()
        _setUp.value = false
        lock()
    }

    private suspend fun wipeContents() = withContext(Dispatchers.IO) {
        writeLock.withLock {
            dir().listFiles()?.forEach { it.delete() }
        }
        thumbs.evictAll()
        _items.value = emptyList()
        clearTemp()
    }

    fun clearTemp() {
        if (!::app.isInitialized) return
        File(app.cacheDir, "vault_tmp").listFiles()?.forEach { it.delete() }
    }

    // ------------------------------------------------------------ listing

    /** Reads every item's details. An unreadable one is skipped (never deleted). */
    private fun scanItems(): List<VaultItem> {
        val key = VaultCrypto.key(app)
        return (dir().listFiles { f -> f.name.endsWith(".qm") } ?: emptyArray())
            .mapNotNull { f ->
                runCatching {
                    val out = ByteArrayOutputStream()
                    f.inputStream().use { VaultCrypto.decrypt(it, out, key) }
                    fromJson(JSONObject(out.toString(Charsets.UTF_8.name())))
                }.getOrNull()?.takeIf { dataFile(it.id).exists() }
            }
            .sortedByDescending { it.addedMillis }
    }

    private suspend fun refreshList() {
        if (_unlocked.value) _items.value = withContext(Dispatchers.IO) { scanItems() }
    }

    /** Removes pieces of moves that never finished (an app crash mid-copy), once they're an hour old. */
    private fun cleanUpLeftovers() {
        val cutoff = System.currentTimeMillis() - 60 * 60 * 1000L
        dir().listFiles()?.forEach { f ->
            val old = f.lastModified() < cutoff
            when {
                f.name.endsWith(".part") && old -> f.delete()
                f.name.endsWith(".qv") && old && !metaFile(f.name.removeSuffix(".qv")).exists() -> {
                    f.delete()
                    thumbFile(f.name.removeSuffix(".qv")).delete()
                }
            }
        }
    }

    private fun toJson(v: VaultItem) = JSONObject().apply {
        put("id", v.id)
        put("name", v.name)
        put("mime", v.mimeType)
        put("video", v.isVideo)
        put("date", v.dateMillis)
        put("duration", v.durationMs)
        put("w", v.width)
        put("h", v.height)
        put("size", v.sizeBytes)
        put("path", v.relativePath)
        put("added", v.addedMillis)
    }

    private fun fromJson(o: JSONObject) = VaultItem(
        id = o.getString("id"),
        name = o.optString("name", "photo"),
        mimeType = o.optString("mime", "image/jpeg"),
        isVideo = o.optBoolean("video", false),
        dateMillis = o.optLong("date", 0L),
        durationMs = o.optLong("duration", 0L),
        width = o.optInt("w", 0),
        height = o.optInt("h", 0),
        sizeBytes = o.optLong("size", 0L),
        relativePath = o.optString("path", ""),
        addedMillis = o.optLong("added", 0L),
    )

    /** Writes [bytes] encrypted to [target], flushed to disk before it's put in place. */
    private fun writeEncryptedSynced(target: File, input: java.io.InputStream, key: javax.crypto.SecretKey): Long {
        val part = File(target.parentFile, target.name + ".part")
        val n = FileOutputStream(part).use { out ->
            val count = VaultCrypto.encrypt(input, out, key)
            out.fd.sync()
            count
        }
        if (!part.renameTo(target)) {
            part.delete()
            throw IOException("Can't store ${target.name}")
        }
        return n
    }

    // ------------------------------------------------------------ moving in and out

    /**
     * Copies [items] into the folder, encrypted, and checks each copy reads back completely.
     * Doesn't touch the originals (the caller deletes them once this succeeds).
     * Returns the items that made it in, paired with their new ids.
     */
    suspend fun add(
        items: List<GalleryItem>,
        onProgress: (done: Int, total: Int) -> Unit,
    ): List<Pair<GalleryItem, String>> = withContext(Dispatchers.IO) {
        ensureKey()
        val key = VaultCrypto.key(app)
        val resolver = app.contentResolver
        val added = mutableListOf<Pair<GalleryItem, String>>()
        items.forEachIndexed { i, item ->
            val id = UUID.randomUUID().toString()
            try {
                writeLock.withLock {
                    val plainBytes = resolver.openInputStream(item.uri)?.use { input ->
                        writeEncryptedSynced(dataFile(id), input, key)
                    } ?: throw IOException("Can't read ${item.name}")
                    if (item.sizeBytes > 0 && plainBytes != item.sizeBytes) throw IOException("Copy of ${item.name} is incomplete")
                    // Read the copy back before anything is deleted
                    val check = dataFile(id).inputStream().use { VaultCrypto.verify(it, key) }
                    if (check != plainBytes) throw IOException("Copy of ${item.name} didn't verify")
                    // A small preview for the grid, made now while the original still exists
                    runCatching {
                        val thumb = resolver.loadThumbnail(item.uri, Size(512, 512), null)
                        val bytes = ByteArrayOutputStream().also { thumb.compress(Bitmap.CompressFormat.JPEG, 85, it) }.toByteArray()
                        writeEncryptedSynced(thumbFile(id), ByteArrayInputStream(bytes), key)
                    }
                    val entry = VaultItem(
                        id = id,
                        name = item.name,
                        mimeType = item.mimeType,
                        isVideo = item.isVideo,
                        dateMillis = item.dateMillis,
                        durationMs = item.durationMs,
                        width = item.width,
                        height = item.height,
                        sizeBytes = plainBytes,
                        relativePath = item.relativePath,
                        addedMillis = System.currentTimeMillis() + i,
                    )
                    writeEncryptedSynced(metaFile(id), ByteArrayInputStream(toJson(entry).toString().toByteArray()), key)
                }
                added += item to id
            } catch (e: Exception) {
                removeFiles(id)
            }
            onProgress(i + 1, items.size)
        }
        refreshList()
        added
    }

    private fun removeFiles(id: String) {
        metaFile(id).delete()
        dataFile(id).delete()
        thumbFile(id).delete()
        File(dir(), "$id.qv.part").delete()
        File(dir(), "$id.qt.part").delete()
        File(dir(), "$id.qm.part").delete()
        thumbs.remove(id)
    }

    /** Removes items from the folder for good. */
    suspend fun delete(ids: Collection<String>) = withContext(Dispatchers.IO) {
        if (ids.isEmpty()) return@withContext
        writeLock.withLock { ids.forEach { removeFiles(it) } }
        refreshList()
    }

    /**
     * Puts items back into the phone's gallery (in their original album when possible) and
     * removes them from the folder once the gallery copy is complete. Returns how many moved back.
     */
    suspend fun restore(items: List<VaultItem>, onProgress: (done: Int, total: Int) -> Unit): Int = withContext(Dispatchers.IO) {
        val key = VaultCrypto.key(app)
        val done = mutableListOf<String>()
        items.forEachIndexed { i, v ->
            val ok = insertIntoGallery(v, v.relativePath.ifBlank { MediaSaver.FOLDER_LABEL + "/" }, key) ||
                insertIntoGallery(v, MediaSaver.FOLDER_LABEL + "/", key)
            if (ok) done += v.id
            onProgress(i + 1, items.size)
        }
        delete(done)
        done.size
    }

    /** Undo for "Move to personal folder": puts these items straight back (no passcode needed). */
    fun restoreInBackground(ids: Collection<String>, onDone: (Int) -> Unit = {}) {
        background.launch {
            val n = runCatching {
                val wanted = ids.toSet()
                val items = withContext(Dispatchers.IO) { scanItems() }.filter { it.id in wanted }
                restore(items) { _, _ -> }
            }.getOrDefault(0)
            withContext(Dispatchers.Main) { onDone(n) }
        }
    }

    // ------------------------------------------------------------ backup files

    private const val BACKUP_MAGIC = 0x5147424B // "QGBK"
    private const val BACKUP_ROUNDS = 310_000
    private val CHECK = "QUARTZ-GALLERY-BACKUP".toByteArray()

    /**
     * Writes everything in the folder to [output] as one file encrypted with [password]
     * (separate from the passcode). Items are re-encrypted chunk by chunk, never written out
     * in the clear. Returns how many items were saved.
     */
    suspend fun exportBackup(output: java.io.OutputStream, password: String, onProgress: (Int, Int) -> Unit): Int =
        withContext(Dispatchers.IO) {
            val vaultKey = VaultCrypto.key(app)
            val items = scanItems()
            val salt = VaultCrypto.randomBytes(16)
            val backupKey = VaultCrypto.passwordKey(password, salt, BACKUP_ROUNDS)
            val out = java.io.DataOutputStream(java.io.BufferedOutputStream(output, 256 * 1024))
            out.writeInt(BACKUP_MAGIC)
            out.writeInt(1)
            out.write(salt)
            out.writeInt(BACKUP_ROUNDS)
            VaultCrypto.encrypt(ByteArrayInputStream(CHECK), out, backupKey)
            out.writeInt(items.size)
            items.forEachIndexed { i, item ->
                VaultCrypto.encrypt(ByteArrayInputStream(toJson(item).toString().toByteArray()), out, backupKey)
                dataFile(item.id).inputStream().buffered(256 * 1024).use { VaultCrypto.transcode(it, out, vaultKey, backupKey) }
                val thumb = thumbFile(item.id)
                out.writeBoolean(thumb.exists())
                if (thumb.exists()) thumb.inputStream().buffered().use { VaultCrypto.transcode(it, out, vaultKey, backupKey) }
                onProgress(i + 1, items.size)
            }
            out.flush()
            items.size
        }

    class WrongPasswordException : Exception("That password doesn't open this backup")

    /** Adds everything from a backup file into the folder. Returns how many items were added. */
    suspend fun importBackup(input: java.io.InputStream, password: String, onProgress: (Int, Int) -> Unit): Int =
        withContext(Dispatchers.IO) {
            ensureKey()
            val vaultKey = VaultCrypto.key(app)
            val inp = java.io.DataInputStream(java.io.BufferedInputStream(input, 256 * 1024))
            if (inp.readInt() != BACKUP_MAGIC) throw IOException("This isn't a Quartz Gallery backup file")
            inp.readInt() // version
            val salt = ByteArray(16).also { inp.readFully(it) }
            val rounds = inp.readInt()
            if (rounds !in 10_000..5_000_000) throw IOException("This backup file is damaged")
            val backupKey = VaultCrypto.passwordKey(password, salt, rounds)
            val check = ByteArrayOutputStream()
            try {
                VaultCrypto.decrypt(inp, check, backupKey, buffered = false)
            } catch (e: Exception) {
                throw WrongPasswordException()
            }
            if (!check.toByteArray().contentEquals(CHECK)) throw WrongPasswordException()
            val count = inp.readInt()
            var added = 0
            for (i in 0 until count) {
                ensureActive()
                val metaBytes = ByteArrayOutputStream()
                VaultCrypto.decrypt(inp, metaBytes, backupKey, buffered = false)
                val meta = fromJson(JSONObject(metaBytes.toString(Charsets.UTF_8.name())))
                val id = UUID.randomUUID().toString()
                writeLock.withLock {
                    try {
                        val part = File(dir(), "$id.qv.part")
                        FileOutputStream(part).use { out ->
                            VaultCrypto.transcode(inp, out, backupKey, vaultKey)
                            out.fd.sync()
                        }
                        if (!part.renameTo(dataFile(id))) throw IOException("Can't store ${meta.name}")
                        if (inp.readBoolean()) {
                            val tpart = File(dir(), "$id.qt.part")
                            FileOutputStream(tpart).use { out -> VaultCrypto.transcode(inp, out, backupKey, vaultKey) }
                            tpart.renameTo(thumbFile(id))
                        }
                        val entry = meta.copy(id = id, addedMillis = System.currentTimeMillis() + i)
                        writeEncryptedSynced(metaFile(id), ByteArrayInputStream(toJson(entry).toString().toByteArray()), vaultKey)
                        added++
                    } catch (e: Exception) {
                        removeFiles(id)
                        throw e
                    }
                }
                onProgress(i + 1, count)
            }
            refreshList()
            added
        }

    private fun insertIntoGallery(v: VaultItem, relativePath: String, key: javax.crypto.SecretKey): Boolean {
        val resolver = app.contentResolver
        val collection = if (v.isVideo) {
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        }
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, v.name)
            put(MediaStore.MediaColumns.MIME_TYPE, v.mimeType)
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
            if (v.dateMillis > 0) put(MediaStore.MediaColumns.DATE_TAKEN, v.dateMillis)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = try {
            resolver.insert(collection, values)
        } catch (e: Exception) {
            null
        } ?: return false
        return try {
            var written = 0L
            resolver.openOutputStream(uri, "w")?.use { out ->
                dataFile(v.id).inputStream().use { VaultCrypto.decrypt(it, out, key) { n -> written = n } }
            } ?: throw IOException("No output")
            if (v.sizeBytes > 0 && written != v.sizeBytes) throw IOException("Incomplete")
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            if (resolver.update(uri, values, null, null) != 1) throw IOException("Couldn't publish")
            true
        } catch (e: Exception) {
            runCatching { resolver.delete(uri, null, null) }
            false
        }
    }

    // ------------------------------------------------------------ viewing

    suspend fun thumbnail(id: String): Bitmap? {
        thumbs.get(id)?.let { return it }
        return withContext(Dispatchers.IO) {
            if (!_unlocked.value) return@withContext null
            runCatching {
                val out = ByteArrayOutputStream()
                thumbFile(id).inputStream().use { VaultCrypto.decrypt(it, out, VaultCrypto.key(app)) }
                val bytes = out.toByteArray()
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            }.getOrNull()?.also { if (_unlocked.value) thumbs.put(id, it) }
        }
    }

    /** Decrypts and decodes a photo (upright), at most [maxPx] on its longest side. */
    suspend fun decodeImage(item: VaultItem, maxPx: Int = 3072): Bitmap? = withContext(Dispatchers.IO) {
        if (!_unlocked.value) return@withContext null
        runCatching {
            val out = ByteArrayOutputStream(max(1024, item.sizeBytes.coerceAtMost(64L shl 20).toInt()))
            dataFile(item.id).inputStream().use { VaultCrypto.decrypt(it, out, VaultCrypto.key(app)) }
            val source = ImageDecoder.createSource(ByteBuffer.wrap(out.toByteArray()))
            ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                val w = info.size.width
                val h = info.size.height
                val longest = max(w, h)
                if (longest > maxPx) {
                    val s = maxPx.toFloat() / longest
                    decoder.setTargetSize((w * s).roundToInt().coerceAtLeast(1), (h * s).roundToInt().coerceAtLeast(1))
                }
            }
        }.getOrNull()
    }

    /**
     * Decrypts a video into the app's private cache so the player can read it. The copy is deleted
     * when the folder locks (leaving it, or the app going to the background).
     */
    suspend fun decryptToTemp(item: VaultItem, onProgress: (Float) -> Unit): File? = withContext(Dispatchers.IO) {
        if (!_unlocked.value) return@withContext null
        val ext = item.name.substringAfterLast('.', "mp4").take(5)
        val f = File(tempDir(), "${item.id}.$ext")
        try {
            if (!(f.exists() && f.length() == item.sizeBytes)) {
                f.outputStream().use { out ->
                    dataFile(item.id).inputStream().use {
                        VaultCrypto.decrypt(it, out, VaultCrypto.key(app)) { done ->
                            if (item.sizeBytes > 0) onProgress((done.toFloat() / item.sizeBytes).coerceIn(0f, 1f))
                        }
                    }
                }
            }
            // Locked while this was running? Don't leave a readable copy behind
            if (!_unlocked.value) {
                f.delete()
                null
            } else {
                f
            }
        } catch (e: Exception) {
            f.delete()
            null
        }
    }
}
