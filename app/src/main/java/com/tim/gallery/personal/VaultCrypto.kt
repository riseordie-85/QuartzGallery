package com.tim.gallery.personal

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Encryption for the personal folder.
 *
 * Files are encrypted with a random 256-bit AES key. That key is itself encrypted ("wrapped")
 * with a key that lives in the phone's secure hardware (Android Keystore) and can never be copied
 * out, so the personal folder's files are unreadable anywhere but in this app on this phone.
 *
 * File format: a small header, then the content in 1 MB chunks, each sealed with AES-GCM
 * (its own random nonce, tagged with its chunk number so chunks can't be reordered), and an end
 * marker so a cut-off file is detected. Chunking keeps memory use flat even for big videos.
 */
object VaultCrypto {
    private const val WRAP_ALIAS = "quartz_vault_wrap"
    private const val MAGIC = 0x51564C54 // "QVLT"
    private const val CHUNK = 1 shl 20
    private const val IV_BYTES = 12
    private const val TAG_BITS = 128

    @Volatile
    private var dataKey: SecretKey? = null
    private val random = SecureRandom()

    /** Set once when the hardware key is gone and a new data key had to be made (see [consumeKeyReset]). */
    @Volatile
    private var keyWasReset = false

    /**
     * True (once) if the old data key was lost — files encrypted with it can never be read again,
     * so the caller should clear them out.
     */
    fun consumeKeyReset(): Boolean = synchronized(this) {
        val r = keyWasReset
        keyWasReset = false
        r
    }

    fun key(context: Context): SecretKey =
        dataKey ?: synchronized(this) { dataKey ?: loadOrCreate(context).also { dataKey = it } }

    private fun wrappingKey(ks: KeyStore): SecretKey {
        (ks.getKey(WRAP_ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(WRAP_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }

    private fun loadOrCreate(context: Context): SecretKey {
        val prefs = context.getSharedPreferences("vault_key", Context.MODE_PRIVATE)
        val wrapped = prefs.getString("dek", null)
        val iv = prefs.getString("iv", null)
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (wrapped != null && iv != null) {
            if (ks.containsAlias(WRAP_ALIAS)) {
                // Any error here is passed on (never replace a key we might still be able to use)
                val c = Cipher.getInstance("AES/GCM/NoPadding")
                c.init(Cipher.DECRYPT_MODE, wrappingKey(ks), GCMParameterSpec(TAG_BITS, b64d(iv)))
                return SecretKeySpec(c.doFinal(b64d(wrapped)), "AES")
            }
            // The hardware key itself is gone (e.g. app data copied to another phone):
            // nothing encrypted before can ever be read again
            keyWasReset = true
        }
        val wk = wrappingKey(ks)
        val raw = ByteArray(32).also { random.nextBytes(it) }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, wk)
        val enc = c.doFinal(raw)
        prefs.edit().putString("dek", b64e(enc)).putString("iv", b64e(c.iv)).commit()
        return SecretKeySpec(raw, "AES")
    }

    /** Encrypts everything from [input] into [output]. Returns how many plain bytes were read. */
    fun encrypt(input: InputStream, output: OutputStream, key: SecretKey, onBytes: (Long) -> Unit = {}): Long {
        val out = DataOutputStream(BufferedOutputStream(output, 64 * 1024))
        out.writeInt(MAGIC)
        out.writeInt(CHUNK)
        val buf = ByteArray(CHUNK)
        var index = 0
        var total = 0L
        while (true) {
            val n = readFully(input, buf)
            if (n <= 0) break
            val iv = ByteArray(IV_BYTES).also { random.nextBytes(it) }
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
            c.updateAAD(ByteBuffer.allocate(4).putInt(index).array())
            val ct = c.doFinal(buf, 0, n)
            out.writeInt(ct.size)
            out.write(iv)
            out.write(ct)
            index++
            total += n
            onBytes(total)
            if (n < CHUNK) break
        }
        out.writeInt(0) // end marker
        out.flush()
        return total
    }

    /** Checks that a file decrypts completely. Returns its plain size; throws if it's damaged. */
    fun verify(input: InputStream, key: SecretKey): Long {
        var count = 0L
        val sink = object : OutputStream() {
            override fun write(b: Int) {
                count++
            }

            override fun write(b: ByteArray, off: Int, len: Int) {
                count += len
            }
        }
        decrypt(input, sink, key)
        return count
    }

    /** Decrypts a file made by [encrypt]. Throws if it was changed or cut off. */
    fun decrypt(input: InputStream, output: OutputStream, key: SecretKey, onBytes: (Long) -> Unit = {}) {
        val inp = DataInputStream(BufferedInputStream(input, 64 * 1024))
        if (inp.readInt() != MAGIC) throw IOException("Not a personal folder file")
        val chunk = inp.readInt()
        var index = 0
        var total = 0L
        val iv = ByteArray(IV_BYTES)
        while (true) {
            val len = inp.readInt()
            if (len == 0) break
            if (len < 16 || len > chunk + 16) throw IOException("Damaged file")
            inp.readFully(iv)
            val ct = ByteArray(len)
            inp.readFully(ct)
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
            c.updateAAD(ByteBuffer.allocate(4).putInt(index).array())
            val plain = c.doFinal(ct)
            output.write(plain)
            index++
            total += plain.size
            onBytes(total)
        }
        output.flush()
    }

    private fun readFully(input: InputStream, buf: ByteArray): Int {
        var off = 0
        while (off < buf.size) {
            val r = input.read(buf, off, buf.size - off)
            if (r < 0) break
            off += r
        }
        return off
    }

    private fun b64e(b: ByteArray) = Base64.encodeToString(b, Base64.NO_WRAP)
    private fun b64d(s: String) = Base64.decode(s, Base64.NO_WRAP)
}
