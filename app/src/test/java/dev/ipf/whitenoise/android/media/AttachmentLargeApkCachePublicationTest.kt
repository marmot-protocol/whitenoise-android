package dev.ipf.whitenoise.android.media

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files

class AttachmentLargeApkCachePublicationTest {
    /** A native-retained APK larger than the former 128 MiB cap reaches a complete FileProvider source. */
    @Test
    fun largeApkLeasePublishesWithoutTruncation() =
        runBlocking {
            val root = Files.createTempDirectory("large-apk-source").toFile()
            try {
                val leaseDirectory = File(root, MediaCacheDirs.NATIVE_ATTACHMENT_LEASES).apply { mkdirs() }
                val source = File(leaseDirectory, "release.lease")
                val apkBytes = 143_162_216L
                RandomAccessFile(source, "rw").use { it.setLength(apkBytes) }
                val finalFile = File(File(root, MediaCacheDirs.SHARED), "release.apk")
                val key = AttachmentCachePublication.attachmentKey("large-apk", 0, 1uL)

                val published =
                    AttachmentCachePublication.publishSourceAfterLoad(key, finalFile) {
                        AttachmentPlaintext.Lease(DiskByteCacheLease(source))
                    }

                assertTrue(published)
                assertEquals(apkBytes, finalFile.length())
                assertFalse(source.exists())
                assertTrue(finalFile.isFile)
            } finally {
                root.deleteRecursively()
            }
        }
}
