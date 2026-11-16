package dev.ipf.whitenoise.android.ui.conversation.media

import android.net.Uri
import dev.ipf.marmotkit.MessageDraftAttachmentFfi
import dev.ipf.whitenoise.android.media.MediaCacheDirs
import dev.ipf.whitenoise.android.media.wipeSessionAttachmentPlaintext
import dev.ipf.whitenoise.android.media.editor.DraftPreparedPhoto
import dev.ipf.whitenoise.android.media.sandbox.PdfPagePolicy
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

    @Test fun pdfFileLivesThroughUseAndIsDeletedOnDismiss() =
        runBlocking {
            val ready = CompletableDeferred<File>()
            val bytes = "%PDF prepared draft".toByteArray()
            val reader =
                launch {
                    withComposerPdfFile(temporary.root, bytes) {
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
                    withComposerPdfFile(temporary.root, byteArrayOf(1)) {
                        owned = it
                        error("renderer failed")
                    }
                }
            assertTrue(result.isFailure)
            assertFalse(checkNotNull(owned).exists())
            withComposerPdfFile(temporary.root, byteArrayOf(2)) { owned = it }
            assertFalse(checkNotNull(owned).exists())
        }

    /** A kill skips the reader's cleanup; the next process removes that copy, and sign-out removes this one's. */
    @Test fun previewCopiesFromEarlierProcessesAreSweptAndSignOutWipesTheRest() {
        val cache = temporary.newFolder("cache")
        val earlier = File(File(cache, MediaCacheDirs.COMPOSER_DOCUMENT_READER), "process-earlier").apply { mkdirs() }
        val orphan = File(earlier, "preview-orphan.pdf").apply { writeText("orphan") }
        val own = File(composerPreviewDirectory(cache).apply { mkdirs() }, "preview-open.pdf").apply { writeText("open") }

        sweepOrphanedComposerPreviews(cache)

        assertFalse(orphan.exists())
        assertFalse(earlier.exists())
        assertTrue("an open reader keeps its copy", own.exists())

        wipeSessionAttachmentPlaintext(cache) { name, failure -> throw AssertionError(name, failure) }

        assertFalse(File(cache, MediaCacheDirs.COMPOSER_DOCUMENT_READER).exists())
    }

    @Test fun oversizedPdfIsRejectedBeforeCreatingAFile() =
        runBlocking {
            val result =
                runCatching {
                    withComposerPdfFile(temporary.root, ByteArray(PdfPagePolicy.MAX_INPUT.toInt() + 1)) {
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
