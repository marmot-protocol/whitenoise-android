package dev.ipf.whitenoise.android.media

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.OutputStream

/** Plaintext attachment data that may be memory-backed or an authenticated private-file lease. */
internal interface AttachmentPlaintext : Closeable {
    val size: Long

    /** Streams the complete plaintext into [output] without transferring resource ownership. */
    fun copyTo(output: OutputStream)

    class Bytes(
        val bytes: ByteArray,
    ) : AttachmentPlaintext {
        override val size: Long = bytes.size.toLong()

        /** Writes the bounded in-memory representation to [output]. */
        override fun copyTo(output: OutputStream) {
            output.write(bytes)
        }

        /** Byte-backed plaintext owns no external resource. */
        override fun close() = Unit
    }

    class Lease internal constructor(
        private val lease: DiskByteCacheLease,
    ) : AttachmentPlaintext {
        val file: File
            get() = lease.file

        override val size: Long
            get() = file.length()

        /** Streams the owner-private lease without loading it into a second byte array. */
        override fun copyTo(output: OutputStream) {
            file.inputStream().use { it.copyTo(output) }
        }

        /** Releases the lease and deletes its temporary plaintext file. */
        override fun close() = lease.close()
    }
}

/**
 * The plaintext is larger than the number of bytes a byte-returning read may hold on the JVM heap.
 *
 * [declaredBytes] is the size the source declared when it was rejected before any allocation, or null when the
 * source declared a smaller size and then streamed past [limitBytes]. Callers present the attachment as too large
 * to preview rather than as a failed transfer, because a retry cannot make it smaller.
 */
internal class AttachmentTooLargeToPresentException(
    val limitBytes: Long,
    val declaredBytes: Long?,
) : IOException(
        if (declaredBytes == null) {
            "attachment plaintext streamed past the $limitBytes byte read limit"
        } else {
            "attachment plaintext of $declaredBytes bytes exceeds the $limitBytes byte read limit"
        },
    )

/**
 * Materializes the plaintext as one array of at most [limit] bytes.
 *
 * The declared [AttachmentPlaintext.size] is checked before anything is allocated, so an oversized source is
 * rejected without a copy. While streaming, the sink refuses the first write that would carry it past [limit],
 * so a source whose declaration is dishonest can never allocate an array larger than the limit. An honest source
 * is copied once into an array of exactly its declared size. [cancellationCheck] runs before every chunk and may
 * throw to abandon the copy, the caller closes the source either way.
 */
internal fun AttachmentPlaintext.toByteArrayWithin(
    limit: Long,
    cancellationCheck: () -> Unit = {},
): ByteArray {
    require(limit in 0L..Int.MAX_VALUE.toLong()) { "read limit must fit a byte array" }
    val declared = size
    if (declared > limit) throw AttachmentTooLargeToPresentException(limit, declared)
    if (this is AttachmentPlaintext.Bytes) return bytes
    val sink = BoundedPlaintextSink(expected = declared.toInt(), limit = limit.toInt(), cancellationCheck)
    copyTo(sink)
    return sink.toByteArray()
}

/**
 * Copies a retained source onto the heap on the IO dispatcher, never past [maxBytes], stopping at the first chunk
 * after the caller's job is cancelled so a cancelled read does not finish copying a file nobody will use.
 */
internal suspend fun AttachmentPlaintext.readWithin(maxBytes: Long): ByteArray =
    withContext(Dispatchers.IO) {
        toByteArrayWithin(maxBytes) { ensureActive() }
    }

/**
 * Collects at most [limit] bytes into one array sized from the declared [expected] length.
 *
 * An honest source fills the array exactly and gets it back without a second copy. A source that outgrows its
 * declaration grows the array, never beyond [limit], and the first write that would exceed the limit is refused
 * before any allocation or copy for it happens.
 */
internal class BoundedPlaintextSink(
    expected: Int,
    private val limit: Int,
    private val cancellationCheck: () -> Unit = {},
) : OutputStream() {
    init {
        require(expected in 0..limit) { "declared size must not exceed the read limit" }
    }

    private var buffer = ByteArray(expected)
    private var written = 0

    /** Bytes the sink can hold before it must grow again, so tests can pin that it never exceeds the limit. */
    val capacity: Int
        get() = buffer.size

    /** Bytes accepted so far. */
    val size: Int
        get() = written

    /** Accepts one byte through the bounded array path. */
    override fun write(b: Int) {
        write(byteArrayOf(b.toByte()), 0, 1)
    }

    /** Accepts [len] bytes, refusing the whole write when it would carry the sink past the limit. */
    override fun write(
        b: ByteArray,
        off: Int,
        len: Int,
    ) {
        if (off < 0 || len < 0 || off > b.size - len) {
            throw IndexOutOfBoundsException("range $off+$len is outside an array of ${b.size} bytes")
        }
        cancellationCheck()
        if (len > limit - written) throw AttachmentTooLargeToPresentException(limit.toLong(), declaredBytes = null)
        if (len > buffer.size - written) grow(written + len)
        System.arraycopy(b, off, buffer, written, len)
        written += len
    }

    /** Doubles the array when it can, and otherwise grows exactly to what is needed, never past the limit. */
    private fun grow(needed: Int) {
        val doubled = buffer.size.toLong() * 2L
        val target = minOf(limit.toLong(), maxOf(needed.toLong(), doubled)).toInt()
        buffer = buffer.copyOf(target)
    }

    /** The collected bytes, which is the original array when the declared length was exact. */
    fun toByteArray(): ByteArray = if (written == buffer.size) buffer else buffer.copyOf(written)
}
