package dev.ipf.whitenoise.android.media

import androidx.annotation.VisibleForTesting
import dev.ipf.whitenoise.android.state.StalenessGuard
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
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

    private val wipeLifetime = StalenessGuard()

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
        val wipeGeneration: Long,
        val stripeGeneration: Long,
    )

    private class Stripe {
        val lifetime = StalenessGuard()
        var invalidatingCount = 0
    }

    /** Opens a process-wide wipe fence before any cache files are removed. */
    @Synchronized
    fun onWipeStarted() {
        wipeLifetime.advance()
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
        if (wipesInProgress > 0) return null
        val stripe = stripeFor(attachmentKey)
        synchronized(stripe) {
            // Take the coordinator only after the stripe so a same-stripe
            // waiter cannot convoy unrelated permit captures behind it.
            synchronized(this) {
                if (wipesInProgress > 0) return null
                if (stripe.invalidatingCount > 0) return null
                return Permit(wipeLifetime.capture(), stripe.lifetime.capture())
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

    /**
     * Loads and consumes exactly one source. File-backed leases may be moved into the
     * publication directory; callers must not reuse the source after returning it.
     */
    @Throws(IOException::class)
    suspend fun publishSourceAfterLoad(
        attachmentKey: String,
        finalFile: File,
        loadSource: suspend () -> AttachmentPlaintext,
    ): Boolean {
        val permit = capturePermit(attachmentKey) ?: return false
        return withContext(Dispatchers.IO) {
            loadSource().use { source -> publishSourceWithPermit(attachmentKey, finalFile, source, permit) }
        }
    }

    /** Commits one closeable plaintext source behind the captured wipe and invalidation fence. */
    @Suppress("ReturnCount", "ThrowsCount")
    private fun publishSourceWithPermit(
        attachmentKey: String,
        finalFile: File,
        source: AttachmentPlaintext,
        permit: Permit,
    ): Boolean {
        if (source.size <= 0L) throw IOException("refusing to publish an empty attachment cache ${finalFile.name}")
        AttachmentPlaintextCache.requireEntryWithinLimit(finalFile, source.size)
        if (!prepareParentForTempWrite(attachmentKey, finalFile, permit)) return false
        val tmp = writeTempFile(finalFile, source) ?: return false
        AttachmentPlaintextCache.protectPublicationFile(finalFile)
        return try {
            commitAwaiterForTests?.invoke()
            val stripe = stripeFor(attachmentKey)
            val published =
                synchronized(stripe) {
                    var accepted = false
                    wipeLifetime.runIfCurrent(permit.wipeGeneration) {
                        if (!permitStillValid(stripe, permit)) {
                            runCatching { tmp.delete() }
                            return@runIfCurrent
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
                            if (!permitStillValid(stripe, permit)) return@runIfCurrent
                            throw IOException("failed to publish attachment cache ${finalFile.name}")
                        }
                        if (!permitStillValid(stripe, permit)) {
                            deleteFinalFile(finalFile)
                            return@runIfCurrent
                        }
                        accepted = true
                    }
                    if (!accepted) runCatching { tmp.delete() }
                    accepted
                }
            if (published) AttachmentPlaintextCache.onPublished(finalFile)
            published
        } finally {
            AttachmentPlaintextCache.unprotectPublicationFile(tmp)
            AttachmentPlaintextCache.unprotectPublicationFile(finalFile)
        }
    }

    /** Publishes retained bytes through the same fenced and durable source path as file leases. */
    @Throws(IOException::class)
    fun publishWithPermit(
        attachmentKey: String,
        finalFile: File,
        bytes: ByteArray,
        permit: Permit,
    ): Boolean =
        AttachmentPlaintext.Bytes(bytes).use { source ->
            publishSourceWithPermit(attachmentKey, finalFile, source, permit)
        }

    /** Invalidates one stripe around plaintext eviction and final-file deletion. */
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
                stripe.lifetime.advance()
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
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (t: Throwable) {
                    evictionError = t
                }
            } finally {
                synchronized(stripe) {
                    try {
                        stripe.lifetime.advance()
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

    /** Creates the temp-file parent only while both captured publication lifetimes remain current. */
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

    /** Removes a final plaintext file or reports an IO failure without an exists/delete race. */
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

    /** Verifies that neither a wipe nor same-stripe invalidation superseded [permit]. */
    private fun permitStillValid(
        stripe: Stripe,
        permit: Permit,
    ): Boolean =
        wipeLifetime.isCurrent(permit.wipeGeneration) &&
            stripe.lifetime.isCurrent(permit.stripeGeneration) &&
            stripe.invalidatingCount == 0

    /** Exposes deterministic stripe selection for concurrency regression tests. */
    @VisibleForTesting
    internal fun stripeIndex(attachmentKey: String): Int = attachmentKey.hashCode() and (STRIPE_COUNT - 1)

    /** Maps an attachment key to its fixed publication/invalidation coordination stripe. */
    private fun stripeFor(attachmentKey: String): Stripe = stripes[stripeIndex(attachmentKey)]

    /** Moves or streams one source into a durable, publication-protected sibling temp file. */
    private fun writeTempFile(
        finalFile: File,
        source: AttachmentPlaintext,
    ): File? {
        val parent = finalFile.parentFile ?: return null
        val tmp = File(parent, "${finalFile.name}.cache-${tmpCounter.incrementAndGet()}-${System.nanoTime()}.tmp")
        val expectedSize = source.size
        AttachmentPlaintextCache.protectPublicationFile(tmp)
        return try {
            // Publication protection is path-based (trim exclusion), so it remains
            // valid when the lease inode is moved onto this already-protected path.
            if (source is AttachmentPlaintext.Lease && source.file.renameTo(tmp)) {
                FileOutputStream(tmp, true).use { output -> output.fd.sync() }
            } else {
                FileOutputStream(tmp).use { output ->
                    source.copyTo(output)
                    output.fd.sync()
                }
            }
            if (tmp.length() != expectedSize) {
                throw IOException("attachment cache source length changed while publishing ${finalFile.name}")
            }
            tmp
        } catch (throwable: Throwable) {
            runCatching { tmp.delete() }
            AttachmentPlaintextCache.unprotectPublicationFile(tmp)
            if (throwable is IOException) null else throw throwable
        }
    }
}
