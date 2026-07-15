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

    @Volatile
    private var wipeGeneration = 0

    @Volatile
    private var wipesInProgress = 0

    @VisibleForTesting
    @Volatile
    var commitAwaiterForTests: (() -> Unit)? = null

    @VisibleForTesting
    @Volatile
    var renameFileForTests: ((File, File) -> Boolean)? = null

    @VisibleForTesting
    @Volatile
    var deleteFileForTests: ((File) -> Boolean)? = null

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
        wipesInProgress++
    }

    @Synchronized
    fun onWipeFinished() {
        check(wipesInProgress > 0) { "attachment cache wipe finished without a matching start" }
        wipesInProgress--
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
        val stripe = stripeFor(attachmentKey)
        synchronized(this) {
            if (wipesInProgress > 0) return null
            synchronized(stripe) {
                if (stripe.invalidatingCount > 0) return null
                return Permit(wipeGeneration, stripe.generation)
            }
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
        val stripe = stripeFor(attachmentKey)
        synchronized(stripe) {
            if (!permitStillValid(stripe, permit)) {
                runCatching { tmp.delete() }
                return false
            }
            val renamed =
                try {
                    renameFileForTests?.invoke(tmp, finalFile) ?: tmp.renameTo(finalFile)
                } catch (throwable: Throwable) {
                    runCatching { tmp.delete() }
                    throw IOException("failed to publish attachment cache ${finalFile.name}", throwable)
                }
            if (!renamed) {
                runCatching { tmp.delete() }
                if (!permitStillValid(stripe, permit)) return false
                throw IOException("failed to publish attachment cache ${finalFile.name}")
            }
            if (!permitStillValid(stripe, permit)) {
                deleteFinalFile(finalFile)
                return false
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
        var deleteError: IOException? = null
        withContext(Dispatchers.IO) {
            synchronized(stripe) {
                stripe.invalidatingCount++
                stripe.generation++
                try {
                    deleteFinalFile(finalFile)
                } catch (e: IOException) {
                    // A failed first delete must not skip plaintext eviction.
                    deleteError = e
                }
            }
            try {
                try {
                    evictPlaintext()
                } catch (t: Throwable) {
                    evictionError = t
                }
            } finally {
                synchronized(stripe) {
                    try {
                        stripe.generation++
                        deleteFinalFile(finalFile)
                    } catch (e: IOException) {
                        if (deleteError == null) deleteError = e
                    } finally {
                        stripe.invalidatingCount--
                    }
                }
            }
        }
        deleteError?.let { throw it }
        evictionError?.let { throw it }
    }

    private fun prepareParentForTempWrite(
        attachmentKey: String,
        finalFile: File,
        permit: Permit,
    ): Boolean {
        val parent = finalFile.parentFile ?: return false
        val stripe = stripeFor(attachmentKey)
        synchronized(stripe) {
            if (!permitStillValid(stripe, permit)) return false
            parent.mkdirs()
            return permitStillValid(stripe, permit)
        }
    }

    @Throws(IOException::class)
    private fun deleteFinalFile(finalFile: File) {
        // delete() first, then treat an already-gone file as success — avoids the
        // exists()/delete() race where a concurrent removal makes delete() fail.
        val deleted =
            try {
                (deleteFileForTests?.invoke(finalFile) ?: finalFile.delete()) || !finalFile.exists()
            } catch (throwable: Throwable) {
                throw IOException("failed to delete corrupt attachment cache ${finalFile.absolutePath}", throwable)
            }
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
