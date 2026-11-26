package dev.ipf.whitenoise.android.ui.conversation.media

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import dev.ipf.marmotkit.MessageDraftAttachmentFfi
import dev.ipf.whitenoise.android.media.AttachmentPlaintext
import dev.ipf.whitenoise.android.media.MediaCacheDirs
import dev.ipf.whitenoise.android.media.editor.DraftPreparedPhoto
import dev.ipf.whitenoise.android.media.sandbox.PdfPagePolicy
import dev.ipf.whitenoise.android.media.wipeSessionAttachmentPlaintext
import dev.ipf.whitenoise.android.ui.conversation.draftPresentationFile
import dev.ipf.whitenoise.android.ui.conversation.draftPresentationUri
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.io.RandomAccessFile

@RunWith(RobolectricTestRunner::class)
class ComposerDocumentReaderTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun routingMatchesTimelinePrecedence() {
        val cases =
            listOf(
                "application/pdf" to "document.html",
                "text/html" to "document.pdf",
                "application/octet-stream" to "DOCUMENT.PDF",
                "application/octet-stream" to "DOCUMENT.XHTML",
                "text/plain" to "notes.txt",
                "text/markdown" to "notes.md",
                "application/json" to "data.json",
                "application/octet-stream" to "archive.zip",
                "application/vnd.android.package-archive" to "app.apk",
            )
        for ((mime, name) in cases) {
            val target = attachmentReaderTarget(mime, name)
            val document = documentAttachmentKind(mime, name)
            assertEquals(document, target?.documentKind)
            assertEquals(if (document == null) textAttachmentCandidate(mime, name) else null, target?.textCandidate)
        }
        assertNotNull(attachmentReaderTarget("text/plain", "notes.txt"))
        assertNull(attachmentReaderTarget("application/zip", "archive.zip"))
    }

    @Test fun selectionClosesOnRemovalSendOrArtifactReplacement() {
        val uri = Uri.parse("content://expired/document")
        val artifact = prepared("application/pdf", "notes.pdf")
        val selection = ComposerDocumentSelection(uri, artifact)
        assertTrue(selection.isCurrent(listOf(uri, uri), mapOf(uri to artifact), false))
        assertTrue(selection.isCurrent(listOf(uri), mapOf(uri to artifact), false))
        assertFalse(selection.isCurrent(emptyList(), mapOf(uri to artifact), false))
        assertFalse(selection.isCurrent(listOf(uri), mapOf(uri to artifact), true))
        assertFalse(selection.isCurrent(listOf(uri), mapOf(uri to artifact.copy()), false))
        assertFalse(selection.isCurrent(listOf(uri), emptyMap(), false))
    }

    /** The restored large PDF previews from its existing private file, with no inline bytes or new reader copy. */
    @Test
    fun restoredPdfUsesItsBackedPresentationFile() =
        runBlocking {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val staged = prepared("application/pdf", "restored.pdf")
            val document =
                staged.copy(
                    attachment = staged.attachment.copy(plaintext = byteArrayOf()),
                    restoredFromNative = true,
                    storedBytes = 33L * 1024 * 1024,
                )
            val stored = checkNotNull(draftPresentationFile(context, document.attachment))
            try {
                RandomAccessFile(stored, "rw").use { it.setLength(document.storedBytes) }
                val restoredUri = checkNotNull(draftPresentationUri(context, stored, document.attachment))
                val pickerUri = Uri.parse("content://picker/document/restored.pdf")

                val target = composerDocumentReaderTarget(context, restoredUri, document)
                assertEquals(DocumentAttachmentKind.Pdf, target?.documentKind)
                withComposerPdfFile(context, document) { assertEquals(stored, it) }
                assertTrue(stored.isFile)
                assertNull(composerDocumentReaderTarget(context, pickerUri, document))
                assertNull(composerDocumentReaderTarget(context, pickerUri, document.copy(restoredFromNative = false)))
                val oversized = document.copy(storedBytes = PdfPagePolicy.MAX_INPUT + 1)
                assertNull(composerDocumentReaderTarget(context, restoredUri, oversized))
            } finally {
                stored.delete()
            }
        }

    @Test fun pdfFileLivesThroughUseAndIsDeletedOnDismiss() =
        runBlocking {
            val ready = CompletableDeferred<File>()
            val bytes = "%PDF prepared draft".toByteArray()
            val reader =
                launch {
                    withPrivatePdfFile(temporary.root, { AttachmentPlaintext.Bytes(bytes) }) {
                        assertTrue(it.readBytes().contentEquals(bytes))
                        ready.complete(it)
                        awaitCancellation()
                    }
                }
            val file = ready.await()
            assertTrue(file.isFile)
            reader.cancelAndJoin()
            assertFalse(file.exists())
        }

    @Test fun pdfFileIsDeletedOnFailureAndSuccess() =
        runBlocking {
            var owned: File? = null
            val result =
                runCatching {
                    withPrivatePdfFile(temporary.root, { AttachmentPlaintext.Bytes(byteArrayOf(1)) }) {
                        owned = it
                        error("renderer failed")
                    }
                }
            assertTrue(result.isFailure)
            assertFalse(checkNotNull(owned).exists())
            withPrivatePdfFile(temporary.root, { AttachmentPlaintext.Bytes(byteArrayOf(2)) }) { owned = it }
            assertFalse(checkNotNull(owned).exists())
        }

    /** The decrypted source is released once copied, so an open reader keeps one plaintext copy, not two. */
    @Test fun sourceIsClosedBeforeTheReaderUsesTheCopyAndOnFailure() =
        runBlocking {
            class Tracked(
                val oversized: Boolean,
            ) : AttachmentPlaintext {
                var closed = false
                override val size = if (oversized) PdfPagePolicy.MAX_INPUT + 1 else 1L

                override fun copyTo(output: OutputStream) = output.write(1)

                override fun close() {
                    closed = true
                }
            }
            val copied = Tracked(oversized = false)
            withPrivatePdfFile(temporary.root, { copied }) { assertTrue(copied.closed) }
            val refused = Tracked(oversized = true)
            assertTrue(runCatching { withPrivatePdfFile(temporary.root, { refused }) {} }.isFailure)
            assertTrue(refused.closed)
        }

    /** A kill skips the reader's cleanup; the next process removes that copy, and sign-out removes this one's. */
    @Test fun previewCopiesFromEarlierProcessesAreSweptAndSignOutWipesTheRest() =
        runBlocking {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val cache = context.cacheDir
            val root = File(cache, MediaCacheDirs.DOCUMENT_READER)
            val earlier = File(root, "process-earlier").apply { mkdirs() }
            val orphan = File(earlier, "preview-orphan.pdf").apply { writeText("orphan") }

            withPrivatePdfFile(cache, { AttachmentPlaintext.Bytes(byteArrayOf(1)) }) { open ->
                sweepStaleSharedMedia(context, maxAgeMillis = Long.MAX_VALUE)

                assertFalse(orphan.exists())
                assertFalse(earlier.exists())
                assertTrue("an open reader keeps its copy", open.exists())

                wipeSessionAttachmentPlaintext(cache) { name, failure -> throw AssertionError(name, failure) }

                assertFalse(root.exists())
            }
        }

    @Test fun oversizedPdfIsRejectedBeforeCreatingAFile() =
        runBlocking {
            val result =
                runCatching {
                    val oversized = AttachmentPlaintext.Bytes(ByteArray(PdfPagePolicy.MAX_INPUT.toInt() + 1))
                    withPrivatePdfFile(temporary.root, { oversized }) {
                        error("must not render")
                    }
                }
            assertTrue(result.exceptionOrNull() is IllegalArgumentException)
            assertTrue(
                temporary.root
                    .listFiles()
                    .orEmpty()
                    .isEmpty(),
            )
        }

    /** A source that streams past its declared size is cut off at the renderer limit and leaves no file behind. */
    @Test fun pdfStreamingPastTheLimitIsRejectedAndDeleted() =
        runBlocking {
            val misreported =
                object : AttachmentPlaintext {
                    override val size = 1L

                    override fun copyTo(output: OutputStream) {
                        val chunk = ByteArray(1024 * 1024)
                        var remaining = PdfPagePolicy.MAX_INPUT + 1
                        while (remaining > 0) {
                            val count = minOf(remaining, chunk.size.toLong()).toInt()
                            output.write(chunk, 0, count)
                            remaining -= count
                        }
                    }

                    override fun close() = Unit
                }
            val result =
                runCatching { withPrivatePdfFile(temporary.root, { misreported }) { error("must not render") } }
            assertTrue(result.exceptionOrNull() is IOException)
            assertTrue(
                documentReaderDirectory(temporary.root)
                    .listFiles()
                    .orEmpty()
                    .isEmpty(),
            )
        }

    @Test fun previewActionsCannotExportAndStayRevokedAfterInvalidation() =
        runBlocking {
            var current = true
            val actions = TextAttachmentNativeActions({ current }, shareSource = null, saveSource = null)
            assertFalse(actions.canSave)
            assertFalse(actions.canShare)
            assertNull(actions.playbackSource)
            actions.save()
            actions.share()
            current = false
            assertFalse(actions.isCurrent())
            actions.release()
            current = true
            assertFalse(actions.isCurrent())
        }

    companion object {
        internal fun prepared(
            mime: String,
            name: String,
        ) = DraftPreparedPhoto(
            MessageDraftAttachmentFfi(
                id = "draft-document",
                fileName = name,
                mediaType = mime,
                plaintext = "prepared bytes".toByteArray(),
                dim = null,
                thumbhash = null,
                durationSeconds = null,
                waveformSamples = emptyList(),
            ),
            "digest",
        )
    }
}
