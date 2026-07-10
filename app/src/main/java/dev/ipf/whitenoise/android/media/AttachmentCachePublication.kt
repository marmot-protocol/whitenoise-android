package dev.ipf.whitenoise.android.media

import androidx.annotation.VisibleForTesting
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicLong

/**
 * Bounded coordinator for voice/video attachment cache publication and
 * invalidation. Mirrors [DiskByteCache]'s wipe generation guard (#154) and
 * coordinates publish vs invalidate through 64 fixed generation stripes (#1241).
 *
 * Invalidation generation is tracked per stripe, not per attachment key: a bump
 * on one stripe may conservatively reject an unrelated in-flight publish that
 * hashes to the same stripe; callers can retry after invalidation completes.
 */
internal object AttachmentCachePublication {
    private const val STRIPE_COUNT = 64
    private val tmpCounter = AtomicLong()
    private val stripes = Array(STRIPE_COUNT) { Stripe() }
    private var wipeGeneration = 0

    @VisibleForTesting
    @Volatile
    var commitAwaiterForTests: (() -> Unit)? = null

    data class Permit(
        val wipeGeneration: Int,
        val stripeGeneration: Int,
    )

    private class Stripe {
        var generation = 0
        var invalidatingCount = 0
    }

    @Synchronized
    fun onWipeStarted() {
        wipeGeneration++
    }

    fun attachmentKey(
        messageIdHex: String,
        attachmentIndex: Int,
        sourceEpoch: ULong,
    ): String = "$messageIdHex#$attachmentIndex#$sourceEpoch"

    /**
     * Capture a publication permit before any retained/controller plaintext load.
     * Returns null when the stripe is mid-invalidation.
     */
    fun capturePermit(attachmentKey: String): Permit? {
        synchronized(this) {
            val stripe = stripeFor(attachmentKey)
            if (stripe.invalidatingCount > 0) return null
            return Permit(wipeGeneration, stripe.generation)
        }
    }

    /**
     * Load plaintext under a permit captured before [loadBytes], then publish.
     */
    @Throws(IOException::class)
    suspend fun publishAfterLoad(
        attachmentKey: String,
        finalFile: File,
        loadBytes: suspend () -> ByteArray,
    ): Boolean {
        val permit = capturePermit(attachmentKey) ?: return false
        return withContext(Dispatchers.IO) {
            val bytes = loadBytes()
            publishWithPermit(attachmentKey, finalFile, bytes, permit)
        }
    }

    @Throws(IOException::class)
    fun publishWithPermit(
        attachmentKey: String,
        finalFile: File,
        bytes: ByteArray,
        permit: Permit,
    ): Boolean {
        if (bytes.isEmpty()) {
            throw IOException("refusing to publish an empty attachment cache ${finalFile.name}")
        }
        if (!prepareParentForTempWrite(attachmentKey, finalFile, permit)) {
            return false
        }
        val tmp = writeTempFile(finalFile, bytes) ?: return false
        commitAwaiterForTests?.invoke()
        synchronized(this) {
            val stripe = stripeFor(attachmentKey)
            if (!permitStillValid(stripe, permit)) {
                runCatching { tmp.delete() }
                return false
            }
            finalFile.parentFile?.mkdirs()
            if (!permitStillValid(stripe, permit)) {
                runCatching { tmp.delete() }
                return false
            }
            if (!tmp.renameTo(finalFile)) {
                runCatching { tmp.delete() }
                throw IOException("failed to publish attachment cache ${finalFile.name}")
            }
            return true
        }
    }

    @Throws(IOException::class)
    suspend fun invalidateAttachmentCache(
        attachmentKey: String,
        finalFile: File,
        evictPlaintext: suspend () -> Unit,
    ) {
        val stripe = stripeFor(attachmentKey)
        var evictionError: Throwable? = null
        var finalDeleteError: IOException? = null
        withContext(Dispatchers.IO) {
            synchronized(this@AttachmentCachePublication) {
                stripe.invalidatingCount++
                try {
                    bumpStripeGenerationAndDelete(stripe, finalFile)
                } catch (t: Throwable) {
                    stripe.invalidatingCount--
                    throw t
                }
            }
            try {
                evictPlaintext()
            } catch (t: Throwable) {
                evictionError = t
            } finally {
                synchronized(this@AttachmentCachePublication) {
                    try {
                        bumpStripeGenerationAndDelete(stripe, finalFile)
                    } catch (e: IOException) {
                        finalDeleteError = e
                    } finally {
                        stripe.invalidatingCount--
                    }
                }
            }
        }
        finalDeleteError?.let { throw it }
        evictionError?.let { throw it }
    }

    private fun prepareParentForTempWrite(
        attachmentKey: String,
        finalFile: File,
        permit: Permit,
    ): Boolean {
        val parent = finalFile.parentFile ?: return false
        synchronized(this) {
            val stripe = stripeFor(attachmentKey)
            if (!permitStillValid(stripe, permit)) return false
            parent.mkdirs()
            return permitStillValid(stripe, permit)
        }
    }

    private fun bumpStripeGenerationAndDelete(
        stripe: Stripe,
        finalFile: File,
    ) {
        stripe.generation++
        val deleted = !finalFile.exists() || finalFile.delete()
        if (!deleted) {
            throw IOException("failed to delete corrupt attachment cache ${finalFile.absolutePath}")
        }
    }

    private fun permitStillValid(
        stripe: Stripe,
        permit: Permit,
    ): Boolean =
        permit.wipeGeneration == wipeGeneration &&
            permit.stripeGeneration == stripe.generation &&
            stripe.invalidatingCount == 0

    private fun stripeFor(attachmentKey: String): Stripe = stripes[attachmentKey.hashCode() and (STRIPE_COUNT - 1)]

    private fun writeTempFile(
        finalFile: File,
        bytes: ByteArray,
    ): File? {
        val parent = finalFile.parentFile ?: return null
        val tmp =
            File(
                parent,
                "${finalFile.name}.cache-${tmpCounter.incrementAndGet()}-${System.nanoTime()}.tmp",
            )
        return try {
            tmp.writeBytes(bytes)
            tmp
        } catch (_: IOException) {
            runCatching { tmp.delete() }
            null
        }
    }
}
