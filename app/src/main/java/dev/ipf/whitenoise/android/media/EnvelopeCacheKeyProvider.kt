package dev.ipf.whitenoise.android.media

import android.util.AtomicFile
import java.io.DataInputStream
import java.io.FileNotFoundException
import java.io.IOException
import java.nio.ByteBuffer
import java.security.GeneralSecurityException
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Keeps one wrapped display-avatar key in no-backup storage. All key and file work runs off main. */
internal class EnvelopeCacheKeyProvider(
    private val wrapped: AtomicFile,
    private val wrappingKey: DiskByteCacheKeyProvider,
    private val resetWrappingKey: () -> Unit,
    private val clearDerivatives: () -> Boolean,
) {
    private var cached: SecretKey? = null

    @Synchronized
    fun getOrCreate(): SecretKey {
        cached?.let { return it }
        val key =
            try {
                unwrap()
            } catch (_: FileNotFoundException) {
                create()
            } catch (_: GeneralSecurityException) {
                recover()
            } catch (_: IOException) {
                recover()
            }
        cached = key
        return key
    }

    /** Retires the software key, cached derivatives and the isolated Android Keystore alias. */
    @Synchronized
    fun clear() {
        cached = null
        wrapped.delete()
        val keyReset = runCatching(resetWrappingKey)
        val diskClear =
            runCatching {
                if (!clearDerivatives()) throw IOException("Could not clear display avatar files")
            }
        keyReset
            .onFailure { keyFailure ->
                diskClear.exceptionOrNull()?.let(keyFailure::addSuppressed)
            }.getOrThrow()
        diskClear.getOrThrow()
    }

    private fun recover(): SecretKey {
        clear()
        return create()
    }

    private fun unwrap(): SecretKey {
        val envelope = ByteArray(ENVELOPE_BYTES)
        val exactLength =
            DataInputStream(wrapped.openRead()).use { stream ->
                stream.readFully(envelope)
                stream.read() == -1
            }
        val header = ByteBuffer.wrap(envelope)
        val validHeader = header.int == MAGIC && header.short == VERSION && header.short.toInt() == IV_BYTES
        if (!exactLength || !validHeader) throw IOException("Invalid display avatar key envelope")
        val iv = ByteArray(IV_BYTES)
        header.get(iv)
        val cipher = Cipher.getInstance(ALGORITHM)
        cipher.init(Cipher.DECRYPT_MODE, wrappingKey.getOrCreate(), GCMParameterSpec(TAG_BITS, iv))
        cipher.updateAAD(associatedData)
        val raw = cipher.doFinal(envelope, PREFIX_BYTES + IV_BYTES, WRAPPED_BYTES)
        return try {
            if (raw.size != KEY_BYTES) throw IOException("Invalid display avatar key size")
            SecretKeySpec(raw, "AES")
        } finally {
            raw.fill(0)
        }
    }

    private fun create(): SecretKey {
        val raw = ByteArray(KEY_BYTES)
        SecureRandom().nextBytes(raw)
        try {
            val cipher = Cipher.getInstance(ALGORITHM)
            cipher.init(Cipher.ENCRYPT_MODE, wrappingKey.getOrCreate())
            cipher.updateAAD(associatedData)
            val iv = cipher.iv
            if (iv.size != IV_BYTES) throw IOException("Invalid display avatar key IV size")
            val envelope =
                ByteBuffer
                    .allocate(ENVELOPE_BYTES)
                    .putInt(MAGIC)
                    .putShort(VERSION)
                    .putShort(IV_BYTES.toShort())
                    .put(iv)
                    .put(cipher.doFinal(raw))
                    .array()
            val out = wrapped.startWrite()
            try {
                out.write(envelope)
                wrapped.finishWrite(out)
            } catch (failure: IOException) {
                wrapped.failWrite(out)
                throw failure
            }
            return SecretKeySpec(raw, "AES")
        } finally {
            raw.fill(0)
        }
    }

    private companion object {
        const val ALGORITHM = "AES/GCM/NoPadding"
        const val MAGIC = 0x574E4441
        const val VERSION: Short = 1
        const val PREFIX_BYTES = 8
        const val IV_BYTES = 12
        const val KEY_BYTES = 32
        const val TAG_BITS = 128
        const val WRAPPED_BYTES = KEY_BYTES + TAG_BITS / Byte.SIZE_BITS
        const val ENVELOPE_BYTES = PREFIX_BYTES + IV_BYTES + WRAPPED_BYTES
        val associatedData = "whitenoise.display_avatars.dek.v1".encodeToByteArray() + byteArrayOf(0, VERSION.toByte())
    }
}
