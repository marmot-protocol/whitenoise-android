package dev.ipf.whitenoise.android.ui.conversation.media

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.ipf.marmotkit.Marmot
import dev.ipf.marmotkit.MarmotAndroid
import dev.ipf.marmotkit.MediaUploadAttachmentRequestFfi
import dev.ipf.marmotkit.MediaUploadRequestFfi
import dev.ipf.marmotkit.TimelineMessageQueryFfi
import dev.ipf.whitenoise.android.WhiteNoiseApplication
import dev.ipf.whitenoise.android.core.MarmotClient
import dev.ipf.whitenoise.android.core.MessageAttachments
import dev.ipf.whitenoise.android.media.AttachmentPlaintextCache
import dev.ipf.whitenoise.android.ui.conversation.ConversationAttachmentReader
import dev.ipf.whitenoise.android.ui.conversation.DocumentReadFailure
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/** Opt-in exact-head SAF, viewer, and relay matrix on a fresh disposable preview emulator. */
@RunWith(AndroidJUnit4::class)
class DocumentProviderRelayMatrixTest {
    @Test
    @Suppress("LongMethod") // One disposable sender/receiver pair must own the full real-device sequence.
    fun providerGrantsSurviveReadAndRelayDeliveryButRevokeAndPlaintextCleanupWork() =
        runBlocking {
            val arguments = InstrumentationRegistry.getArguments()
            assumeTrue(arguments.getString("documentProviderMatrix") == "true")
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            check(context.packageName == "dev.ipf.whitenoise.android.preview.pr2830")
            val fixtureCases =
                listOf(
                    Case("text", "note.txt", "text/plain"),
                    Case("pdf", "paper.pdf", "application/pdf"),
                    Case("zip", "bundle.zip", "application/zip"),
                    Case(
                        "office",
                        "report.docx",
                        "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                    ),
                    Case("audio", "tone.wav", "audio/wav"),
                    Case("video", "clip.mp4", "video/mp4"),
                    Case("binary", "opaque.bin", "application/octet-stream"),
                    Case("mismatch", "looks-like-pdf.pdf", "application/octet-stream"),
                    Case("unhandled", "no-viewer.wnmatrix", "application/vnd.wnmatrix.no-viewer"),
                )
            val allIds = fixtureCases.map(Case::id) + listOf("empty", "unreadable")
            allIds.forEach { grant(context, it) }
            fixtureCases.forEach { fixture ->
                val opened =
                    runCatching { context.contentResolver.openInputStream(uri(fixture.id))?.use { it.read() } }
                check(opened.isSuccess && opened.getOrNull() != null) {
                    "Granted ${fixture.id} document is unreadable: ${opened.exceptionOrNull()}"
                }
            }

            val reader =
                ConversationAttachmentReader(
                    (context.applicationContext as WhiteNoiseApplication).appState,
                    context,
                )
            val result = reader.readPickedDocuments(allIds.map(::uri))
            assertEquals(setOf(DocumentReadFailure.EMPTY, DocumentReadFailure.UNREADABLE), result.failures)
            assertFalse(result.albumOverflowed)
            assertEquals(fixtureCases.size, result.attachments.size)
            fixtureCases.zip(result.attachments).forEach { (fixture, attachment) ->
                assertEquals(fixture.name, attachment.fileName)
                assertEquals(fixture.mime, attachment.mediaType)
                assertTrue(attachment.plaintextBytes.isNotEmpty())
            }

            revoke(context, "text")
            awaitCondition {
                runCatching { context.contentResolver.openInputStream(uri("text"))?.use { it.read() } }.isFailure
            }
            val afterRevoke = reader.readPickedDocuments(listOf(uri("text")))
            assertEquals(setOf(DocumentReadFailure.UNREADABLE), afterRevoke.failures)
            assertTrue(afterRevoke.attachments.isEmpty())

            MarmotAndroid.initialize(context)
            val root = File(context.cacheDir, "document-provider-matrix-${UUID.randomUUID()}").apply { mkdirs() }
            val marmot = Marmot(root.absolutePath, MarmotClient.bootstrapRelays)
            val accounts = mutableListOf<String>()
            try {
                withTimeout(600_000) {
                    marmot.start()
                    val sender = marmot.createIdentity(MarmotClient.bootstrapRelays, MarmotClient.bootstrapRelays)
                    accounts += sender.label
                    val receiver = marmot.createIdentity(MarmotClient.bootstrapRelays, MarmotClient.bootstrapRelays)
                    accounts += receiver.label
                    val group = marmot.createGroup(sender.label, "Document matrix", listOf(receiver.accountIdHex), null)
                    awaitCondition(180_000) { marmot.chatList(receiver.label, false).any { it.groupIdHex == group } }
                    marmot.acceptGroupInvite(receiver.label, group)

                    val uploaded =
                        marmot.uploadMedia(
                            sender.label,
                            group,
                            MediaUploadRequestFfi(
                                attachments =
                                    result.attachments.map { attachment ->
                                        MediaUploadAttachmentRequestFfi(
                                            fileName = attachment.fileName,
                                            mediaType = attachment.mediaType,
                                            plaintext = attachment.plaintextBytes,
                                            dim = null,
                                            thumbhash = null,
                                        )
                                    },
                                caption = "Disposable PR 2830 document matrix",
                                send = true,
                                blossomServer = null,
                            ),
                        )
                    val messageId = requireNotNull(uploaded.sent?.messageIds?.singleOrNull())
                    var receivedReferences = emptyList<dev.ipf.marmotkit.MediaAttachmentReferenceFfi>()
                    awaitCondition(180_000) {
                        val message =
                            marmot.timelineMessages(
                                receiver.label,
                                TimelineMessageQueryFfi(group, null, null, null, null, null, 30u),
                            ).messages.firstOrNull { it.messageIdHex == messageId }
                        receivedReferences = message?.let { MessageAttachments.acceptedReferences(it.media) }.orEmpty()
                        receivedReferences.size == fixtureCases.size
                    }
                    fixtureCases.indices.forEach { index ->
                        val received = marmot.downloadMedia(receiver.label, group, receivedReferences[index])
                        assertEquals(fixtureCases[index].name, received.fileName)
                        assertEquals(fixtureCases[index].mime, received.mediaType)
                        assertArrayEquals(result.attachments[index].plaintextBytes, received.plaintext)
                    }

                    val pdfIndex = fixtureCases.indexOfFirst { it.id == "pdf" }
                    val pdf = marmot.downloadMedia(receiver.label, group, receivedReferences[pdfIndex])
                    val pdfFile =
                        materializeDocumentAttachment(context, messageId, pdfIndex, receivedReferences[pdfIndex]) {
                            pdf.plaintext
                        }
                    val viewsBefore = viewerStatus(context).views
                    assertEquals(
                        OpenAttachmentResult.Opened,
                        openAttachmentExternally(context, pdfFile, pdf.mediaType, pdf.fileName),
                    )
                    awaitCondition {
                        val status = viewerStatus(context)
                        status.views > viewsBefore &&
                            status.sha256 == sha256(pdf.plaintext) &&
                            status.bytes == pdf.plaintext.size.toLong()
                    }

                    val unhandledIndex = fixtureCases.indexOfFirst { it.id == "unhandled" }
                    val unhandled = marmot.downloadMedia(receiver.label, group, receivedReferences[unhandledIndex])
                    val unhandledFile =
                        materializeDocumentAttachment(
                            context,
                            messageId,
                            unhandledIndex,
                            receivedReferences[unhandledIndex],
                        ) {
                            unhandled.plaintext
                        }
                    assertEquals(
                        OpenAttachmentResult.NoHandler,
                        openAttachmentExternally(context, unhandledFile, unhandled.mediaType, unhandled.fileName),
                    )
                    AttachmentPlaintextCache.trimDirectoryToByteCap(pdfFile.parentFile, 0)
                    assertFalse(pdfFile.exists())
                    assertFalse(unhandledFile.exists())
                }
            } finally {
                accounts.forEach { account -> runCatching { withTimeout(30_000) { marmot.removeAccount(account) } } }
                runCatching { withTimeout(30_000) { marmot.shutdownAndClose() } }.onFailure { marmot.close() }
                root.deleteRecursively()
            }
        }

    private data class Case(val id: String, val name: String, val mime: String)

    private data class ViewerStatus(val sha256: String, val bytes: Long, val views: Int)

    private fun uri(id: String): Uri = DocumentsContract.buildDocumentUri("dev.ipf.fixture.documents", id)

    private fun grant(context: Context, id: String) = sendGrantCommand(context, "GRANT", id)

    private fun revoke(context: Context, id: String) = sendGrantCommand(context, "REVOKE", id)

    private fun sendGrantCommand(context: Context, action: String, id: String) {
        val reply =
            context.contentResolver.call(
                Uri.parse("content://dev.ipf.fixture.status"),
                action.lowercase(),
                id,
                Bundle().apply { putString("targetPackage", context.packageName) },
            )
        check(reply?.getBoolean("ok") == true) { "Fixture $action failed for $id" }
    }

    private fun viewerStatus(context: Context): ViewerStatus {
        val uri = Uri.parse("content://dev.ipf.fixture.status/viewer")
        return requireNotNull(context.contentResolver.query(uri, null, null, null, null)).use { cursor ->
            check(cursor.moveToFirst())
            ViewerStatus(
                cursor.getString(cursor.getColumnIndexOrThrow("sha256")),
                cursor.getLong(cursor.getColumnIndexOrThrow("bytes")),
                cursor.getInt(cursor.getColumnIndexOrThrow("views")),
            )
        }
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private suspend fun awaitCondition(
        timeoutMs: Long = 30_000,
        condition: suspend () -> Boolean,
    ) {
        withTimeout(timeoutMs) {
            while (!condition()) delay(500)
        }
    }
}
