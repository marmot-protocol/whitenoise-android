package dev.ipf.whitenoise.android.media

import java.io.Closeable
import java.io.File
import java.io.OutputStream

/** Plaintext attachment data that may be memory-backed or an authenticated private-file lease. */
internal interface AttachmentPlaintext : Closeable {
    val size: Long

    fun copyTo(output: OutputStream)

    class Bytes(
        val bytes: ByteArray,
    ) : AttachmentPlaintext {
        override val size: Long = bytes.size.toLong()

        override fun copyTo(output: OutputStream) {
            output.write(bytes)
        }

        override fun close() = Unit
    }

    class Lease internal constructor(
        private val lease: DiskByteCacheLease,
    ) : AttachmentPlaintext {
        val file: File
            get() = lease.file

        override val size: Long
            get() = file.length()

        override fun copyTo(output: OutputStream) {
            file.inputStream().use { it.copyTo(output) }
        }

        override fun close() = lease.close()
    }
}
