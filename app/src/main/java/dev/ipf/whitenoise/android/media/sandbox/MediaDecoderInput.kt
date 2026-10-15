package dev.ipf.whitenoise.android.media.sandbox

import android.os.ParcelFileDescriptor

/** An exact regular input capability, including truncation/trailing-byte guards, for both workers. */
@Suppress("ReturnCount") // Length and read failures stop before native parsing.
internal fun readDecoderInput(input: ParcelFileDescriptor): ByteArray? {
    val size = input.statSize
    if (size !in 1..MediaDecodePolicy.MAX_INPUT_BYTES.toLong()) return null
    return ParcelFileDescriptor.AutoCloseInputStream(ParcelFileDescriptor.dup(input.fileDescriptor)).use { stream ->
        val bytes = ByteArray(size.toInt())
        var offset = 0
        while (offset < bytes.size) {
            val count = stream.read(bytes, offset, bytes.size - offset)
            if (count <= 0) return null
            offset += count
        }
        bytes.takeIf { stream.read() == -1 }
    }
}
