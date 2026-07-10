package dev.ipf.whitenoise.android.media

import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicLong

/**
 * Atomic publication for per-message voice/video attachment cache files.
 * Writes decrypted bytes to a unique sibling `.tmp` and renames onto the final
 * path only after the write completes, mirroring [DiskByteCache] (#1241).
 */
internal object AttachmentCacheIo {
    private val tmpCounter = AtomicLong()

    @Throws(IOException::class)
    fun writeBytesAtomically(
        finalFile: File,
        bytes: ByteArray,
    ) {
        if (bytes.isEmpty()) {
            throw IOException("refusing to publish an empty attachment cache ${finalFile.name}")
        }
        finalFile.parentFile?.mkdirs()
        val tmp =
            File(
                finalFile.parentFile,
                "${finalFile.name}.cache-${tmpCounter.incrementAndGet()}-${System.nanoTime()}.tmp",
            )
        try {
            tmp.writeBytes(bytes)
            if (!tmp.renameTo(finalFile)) {
                throw IOException("failed to publish attachment cache ${finalFile.name}")
            }
        } finally {
            if (tmp.exists()) runCatching { tmp.delete() }
        }
    }
}
