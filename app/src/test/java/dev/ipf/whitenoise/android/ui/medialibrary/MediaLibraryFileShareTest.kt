package dev.ipf.whitenoise.android.ui.medialibrary

import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.ResolveInfo
import android.net.Uri
import android.provider.OpenableColumns
import androidx.test.core.app.ApplicationProvider
import dev.ipf.marmotkit.EncryptedMediaVersionFfi
import dev.ipf.marmotkit.MediaAttachmentReferenceFfi
import dev.ipf.marmotkit.MediaLocatorFfi
import dev.ipf.whitenoise.android.FileProviderStrategyCacheRule
import dev.ipf.whitenoise.android.media.AttachmentPlaintext
import dev.ipf.whitenoise.android.media.MediaCacheDirs
import dev.ipf.whitenoise.android.state.ATTACHMENT_PRESENTATION_MAX_BYTES
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.io.OutputStream

/** The Files tab's Share streams the whole verified file to the share sheet, whatever its size. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class MediaLibraryFileShareTest {
    @get:Rule
    val fileProviderStrategyCacheRule = FileProviderStrategyCacheRule()

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    /** A file over the 32 MiB preview budget is streamed whole to the share sheet under its own name. */
    @Test
    fun fileLargerThanThePreviewBudgetIsStreamedWholeToTheShareSheet() =
        runTest {
            clearSharedFiles()
            registerShareTarget(PDF)
            val source = PatternedPlaintext(ATTACHMENT_PRESENTATION_MAX_BYTES + CHUNK_BYTES + 7)

            shareLibraryFile(context, row("report.pdf", PDF), "Share", isCurrent = { true }) { source }

            val uri = sharedStream(PDF)
            assertEquals("report.pdf", displayName(uri))
            assertEquals(source.size, streamedLengthMatchingPattern(uri))
            assertTrue("the source must be streamed in bounded writes", source.largestWrite <= CHUNK_BYTES)
            assertEquals(1, source.closes)
        }

    /** An own pending item still held for upload shares its retained bytes. */
    @Test
    fun ownPendingRowSharesItsRetainedBytes() =
        runTest {
            clearSharedFiles()
            registerShareTarget(PDF)
            val bytes = "retained upload bytes".encodeToByteArray()

            shareLibraryFile(context, row("draft.pdf", PDF, mine = true), "Share", isCurrent = { true }) {
                AttachmentPlaintext.Bytes(bytes)
            }

            val uri = sharedStream(PDF)
            assertEquals("draft.pdf", displayName(uri))
            assertArrayEquals(bytes, context.contentResolver.openInputStream(uri)!!.use { it.readBytes() })
        }

    /** A row that leaves the screen while its file is staged opens no share sheet and leaves no copy behind. */
    @Test
    fun rowLeavingDuringStagingSharesNothingAndDeletesTheStagedFile() =
        runTest {
            clearSharedFiles()
            registerShareTarget(PDF)

            val failure =
                runCatching {
                    shareLibraryFile(context, row("report.pdf", PDF), "Share", isCurrent = { false }) {
                        AttachmentPlaintext.Bytes("secret".encodeToByteArray())
                    }
                }.exceptionOrNull()

            assertTrue("$failure", failure is CancellationException)
            assertNull(shadowOf(RuntimeEnvironment.getApplication()).nextStartedActivity)
            assertTrue(sharedFiles().isEmpty())
        }

    /** The row's Share reads the streamed attachment source, never the budgeted whole-file byte transfer. */
    @Test
    fun fileRowShareUsesTheStreamedSourceInsteadOfTheBudgetedByteTransfer() {
        val row =
            mediaLibrarySource()
                .substringAfter("internal fun FileLibraryRow(")
                .substringBefore("/** Links tab of the library. */")
                .replace(Regex("\\s+"), " ")

        assertTrue("shareLibraryFile(" in row)
        assertTrue("attachmentSource( controller, sharedRow.messageIdHex," in row)
        assertFalse("requestAttachmentTransfer(" in row)
        assertFalse("shareImage(" in row)
        assertFalse("fetchBytes" in row)
    }

    /** Plaintext of [size] bytes that writes a position pattern in bounded chunks and records how it was read. */
    private class PatternedPlaintext(
        override val size: Long,
    ) : AttachmentPlaintext {
        var largestWrite = 0
        var closes = 0

        override fun copyTo(output: OutputStream) {
            val chunk = ByteArray(CHUNK_BYTES)
            var position = 0L
            while (position < size) {
                val length = minOf(CHUNK_BYTES.toLong(), size - position).toInt()
                for (offset in 0 until length) chunk[offset] = patternByte(position + offset)
                output.write(chunk, 0, length)
                largestWrite = maxOf(largestWrite, length)
                position += length
            }
        }

        override fun close() {
            closes += 1
        }
    }

    /** Reads [uri] to its end, checking every byte against the pattern, and returns its length. */
    private fun streamedLengthMatchingPattern(uri: Uri): Long =
        context.contentResolver.openInputStream(uri)!!.use { input ->
            val buffer = ByteArray(CHUNK_BYTES)
            var position = 0L
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                for (offset in 0 until read) {
                    if (buffer[offset] != patternByte(position + offset)) {
                        throw AssertionError("byte ${position + offset} differs from the original file")
                    }
                }
                position += read
            }
            position
        }

    /** The single stream handed to the share sheet for a file of [mediaType]. */
    private fun sharedStream(mediaType: String): Uri {
        val chooser = requireNotNull(shadowOf(RuntimeEnvironment.getApplication()).nextStartedActivity)
        val send = requireNotNull(chooser.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java))
        assertEquals(Intent.ACTION_SEND, send.action)
        assertEquals(mediaType, send.type)
        return requireNotNull(send.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java))
    }

    private fun registerShareTarget(mediaType: String) {
        val application = ApplicationInfo().apply { packageName = SHARE_TARGET_PACKAGE }
        shadowOf(context.packageManager).addResolveInfoForIntent(
            Intent(Intent.ACTION_SEND).setType(mediaType),
            ResolveInfo().apply {
                activityInfo =
                    ActivityInfo().apply {
                        packageName = SHARE_TARGET_PACKAGE
                        name = "$SHARE_TARGET_PACKAGE.ShareActivity"
                        exported = true
                        applicationInfo = application
                    }
            },
        )
    }

    private fun row(
        fileName: String,
        mediaType: String,
        mine: Boolean = false,
    ) = SharedMediaRow(
        messageIdHex = "message",
        attachmentIndex = 0,
        reference =
            MediaAttachmentReferenceFfi(
                locators = listOf(MediaLocatorFfi(kind = "blossom-v1", value = "https://cdn.example.test/blob")),
                ciphertextSha256 = "a".repeat(64),
                plaintextSha256 = "b".repeat(64),
                nonceHex = "c".repeat(48),
                fileName = fileName,
                mediaType = mediaType,
                version = EncryptedMediaVersionFfi.V1,
                sourceEpoch = 1uL,
                dim = null,
                thumbhash = null,
            ),
        mine = mine,
        recordedAt = 1uL,
        sender = "sender",
    )

    private fun displayName(uri: Uri): String =
        context.contentResolver
            .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)!!
            .use { cursor ->
                assertTrue(cursor.moveToFirst())
                cursor.getString(cursor.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME))
            }

    private fun clearSharedFiles() {
        sharedFiles().forEach(File::delete)
    }

    private fun sharedFiles(): List<File> =
        File(context.cacheDir, MediaCacheDirs.SHARED)
            .listFiles()
            ?.filter(File::isFile)
            .orEmpty()

    private fun mediaLibrarySource(): String =
        listOf(
            File("src/main/java/dev/ipf/whitenoise/android/ui/medialibrary/MediaLibrary.kt"),
            File("app/src/main/java/dev/ipf/whitenoise/android/ui/medialibrary/MediaLibrary.kt"),
        ).firstOrNull(File::isFile)?.readText() ?: error("Missing MediaLibrary.kt source file")

    private companion object {
        const val PDF = "application/pdf"
        const val SHARE_TARGET_PACKAGE = "com.example.files"
        const val CHUNK_BYTES = 1024 * 1024

        fun patternByte(position: Long): Byte = (position % 251).toByte()
    }
}
