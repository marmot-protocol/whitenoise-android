package dev.ipf.whitenoise.android.share

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.ipf.whitenoise.android.PullRequestDeviceSmoke
import dev.ipf.whitenoise.android.media.AndroidKeystoreDiskByteCacheKeyProvider
import dev.ipf.whitenoise.android.media.DiskByteCache
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.security.KeyStore
import java.util.UUID

/** Exercises real Android filesystem, provider and Keystore behavior absent from Robolectric. */
@PullRequestDeviceSmoke
@RunWith(AndroidJUnit4::class)
class PrivateSharePlatformPersistenceDeviceTest {
    @Test
    fun externalIntentCannotImportOurOtherPrivateFileProvider() =
        kotlinx.coroutines.runBlocking {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val directory = File(context.cacheDir, "shared_media").apply { mkdirs() }
            val file = File(directory, "share-ownership-${UUID.randomUUID()}.bin")
            try {
                file.writeBytes(byteArrayOf(4, 5, 6))
                val uri =
                    androidx.core.content.FileProvider
                        .getUriForFile(context, "${context.packageName}.fileprovider", file)
                val request = ShareRequest(SharePayload(null, listOf(uri), null), null, UUID.randomUUID().toString())
                val result = ShareFileImporter(context).import(request)
                assertEquals(listOf(ShareImportError.Scheme), result.payload.importErrors)
                assertTrue(result.payload.streamUris.isEmpty())
                assertArrayEquals(byteArrayOf(4, 5, 6), file.readBytes())
            } finally {
                file.delete()
            }
        }

    @Test
    fun privateMetadataAndReadOnlyProviderSurviveRepositoryRecreation() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val files = PrivateShareFiles(context)
        val (uri, file) = files.newFile()
        try {
            val bytes = byteArrayOf(1, 2, 3)
            file.writeBytes(bytes)
            files.finish(uri, "fixture.md", "text/markdown", bytes.size.toLong())
            assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(file.toPath()))
            assertEquals("fixture.md", PrivateShareFiles(context).metadata(uri)?.getString("name"))
            assertEquals("text/markdown", context.contentResolver.getType(uri))
            context.contentResolver.openInputStream(uri)!!.use { assertArrayEquals(bytes, it.readBytes()) }
        } finally {
            files.delete(uri)
        }
    }

    @Test
    fun encryptedInterruptionMarkerSurvivesStoreRecreation() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val id = UUID.randomUUID().toString()
        val directory = File(context.noBackupFilesDir, "share-platform-test-$id")
        val alias = "share-platform-test-$id"

        fun store() =
            EncryptedPendingShareRequestStore(
                DiskByteCache(
                    directory,
                    PENDING_SHARE_REQUEST_CACHE_BYTES.toLong(),
                    AndroidKeystoreDiskByteCacheKeyProvider(alias),
                ),
            )
        try {
            val request =
                ShareRequest(
                    SharePayload(null, emptyList(), null, true, listOf(ShareImportError.Interrupted)),
                    shortcutId = null,
                    requestId = id,
                )
            assertTrue("Android encrypted marker must persist", store().save(request))
            assertEquals(request, store().load(id))
        } finally {
            directory.deleteRecursively()
            KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(alias)
        }
    }
}
