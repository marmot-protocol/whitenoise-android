package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.LocalSendAcceptanceFfi
import dev.ipf.marmotkit.LocalSendStatusFfi
import dev.ipf.marmotkit.MarmotInterface
import dev.ipf.marmotkit.MarmotKitException
import dev.ipf.marmotkit.MediaFileTransferControlFfi
import dev.ipf.marmotkit.MediaFileUploadAttachmentRequestFfi
import dev.ipf.marmotkit.MediaFileUploadRequestFfi
import dev.ipf.marmotkit.MediaUploadResultFfi
import dev.ipf.marmotkit.MediaUploadSubmissionFfi
import dev.ipf.marmotkit.MessageDraftRevisionFfi
import dev.ipf.marmotkit.NoPointer
import dev.ipf.marmotkit.SelectedMessageDraftAttachmentFfi
import dev.ipf.marmotkit.SelectedMessageDraftContentFfi
import dev.ipf.marmotkit.SelectedMessageDraftFfi
import dev.ipf.marmotkit.SendAcceptDispositionFfi
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DurableLocalSendsTest {
    @Test
    fun pendingEditAdmissionUsesNativeOriginalTokenAndRecoversWithoutResendingOriginal() =
        runTest {
            val calls = mutableListOf<String>()
            var admitted = false
            val statusEngine =
                nativeBoundary { method, _ ->
                    calls += method
                    check(method == "localSendStatus")
                    if (admitted) LocalSendStatusFfi.Queued else null
                }
            // A JVM proxy wraps checked native exceptions; this override preserves the binding's real failure.
            val engine =
                object : MarmotInterface by statusEngine {
                    override suspend fun editLocalMessageWithClientToken(
                        accountRef: String,
                        groupIdHex: String,
                        originalClientToken: String,
                        content: String,
                        editClientToken: String,
                    ): LocalSendAcceptanceFfi {
                        calls += "editLocalMessageWithClientToken"
                        assertEquals("original-token", originalClientToken)
                        assertEquals("revised text", content)
                        assertEquals("edit-token", editClientToken)
                        admitted = true
                        throw MarmotKitException.Runtime("transport closed after durable edit admission")
                    }
                }
            val intent = DurablePendingEditIntent("original-token", "edit-token")
            val first = engine.admitPendingMessageEdit("account", "group", intent, "revised text")
            assertEquals(SendAcceptDispositionFfi.ACCEPTED_PENDING, first.acceptDisposition)
            val retry = engine.admitPendingMessageEdit("account", "group", intent, "revised text")
            assertEquals(SendAcceptDispositionFfi.ACCEPTED_PENDING, retry.acceptDisposition)
            assertEquals(1, calls.count { it == "editLocalMessageWithClientToken" })
        }

    /** Fresh local acceptance skips recovery I/O, remains pending, and preserves the optimistic token. */
    @Test
    fun textAndReplyUseStableTokenWithoutClaimingDelivery() =
        runTest {
            for (reply in listOf(null, "parent")) {
                val calls = mutableListOf<String>()
                val engine =
                    nativeBoundary { method, args ->
                        calls += method
                        when (method) {
                            "sendTextWithClientToken", "replyToMessageWithClientToken" -> {
                                assertEquals("logical-token", args[args.size - 2])
                                LocalSendAcceptanceFfi("logical-token", "11".repeat(32))
                            }
                            else -> error(method)
                        }
                    }
                val result =
                    engine.sendComposerTextWithToken(
                        "account",
                        "group",
                        reply,
                        "hello",
                        "logical-token",
                        probeExistingAdmission = false,
                    )
                assertEquals(SendAcceptDispositionFfi.ACCEPTED_PENDING, result.acceptDisposition)
                assertEquals(0u, result.published)
                assertEquals(listOf("11".repeat(32)), result.messageIds)
                val expectedMethod = if (reply == null) "sendTextWithClientToken" else "replyToMessageWithClientToken"
                assertEquals(listOf(expectedMethod), calls)
            }
        }

    /** A retained token owns the send after interruption; retry must not call admission again. */
    @Test
    fun recoveredOwnershipDoesNotReadmit() =
        runTest {
            for (status in listOf(LocalSendStatusFfi.Queued, LocalSendStatusFfi.EngineOwned)) {
                val engine =
                    nativeBoundary { method, _ ->
                        check(method == "localSendStatus")
                        status
                    }
                val result = engine.sendComposerTextWithToken("account", "group", null, "hello", "same-token")
                assertEquals(SendAcceptDispositionFfi.ACCEPTED_PENDING, result.acceptDisposition)
            }
        }

    /** A rejected token is terminal instead of silently sending the same payload under another identity. */
    @Test
    fun rejectedTokenRequiresDeliberateNewSubmission() =
        runTest {
            val engine =
                nativeBoundary { method, _ ->
                    check(method == "localSendStatus")
                    LocalSendStatusFfi.Rejected
                }
            val failure =
                runCatching {
                    engine.sendComposerTextWithToken("account", "group", null, "hello", "rejected")
                }.exceptionOrNull()
            assertTrue(failure is IllegalStateException)
        }

    /** A failed status probe is diagnostic context, not a replacement for the admission failure. */
    @Test
    fun recoveryProbeCannotMaskAdmissionFailure() =
        runTest {
            val admissionFailure = MarmotKitException.Publish("primary admission failure")
            val recoveryFailure = MarmotKitException.Runtime("secondary status failure")
            var statusReads = 0
            val engine =
                nativeBoundary { method, _ ->
                    when (method) {
                        "localSendStatus" -> {
                            statusReads += 1
                            if (statusReads == 1) null else throw recoveryFailure
                        }
                        else -> error(method)
                    }
                }

            val thrown =
                runCatching {
                    engine.admitLocalSend("account", "group", "token") { throw admissionFailure }
                }.exceptionOrNull()

            assertTrue(thrown === admissionFailure)
            val diagnostic = admissionFailure.suppressed.single()
            assertTrue(diagnostic === recoveryFailure || diagnostic.cause === recoveryFailure)
        }

    /** An interrupted call that already transferred ownership must never be admitted again. */
    @Test
    fun transportClosureRecoversOwnershipWithoutReadmission() =
        runTest {
            var statusReads = 0
            var admissions = 0
            val engine =
                nativeBoundary { method, _ ->
                    check(method == "localSendStatus")
                    statusReads += 1
                    LocalSendStatusFfi.EngineOwned
                }

            val result =
                engine.admitLocalSend(
                    "account",
                    "group",
                    "same-token",
                    probeExistingAdmission = false,
                ) {
                    admissions += 1
                    throw MarmotKitException.TransportClosed()
                }

            assertEquals(SendAcceptDispositionFfi.ACCEPTED_PENDING, result.acceptDisposition)
            assertEquals(1, admissions)
            assertEquals(1, statusReads)
        }

    /** A pre-ownership transport closure retries one logical submission with its original token. */
    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun transportClosureWithoutOwnershipRetriesTheSameToken() =
        runTest {
            val admittedTokens = mutableListOf<String>()
            val engine =
                nativeBoundary { method, _ ->
                    check(method == "localSendStatus")
                    null
                }
            val send =
                async {
                    retryPendingConversationSend(
                        retryableFailure = { failure ->
                            isTransientRelaySendError(failure) || isTransientRuntimeWorkerError(failure)
                        },
                    ) {
                        engine.admitLocalSend("account", "group", "same-token") {
                            admittedTokens += "same-token"
                            if (admittedTokens.size == 1) throw MarmotKitException.TransportClosed()
                            LocalSendAcceptanceFfi("same-token", "22".repeat(32))
                        }
                    }
                }

            runCurrent()
            assertEquals(listOf("same-token"), admittedTokens)
            advanceTimeBy(SEND_RETRY_BACKOFF_MS)
            runCurrent()

            assertEquals(SendAcceptDispositionFfi.ACCEPTED_PENDING, send.await().acceptDisposition)
            assertEquals(listOf("same-token", "same-token"), admittedTokens)
        }

    /**
     * File-backed composer media follows the byte path's admission rule: a matching non-reply draft keeps
     * the upload upload-only, and otherwise the same native call admits the send under the caller's token.
     */
    @Test
    fun fileBackedComposerMediaKeepsTheDraftAndTokenAdmissionRule() =
        runTest {
            for (draftBacked in listOf(false, true)) {
                val sends = mutableListOf<Boolean>()
                val control = MediaFileTransferControlFfi(NoPointer)
                val engine =
                    nativeBoundary { method, args ->
                        when (method) {
                            "localSendStatus" -> null
                            "selectedMessageDraft" -> selectedDraft(withLargeDocument = draftBacked)
                            "uploadMediaFilesWithClientToken" -> {
                                val request = args[2] as MediaFileUploadRequestFfi
                                assertTrue("the caller's control reaches the native call", args[3] === control)
                                assertEquals("logical-token", args[4])
                                sends += request.send
                                val acceptance =
                                    if (request.send) LocalSendAcceptanceFfi("logical-token", "33".repeat(32)) else null
                                MediaUploadSubmissionFfi(MediaUploadResultFfi(emptyList(), null), acceptance)
                            }
                            else -> error(method)
                        }
                    }

                val result =
                    engine.uploadOrAdmitComposerMediaFilesWithToken(
                        "account",
                        "group",
                        largeDocumentRequest(),
                        control,
                        "logical-token",
                    )

                assertEquals(listOf(!draftBacked), sends)
                assertEquals(!draftBacked, result.acceptance != null)
                assertEquals(false, result.recoveredWithoutUpload)
            }
        }

    /** One large document staged at a private path, as the composer hands it to the file upload. */
    private fun largeDocumentRequest() =
        MediaFileUploadRequestFfi(
            attachments =
                listOf(
                    MediaFileUploadAttachmentRequestFfi(
                        sourcePath = "/private/upload-source",
                        expectedSize = 40uL * 1024uL * 1024uL,
                        fileName = "large.pdf",
                        mediaType = "application/pdf",
                        dim = null,
                        thumbhash = null,
                    ),
                ),
            caption = null,
            send = false,
            blossomServer = null,
        )

    /** The selected native draft, holding the same document descriptor when [withLargeDocument]. */
    private fun selectedDraft(withLargeDocument: Boolean): SelectedMessageDraftFfi {
        val attachments =
            if (withLargeDocument) {
                listOf(
                    SelectedMessageDraftAttachmentFfi(
                        "large.pdf",
                        "large.pdf",
                        "application/pdf",
                        1uL,
                        null,
                        null,
                        null,
                        emptyList(),
                    ),
                )
            } else {
                emptyList()
            }
        return SelectedMessageDraftFfi(
            draftRevisionStub(),
            SelectedMessageDraftContentFfi("group", "", null, attachments, 1L, 1L),
        )
    }

    /** An opaque native revision handle; the admission rule never dereferences it. */
    private fun draftRevisionStub(): MessageDraftRevisionFfi {
        val unsafeClass = Class.forName("sun.misc.Unsafe")
        val unsafe = unsafeClass.getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null)
        return unsafeClass
            .getMethod("allocateInstance", Class::class.java)
            .invoke(unsafe, MessageDraftRevisionFfi::class.java) as MessageDraftRevisionFfi
    }
}
