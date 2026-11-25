package dev.ipf.whitenoise.android.media

import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.GeneralSecurityException
import java.util.logging.Logger
import javax.crypto.AEADBadTagException
import javax.crypto.SecretKey

internal fun interface DiskByteCacheKeyProvider {
    /** Returns the non-exportable key used to authenticate encrypted cache envelopes. */
    @Throws(GeneralSecurityException::class, IOException::class)
    fun getOrCreate(): SecretKey
}

/** Captures wipe and expiry-sweep state for a deferred cache publication. */
internal data class DiskByteCachePublicationToken(
    val generation: Long,
    val expirySweepEpoch: Long,
)

/** Owner-private authenticated plaintext materialization. Closing deletes it. */
internal class DiskByteCacheLease internal constructor(
    val file: File,
    private val onDeleteFailure: (String) -> Unit = { message ->
        Logger.getLogger("DiskByteCacheLease").warning(message)
    },
) : Closeable {
    /** Deletes the owner-private plaintext lease, leaving failed cleanup for hydration. */
    override fun close() {
        val failure =
            runCatching { file.delete() || !file.exists() }
                .fold(
                    onSuccess = { deleted -> if (deleted) null else "delete returned false" },
                    onFailure = { error -> error.message ?: error::class.java.simpleName },
                )
        if (failure != null) {
            // Orphan lease files are retried by the next hydration sweep or clear().
            onDeleteFailure("failed to delete plaintext cache lease ${file.absolutePath}: $failure")
        }
    }
}

/** Reserves proportional process headroom before permitting a plaintext allocation. */
internal fun plaintextAllocationBudget(
    availableBytes: Long,
    maxMemoryBytes: Long,
): Long {
    val available = availableBytes.coerceAtLeast(0L)
    val reserve =
        minOf(
            32L * 1024L * 1024L,
            maxMemoryBytes.coerceAtLeast(0L) / 8L,
            available / 4L,
        )
    return (available - reserve).coerceAtLeast(0L)
}

/** Reports allocatable plaintext bytes after retaining process safety headroom. */
internal fun defaultAvailablePlaintextAllocationBytes(): Long {
    val runtime = Runtime.getRuntime()
    val available = runtime.maxMemory() - runtime.totalMemory() + runtime.freeMemory()
    return plaintextAllocationBudget(available, runtime.maxMemory())
}

/** Reads an exact byte count from an envelope stream or fails as truncated. */
@Throws(IOException::class)
internal fun InputStream.readExactly(size: Int): ByteArray {
    val bytes = ByteArray(size)
    var offset = 0
    while (offset < size) {
        val read = read(bytes, offset, size - offset)
        if (read < 0) throw IOException("truncated encrypted cache entry")
        offset += read
    }
    return bytes
}

/** Detects an AES-GCM authentication tag failure anywhere in the causal chain. */
internal fun Throwable.isAuthenticationFailure(): Boolean {
    val chain = generateSequence<Throwable>(this) { it.cause }
    return chain.any { it is AEADBadTagException }
}

/** Detects an authentication failure through wrapped I/O causes. */
internal fun IOException.isAuthenticationFailure(): Boolean = (this as Throwable).isAuthenticationFailure()

/** Identifies structural failures that make an encrypted envelope unsafe to retain. */
internal fun IOException.isMalformedEnvelope(): Boolean =
    message == "invalid cache envelope magic" ||
        message == "unsupported cache envelope version" ||
        message == "truncated encrypted cache entry" ||
        message == "truncated encrypted cache payload" ||
        message == "empty decrypted cache payload" ||
        message == "decrypted cache payload exceeds entry limit" ||
        message == "invalid decrypted cache chunk length" ||
        message == "encrypted cache payload exceeds entry limit"
