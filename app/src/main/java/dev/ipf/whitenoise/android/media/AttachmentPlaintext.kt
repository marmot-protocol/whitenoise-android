package dev.ipf.whitenoise.android.media

import java.io.Closeable
import java.io.File
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
