package dev.ipf.whitenoise.android.media

import java.io.Closeable
import java.io.File
import java.io.OutputStream

/** Plaintext attachment data that may be memory-backed or an authenticated private-file lease. */
internal sealed interface AttachmentPlaintext : Closeable {
    val size: Long

    fun copyTo(output: OutputStream)

    fun readBytes(): ByteArray

    class Bytes(
        val bytes: ByteArray,
    ) : AttachmentPlaintext {
        override val size: Long = bytes.size.toLong()

        override fun copyTo(output: OutputStream) {
            output.write(bytes)
        }

        override fun readBytes(): ByteArray = bytes

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

        override fun readBytes(): ByteArray = file.readBytes()

        override fun close() = lease.close()
    }
}
