package dev.ipf.whitenoise.android.state

import androidx.compose.ui.text.input.TextFieldValue
import androidx.test.core.app.ApplicationProvider
import dev.ipf.marmotkit.AccountSummaryFfi
import dev.ipf.marmotkit.AppBlobEndpointFfi
import dev.ipf.marmotkit.AppGroupEncryptedMediaComponentFfi
import dev.ipf.marmotkit.AppGroupMemberRecordFfi
import dev.ipf.marmotkit.AppGroupRecordFfi
import dev.ipf.marmotkit.AppProtocolProfileFfi
import dev.ipf.marmotkit.ChatConversationKindFfi
import dev.ipf.marmotkit.ChatListMessageDeliveryStateFfi
import dev.ipf.marmotkit.ChatListMessagePreviewFfi
import dev.ipf.marmotkit.ChatListRowFfi
import dev.ipf.marmotkit.ChatListSubscriptionUpdateFfi
import dev.ipf.marmotkit.ChatListUpdateTriggerFfi
import dev.ipf.marmotkit.DeletionSourceFfi
import dev.ipf.marmotkit.EncryptedMediaVersionFfi
import dev.ipf.marmotkit.GroupLifecycleStateFfi
import dev.ipf.marmotkit.GroupMemberDetailsFfi
import dev.ipf.marmotkit.GroupRosterFfi
import dev.ipf.marmotkit.LocalSendStatusFfi
import dev.ipf.marmotkit.MarkdownDocumentFfi
import dev.ipf.marmotkit.MarmotKitException
import dev.ipf.marmotkit.MessageTagFfi
import dev.ipf.marmotkit.SelfMembershipFfi
import dev.ipf.marmotkit.SendAcceptDispositionFfi
import dev.ipf.marmotkit.SendMaintenanceDispositionFfi
import dev.ipf.marmotkit.SendSummaryFfi
import dev.ipf.marmotkit.TimelineEditSummaryFfi
import dev.ipf.marmotkit.TimelineMessageChangeFfi
import dev.ipf.marmotkit.TimelineMessageRecordFfi
import dev.ipf.marmotkit.TimelinePageFfi
import dev.ipf.marmotkit.TimelineReactionSummaryFfi
import dev.ipf.marmotkit.TimelineUpdateTriggerFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.audio.ConversationDictationSendRequest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import java.util.concurrent.TimeUnit

/** Integration boundary for optimistic send state plus the shared relay retry policy (#2016). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en")
@Suppress("LargeClass") // Send, retry, projection, preview, and durable-draft scenarios share one controller fixture.
class ConversationSendRetryIntegrationTest {
    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun confirmedProjectionUsesNativeEditsOnlyWhenItsLedgerTokenIsRetained() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            try {
                for (originalToken in listOf(null, "retained-original-token")) {
                    val nativeEdits = mutableListOf<Pair<String, String>>()
                    val wireEdits = mutableListOf<Pair<String, String>>()
                    val controller =
                        ConversationController(
                            appState = appState(),
                            initialGroup = group(),
                            initialMemberSnapshot = memberSnapshot(),
                            groupRosterReader = { _, _ -> authoritativeRoster() },
                            pendingMessageEditPublisher = { _, _, original, text, _ ->
                                nativeEdits += original to text
                                pendingLocalSend()
                            },
                            messageEditPublisher = { _, _, target, text -> wireEdits += target to text },
                        )
                    try {
                        controller.retryMembers()
                        applyProjection(controller, projectedMessage(5uL, null, null).copy(clientToken = originalToken))
                        controller.beginMessageEdit(CONFIRMED_MESSAGE_ID)
                        controller.send("confirmed revision")
                        val expectedNative = originalToken?.let { listOf(it to "confirmed revision") }.orEmpty()
                        val expectedWire =
                            if (originalToken == null) {
                                listOf(CONFIRMED_MESSAGE_ID to "confirmed revision")
                            } else {
                                emptyList()
                            }
                        assertEquals(expectedNative, nativeEdits)
                        assertEquals(expectedWire, wireEdits)
                        assertNull(controller.editingMessageId)
                    } finally {
                        controller.onCleared()
                    }
                }
            } finally {
                Dispatchers.resetMain()
            }
        }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun replacementControllerKeepsNewRevisionsInTheNativeSubmissionOrder() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val originalTokens = mutableListOf<String>()
            val revisionTokens = mutableListOf<String>()
            val state = appState()
            try {
                repeat(2) { revision ->
                    val controller =
                        ConversationController(
                            appState = state,
                            initialGroup = group(),
                            initialMemberSnapshot = memberSnapshot(),
                            groupRosterReader = { _, _ -> authoritativeRoster() },
                            pendingMessageEditPublisher = { _, _, original, _, token ->
                                originalTokens += original
                                revisionTokens += token
                                pendingLocalSend()
                            },
                            messageEditPublisher = { _, _, _, _ ->
                                error("retained native original must keep ordering")
                            },
                        )
                    try {
                        controller.retryMembers()
                        applyProjection(
                            controller,
                            projectedMessage(5uL, null, null).copy(clientToken = "retained-original-token"),
                        )
                        controller.beginMessageEdit(CONFIRMED_MESSAGE_ID)
                        controller.send("revision $revision")
                        assertNull(controller.editingMessageId)
                    } finally {
                        controller.onCleared()
                    }
                }
                assertEquals(listOf("retained-original-token", "retained-original-token"), originalTokens)
                assertEquals(2, revisionTokens.distinct().size)
            } finally {
                Dispatchers.resetMain()
            }
        }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun publishedOriginalBeforeProjectionKeepsBothRevisionsOnTheNativePath() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val revisions = mutableListOf<Triple<String, String, String>>()
            val controller =
                nativeEditControllerWithStatus(
                    originalReturn = CompletableDeferred<Unit>().also { it.complete(Unit) },
                    publisher = { _, _, original, text, token ->
                        revisions += Triple(original, text, token)
                        pendingLocalSend()
                    },
                    statusReader = { _, _, _ -> LocalSendStatusFfi.Queued },
                    originalSummary = successfulSendSummary(),
                )
            try {
                controller.retryMembers()
                controller.send("hello")
                val original = controller.timeline.single()
                assertEquals(MessageStatus.Sent, original.status)
                val token = original.record.messageIdHex
                controller.beginMessageEdit(token)
                controller.send("first revision")
                assertEquals(1, revisions.size)
                assertNull(controller.editingMessageId)
                controller.beginMessageEdit(token)
                controller.send("second revision")
                assertEquals(listOf(token, token), revisions.map { it.first })
                assertEquals(listOf("first revision", "second revision"), revisions.map { it.second })
                assertEquals(2, revisions.map { it.third }.distinct().size)
                assertNull(controller.editingMessageId)
            } finally {
                controller.onCleared()
                Dispatchers.resetMain()
            }
        }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun canonicalPendingTargetKeepsTheOriginalNativeClientToken() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val originalTokens = mutableListOf<String>()
            val controller =
                nativeEditController(CompletableDeferred<Unit>().also { it.complete(Unit) }) { _, _, original, _, _ ->
                    originalTokens += original
                    pendingLocalSend()
                }
            try {
                controller.retryMembers()
                controller.send("hello")
                val originalToken =
                    controller.timeline
                        .single()
                        .record.messageIdHex
                applyProjection(
                    controller,
                    projectedMessage(5uL, null, null, sourceMessageIdHex = null).copy(clientToken = originalToken),
                )
                settleNativePresentation()
                controller.beginMessageEdit(CONFIRMED_MESSAGE_ID)
                controller.send("canonical pending revision")
                assertEquals(listOf(originalToken), originalTokens)
                assertNull(controller.editingMessageId)
            } finally {
                controller.onCleared()
                Dispatchers.resetMain()
            }
        }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun oneDiscardRemovesAFailedOriginalAfterItsEarlierEditWasRejected() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val originalStarted = CompletableDeferred<Unit>()
            val originalReturn = CompletableDeferred<Unit>()
            var statusReads = 0
            val controller =
                ConversationController(
                    appState = appState(),
                    initialGroup = group(),
                    initialMemberSnapshot = memberSnapshot(),
                    groupRosterReader = { _, _ -> authoritativeRoster() },
                    textPublisher = { _, _, _, _ ->
                        originalStarted.complete(Unit)
                        originalReturn.await()
                        throw MarmotKitException.Runtime("original was never admitted")
                    },
                    pendingMessageEditPublisher = { _, _, _, _, _ ->
                        throw MarmotKitException.Runtime("original token does not exist")
                    },
                    pendingEditStatusReader = { _, _, _ ->
                        statusReads += 1
                        LocalSendStatusFfi.Rejected
                    },
                )
            try {
                controller.retryMembers()
                val originalSend = async(start = CoroutineStart.UNDISPATCHED) { controller.send("hello") }
                originalStarted.await()
                val originalToken =
                    controller.timeline
                        .single()
                        .record.messageIdHex
                controller.beginMessageEdit(originalToken)
                controller.send("rejected revision")
                runCurrent()
                assertEquals(MessageStatus.Failed, controller.timeline.single().status)
                originalReturn.complete(Unit)
                originalSend.await()
                assertEquals(MessageStatus.Failed, controller.timeline.single().status)
                val readsBeforeDiscard = statusReads
                controller.discardFailedSend(controller.timeline.single())
                assertTrue(controller.timeline.isEmpty())
                assertEquals(readsBeforeDiscard, statusReads)
            } finally {
                controller.onCleared()
                Dispatchers.resetMain()
            }
        }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun temporaryTargetRetryReplacesAnAuthoritativelyRejectedEditToken() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val editTokens = mutableListOf<String>()
            var reads = 0
            val controller =
                nativeEditControllerWithStatus(
                    CompletableDeferred<Unit>().also { it.complete(Unit) },
                    { _, _, _, _, token ->
                        editTokens += token
                        if (editTokens.size == 1) throw MarmotKitException.Runtime("definite native rejection")
                        pendingLocalSend()
                    },
                    { _, _, _ ->
                        reads += 1
                        if (reads == 1) LocalSendStatusFfi.Rejected else LocalSendStatusFfi.Queued
                    },
                )
            try {
                controller.retryMembers()
                controller.send("hello")
                controller.beginMessageEdit(
                    controller.timeline
                        .single()
                        .record.messageIdHex,
                )
                controller.send("retained revision")
                settleNativePresentation()
                assertEquals(MessageStatus.Failed, controller.timeline.single().status)
                controller.retryFailedSend(controller.timeline.single())
                settleNativePresentation()
                assertEquals(2, editTokens.size)
                assertFalse(editTokens[0] == editTokens[1])
                assertEquals(MessageStatus.Pending, controller.timeline.single().status)
            } finally {
                controller.onCleared()
                Dispatchers.resetMain()
            }
        }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun disposingControllerCancelsItsPendingEditStatusRead() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val started = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            var completedRead = false
            val controller =
                nativeEditControllerWithStatus(
                    CompletableDeferred<Unit>().also { it.complete(Unit) },
                    { _, _, _, _, _ -> pendingLocalSend(listOf("edit-id")) },
                    { _, _, _ ->
                        started.complete(Unit)
                        release.await()
                        completedRead = true
                        publishedNativeEditStatus("edit-id")
                    },
                )
            try {
                controller.retryMembers()
                controller.send("hello")
                val originalToken =
                    controller.timeline
                        .single()
                        .record.messageIdHex
                controller.beginMessageEdit(originalToken)
                controller.send("revision")
                applyNativeEditProjection(controller, originalToken, "revision", "edit-id")
                started.await()
                controller.onCleared()
                release.complete(Unit)
                settleNativePresentation()
                assertFalse(completedRead)
                assertEquals(MessageStatus.Pending, controller.timeline.single().status)
            } finally {
                release.complete(Unit)
                controller.onCleared()
                Dispatchers.resetMain()
            }
        }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun durableEditThroughAppStatePreservesThePreEditDraft() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val state = appState()
            var editCalls = 0
            var sendCallbacks = 0
            val controller =
                nativeEditControllerWithStatus(
                    CompletableDeferred<Unit>().also { it.complete(Unit) },
                    { _, _, _, _, _ ->
                        editCalls += 1
                        pendingLocalSend()
                    },
                    { _, _, _ -> LocalSendStatusFfi.Queued },
                    state,
                )
            try {
                controller.retryMembers()
                controller.send("hello")
                state.setDraft(GROUP_ID, TextFieldValue("separate unsent draft"))
                controller.beginMessageEdit(
                    controller.timeline
                        .single()
                        .record.messageIdHex,
                )
                val newMessageAccepted = state.sendConversationText(controller, "edited body") { sendCallbacks += 1 }
                settleNativePresentation()
                assertFalse(newMessageAccepted)
                assertEquals(0, sendCallbacks)
                assertEquals(1, editCalls)
                assertEquals("separate unsent draft", state.draftFor(GROUP_ID))
                assertNull(controller.editingMessageId)
                assertEquals("edited body", controller.displayedText(controller.timeline.single().record))
            } finally {
                controller.onCleared()
                Dispatchers.resetMain()
            }
        }

    @Test
    fun editPublicationBeforeAdmissionReplyDismissesTheEditor() = publicationBeforeAdmissionReply(false)

    @Test
    fun editPublicationBeforeLostAdmissionReplyDismissesTheEditor() = publicationBeforeAdmissionReply(true)

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun publicationBeforeAdmissionReply(loseReply: Boolean) =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val reply = CompletableDeferred<Unit>()
            var editToken: String? = null
            var admissions = 0
            val controller =
                nativeEditController(CompletableDeferred<Unit>().also { it.complete(Unit) }) { _, _, _, _, token ->
                    admissions += 1
                    editToken = token
                    reply.await()
                    if (loseReply) throw MarmotKitException.Runtime("admission acknowledgement lost")
                    pendingLocalSend(listOf("edit-id"))
                }
            try {
                controller.retryMembers()
                controller.send("hello")
                val originalToken =
                    controller.timeline
                        .single()
                        .record.messageIdHex
                controller.beginMessageEdit(originalToken)
                val edit = async(start = CoroutineStart.UNDISPATCHED) { controller.send("revision") }
                applyNativeEditProjection(controller, originalToken)
                applyProjection(
                    controller,
                    projectedMessage(6uL, null, null).copy(
                        messageIdHex = "edit-id",
                        sourceMessageIdHex = "edit-wire-id",
                        clientToken = editToken,
                        kind = 1009uL,
                        plaintext = "revision",
                        tags = listOf(MessageTagFfi(listOf("e", CONFIRMED_MESSAGE_ID))),
                    ),
                )
                settleNativePresentation()
                assertEquals(
                    "revision",
                    controller.displayedText(controller.timeline.first { it.record.kind == 9uL }.record),
                )
                assertNotNull(controller.editingMessageId)
                reply.complete(Unit)
                edit.await()
                assertNull(controller.editingMessageId)
                // Another Send cannot mint the same edit again after positive publication proof.
                assertEquals(1, admissions)
            } finally {
                reply.complete(Unit)
                controller.onCleared()
                Dispatchers.resetMain()
            }
        }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun rapidPendingRevisionsEnterNativeAdmissionInSubmissionOrder() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val olderReply = CompletableDeferred<Unit>()
            val newerReply = CompletableDeferred<Unit>()
            val admissions = mutableListOf<String>()
            val controller =
                nativeEditController(CompletableDeferred<Unit>().also { it.complete(Unit) }) { _, _, _, text, _ ->
                    admissions += text
                    if (text == "older") olderReply.await() else newerReply.await()
                    pendingLocalSend(listOf("$text-edit-id"))
                }
            try {
                controller.retryMembers()
                controller.send("hello")
                val token =
                    controller.timeline
                        .single()
                        .record.messageIdHex
                controller.beginMessageEdit(token)
                val older = async(start = CoroutineStart.UNDISPATCHED) { controller.send("older") }
                val newer = async(start = CoroutineStart.UNDISPATCHED) { controller.send("newer") }
                assertEquals(listOf("older"), admissions)
                assertEquals("newer", controller.displayedText(controller.timeline.single().record))
                olderReply.complete(Unit)
                older.await()
                assertNotNull(controller.editingMessageId)
                runCurrent()
                assertEquals(listOf("older", "newer"), admissions)
                newerReply.complete(Unit)
                newer.await()
                assertNull(controller.editingMessageId)
                assertEquals("newer", controller.displayedText(controller.timeline.single().record))
            } finally {
                olderReply.complete(Unit)
                newerReply.complete(Unit)
                controller.onCleared()
                Dispatchers.resetMain()
            }
        }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun nativePendingEditIsDurableBeforeOriginalDeliveryAndSettlesByExactEditId() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val originalReturn = CompletableDeferred<Unit>()
            val edits = mutableListOf<Pair<String, String>>()
            var editPublished = false
            val controller =
                nativeEditControllerWithStatus(originalReturn, { _, _, originalToken, text, _ ->
                    edits += originalToken to text
                    pendingLocalSend(listOf("edit-id"))
                }, { _, _, _ ->
                    if (editPublished) {
                        publishedNativeEditStatus("edit-id")
                    } else {
                        LocalSendStatusFfi.Queued
                    }
                })
            try {
                controller.retryMembers()
                val original = async(start = CoroutineStart.UNDISPATCHED) { controller.send("hello") }
                val token =
                    controller.timeline
                        .single()
                        .record.messageIdHex
                controller.beginMessageEdit(token)
                controller.send("revision")
                assertNull(controller.editingMessageId)
                assertEquals(listOf(token to "revision"), edits)
                assertEquals("revision", controller.displayedText(controller.timeline.single().record))
                // The canonical projection beats the original admission response.
                applyNativeEditProjection(controller, token)
                assertEquals("revision", controller.displayedText(controller.timeline.single().record))
                assertEquals(MessageStatus.Pending, controller.timeline.single().status)
                // Admission already projects the revision, but the engine has not claimed it yet.
                applyNativeEditProjection(controller, token, "revision", "edit-id")
                settleNativePresentation()
                assertEquals(MessageStatus.Pending, controller.timeline.single().status)
                originalReturn.complete(Unit)
                original.await()
                editPublished = true
                applyNativeEditProjection(controller, token, "revision", "edit-id")
                settleNativePresentation()
                assertEquals("revision", controller.displayedText(controller.timeline.single().record))
                assertEquals(MessageStatus.Sent, controller.timeline.single().status)
                assertEquals(1, edits.size)
            } finally {
                originalReturn.complete(Unit)
                controller.onCleared()
                Dispatchers.resetMain()
            }
        }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun nativeEditEventConfirmsPublicationWhileEngineOwnershipKeepsTheClockPending() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            var editToken: String? = null
            val controller =
                nativeEditControllerWithStatus(
                    CompletableDeferred<Unit>().also { it.complete(Unit) },
                    { _, _, _, _, token ->
                        editToken = token
                        pendingLocalSend(listOf("edit-id"))
                    },
                    { _, _, _ -> LocalSendStatusFfi.EngineOwned },
                )
            try {
                controller.retryMembers()
                controller.send("hello")
                val originalToken =
                    controller.timeline
                        .single()
                        .record.messageIdHex
                controller.beginMessageEdit(originalToken)
                controller.send("revision")
                applyNativeEditProjection(controller, originalToken, "revision", "edit-id")
                settleNativePresentation()
                assertEquals(MessageStatus.Pending, controller.timeline.single().status)
                applyProjection(
                    controller,
                    projectedMessage(6uL, null, null).copy(
                        messageIdHex = "edit-id",
                        clientToken = editToken,
                        kind = 1009uL,
                        plaintext = "revision",
                        tags = listOf(MessageTagFfi(listOf("e", CONFIRMED_MESSAGE_ID))),
                    ),
                )
                settleNativePresentation()
                val publishedOriginal = controller.timeline.first { it.record.kind == 9uL }
                assertEquals(MessageStatus.Sent, publishedOriginal.status)
                assertEquals("revision", controller.displayedText(publishedOriginal.record))
            } finally {
                controller.onCleared()
                Dispatchers.resetMain()
            }
        }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun failedNativeAdmissionRetainsEditorAndRetriesTheSameRevisionToken() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val originalReturn = CompletableDeferred<Unit>()
            val editTokens = mutableListOf<String>()
            val controller =
                nativeEditController(originalReturn, { _, _, _, _, editToken ->
                    editTokens += editToken
                    if (editTokens.size == 1) throw MarmotKitException.Runtime("acknowledgement unavailable")
                    pendingLocalSend(listOf("edit-id"))
                })
            try {
                controller.retryMembers()
                val original = async(start = CoroutineStart.UNDISPATCHED) { controller.send("hello") }
                val token =
                    controller.timeline
                        .single()
                        .record.messageIdHex
                controller.beginMessageEdit(token)
                controller.send("retained revision")
                assertEquals(token, controller.editingMessageId)
                controller.send("retained revision")
                assertEquals(2, editTokens.size)
                assertEquals(editTokens[0], editTokens[1])
                assertNull(controller.editingMessageId)
                originalReturn.complete(Unit)
                original.await()
            } finally {
                originalReturn.complete(Unit)
                controller.onCleared()
                Dispatchers.resetMain()
            }
        }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun lateNativeEditFailureCannotReplaceANewerSubmittedRevision() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val originalReturn = CompletableDeferred<Unit>()
            val olderEditReturn = CompletableDeferred<Unit>()
            val olderEditStarted = CompletableDeferred<Unit>()
            val controller =
                nativeEditController(originalReturn, { _, _, _, text, _ ->
                    if (text == "older") {
                        olderEditStarted.complete(Unit)
                        olderEditReturn.await()
                        throw MarmotKitException.Runtime("late old failure")
                    }
                    pendingLocalSend(listOf("newer-edit-id"))
                })
            try {
                controller.retryMembers()
                val original = async(start = CoroutineStart.UNDISPATCHED) { controller.send("hello") }
                val token =
                    controller.timeline
                        .single()
                        .record.messageIdHex
                controller.beginMessageEdit(token)
                val older = async { controller.send("older") }
                olderEditStarted.await()
                controller.beginMessageEdit(token)
                val newer = async(start = CoroutineStart.UNDISPATCHED) { controller.send("newer") }
                assertEquals("newer", controller.displayedText(controller.timeline.single().record))
                olderEditReturn.complete(Unit)
                older.await()
                newer.await()
                assertEquals("newer", controller.displayedText(controller.timeline.single().record))
                assertNull(controller.editingMessageId)
                applyNativeEditProjection(controller, token)
                assertEquals("newer", controller.displayedText(controller.timeline.single().record))
                assertEquals(MessageStatus.Pending, controller.timeline.single().status)
                originalReturn.complete(Unit)
                original.await()
            } finally {
                originalReturn.complete(Unit)
                olderEditReturn.complete(Unit)
                controller.onCleared()
                Dispatchers.resetMain()
            }
        }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun editAfterAcceptedPendingReturnStaysOnTheTemporaryBubbleUntilProjection() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val originalReturn = CompletableDeferred<Unit>().also { it.complete(Unit) }
            val edits = mutableListOf<String>()
            val controller =
                nativeEditController(originalReturn) { _, _, _, text, _ ->
                    edits += text
                    pendingLocalSend(listOf("edit-id"))
                }
            try {
                controller.retryMembers()
                controller.send("hello")
                val token =
                    controller.timeline
                        .single()
                        .record.messageIdHex
                controller.beginMessageEdit(token)
                controller.send("accepted pending revision")
                assertEquals("accepted pending revision", controller.displayedText(controller.timeline.single().record))
                assertNull(controller.editingMessageId)
                applyNativeEditProjection(controller, token)
                assertEquals("accepted pending revision", controller.displayedText(controller.timeline.single().record))
                assertEquals(listOf("accepted pending revision"), edits)
            } finally {
                controller.onCleared()
                Dispatchers.resetMain()
            }
        }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun lostNativeEditAcknowledgementSettlesFromNativeStatusAfterTargetReprojects() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val originalReturn = CompletableDeferred<Unit>().also { it.complete(Unit) }
            var statusReads = 0
            val controller =
                nativeEditControllerWithStatus(
                    originalReturn,
                    { _, _, _, _, _ -> pendingLocalSend() },
                    { _, _, _ ->
                        statusReads += 1
                        publishedNativeEditStatus("edit-id")
                    },
                )
            try {
                controller.retryMembers()
                controller.send("hello")
                val token =
                    controller.timeline
                        .single()
                        .record.messageIdHex
                controller.beginMessageEdit(token)
                controller.send("recovered revision")
                applyNativeEditProjection(controller, token, "recovered revision", "edit-id")
                settleNativePresentation()
                assertEquals(1, statusReads)
                assertEquals(MessageStatus.Sent, controller.timeline.single().status)
                assertEquals("recovered revision", controller.displayedText(controller.timeline.single().record))
            } finally {
                controller.onCleared()
                Dispatchers.resetMain()
            }
        }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun identicalProjectionDuringNativeStatusReadRechecksWithoutAnotherSend() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val firstRead = CompletableDeferred<Unit>()
            val releaseRead = CompletableDeferred<Unit>()
            var reads = 0
            val controller =
                nativeEditControllerWithStatus(
                    CompletableDeferred<Unit>().also { it.complete(Unit) },
                    { _, _, _, _, _ -> pendingLocalSend(listOf("edit-id")) },
                    { _, _, _ ->
                        reads += 1
                        if (reads == 1) {
                            firstRead.complete(Unit)
                            releaseRead.await()
                            LocalSendStatusFfi.Queued
                        } else {
                            publishedNativeEditStatus("edit-id")
                        }
                    },
                )
            try {
                controller.retryMembers()
                controller.send("hello")
                val token =
                    controller.timeline
                        .single()
                        .record.messageIdHex
                controller.beginMessageEdit(token)
                controller.send("recovered revision")
                applyNativeEditProjection(controller, token, "recovered revision", "edit-id")
                firstRead.await()
                applyNativeEditProjection(controller, token, "recovered revision", "edit-id")
                releaseRead.complete(Unit)
                settleNativePresentation()
                assertEquals(2, reads)
                assertEquals(MessageStatus.Sent, controller.timeline.single().status)
                assertEquals("recovered revision", controller.displayedText(controller.timeline.single().record))
            } finally {
                releaseRead.complete(Unit)
                controller.onCleared()
                Dispatchers.resetMain()
            }
        }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun completedNativeAdmissionWithPendingPublicationDoesNotConfirmANewerRevision() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val originalReturn = CompletableDeferred<Unit>().also { it.complete(Unit) }
            val controller =
                nativeEditControllerWithStatus(
                    originalReturn,
                    { _, _, _, _, _ -> pendingLocalSend() },
                    { _, _, _ -> LocalSendStatusFfi.Completed(pendingLocalSend(listOf("pending-edit-id"))) },
                )
            try {
                controller.retryMembers()
                controller.send("hello")
                val token =
                    controller.timeline
                        .single()
                        .record.messageIdHex
                controller.beginMessageEdit(token)
                controller.send("queued revision")
                applyNativeEditProjection(controller, token, "older accepted edit", "older-edit-id")
                settleNativePresentation()
                assertEquals("queued revision", controller.displayedText(controller.timeline.single().record))
                assertEquals(MessageStatus.Pending, controller.timeline.single().status)
            } finally {
                controller.onCleared()
                Dispatchers.resetMain()
            }
        }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun terminalNativeEditRejectionRequiresADeliberateNewRetryToken() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val originalReturn = CompletableDeferred<Unit>().also { it.complete(Unit) }
            val tokens = mutableListOf<String>()
            var statusReads = 0
            val controller =
                nativeEditControllerWithStatus(
                    originalReturn,
                    { _, _, _, text, token ->
                        assertEquals("retained revision", text)
                        tokens += token
                        pendingLocalSend(listOf("edit-${tokens.size}"))
                    },
                    { _, _, _ ->
                        statusReads += 1
                        if (statusReads == 1) LocalSendStatusFfi.Rejected else LocalSendStatusFfi.Queued
                    },
                )
            try {
                controller.retryMembers()
                controller.send("hello")
                val token =
                    controller.timeline
                        .single()
                        .record.messageIdHex
                controller.beginMessageEdit(token)
                controller.send("retained revision")
                applyNativeEditProjection(controller, token, "retained revision", "edit-1")
                settleNativePresentation()
                assertEquals(MessageStatus.Failed, controller.timeline.single().status)
                applyNativeEditProjection(controller, token)
                assertEquals(MessageStatus.Failed, controller.timeline.single().status)
                controller.retryFailedSend(controller.timeline.single())
                settleNativePresentation()
                assertEquals(2, tokens.size)
                assertFalse(tokens[0] == tokens[1])
                assertEquals("retained revision", controller.displayedText(controller.timeline.single().record))
                assertEquals(MessageStatus.Pending, controller.timeline.single().status)
            } finally {
                controller.onCleared()
                Dispatchers.resetMain()
            }
        }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun duplicatePendingEditSubmissionDoesNotInvalidateTheAcceptedEditorCallback() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val admissionReturn = CompletableDeferred<Unit>()
            var admissions = 0
            val controller =
                nativeEditController(
                    CompletableDeferred<Unit>().also { it.complete(Unit) },
                ) { _, _, _, _, _ ->
                    admissions += 1
                    admissionReturn.await()
                    pendingLocalSend(listOf("edit-id"))
                }
            try {
                controller.retryMembers()
                controller.send("hello")
                val token =
                    controller.timeline
                        .single()
                        .record.messageIdHex
                controller.beginMessageEdit(token)
                val first = async(start = CoroutineStart.UNDISPATCHED) { controller.send("same revision") }
                controller.send("same revision")
                assertEquals(1, admissions)
                admissionReturn.complete(Unit)
                first.await()
                assertNull(controller.editingMessageId)
                assertEquals("same revision", controller.displayedText(controller.timeline.single().record))
            } finally {
                admissionReturn.complete(Unit)
                controller.onCleared()
                Dispatchers.resetMain()
            }
        }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun olderNativeEditSuccessCannotDismissANewerUnadmittedRevision() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val olderReturn = CompletableDeferred<Unit>()
            val newerReturn = CompletableDeferred<Unit>()
            val controller =
                nativeEditControllerWithStatus(
                    CompletableDeferred<Unit>().also { it.complete(Unit) },
                    { _, _, _, text, _ ->
                        if (text == "older revision") {
                            olderReturn.await()
                            pendingLocalSend(listOf("older-edit-id"))
                        } else {
                            newerReturn.await()
                            throw MarmotKitException.Runtime("newer admission failed")
                        }
                    },
                    { _, _, _ -> null },
                )
            try {
                controller.retryMembers()
                controller.send("hello")
                val token =
                    controller.timeline
                        .single()
                        .record.messageIdHex
                controller.beginMessageEdit(token)
                val older = async(start = CoroutineStart.UNDISPATCHED) { controller.send("older revision") }
                val newer = async(start = CoroutineStart.UNDISPATCHED) { controller.send("newer revision") }
                applyNativeEditProjection(controller, token, "older revision", "older-edit-id")
                olderReturn.complete(Unit)
                older.await()
                assertEquals(token, controller.editingMessageId)
                assertEquals("newer revision", controller.displayedText(controller.timeline.single().record))
                assertEquals(MessageStatus.Pending, controller.timeline.single().status)
                newerReturn.complete(Unit)
                newer.await()
                settleNativePresentation()
                assertEquals(token, controller.editingMessageId)
                assertEquals("newer revision", controller.displayedText(controller.timeline.single().record))
                assertEquals(MessageStatus.Failed, controller.timeline.single().status)
            } finally {
                olderReturn.complete(Unit)
                newerReturn.complete(Unit)
                controller.onCleared()
                Dispatchers.resetMain()
            }
        }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun ambiguousNativeEditFailureCannotDiscardAnAuthoritativelyQueuedRevision() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val controller =
                nativeEditController(
                    CompletableDeferred<Unit>().also { it.complete(Unit) },
                ) { _, _, _, _, _ -> throw MarmotKitException.Runtime("acknowledgement unavailable") }
            try {
                controller.retryMembers()
                controller.send("hello")
                val token =
                    controller.timeline
                        .single()
                        .record.messageIdHex
                controller.beginMessageEdit(token)
                controller.send("retained revision")
                assertEquals(MessageStatus.Failed, controller.timeline.single().status)
                controller.discardFailedSend(controller.timeline.single())
                assertEquals(MessageStatus.Pending, controller.timeline.single().status)
                assertEquals("retained revision", controller.displayedText(controller.timeline.single().record))
                assertEquals(token, controller.editingMessageId)
            } finally {
                controller.onCleared()
                Dispatchers.resetMain()
            }
        }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun lateNativeEditFailureAfterAccountSwitchPreservesTheNewerDraftWithoutFeedback() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val originalReturn = CompletableDeferred<Unit>().also { it.complete(Unit) }
            val editStarted = CompletableDeferred<Unit>()
            val editReturn = CompletableDeferred<Unit>()
            val state =
                appState(
                    additionalAccounts =
                        listOf(
                            AccountSummaryFfi("bob", "b".repeat(64), true, false, false, true),
                        ),
                )
            val controller =
                nativeEditControllerWithStatus(
                    originalReturn,
                    publisher = { account, _, _, _, _ ->
                        assertEquals(ACCOUNT_REF, account)
                        editStarted.complete(Unit)
                        editReturn.await()
                        throw MarmotKitException.Runtime("old account admission failed")
                    },
                    statusReader = { _, _, _ -> LocalSendStatusFfi.Queued },
                    state = state,
                )
            try {
                controller.retryMembers()
                controller.send("hello")
                val token =
                    controller.timeline
                        .single()
                        .record.messageIdHex
                controller.beginMessageEdit(token)
                val edit = async { controller.send("retained revision") }
                editStarted.await()
                controller.appState.setDraft(ACCOUNT_REF, GROUP_ID, TextFieldValue("newer draft"))
                val preload = AccountSwitchPreloadPolicy.STARTUP_RESTORATION
                assertTrue(state.setActiveAccount("bob", preloadPolicy = preload))
                controller.appState.setDraft("bob", GROUP_ID, TextFieldValue("other account draft"))
                editReturn.complete(Unit)
                edit.await()
                assertEquals("newer draft", controller.appState.draftFor(ACCOUNT_REF, GROUP_ID))
                assertEquals("other account draft", controller.appState.draftFor("bob", GROUP_ID))
                assertNull(controller.appState.toast)
                assertEquals(token, controller.editingMessageId)
            } finally {
                editReturn.complete(Unit)
                controller.onCleared()
                Dispatchers.resetMain()
            }
        }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun cancellingReopenedEditorDispatchesTheSubmittedRevisionAfterOriginalConfirms() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val releaseOriginal = CompletableDeferred<Unit>()
            val publishedEdit = CompletableDeferred<Pair<String, String>>()
            var editCalls = 0
            val appState = appState()
            val controller =
                ConversationController(
                    appState = appState,
                    initialGroup = group(),
                    initialMemberSnapshot = memberSnapshot(),
                    groupRosterReader = { _, _ -> authoritativeRoster() },
                    textPublisher = { _, _, _, _ ->
                        releaseOriginal.await()
                        successfulSendSummary()
                    },
                    messageEditPublisher = { _, _, target, text ->
                        editCalls += 1
                        publishedEdit.complete(target to text)
                    },
                )

            try {
                controller.retryMembers()
                assertTrue(controller.canSendMessages)
                val original = async(start = CoroutineStart.UNDISPATCHED) { controller.send("original") }
                val optimisticMessage = controller.timeline.single()
                val clientToken = optimisticMessage.record.messageIdHex
                controller.beginMessageEdit(clientToken)
                controller.send("revision A")
                controller.beginMessageEdit(clientToken)

                releaseOriginal.complete(Unit)
                original.await()
                val handoffKey = "$ACCOUNT_REF|$GROUP_ID|$clientToken"
                assertTrue(appState.pendingMessageEditHandoff.hasSession(handoffKey))
                controller.cancelMessageEdit()
                assertFalse(appState.pendingMessageEditHandoff.hasSession(handoffKey))
                runCurrent()

                assertEquals(
                    CONFIRMED_MESSAGE_ID to "revision A",
                    withTimeout(5_000) { publishedEdit.await() },
                )
                controller.cancelMessageEdit()
                assertEquals(1, editCalls)
            } finally {
                releaseOriginal.complete(Unit)
                controller.onCleared()
                Dispatchers.resetMain()
            }
        }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun switchingEditTargetsSettlesThePreviousPendingRevision() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val releaseOriginal = CompletableDeferred<Unit>()
            val publishedEdit = CompletableDeferred<Pair<String, String>>()
            var editCalls = 0
            val appState = appState()
            val controller =
                ConversationController(
                    appState = appState,
                    initialGroup = group(),
                    initialMemberSnapshot = memberSnapshot(),
                    groupRosterReader = { _, _ -> authoritativeRoster() },
                    textPublisher = { _, _, _, _ ->
                        releaseOriginal.await()
                        successfulSendSummary()
                    },
                    messageEditPublisher = { _, _, target, text ->
                        editCalls += 1
                        publishedEdit.complete(target to text)
                    },
                )

            try {
                controller.retryMembers()
                assertTrue(controller.canSendMessages)
                val original = async(start = CoroutineStart.UNDISPATCHED) { controller.send("original") }
                val clientToken =
                    controller.timeline
                        .single()
                        .record.messageIdHex
                controller.beginMessageEdit(clientToken)
                controller.send("revision A")
                controller.beginMessageEdit(clientToken)

                val otherMessageId = "b2".repeat(32)
                controller.beginMessageEdit(otherMessageId)
                assertEquals(otherMessageId, controller.editingMessageId)
                val handoffKey = "$ACCOUNT_REF|$GROUP_ID|$clientToken"
                assertTrue(appState.pendingMessageEditHandoff.hasSession(handoffKey))

                releaseOriginal.complete(Unit)
                original.await()
                runCurrent()
                assertEquals(
                    CONFIRMED_MESSAGE_ID to "revision A",
                    withTimeout(5_000) { publishedEdit.await() },
                )
                controller.cancelMessageEdit()
                assertNull(controller.editingMessageId)
                assertFalse(appState.pendingMessageEditHandoff.hasSession(handoffKey))
                assertEquals(1, editCalls)
            } finally {
                releaseOriginal.complete(Unit)
                controller.onCleared()
                Dispatchers.resetMain()
            }
        }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    @Suppress("LongMethod") // One controller replacement must cover the original confirmation and queued edit.
    fun replacingControllerDispatchesTheSubmittedRevisionAfterOriginalConfirms() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val releaseOriginal = CompletableDeferred<Unit>()
            val publishedEdit = CompletableDeferred<Pair<String, String>>()
            var editCalls = 0
            val appState = appState()
            val controller =
                ConversationController(
                    appState = appState,
                    initialGroup = group(),
                    initialMemberSnapshot = memberSnapshot(),
                    groupRosterReader = { _, _ -> authoritativeRoster() },
                    textPublisher = { _, _, _, _ ->
                        releaseOriginal.await()
                        successfulSendSummary()
                    },
                    messageEditPublisher = { _, _, target, text ->
                        editCalls += 1
                        publishedEdit.complete(target to text)
                    },
                )
            var replacement: ConversationController? = null

            try {
                controller.retryMembers()
                assertTrue(controller.canSendMessages)
                val original = async(start = CoroutineStart.UNDISPATCHED) { controller.send("original") }
                val clientToken =
                    controller.timeline
                        .single()
                        .record.messageIdHex
                controller.beginMessageEdit(clientToken)
                controller.send("revision A")
                controller.beginMessageEdit(clientToken)

                releaseOriginal.complete(Unit)
                original.await()
                val handoffKey = "$ACCOUNT_REF|$GROUP_ID|$clientToken"
                assertTrue(appState.pendingMessageEditHandoff.hasSession(handoffKey))

                controller.onCleared()
                val recreated =
                    ConversationController(
                        appState = appState,
                        initialGroup = group(),
                        initialMemberSnapshot = memberSnapshot(),
                        groupRosterReader = { _, _ -> authoritativeRoster() },
                    )
                replacement = recreated
                assertFalse(appState.pendingMessageEditHandoff.hasSession(handoffKey))
                recreated.cancelMessageEdit()
                runCurrent()

                assertEquals(
                    CONFIRMED_MESSAGE_ID to "revision A",
                    withTimeout(5_000) { publishedEdit.await() },
                )
                controller.onCleared()
                recreated.onCleared()
                assertEquals(1, editCalls)
            } finally {
                releaseOriginal.complete(Unit)
                replacement?.onCleared()
                controller.onCleared()
                Dispatchers.resetMain()
            }
        }

    /** Exact caller identity settles only its bubble even when text and timestamps are identical. */
    @Test
    fun callerTokenReconcilesIdenticalOptimisticMessagesWithoutHeuristics() =
        runTest {
            val controller =
                ConversationController(
                    appState = appState(),
                    initialGroup = group(),
                    initialMemberSnapshot = memberSnapshot(),
                    textPublisher = { _, _, _, _ -> pendingLocalSend() },
                    clockMillis = { 20_000L },
                )
            controller.send("hello")
            val first =
                controller.timeline
                    .single()
                    .record.messageIdHex
            controller.send("hello")
            val second =
                controller.timeline
                    .first { it.record.messageIdHex != first }
                    .record.messageIdHex
            val projection = projectedMessage(20uL, null, null).copy(clientToken = second)
            applyProjection(controller, projection)
            assertEquals(2, controller.timeline.size)
            assertTrue(
                controller.timeline.any {
                    it.record.messageIdHex == first && it.status == MessageStatus.Pending
                },
            )
            assertFalse(controller.timeline.any { it.record.messageIdHex == second })
            // An unrelated caller token with identical visible content must not consume the remaining bubble.
            applyProjection(controller, projection.copy(messageIdHex = "ee".repeat(32), clientToken = "another-caller"))
            assertTrue(
                controller.timeline.any {
                    it.record.messageIdHex == first && it.status == MessageStatus.Pending
                },
            )
        }

    /** Hands dictation back to Idle at optimistic publication, before transport settles. */
    @Test
    fun dictationPendingCallbackPrecedesDurableAcceptance() =
        runTest {
            val appState = appState()
            appState.setDraft(GROUP_ID, TextFieldValue("typed"))
            val releasePublish = CompletableDeferred<Unit>()
            var pendingShown = 0
            val controller =
                ConversationController(
                    appState = appState,
                    initialGroup = group(),
                    initialMemberSnapshot = memberSnapshot(),
                    textPublisher = { _, _, _, _ ->
                        releasePublish.await()
                        successfulSendSummary()
                    },
                )
            appState.attachConversationController(controller)
            val request =
                ConversationDictationSendRequest(
                    accountRef = ACCOUNT_REF,
                    groupIdHex = GROUP_ID,
                    expectedDraftRevision = appState.composerDraftGeneration(ACCOUNT_REF, GROUP_ID),
                    expectedDraftText = "typed",
                    payload = "typed spoken",
                    onPendingShown = { pendingShown += 1 },
                )

            try {
                val send =
                    async(start = CoroutineStart.UNDISPATCHED) {
                        appState.sendDictationTranscriptIfOriginUnchanged(request)
                    }

                assertEquals(1, pendingShown)
                assertEquals(MessageStatus.Pending, controller.timeline.single().status)
                assertFalse(send.isCompleted)

                releasePublish.complete(Unit)
                assertTrue(send.await())
            } finally {
                releasePublish.complete(Unit)
                appState.detachConversationController(controller)
            }
        }

    @Test
    fun rejectedAndUnknownDictationReplySendsRestoreTheCapturedReplyTarget() =
        runTest {
            val failures =
                listOf(
                    MarmotKitException.Publish("relay rejected event"),
                    MarmotKitException.Publish("send event timed out"),
                )

            failures.forEach { failure ->
                val appState = appState()
                appState.setDraft(GROUP_ID, TextFieldValue("typed"))
                val reply = timelineAppMessage(REPLY_MESSAGE_ID)
                val controller =
                    ConversationController(
                        appState = appState,
                        initialGroup = group(),
                        initialMemberSnapshot = memberSnapshot(),
                        textPublisher = { replyTarget, _, _, _ ->
                            assertEquals(REPLY_MESSAGE_ID, replyTarget)
                            throw failure
                        },
                    )
                controller.replyingTo = reply
                appState.attachConversationController(controller)
                val request =
                    ConversationDictationSendRequest(
                        accountRef = ACCOUNT_REF,
                        groupIdHex = GROUP_ID,
                        expectedDraftRevision = appState.composerDraftGeneration(ACCOUNT_REF, GROUP_ID),
                        expectedDraftText = "typed",
                        payload = "spoken reply",
                        replyToMessageIdHex = REPLY_MESSAGE_ID,
                    )

                try {
                    assertFalse(appState.sendDictationTranscriptIfOriginUnchanged(request))
                    assertEquals(reply, controller.replyingTo)
                } finally {
                    appState.detachConversationController(controller)
                }
            }
        }

    @Test
    fun acceptInviteRetriesAClosedRuntimeWorkerWithoutRollingBackOrReportingAnError() =
        runTest {
            val appState = appState()
            var attempts = 0
            lateinit var controller: ConversationController
            controller =
                ConversationController(
                    appState = appState,
                    initialGroup = group(pendingConfirmation = true),
                    initialMemberSnapshot = memberSnapshot(),
                    inviteAcceptor = { account, groupIdHex ->
                        attempts += 1
                        assertEquals(ACCOUNT_REF, account)
                        assertEquals(GROUP_ID, groupIdHex)
                        assertFalse(controller.group.pendingConfirmation)
                        if (attempts == 1) throw MarmotKitException.TransportClosed()
                        group(pendingConfirmation = false)
                    },
                )

            assertTrue(controller.acceptInvite(notify = false))

            assertEquals(2, attempts)
            assertFalse(controller.group.pendingConfirmation)
            assertEquals(null, appState.toast)
        }

    @Test
    fun acceptInviteRetriesAConnectGapWithoutRollingBackOrReportingAnError() =
        runTest {
            val appState = appState()
            var attempts = 0
            lateinit var controller: ConversationController
            controller =
                ConversationController(
                    appState = appState,
                    initialGroup = group(pendingConfirmation = true),
                    initialMemberSnapshot = memberSnapshot(),
                    inviteAcceptor = { _, _ ->
                        attempts += 1
                        assertFalse(controller.group.pendingConfirmation)
                        if (attempts == 1) {
                            throw MarmotKitException.Publish("connect relay failed")
                        }
                        group(pendingConfirmation = false)
                    },
                )

            assertTrue(controller.acceptInvite(notify = false))

            assertEquals(2, attempts)
            assertFalse(controller.group.pendingConfirmation)
            assertEquals(null, appState.toast)
        }

    /** Keeps one logical acceptance pending across typed transient runtime contention. */
    @Test
    fun acceptInviteRetriesTypedContentionWithoutRollingBackOrReportingAnError() =
        runTest {
            val transientFailures =
                listOf(
                    MarmotKitException.AccountWorkerBusy(),
                    MarmotKitException.RuntimeBusy(),
                    MarmotKitException.AccountSessionBusy(),
                    MarmotKitException.StorageBusy("database is locked"),
                )

            transientFailures.forEach { transientFailure ->
                val appState = appState()
                var attempts = 0
                lateinit var controller: ConversationController
                controller =
                    ConversationController(
                        appState = appState,
                        initialGroup = group(pendingConfirmation = true),
                        initialMemberSnapshot = memberSnapshot(),
                        inviteAcceptor = { _, _ ->
                            attempts += 1
                            assertFalse(controller.group.pendingConfirmation)
                            if (attempts == 1) throw transientFailure
                            group(pendingConfirmation = false)
                        },
                    )

                assertTrue(controller.acceptInvite(notify = false))

                assertEquals(2, attempts)
                assertFalse(controller.group.pendingConfirmation)
                assertEquals(null, appState.toast)
            }
        }

    /** Exhaustion restores the authoritative invite once and reports one actionable failure. */
    @Test
    fun acceptInviteRollsBackOnceAfterPersistentContentionExhaustsTheRetryBudget() =
        runTest {
            val appState = appState()
            var attempts = 0
            val controller =
                ConversationController(
                    appState = appState,
                    initialGroup = group(pendingConfirmation = true),
                    initialMemberSnapshot = memberSnapshot(),
                    inviteAcceptor = { _, _ ->
                        attempts += 1
                        throw MarmotKitException.StorageBusy("database is locked")
                    },
                )

            assertFalse(controller.acceptInvite(notify = false))

            assertEquals(IDEMPOTENT_CONTENTION_RETRY_ATTEMPTS, attempts)
            assertTrue(controller.group.pendingConfirmation)
            assertTrue(appState.toast?.diagnosticReport?.contains("operation=GROUP_INVITE_ACCEPT") == true)
            assertTrue(appState.toast?.diagnosticReport?.contains("error=RESOURCE_BUSY") == true)
        }

    /** Cancellation restores truthful invite state without presenting an error. */
    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun cancellingInviteAcceptanceDuringContentionBackoffRollsBackSilently() =
        runTest {
            val appState = appState()
            var attempts = 0
            val controller =
                ConversationController(
                    appState = appState,
                    initialGroup = group(pendingConfirmation = true),
                    initialMemberSnapshot = memberSnapshot(),
                    inviteAcceptor = { _, _ ->
                        attempts += 1
                        throw MarmotKitException.AccountWorkerBusy()
                    },
                )
            val acceptance = async { controller.acceptInvite(notify = false) }

            advanceTimeBy(10_000L)
            runCurrent()
            assertEquals(5, attempts)
            assertFalse(controller.group.pendingConfirmation)

            acceptance.cancelAndJoin()

            assertTrue(controller.group.pendingConfirmation)
            assertEquals(null, appState.toast)
        }

    /** A catch-up longer than the original retry budget retains one optimistic acceptance. */
    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun acceptsInviteAfterTenSecondsOfWorkerContention() =
        runTest {
            val appState = appState()
            var attempts = 0
            lateinit var controller: ConversationController
            controller =
                ConversationController(
                    appState = appState,
                    initialGroup = group(pendingConfirmation = true),
                    initialMemberSnapshot = memberSnapshot(),
                    inviteAcceptor = { _, _ ->
                        attempts += 1
                        assertFalse(controller.group.pendingConfirmation)
                        assertEquals(null, appState.toast)
                        if (testScheduler.currentTime < 10_000L) throw MarmotKitException.AccountWorkerBusy()
                        group(pendingConfirmation = false)
                    },
                )
            val acceptance = async { controller.acceptInvite(notify = false) }

            advanceTimeBy(10_000L)
            assertFalse(acceptance.isCompleted)
            assertFalse(controller.acceptInvite(notify = false))
            assertEquals(5, attempts)
            assertTrue(acceptance.await())
            assertEquals(6, attempts)
            assertFalse(controller.group.pendingConfirmation)
            assertEquals(null, appState.toast)
        }

    /** A repeated tap cannot start another logical accept while the first one is pending. */
    @Test
    fun repeatedInviteTapIsDroppedWhileContentionRetryIsInFlight() =
        runTest {
            val appState = appState()
            val firstAttemptStarted = CompletableDeferred<Unit>()
            val releaseFirstAttempt = CompletableDeferred<Unit>()
            var attempts = 0
            val controller =
                ConversationController(
                    appState = appState,
                    initialGroup = group(pendingConfirmation = true),
                    initialMemberSnapshot = memberSnapshot(),
                    inviteAcceptor = { _, _ ->
                        attempts += 1
                        if (attempts == 1) {
                            firstAttemptStarted.complete(Unit)
                            releaseFirstAttempt.await()
                            throw MarmotKitException.AccountWorkerBusy()
                        }
                        group(pendingConfirmation = false)
                    },
                )
            val firstAcceptance = async { controller.acceptInvite(notify = false) }
            firstAttemptStarted.await()

            assertFalse(controller.acceptInvite(notify = false))
            assertEquals(1, attempts)
            assertEquals(null, appState.toast)

            releaseFirstAttempt.complete(Unit)
            assertTrue(firstAcceptance.await())
            assertEquals(2, attempts)
            assertFalse(controller.group.pendingConfirmation)
        }

    @Test
    fun durableAcceptanceClearsTheCapturedComposerDraft() {
        val appState = appState()
        appState.setDraft(GROUP_ID, TextFieldValue("hello"))
        val pendingClear = requireNotNull(appState.captureDraftForSend(ACCOUNT_REF, GROUP_ID))

        appState.clearDraftAfterSuccessfulSend(pendingClear)

        assertEquals(null, appState.draftFor(GROUP_ID))
    }

    @Test
    fun durableAcceptanceDoesNotClearANewerComposerDraft() {
        val appState = appState()
        appState.setDraft(GROUP_ID, TextFieldValue("first message"))
        val pendingClear = requireNotNull(appState.captureDraftForSend(ACCOUNT_REF, GROUP_ID))
        appState.setDraft(GROUP_ID, TextFieldValue("next message"))

        appState.clearDraftAfterSuccessfulSend(pendingClear)

        assertEquals("next message", appState.draftFor(GROUP_ID))
    }

    /** A definite post-acceptance failure restores the geometry captured with its draft. */
    @Test
    fun terminalFailureRestoresTheCapturedComposerExpansion() =
        runTest {
            val appState = appState()
            appState.setDraft(GROUP_ID, TextFieldValue("restore height"))
            appState.composerExpansionStateRetention.update(
                ACCOUNT_REF,
                GROUP_ID,
                manualExpansion(240f),
                appState.composerDraftGeneration(ACCOUNT_REF, GROUP_ID),
            )
            val controller =
                ConversationController(
                    appState = appState,
                    initialGroup = group(),
                    initialMemberSnapshot = memberSnapshot(),
                    textPublisher = { _, _, _, _ ->
                        assertNull(appState.composerExpansionStateRetention.preferenceFor(ACCOUNT_REF, GROUP_ID))
                        throw MarmotKitException.Publish("relay rejected event")
                    },
                )

            appState.sendConversationText(controller, "restore height")

            assertEquals(
                manualExpansion(240f),
                appState.composerExpansionStateRetention.preferenceFor(ACCOUNT_REF, GROUP_ID),
            )
        }

    /** A stale durable callback cannot remove geometry selected for a newer draft generation. */
    @Test
    fun lateDurableAcceptanceCannotDeleteANewerDraftsComposerExpansion() =
        runTest {
            val appState = appState()
            appState.setDraft(GROUP_ID, TextFieldValue("first"))
            appState.composerExpansionStateRetention.update(
                ACCOUNT_REF,
                GROUP_ID,
                manualExpansion(240f),
                appState.composerDraftGeneration(ACCOUNT_REF, GROUP_ID),
            )
            val publishStarted = CompletableDeferred<Unit>()
            val finishPublish = CompletableDeferred<Unit>()
            val controller =
                ConversationController(
                    appState = appState,
                    initialGroup = group(),
                    initialMemberSnapshot = memberSnapshot(),
                    textPublisher = { _, _, _, _ ->
                        publishStarted.complete(Unit)
                        finishPublish.await()
                        SendSummaryFfi(
                            published = 1u,
                            messageIds = listOf(CONFIRMED_MESSAGE_ID),
                            acceptDisposition = SendAcceptDispositionFfi.PUBLISHED,
                            maintenanceDisposition = SendMaintenanceDispositionFfi.READY,
                        )
                    },
                )
            val send = async { appState.sendConversationText(controller, "first") }
            publishStarted.await()
            assertNull(appState.composerExpansionStateRetention.preferenceFor(ACCOUNT_REF, GROUP_ID))

            appState.setDraft(GROUP_ID, TextFieldValue("next"))
            appState.composerExpansionStateRetention.update(
                ACCOUNT_REF,
                GROUP_ID,
                manualExpansion(300f),
                appState.composerDraftGeneration(ACCOUNT_REF, GROUP_ID),
            )
            finishPublish.complete(Unit)
            send.await()

            assertEquals(
                manualExpansion(300f),
                appState.composerExpansionStateRetention.preferenceFor(ACCOUNT_REF, GROUP_ID),
            )
        }

    @Test
    fun preAcceptanceFailureKeepsTheComposerDraftForRehydration() =
        runTest {
            val appState = appState()
            appState.setDraft(GROUP_ID, TextFieldValue("survives restart"))
            val controller =
                ConversationController(
                    appState = appState,
                    initialGroup = group(),
                    initialMemberSnapshot = memberSnapshot(),
                    textPublisher = { _, _, _, _ ->
                        throw MarmotKitException.Publish("relay rejected event")
                    },
                )

            appState.sendConversationText(controller, "survives restart")

            assertEquals("survives restart", appState.draftFor(GROUP_ID))
            assertEquals(MessageStatus.Failed, controller.timeline.single().status)
        }

    /** The `:party:` emoji image these tests send. */
    private fun partyArtwork() = LocalEmojiArtwork(":party:", "party.png", "image/png", byteArrayOf(1))

    /** A controller whose text uses one custom emoji and whose emoji send runs [send] instead of a native call. */
    private fun emojiController(
        appState: WhiteNoiseAppState,
        send: suspend () -> SendSummaryFfi,
    ) = ConversationController(
        appState = appState,
        initialGroup = group(),
        initialMemberSnapshot = memberSnapshot(),
        customEmojiReader = { listOf(partyArtwork()) },
        customEmojiSender = { _, _, _, _, _ -> send() },
    )

    /**
     * Cancelling while the emoji images upload must not wait for the upload, must publish nothing, and
     * must not hold the group commit lock, so reactions and edits stay usable meanwhile.
     */
    @Test
    fun cancellingDuringACustomEmojiUploadPublishesNothingAndDoesNotBlockMutations() =
        runTest {
            val uploadStarted = CompletableDeferred<Unit>()
            val releaseUpload = CompletableDeferred<Unit>()
            var published = 0
            val appState = appState()
            val controller =
                ConversationController(
                    appState = appState,
                    initialGroup = group(),
                    initialMemberSnapshot = memberSnapshot(),
                    customEmojiReader = { listOf(partyArtwork()) },
                    customEmojiSender = { _, _, _, ensureCurrent, publishLock ->
                        uploadStarted.complete(Unit)
                        releaseUpload.await()
                        publishLock {
                            ensureCurrent()
                            published += 1
                            successfulSendSummary()
                        }
                    },
                )

            val send =
                async(start = CoroutineStart.UNDISPATCHED) {
                    appState.sendConversationText(controller, "hi :party:")
                }
            uploadStarted.await()
            val pending = controller.timeline.single().record

            // Other group mutations take the same lock and must not wait for the upload.
            withTimeout(5_000) { appState.withGroupCommitLock(ACCOUNT_REF, GROUP_ID) { } }
            // Cancel must also complete without waiting for the upload.
            withTimeout(5_000) { assertTrue(controller.deleteMessage(pending, presentFailure = false)) }
            assertTrue(controller.timeline.isEmpty())

            releaseUpload.complete(Unit)
            send.await()

            assertEquals(0, published)
            assertTrue(controller.timeline.isEmpty())
        }

    /**
     * A cancel that lands right after the emoji publish must not report success for a send MDK now
     * owns. The publish and its acceptance bookkeeping share the commit lock, so the waiting cancel
     * sees the finished send, and the yield after the lock models the hop back from the native call.
     */
    @Test
    fun cancelRightAfterAnEmojiPublishDoesNotHideAPublishedSend() =
        runTest {
            val publishing = CompletableDeferred<Unit>()
            val finishPublish = CompletableDeferred<Unit>()
            val appState = appState()
            val controller =
                ConversationController(
                    appState = appState,
                    initialGroup = group(),
                    initialMemberSnapshot = memberSnapshot(),
                    customEmojiReader = { listOf(partyArtwork()) },
                    customEmojiSender = { _, _, _, ensureCurrent, publishLock ->
                        val summary =
                            publishLock {
                                ensureCurrent()
                                publishing.complete(Unit)
                                finishPublish.await()
                                successfulSendSummary()
                            }
                        yield()
                        summary
                    },
                )

            val send =
                async(start = CoroutineStart.UNDISPATCHED) {
                    appState.sendConversationText(controller, "hi :party:")
                }
            publishing.await()
            val pending = controller.timeline.single().record
            val cancel = async { controller.deleteMessage(pending, presentFailure = false) }
            runCurrent()
            finishPublish.complete(Unit)

            assertFalse("a published send must not be cancelled away", cancel.await())
            send.await()
            assertEquals(1, controller.timeline.size)
        }

    /** A publish that fails ambiguously may be on a relay, so it cannot be cancelled away either. */
    @Test
    fun cancelRightAfterAnUncertainEmojiPublishCannotRemoveTheRow() =
        runTest {
            val publishing = CompletableDeferred<Unit>()
            val finishPublish = CompletableDeferred<Unit>()
            val appState = appState()
            val controller =
                ConversationController(
                    appState = appState,
                    initialGroup = group(),
                    initialMemberSnapshot = memberSnapshot(),
                    customEmojiReader = { listOf(partyArtwork()) },
                    customEmojiSender = { _, _, _, ensureCurrent, publishLock ->
                        try {
                            publishLock {
                                ensureCurrent()
                                publishing.complete(Unit)
                                finishPublish.await()
                                throw MarmotKitException.TransportClosed()
                            }
                        } finally {
                            yield()
                        }
                    },
                )

            val send =
                async(start = CoroutineStart.UNDISPATCHED) {
                    appState.sendConversationText(controller, "hi :party:")
                }
            publishing.await()
            val pending = controller.timeline.single().record
            val cancel = async { controller.deleteMessage(pending, presentFailure = false) }
            runCurrent()
            finishPublish.complete(Unit)

            assertFalse("an uncertain publish must not be cancelled away", cancel.await())
            send.await()
            assertEquals(MessageStatus.Pending, controller.timeline.single().status)
        }

    /** An upload-stage failure published nothing, so the bubble ends Failed and never stays Pending. */
    @Test
    fun customEmojiUploadFailureEndsFailedNotPending() =
        runTest {
            var sends = 0
            val appState = appState()
            val controller =
                emojiController(appState) {
                    sends += 1
                    throw EmojiUploadFailure(IllegalStateException("blob server rejected the image"))
                }

            appState.sendConversationText(controller, "hi :party:")

            assertEquals(1, sends)
            assertEquals(MessageStatus.Failed, controller.timeline.single().status)
        }

    /** A publish-stage connection loss may have reached a relay, so the bubble stays Pending and is not resent. */
    @Test
    fun customEmojiPublishLossStaysPending() =
        runTest {
            var sends = 0
            val appState = appState()
            val controller =
                emojiController(appState) {
                    sends += 1
                    throw MarmotKitException.TransportClosed()
                }

            appState.sendConversationText(controller, "hi :party:")

            assertEquals(1, sends)
            assertEquals(MessageStatus.Pending, controller.timeline.single().status)
        }

    /** A changed chat is a definite failure the user can retry, not an uncertain delivery. */
    @Test
    fun customEmojiChatChangedEndsFailed() =
        runTest {
            val appState = appState()
            val controller =
                emojiController(appState) {
                    throw EmojiChatChangedException(MarmotKitException.InvalidMediaReference("stale"))
                }

            appState.sendConversationText(controller, "hi :party:")

            assertEquals(MessageStatus.Failed, controller.timeline.single().status)
        }

    /**
     * Mimics the real emoji sender's two attempts: each runs [publishLock] around a publish that MDK
     * rejects as a stale epoch, [betweenAttempts] runs after the first rejection, and a second
     * rejection is reported as a changed chat.
     */
    private suspend fun staleEpochTwice(
        ensureCurrent: suspend () -> Unit,
        publishLock: EmojiPublishLock,
        betweenAttempts: suspend () -> Unit = {},
        onPublish: () -> Unit = {},
    ): SendSummaryFfi {
        var last: MarmotKitException? = null
        repeat(2) { attempt ->
            if (attempt == 1) betweenAttempts()
            try {
                return publishLock {
                    ensureCurrent()
                    onPublish()
                    throw MarmotKitException.InvalidMediaReference(STALE_EPOCH_DETAILS)
                }
            } catch (rejection: MarmotKitException.InvalidMediaReference) {
                last = rejection
            }
        }
        throw EmojiChatChangedException(checkNotNull(last))
    }

    /** Two stale-epoch rejections published nothing, so the row ends Failed rather than Pending forever. */
    @Test
    fun twiceStaleEmojiPublishEndsFailedNotPending() =
        runTest {
            var publishes = 0
            val appState = appState()
            val controller =
                ConversationController(
                    appState = appState,
                    initialGroup = group(),
                    initialMemberSnapshot = memberSnapshot(),
                    customEmojiReader = { listOf(partyArtwork()) },
                    customEmojiSender = { _, _, _, ensureCurrent, publishLock ->
                        staleEpochTwice(ensureCurrent, publishLock, onPublish = { publishes += 1 })
                    },
                )

            appState.sendConversationText(controller, "hi :party:")

            assertEquals(2, publishes)
            assertEquals(MessageStatus.Failed, controller.timeline.single().status)
        }

    /** A stale-epoch rejection leaves the send pre-acceptance, so a cancel between the two attempts succeeds. */
    @Test
    fun cancelBetweenStaleEmojiAttemptsSucceedsAndPublishesNothingMore() =
        runTest {
            val betweenAttempts = CompletableDeferred<Unit>()
            val resume = CompletableDeferred<Unit>()
            var publishes = 0
            val appState = appState()
            val controller =
                ConversationController(
                    appState = appState,
                    initialGroup = group(),
                    initialMemberSnapshot = memberSnapshot(),
                    customEmojiReader = { listOf(partyArtwork()) },
                    customEmojiSender = { _, _, _, ensureCurrent, publishLock ->
                        staleEpochTwice(
                            ensureCurrent,
                            publishLock,
                            betweenAttempts = {
                                betweenAttempts.complete(Unit)
                                resume.await()
                            },
                            onPublish = { publishes += 1 },
                        )
                    },
                )

            val send =
                async(start = CoroutineStart.UNDISPATCHED) {
                    appState.sendConversationText(controller, "hi :party:")
                }
            betweenAttempts.await()
            val pending = controller.timeline.single().record

            assertTrue(withTimeout(5_000) { controller.deleteMessage(pending, presentFailure = false) })
            assertTrue(controller.timeline.isEmpty())

            resume.complete(Unit)
            send.await()

            assertEquals(1, publishes)
            assertTrue(controller.timeline.isEmpty())
        }

    /** A connectivity failure during upload is retried by the recovery loop and then sends once. */
    @Test
    fun customEmojiUploadConnectivityFailureIsRetried() =
        runTest {
            var sends = 0
            val appState = appState()
            val controller =
                emojiController(appState) {
                    sends += 1
                    if (sends == 1) throw EmojiUploadFailure(MarmotKitException.TransportClosed())
                    successfulSendSummary()
                }

            appState.sendConversationText(controller, "hi :party:")

            assertEquals(2, sends)
        }

    @Test
    fun successfulManualRetryClearsTheDraftCapturedByTheInitialSend() =
        runTest {
            val appState = appState()
            appState.setDraft(GROUP_ID, TextFieldValue("retry me"))
            var attempts = 0
            val controller =
                ConversationController(
                    appState = appState,
                    initialGroup = group(),
                    initialMemberSnapshot = memberSnapshot(),
                    textPublisher = { _, _, _, _ ->
                        attempts += 1
                        if (attempts == 1) {
                            throw MarmotKitException.Publish("relay rejected event")
                        }
                        SendSummaryFfi(
                            published = 1u,
                            messageIds = listOf(CONFIRMED_MESSAGE_ID),
                            acceptDisposition = SendAcceptDispositionFfi.PUBLISHED,
                            maintenanceDisposition = SendMaintenanceDispositionFfi.READY,
                        )
                    },
                )

            appState.sendConversationText(controller, "retry me")
            assertEquals("retry me", appState.draftFor(GROUP_ID))

            controller.retryFailedSend(controller.timeline.single())

            assertEquals(null, appState.draftFor(GROUP_ID))
            assertEquals(MessageStatus.Sent, controller.timeline.single().status)
        }

    @Test
    fun ambiguousManualRetryStaysPendingAndCannotMintAnotherEvent() =
        runTest {
            val appState = appState()
            var attempts = 0
            val controller =
                ConversationController(
                    appState = appState,
                    initialGroup = group(),
                    initialMemberSnapshot = memberSnapshot(),
                    textPublisher = { _, _, _, _ ->
                        attempts += 1
                        when (attempts) {
                            1 -> throw MarmotKitException.Publish("relay rejected event")
                            else -> throw MarmotKitException.Publish("send event timed out")
                        }
                    },
                )

            appState.sendConversationText(controller, "retry once")
            controller.retryFailedSend(controller.timeline.single())

            assertEquals(2, attempts)
            assertEquals(MessageStatus.Pending, controller.timeline.single().status)

            controller.retryFailedSend(controller.timeline.single())

            assertEquals("a pending ambiguous retry must not publish again", 2, attempts)
            assertEquals(MessageStatus.Pending, controller.timeline.single().status)
        }

    @Test
    fun evictionDuringManualRetryRemovesTheBubbleAndMembership() =
        runTest {
            val appState = appState()
            var attempts = 0
            val controller =
                ConversationController(
                    appState = appState,
                    initialGroup = group(),
                    initialMemberSnapshot = memberSnapshot(),
                    textPublisher = { _, _, _, _ ->
                        attempts += 1
                        if (attempts == 1) {
                            throw MarmotKitException.Publish("relay rejected event")
                        }
                        throw IllegalStateException("GroupStateError::UseAfterEviction")
                    },
                )

            appState.sendConversationText(controller, "cannot retry")
            controller.retryFailedSend(controller.timeline.single())

            assertEquals(2, attempts)
            assertTrue(controller.timeline.isEmpty())
            assertFalse(controller.isSelfMember)
        }

    @Test
    fun successfulRetryFromReplacementControllerClearsTheInitiallyCapturedDraft() =
        runTest {
            val appState = appState()
            appState.setDraft(GROUP_ID, TextFieldValue("retry after navigation"))
            val failedController =
                ConversationController(
                    appState = appState,
                    initialGroup = group(),
                    initialMemberSnapshot = memberSnapshot(),
                    textPublisher = { _, _, _, _ ->
                        throw MarmotKitException.Publish("relay rejected event")
                    },
                )

            appState.sendConversationText(failedController, "retry after navigation")
            assertEquals("retry after navigation", appState.draftFor(GROUP_ID))
            assertEquals(MessageStatus.Failed, failedController.timeline.single().status)

            val replacementController =
                ConversationController(
                    appState = appState,
                    initialGroup = group(),
                    initialMemberSnapshot = memberSnapshot(),
                    textPublisher = { _, _, _, _ ->
                        SendSummaryFfi(
                            published = 1u,
                            messageIds = listOf(CONFIRMED_MESSAGE_ID),
                            acceptDisposition = SendAcceptDispositionFfi.PUBLISHED,
                            maintenanceDisposition = SendMaintenanceDispositionFfi.READY,
                        )
                    },
                )

            replacementController.retryFailedSend(replacementController.timeline.single())

            assertEquals(null, appState.draftFor(GROUP_ID))
            assertEquals(MessageStatus.Sent, replacementController.timeline.single().status)
        }

    @Test
    fun retryAdmissionWinsAgainstAStaleDiscardAction() =
        runTest {
            val appState = appState()
            appState.setDraft(GROUP_ID, TextFieldValue("discard while retrying"))
            val retryStarted = CompletableDeferred<Unit>()
            val acceptRetry = CompletableDeferred<Unit>()
            var attempts = 0
            val controller =
                ConversationController(
                    appState = appState,
                    initialGroup = group(),
                    initialMemberSnapshot = memberSnapshot(),
                    textPublisher = { _, _, _, _ ->
                        attempts += 1
                        if (attempts == 1) {
                            throw MarmotKitException.Publish("relay rejected event")
                        }
                        retryStarted.complete(Unit)
                        acceptRetry.await()
                        SendSummaryFfi(
                            published = 1u,
                            messageIds = listOf(CONFIRMED_MESSAGE_ID),
                            acceptDisposition = SendAcceptDispositionFfi.PUBLISHED,
                            maintenanceDisposition = SendMaintenanceDispositionFfi.READY,
                        )
                    },
                )

            appState.sendConversationText(controller, "discard while retrying")
            val failedItem = controller.timeline.single()
            val retry = async { controller.retryFailedSend(failedItem) }
            retryStarted.await()

            val discard = async { controller.discardFailedSend(failedItem) }
            runCurrent()
            assertFalse(discard.isCompleted)

            acceptRetry.complete(Unit)
            retry.await()
            discard.await()

            assertEquals(null, appState.draftFor(GROUP_ID))
            assertEquals(MessageStatus.Sent, controller.timeline.single().status)
            assertEquals(
                CONFIRMED_MESSAGE_ID,
                controller.timeline
                    .single()
                    .record.messageIdHex,
            )
        }

    @Test
    fun cancellationBeforeAdmissionPreventsTextAndReplyPublication() =
        runTest {
            val parseStarted = CompletableDeferred<Unit>()
            val releaseParse = CompletableDeferred<Unit>()
            var publishCalls = 0
            val controller =
                ConversationController(
                    appState = appState(),
                    initialGroup = group(),
                    initialMemberSnapshot = memberSnapshot(),
                    markdownParser = {
                        parseStarted.complete(Unit)
                        releaseParse.await()
                        MarkdownDocumentFfi(
                            truncated = false,
                            blocks = emptyList(),
                            blankLinesBefore = ByteArray(0),
                        )
                    },
                    textPublisher = { _, _, _, _ ->
                        publishCalls += 1
                        successfulSendSummary()
                    },
                )
            controller.replyingTo = timelineAppMessage(REPLY_MESSAGE_ID)

            val send = async(start = CoroutineStart.UNDISPATCHED) { controller.send("cancel this reply") }
            parseStarted.await()
            val pending = controller.timeline.single().record

            assertTrue(controller.deleteMessage(pending, presentFailure = false))
            assertTrue(controller.deleteMessage(pending, presentFailure = false))
            assertTrue(controller.timeline.isEmpty())

            releaseParse.complete(Unit)
            send.await()

            assertEquals(0, publishCalls)
            assertTrue(controller.timeline.isEmpty())
        }

    @Test
    fun cancellingBeforeAdmissionRestoresCapturedComposerDraft() =
        runTest {
            val appState = appState()
            appState.setDraft(GROUP_ID, TextFieldValue("cancel before admission"))
            val parseStarted = CompletableDeferred<Unit>()
            val releaseParse = CompletableDeferred<Unit>()
            val controller =
                ConversationController(
                    appState = appState,
                    initialGroup = group(),
                    initialMemberSnapshot = memberSnapshot(),
                    markdownParser = {
                        parseStarted.complete(Unit)
                        releaseParse.await()
                        MarkdownDocumentFfi(
                            truncated = false,
                            blocks = emptyList(),
                            blankLinesBefore = ByteArray(0),
                        )
                    },
                    textPublisher = { _, _, _, _ -> successfulSendSummary() },
                )

            val send = async { appState.sendConversationText(controller, "cancel before admission") }
            parseStarted.await()
            assertEquals(null, appState.draftFor(GROUP_ID))
            assertTrue(controller.deleteMessage(controller.timeline.single().record, presentFailure = false))

            releaseParse.complete(Unit)
            send.await()

            assertTrue(controller.timeline.isEmpty())
            assertEquals("cancel before admission", appState.draftFor(GROUP_ID))
        }

    @Test
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun cancellingOfflineTextRetryRestoresDraftAndUnblocksNextSendWithoutBackoff() =
        runTest {
            val appState = appState()
            appState.setDraft(GROUP_ID, TextFieldValue("offline draft"))
            val firstFailed = CompletableDeferred<Unit>()
            val published = mutableListOf<String>()
            val controller =
                ConversationController(
                    appState = appState,
                    initialGroup = group(),
                    initialMemberSnapshot = memberSnapshot(),
                    textPublisher = { _, _, _, text ->
                        published += text
                        if (text == "offline draft") {
                            firstFailed.complete(Unit)
                            throw MarmotKitException.Publish("connect relay failed")
                        }
                        successfulSendSummary()
                    },
                )
            val first = async { appState.sendConversationText(controller, "offline draft") }
            firstFailed.await()
            runCurrent()
            val pending = controller.timeline.single().record
            assertTrue(controller.deleteCapabilityFor(pending).canDeleteAtAll)
            assertEquals(null, appState.draftFor(GROUP_ID))
            assertTrue(controller.deleteMessage(pending, presentFailure = false))
            val cancellationAtMs = testScheduler.currentTime
            val second = async { controller.send("next") }
            first.await()
            second.await()
            assertTrue(
                "cancelled retry held text order through backoff",
                testScheduler.currentTime - cancellationAtMs < SEND_RETRY_BACKOFF_MS,
            )
            assertEquals("offline draft", appState.draftFor(GROUP_ID))
            assertEquals(listOf("offline draft", "next"), published)
            assertEquals(1, controller.timeline.size)
            testScheduler.advanceTimeBy(60_000)
            runCurrent()
            assertEquals(listOf("offline draft", "next"), published)
        }

    @Test
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun replacementControllerCancellationWakesOriginalRetryBeforeBackoff() =
        runTest {
            val appState = appState()
            val firstFailed = CompletableDeferred<Unit>()
            val published = mutableListOf<String>()
            val original =
                ConversationController(
                    appState = appState,
                    initialGroup = group(),
                    initialMemberSnapshot = memberSnapshot(),
                    textPublisher = { _, _, _, text ->
                        published += text
                        firstFailed.complete(Unit)
                        throw MarmotKitException.Publish("connect relay failed")
                    },
                )
            val first = async { original.send("offline") }
            firstFailed.await()
            runCurrent()
            val replacement =
                ConversationController(
                    appState = appState,
                    initialGroup = group(),
                    initialMemberSnapshot = memberSnapshot(),
                    textPublisher = { _, _, _, text ->
                        published += text
                        successfulSendSummary()
                    },
                )
            assertTrue(replacement.deleteMessage(replacement.timeline.single().record, presentFailure = false))
            val cancellationAtMs = testScheduler.currentTime
            val second = async { replacement.send("next") }
            first.await()
            second.await()
            assertTrue(
                "replacement cancellation left original retry sleeping",
                testScheduler.currentTime - cancellationAtMs < SEND_RETRY_BACKOFF_MS,
            )
            assertEquals(listOf("offline", "next"), published)
            assertEquals(1, replacement.timeline.size)
        }

    @Test
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun cancellingOfflineReplyDuringBackoffPreventsLaterAdmission() =
        runTest {
            val firstFailed = CompletableDeferred<Unit>()
            var publishCalls = 0
            val controller =
                ConversationController(
                    appState = appState(),
                    initialGroup = group(),
                    initialMemberSnapshot = memberSnapshot(),
                    textPublisher = { _, _, _, _ ->
                        publishCalls += 1
                        firstFailed.complete(Unit)
                        throw MarmotKitException.Publish("connect relay failed")
                    },
                )
            controller.replyingTo = timelineAppMessage(REPLY_MESSAGE_ID)
            val send = async { controller.send("offline reply") }
            firstFailed.await()
            runCurrent()
            assertTrue(controller.deleteMessage(controller.timeline.single().record, presentFailure = false))
            send.await()
            testScheduler.advanceTimeBy(60_000)
            runCurrent()
            assertEquals(1, publishCalls)
            assertTrue(controller.timeline.isEmpty())
        }

    @Test
    fun cancelledSendTombstonesStayBounded() =
        runTest {
            val appState = appState()
            val controller =
                ConversationController(
                    appState = appState,
                    initialGroup = group(),
                    initialMemberSnapshot = memberSnapshot(),
                    textPublisher = { _, _, _, _ ->
                        throw MarmotKitException.Publish("relay rejected event")
                    },
                )

            repeat(70) { index ->
                controller.send("cancel failed send $index")
                controller.discardFailedSend(controller.timeline.single())
            }

            val phases = appState.optimisticSendPhases(ACCOUNT_REF, GROUP_ID)
            assertEquals(64, phases.size)
            assertTrue(phases.values.all { it == OptimisticSendPhase.CANCELLED })
        }

    @Test
    fun staleFailedActionUsesTheStableOptimisticKeyAfterRecordRefresh() =
        runTest {
            val appState = appState()
            val controller =
                ConversationController(
                    appState = appState,
                    initialGroup = group(),
                    initialMemberSnapshot = memberSnapshot(),
                    textPublisher = { _, _, _, _ ->
                        throw MarmotKitException.Publish("relay rejected event")
                    },
                )
            controller.send("retry refresh")
            val captured = controller.timeline.single()
            val refreshed =
                captured.copy(
                    record = captured.record.copy(messageIdHex = "00000000-0000-4000-8000-000000000001"),
                )
            appState.optimisticMessages(ACCOUNT_REF, GROUP_ID)[captured.id] = refreshed

            assertTrue(
                controller
                    .deleteCapabilityFor(refreshed.record, optimisticKeyOverride = captured.id)
                    .canDeleteForEveryone,
            )
            controller.discardFailedSend(captured)

            assertTrue(controller.timeline.isEmpty())
        }

    @Test
    fun acceptedPendingAdmissionRejectsDeletionAndRetainsTheBubble() =
        runTest {
            val admissionStarted = CompletableDeferred<Unit>()
            val finishAdmission = CompletableDeferred<Unit>()
            val controller =
                ConversationController(
                    appState = appState(),
                    initialGroup = group(),
                    initialMemberSnapshot = memberSnapshot(),
                    textPublisher = { _, _, _, _ ->
                        admissionStarted.complete(Unit)
                        finishAdmission.await()
                        pendingLocalSend()
                    },
                )
            val send = async(start = CoroutineStart.UNDISPATCHED) { controller.send("durably queued") }
            admissionStarted.await()
            val pending = controller.timeline.single().record

            val deletion = async { controller.deleteMessage(pending, presentFailure = false) }
            runCurrent()
            assertFalse(deletion.isCompleted)
            finishAdmission.complete(Unit)
            send.await()

            assertFalse(deletion.await())
            assertEquals(MessageStatus.Pending, controller.timeline.single().status)
            val capability = controller.deleteCapabilityFor(controller.timeline.single().record)
            assertFalse(capability.canDeleteForEveryone)
            assertFalse(capability.canDeleteForMe)
        }

    @Test
    fun acceptedPendingClearsTheCapturedComposerDraft() =
        runTest {
            val appState = appState()
            appState.setDraft(GROUP_ID, TextFieldValue("queued safely"))
            val controller =
                ConversationController(
                    appState = appState,
                    initialGroup = group(),
                    initialMemberSnapshot = memberSnapshot(),
                    textPublisher = { _, _, _, _ ->
                        assertEquals(null, appState.draftFor(GROUP_ID))
                        SendSummaryFfi(
                            published = 0u,
                            messageIds = listOf(CONFIRMED_MESSAGE_ID),
                            acceptDisposition = SendAcceptDispositionFfi.ACCEPTED_PENDING,
                            maintenanceDisposition = SendMaintenanceDispositionFfi.READY,
                        )
                    },
                )

            appState.sendConversationText(controller, "queued safely")

            assertEquals(null, appState.draftFor(GROUP_ID))
            assertEquals(MessageStatus.Pending, controller.timeline.single().status)
        }

    @Test
    fun acceptedPendingProjectionSettlesTheExactOptimisticChatListEntry() =
        runTest {
            val appState = appState()
            val chatsController =
                attachedChatsController(
                    appState = appState,
                    accountRef = ACCOUNT_REF,
                    row = chatListRow(),
                )
            val controller =
                ConversationController(
                    appState = appState,
                    initialGroup = group(),
                    initialMemberSnapshot = memberSnapshot(),
                    textPublisher = { _, _, _, _ ->
                        SendSummaryFfi(
                            published = 0u,
                            messageIds = listOf(CONFIRMED_MESSAGE_ID),
                            acceptDisposition = SendAcceptDispositionFfi.ACCEPTED_PENDING,
                            maintenanceDisposition = SendMaintenanceDispositionFfi.READY,
                        )
                    },
                )

            controller.send("hello")
            chatsController.setChatListVisible(true)
            val optimisticMessageId =
                controller.timeline
                    .single()
                    .record.messageIdHex
            val optimisticPreview =
                chatsController.items
                    .single()
                    .projection
                    ?.lastMessage
            assertEquals(optimisticMessageId, optimisticPreview?.messageIdHex)
            assertEquals(
                ChatListMessageDeliveryStateFfi.PENDING,
                optimisticPreview?.deliveryState,
            )

            chatsController.setChatListVisible(false)
            applyProjection(
                controller,
                projectedMessage(
                    recordedAt = 20uL,
                    retentionSeconds = null,
                    retentionExpiresAt = null,
                ),
            )
            chatsController.setChatListVisible(true)

            val confirmedPreview =
                chatsController.items
                    .single()
                    .projection
                    ?.lastMessage
            assertEquals(CONFIRMED_MESSAGE_ID, confirmedPreview?.messageIdHex)
            assertEquals(
                ChatListMessageDeliveryStateFfi.DELIVERED,
                confirmedPreview?.deliveryState,
            )
        }

    @Test
    fun acceptedPendingSendReturnSettlesAProjectionThatArrivedFirst() =
        runTest {
            val appState = appState()
            val chatsController =
                ChatsController(
                    appState = appState,
                    initialAccountRef = ACCOUNT_REF,
                    memberSnapshotLoader = { _, _ -> emptyList() },
                )
            appState.attachChatsController(chatsController)
            chatsController.setChatListVisible(false)
            chatsController.applyChatListRow(chatListRow())
            lateinit var controller: ConversationController
            controller =
                ConversationController(
                    appState = appState,
                    initialGroup = group(),
                    initialMemberSnapshot = memberSnapshot(),
                    textPublisher = { _, _, _, _ ->
                        controller.testApplyLiveTimelineChangesAndRegisterStreams(
                            listOf(
                                TimelineMessageChangeFfi.Upsert(
                                    trigger = TimelineUpdateTriggerFfi.NEW_MESSAGE,
                                    message =
                                        projectedMessage(
                                            recordedAt = 20uL,
                                            retentionSeconds = null,
                                            retentionExpiresAt = null,
                                        ),
                                ),
                            ),
                        )
                        SendSummaryFfi(
                            published = 0u,
                            messageIds = listOf(CONFIRMED_MESSAGE_ID),
                            acceptDisposition = SendAcceptDispositionFfi.ACCEPTED_PENDING,
                            maintenanceDisposition = SendMaintenanceDispositionFfi.READY,
                        )
                    },
                )

            controller.send("hello")
            chatsController.setChatListVisible(true)

            val confirmedPreview =
                chatsController.items
                    .single()
                    .projection
                    ?.lastMessage
            assertEquals(CONFIRMED_MESSAGE_ID, confirmedPreview?.messageIdHex)
            assertEquals(
                ChatListMessageDeliveryStateFfi.DELIVERED,
                confirmedPreview?.deliveryState,
            )
        }

    @Test
    fun sameSecondIncomingSubscriptionOwnsFirstReturnFrameAndRejectsLateSentEcho() =
        runTest {
            val appState = appState()
            val chatsController =
                attachedChatsController(
                    appState = appState,
                    accountRef = ACCOUNT_REF,
                    row = chatListRow(),
                )
            val conversationController = acceptedPendingConversationController(appState)

            conversationController.send("hello")
            val sentRow = sentChatListRow()
            val incomingMessageId = "0a".repeat(32)
            val incomingRow = incomingChatListRow(sentRow, incomingMessageId)

            chatsController.applyNewLastMessage(incomingRow)
            chatsController.setChatListVisible(true)
            assertIncomingOwnsChatListProjection(chatsController, incomingMessageId)

            chatsController.setChatListVisible(false)
            chatsController.applyNewLastMessage(sentRow)
            chatsController.setChatListVisible(true)
            assertIncomingOwnsChatListProjection(chatsController, incomingMessageId)
        }

    @Test
    fun acceptedPendingSeparatesOptimisticAndDurableAcceptanceCallbacks() =
        runTest {
            val callbacks = mutableListOf<String>()
            val controller =
                ConversationController(
                    appState = appState(),
                    initialGroup = group(),
                    initialMemberSnapshot = memberSnapshot(),
                    textPublisher = { _, _, _, _ ->
                        assertEquals(listOf("optimistic"), callbacks)
                        SendSummaryFfi(
                            published = 0u,
                            messageIds = listOf(CONFIRMED_MESSAGE_ID),
                            acceptDisposition = SendAcceptDispositionFfi.ACCEPTED_PENDING,
                            maintenanceDisposition = SendMaintenanceDispositionFfi.READY,
                        )
                    },
                )

            controller.send(
                text = "hello",
                onAccepted = { callbacks += "optimistic" },
                onDurablyAccepted = { callbacks += "durable" },
            )

            assertEquals(listOf("optimistic", "durable"), callbacks)
            assertEquals(MessageStatus.Pending, controller.timeline.single().status)
        }

    @Test
    fun optimisticRetentionHintSurvivesPendingToSentWithoutStartingTheCountdown() =
        runTest {
            lateinit var controller: ConversationController
            controller =
                ConversationController(
                    appState = appState(),
                    initialGroup = group(disappearingMessageSecs = 30uL),
                    initialMemberSnapshot = memberSnapshot(),
                    textPublisher = { _, _, _, _ ->
                        val pending = controller.timeline.single()
                        assertEquals(MessageStatus.Pending, pending.status)
                        assertEquals(30uL, pending.retentionAtSendSeconds)
                        assertEquals(null, pending.record.retentionSeconds)
                        assertEquals(null, pending.record.retentionExpiresAt)
                        SendSummaryFfi(
                            published = 1u,
                            messageIds = listOf(CONFIRMED_MESSAGE_ID),
                            acceptDisposition = SendAcceptDispositionFfi.PUBLISHED,
                            maintenanceDisposition = SendMaintenanceDispositionFfi.READY,
                        )
                    },
                )

            controller.send("hello")

            val sent = controller.timeline.single()
            assertEquals(MessageStatus.Sent, sent.status)
            assertEquals(30uL, sent.retentionAtSendSeconds)
            assertEquals(null, sent.record.retentionSeconds)
            assertEquals(null, sent.record.retentionExpiresAt)
        }

    @Test
    fun retentionHintSurvivesProjectionAndPageRefreshWhileExpiryIsPending() =
        runTest {
            val recordedAt = (System.currentTimeMillis() / 1_000L).toULong()
            val waitingProjection =
                projectedMessage(
                    recordedAt = recordedAt,
                    retentionSeconds = null,
                    retentionExpiresAt = null,
                )
            lateinit var controller: ConversationController
            controller =
                ConversationController(
                    appState = appState(),
                    initialGroup = group(disappearingMessageSecs = 30uL),
                    initialMemberSnapshot = memberSnapshot(),
                    textPublisher = { _, _, _, _ ->
                        controller.testApplyLiveTimelineChangesAndRegisterStreams(
                            listOf(
                                TimelineMessageChangeFfi.Upsert(
                                    trigger = TimelineUpdateTriggerFfi.NEW_MESSAGE,
                                    message = waitingProjection,
                                ),
                            ),
                        )
                        SendSummaryFfi(
                            published = 1u,
                            messageIds = listOf(CONFIRMED_MESSAGE_ID),
                            acceptDisposition = SendAcceptDispositionFfi.PUBLISHED,
                            maintenanceDisposition = SendMaintenanceDispositionFfi.READY,
                        )
                    },
                )

            controller.send("hello")
            assertEquals(30uL, controller.timeline.single().retentionAtSendSeconds)

            controller.testRefreshCurrentTimeline(ACCOUNT_REF) {
                TimelinePageFfi(
                    messages = listOf(waitingProjection),
                    hasMoreBefore = false,
                    hasMoreAfter = false,
                )
            }
            assertEquals(30uL, controller.timeline.single().retentionAtSendSeconds)
        }

    @Test
    fun ambiguousTimeoutAfterLocalProjectionCompletesDurableAcceptanceWithoutRepublishing() =
        runTest {
            val appState = appState()
            val callbacks = mutableListOf<String>()
            var attempts = 0
            lateinit var controller: ConversationController
            controller =
                ConversationController(
                    appState = appState,
                    initialGroup = group(),
                    initialMemberSnapshot = memberSnapshot(),
                    textPublisher = { _, _, _, _ ->
                        attempts += 1
                        val optimisticRecordedAt =
                            controller.timeline
                                .single()
                                .record.recordedAt
                        applyProjection(
                            controller,
                            projectedMessage(
                                recordedAt = optimisticRecordedAt,
                                retentionSeconds = null,
                                retentionExpiresAt = null,
                                sourceMessageIdHex = null,
                            ),
                        )
                        throw MarmotKitException.Publish("send event timed out")
                    },
                )

            controller.send(
                text = "hello",
                onAccepted = { callbacks += "optimistic" },
                onDurablyAccepted = { callbacks += "durable" },
            )

            assertEquals(1, attempts)
            assertEquals(listOf("optimistic", "durable"), callbacks)
            assertEquals(MessageStatus.Pending, controller.timeline.single().status)
            assertEquals(null, appState.toast)
        }

    @Test
    fun ambiguousSendTimeoutStaysPendingAndSilentWithoutResending() =
        runTest {
            val appState = appState()
            var attempts = 0
            val controller =
                ConversationController(
                    appState = appState,
                    initialGroup = group(),
                    initialMemberSnapshot = memberSnapshot(),
                    textPublisher = { _, _, _, _ ->
                        attempts += 1
                        throw MarmotKitException.Publish("send event timed out")
                    },
                )

            controller.send("hello")

            assertEquals(1, attempts)
            assertEquals(MessageStatus.Pending, controller.timeline.single().status)
            assertEquals(null, appState.toast)
        }

    @Test
    fun sendKeepsRetryingPastTheShortConnectWindowAndStaysPendingUntilAccepted() =
        runTest {
            val appState = appState()
            var attempts = 0
            lateinit var controller: ConversationController
            controller =
                ConversationController(
                    appState = appState,
                    initialGroup = group(),
                    initialMemberSnapshot = memberSnapshot(),
                    textPublisher = { _, _, _, _ ->
                        attempts += 1
                        assertEquals(MessageStatus.Pending, controller.timeline.single().status)
                        if (attempts <= SEND_RETRY_ATTEMPTS) {
                            throw MarmotKitException.Publish("connect relay failed")
                        }
                        SendSummaryFfi(
                            published = 1u,
                            messageIds = listOf(CONFIRMED_MESSAGE_ID),
                            acceptDisposition = SendAcceptDispositionFfi.PUBLISHED,
                            maintenanceDisposition = SendMaintenanceDispositionFfi.READY,
                        )
                    },
                )

            controller.send("hello")

            assertEquals(SEND_RETRY_ATTEMPTS + 1, attempts)
            assertEquals(MessageStatus.Sent, controller.timeline.single().status)
            assertEquals(null, appState.toast)
        }

    @Test
    fun pendingConnectRetryReleasesTheConversationCommitLockDuringBackoff() =
        runTest {
            val appState = appState()
            val firstAttemptStarted = CompletableDeferred<Unit>()
            val allowRetryToSucceed = CompletableDeferred<Unit>()
            var attempts = 0
            lateinit var controller: ConversationController
            controller =
                ConversationController(
                    appState = appState,
                    initialGroup = group(),
                    initialMemberSnapshot = memberSnapshot(),
                    textPublisher = { _, _, _, _ ->
                        attempts += 1
                        if (attempts == 1) {
                            firstAttemptStarted.complete(Unit)
                            throw MarmotKitException.Publish("connect relay failed")
                        }
                        allowRetryToSucceed.await()
                        successfulSendSummary()
                    },
                )

            val send = async { controller.send("hello") }
            firstAttemptStarted.await()
            val otherCommitCompleted = CompletableDeferred<Unit>()
            val otherCommit =
                async {
                    appState.withGroupCommitLock(ACCOUNT_REF, GROUP_ID) {
                        otherCommitCompleted.complete(Unit)
                    }
                }
            yield()

            assertEquals(MessageStatus.Pending, controller.timeline.single().status)
            assertTrue(
                "an offline send must release the group commit lock before retry backoff",
                otherCommitCompleted.isCompleted,
            )

            allowRetryToSucceed.complete(Unit)
            otherCommit.await()
            send.await()
            assertEquals(MessageStatus.Sent, controller.timeline.single().status)
        }

    @Test
    fun pendingConnectRetryKeepsALaterTextSendBehindTheEarlierMessage() =
        runBlocking {
            val attempts = mutableListOf<String>()
            val firstAttemptStarted = CompletableDeferred<Unit>()
            var firstAttempts = 0
            val controller =
                ConversationController(
                    appState = appState(),
                    initialGroup = group(),
                    initialMemberSnapshot = memberSnapshot(),
                    textPublisher = { _, _, _, text ->
                        attempts += text
                        if (text == "first") {
                            firstAttempts += 1
                            if (firstAttempts == 1) {
                                firstAttemptStarted.complete(Unit)
                                throw MarmotKitException.Publish("connect relay failed")
                            }
                        }
                        SendSummaryFfi(
                            published = 1u,
                            messageIds = listOf(if (text == "first") "11".repeat(32) else "22".repeat(32)),
                            acceptDisposition = SendAcceptDispositionFfi.PUBLISHED,
                            maintenanceDisposition = SendMaintenanceDispositionFfi.READY,
                        )
                    },
                )

            val first = async { controller.send("first") }
            firstAttemptStarted.await()
            val second = async { controller.send("second") }
            delay(SEND_RETRY_BACKOFF_MS / 4)

            assertEquals(
                "a later text must not publish while the earlier text is backing off",
                listOf("first"),
                attempts,
            )

            first.await()
            second.await()
            assertEquals(listOf("first", "first", "second"), attempts)
        }

    @Test
    fun sendRetriesConnectFailureThenCommitsOneSentTimelineRow() =
        runTest {
            var attempts = 0
            var accepted = 0
            val controller =
                ConversationController(
                    appState = appState(),
                    initialGroup = group(),
                    initialMemberSnapshot = memberSnapshot(),
                    textPublisher = { replyTarget, account, groupIdHex, text ->
                        attempts += 1
                        assertEquals(null, replyTarget)
                        assertEquals(ACCOUNT_REF, account)
                        assertEquals(GROUP_ID, groupIdHex)
                        assertEquals("hello", text)
                        if (attempts == 1) {
                            throw MarmotKitException.Publish("connect relay failed")
                        }
                        SendSummaryFfi(
                            published = 1u,
                            messageIds = listOf(CONFIRMED_MESSAGE_ID),
                            acceptDisposition = SendAcceptDispositionFfi.PUBLISHED,
                            maintenanceDisposition = SendMaintenanceDispositionFfi.READY,
                        )
                    },
                )

            controller.send(" hello ") { accepted += 1 }

            assertEquals(2, attempts)
            assertEquals(1, accepted)
            assertEquals(listOf(CONFIRMED_MESSAGE_ID), controller.timeline.map { it.record.messageIdHex })
            assertEquals(MessageStatus.Sent, controller.timeline.single().status)
            assertEquals(
                "hello",
                controller.timeline
                    .single()
                    .record.plaintext,
            )
            assertFalse(
                controller.timeline.any {
                    it.status == MessageStatus.Pending || it.status == MessageStatus.Failed
                },
            )
        }

    /** A retry that publishes retires the failure notice its own first attempt raised (#2666). */
    @Test
    fun successfulRetryDismissesItsStaleFailureNotice() =
        runTest {
            val appState = appState()
            var attempts = 0
            val controller =
                ConversationController(
                    appState = appState,
                    initialGroup = group(),
                    initialMemberSnapshot = memberSnapshot(),
                    textPublisher = { _, _, _, _ ->
                        attempts += 1
                        if (attempts == 1) throw MarmotKitException.Publish("signer rejected event")
                        successfulSendSummary()
                    },
                )

            appState.sendConversationText(controller, "retry me")
            assertNotNull("the first failure must be reported", appState.toast)

            controller.retryFailedSend(controller.timeline.single())

            assertNull("a recovered send must not keep claiming it failed", appState.toast)
        }

    /** Reaching the durable accepted-pending state counts as recovery for the same notice. */
    @Test
    fun acceptedPendingRetryDismissesItsStaleFailureNotice() =
        runTest {
            val appState = appState()
            var attempts = 0
            val controller =
                ConversationController(
                    appState = appState,
                    initialGroup = group(),
                    initialMemberSnapshot = memberSnapshot(),
                    textPublisher = { _, _, _, _ ->
                        attempts += 1
                        if (attempts == 1) throw MarmotKitException.Publish("signer rejected event")
                        successfulSendSummary().copy(
                            published = 0u,
                            acceptDisposition = SendAcceptDispositionFfi.ACCEPTED_PENDING,
                        )
                    },
                )

            appState.sendConversationText(controller, "accept me")
            assertNotNull(appState.toast)

            controller.retryFailedSend(controller.timeline.single())

            assertNull(appState.toast)
        }

    /** A retry that fails again keeps current failure feedback on screen. */
    @Test
    fun failedRetryKeepsTheCurrentFailureNotice() =
        runTest {
            val appState = appState()
            val controller =
                ConversationController(
                    appState = appState,
                    initialGroup = group(),
                    initialMemberSnapshot = memberSnapshot(),
                    textPublisher = { _, _, _, _ -> throw MarmotKitException.Publish("signer rejected event") },
                )

            appState.sendConversationText(controller, "keep failing")
            controller.retryFailedSend(controller.timeline.single())

            assertNotNull("a still-failing send must keep its error", appState.toast)
            assertEquals(MessageStatus.Failed, controller.timeline.single().status)
        }

    /** Recovery retires only its own notice, never a newer unrelated one. */
    @Test
    fun recoveryLeavesANewerUnrelatedNoticeAlone() =
        runTest {
            val appState = appState()
            var attempts = 0
            val controller =
                ConversationController(
                    appState = appState,
                    initialGroup = group(),
                    initialMemberSnapshot = memberSnapshot(),
                    textPublisher = { _, _, _, _ ->
                        attempts += 1
                        if (attempts == 1) throw MarmotKitException.Publish("signer rejected event")
                        successfulSendSummary()
                    },
                )

            appState.sendConversationText(controller, "retry me")
            appState.present(R.string.toast_group_updated)
            val unrelated = appState.toast

            controller.retryFailedSend(controller.timeline.single())

            assertEquals("an unrelated notice must survive another send's recovery", unrelated, appState.toast)
        }

    /** A recovery in one conversation cannot dismiss another conversation's failure. */
    @Test
    fun recoveryDoesNotDismissAnotherConversationsFailure() =
        runTest {
            val appState = appState()
            val failing =
                ConversationController(
                    appState = appState,
                    initialGroup = group(),
                    initialMemberSnapshot = memberSnapshot(),
                    textPublisher = { _, _, _, _ -> throw MarmotKitException.Publish("signer rejected event") },
                )
            val recovering =
                ConversationController(
                    appState = appState,
                    initialGroup = group().copy(groupIdHex = OTHER_GROUP_ID),
                    initialMemberSnapshot = memberSnapshot(),
                    textPublisher = { _, _, _, _ -> successfulSendSummary() },
                )

            appState.sendConversationText(failing, "this one failed")
            val failureNotice = appState.toast
            assertNotNull(failureNotice)

            appState.sendConversationText(recovering, "this one worked")

            assertEquals("another chat's failure is still true", failureNotice, appState.toast)
        }

    private fun successfulSendSummary() =
        SendSummaryFfi(
            published = 1u,
            messageIds = listOf(CONFIRMED_MESSAGE_ID),
            acceptDisposition = SendAcceptDispositionFfi.PUBLISHED,
            maintenanceDisposition = SendMaintenanceDispositionFfi.READY,
        )

    private fun acceptedPendingConversationController(appState: WhiteNoiseAppState): ConversationController {
        lateinit var controller: ConversationController
        controller =
            ConversationController(
                appState = appState,
                initialGroup = group(),
                initialMemberSnapshot = memberSnapshot(),
                textPublisher = { _, _, _, _ ->
                    controller.testApplyLiveTimelineChangesAndRegisterStreams(
                        listOf(
                            TimelineMessageChangeFfi.Upsert(
                                trigger = TimelineUpdateTriggerFfi.NEW_MESSAGE,
                                message =
                                    projectedMessage(
                                        recordedAt = 20uL,
                                        retentionSeconds = null,
                                        retentionExpiresAt = null,
                                    ),
                            ),
                        ),
                    )
                    SendSummaryFfi(
                        published = 0u,
                        messageIds = listOf(CONFIRMED_MESSAGE_ID),
                        acceptDisposition = SendAcceptDispositionFfi.ACCEPTED_PENDING,
                        maintenanceDisposition = SendMaintenanceDispositionFfi.READY,
                    )
                },
            )
        return controller
    }

    private fun sentChatListRow(): ChatListRowFfi {
        val row = chatListRow()
        return row.copy(
            lastMessage =
                requireNotNull(row.lastMessage).copy(
                    messageIdHex = CONFIRMED_MESSAGE_ID,
                    plaintext = "hello",
                    timelineAt = 20uL,
                    groupSystem = null,
                    deliveryState = ChatListMessageDeliveryStateFfi.DELIVERED,
                ),
            activitySortAt = 20uL,
            updatedAt = 20uL,
        )
    }

    private fun incomingChatListRow(
        sentRow: ChatListRowFfi,
        messageId: String,
    ): ChatListRowFfi =
        sentRow.copy(
            lastMessage =
                requireNotNull(sentRow.lastMessage).copy(
                    messageIdHex = messageId,
                    sender = "e5".repeat(32),
                    plaintext = "same-second incoming",
                    groupSystem = null,
                    deliveryState = ChatListMessageDeliveryStateFfi.NOT_APPLICABLE,
                ),
            unreadCount = 1uL,
            hasUnread = true,
            firstUnreadMessageIdHex = messageId,
        )

    private fun ChatsController.applyNewLastMessage(row: ChatListRowFfi) {
        applyChatListSubscriptionUpdate(
            accountRef = ACCOUNT_REF,
            update =
                ChatListSubscriptionUpdateFfi.Row(
                    trigger = ChatListUpdateTriggerFfi.NEW_LAST_MESSAGE,
                    row = row,
                ),
        )
    }

    private fun assertIncomingOwnsChatListProjection(
        controller: ChatsController,
        messageId: String,
    ) {
        val projection = controller.items.single().projection
        assertEquals(messageId, projection?.lastMessage?.messageIdHex)
        assertEquals("same-second incoming", projection?.lastMessage?.plaintext)
        assertEquals(1uL, projection?.unreadCount)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun TestScope.settleNativePresentation() {
        // Optimistic ID handoff preserves one drawn frame before publishing subsequent mutations.
        repeat(4) {
            runCurrent()
            ShadowLooper.idleMainLooper(20, TimeUnit.MILLISECONDS)
        }
        runCurrent()
    }

    private fun applyNativeEditProjection(
        controller: ConversationController,
        token: String,
        text: String = "hello",
        editId: String? = null,
    ) = applyProjection(
        controller,
        projectedMessage(5uL, null, null).copy(
            clientToken = token,
            plaintext = text,
            edit = editId?.let { TimelineEditSummaryFfi(1uL, it, 6uL) },
        ),
    )

    private fun publishedNativeEditStatus(editId: String) =
        LocalSendStatusFfi.Completed(
            SendSummaryFfi(1u, listOf(editId), SendAcceptDispositionFfi.PUBLISHED, SendMaintenanceDispositionFfi.READY),
        )

    private fun nativeEditController(
        originalReturn: CompletableDeferred<Unit>,
        publisher: PendingMessageEditPublisher,
    ) = nativeEditControllerWithStatus(
        originalReturn = originalReturn,
        publisher = publisher,
        statusReader = { _, _, _ -> LocalSendStatusFfi.Queued },
    )

    private fun nativeEditControllerWithStatus(
        originalReturn: CompletableDeferred<Unit>,
        publisher: PendingMessageEditPublisher,
        statusReader: suspend (String, String, String) -> LocalSendStatusFfi?,
        state: WhiteNoiseAppState = appState(),
        originalSummary: SendSummaryFfi = pendingLocalSend(listOf(CONFIRMED_MESSAGE_ID)),
    ) = ConversationController(
        appState = state,
        initialGroup = group(),
        initialMemberSnapshot = memberSnapshot(),
        groupRosterReader = { _, _ -> authoritativeRoster() },
        textPublisher = { _, _, _, _ ->
            originalReturn.await()
            originalSummary
        },
        pendingMessageEditPublisher = publisher,
        pendingEditStatusReader = statusReader,
        messageEditPublisher = { _, _, _, _ -> error("pending revision must not use a wire edit") },
    )

    private fun appState(additionalAccounts: List<AccountSummaryFfi> = emptyList()) =
        WhiteNoiseAppState(
            context = ApplicationProvider.getApplicationContext(),
            draftStore = DraftStore(TestDraftPersistence()),
            accountIdHexResolver = { ACCOUNT_ID },
            accounts =
                listOf(
                    AccountSummaryFfi(
                        label = ACCOUNT_REF,
                        accountIdHex = ACCOUNT_ID,
                        localSigning = true,
                        externalSigning = false,
                        signedOut = false,
                        running = true,
                    ),
                ) + additionalAccounts,
            activeAccountRef = ACCOUNT_REF,
        )

    private fun memberSnapshot() =
        GroupMemberSnapshot(
            listOf(
                AppGroupMemberRecordFfi(
                    memberIdHex = ACCOUNT_ID,
                    account = ACCOUNT_REF,
                    local = true,
                ),
            ),
        )

    private fun authoritativeRoster() =
        GroupRosterFfi(
            groupIdHex = GROUP_ID,
            members =
                listOf(
                    GroupMemberDetailsFfi(
                        memberIdHex = ACCOUNT_ID,
                        account = ACCOUNT_REF,
                        local = true,
                        isAdmin = true,
                        isSelf = true,
                        npub = "npub-$ACCOUNT_ID",
                        displayName = null,
                    ),
                ),
            epoch = 1uL,
            rosterRevision = 1uL,
            selfMembership = SelfMembershipFfi.MEMBER,
            memberCount = 1u,
            lifecycleState = GroupLifecycleStateFfi.STABLE,
        )

    private fun group(
        disappearingMessageSecs: ULong = 0uL,
        pendingConfirmation: Boolean = false,
    ) = AppGroupRecordFfi(
        groupIdHex = GROUP_ID,
        protocolProfile = AppProtocolProfileFfi.LEGACY,
        endpoint = "wss://relay.example",
        profilePresent = true,
        name = "Retry group",
        description = "",
        admins = listOf(ACCOUNT_ID),
        relays = listOf("wss://relay.example"),
        nostrGroupIdHex = "04".repeat(32),
        avatarUrl = null,
        avatarDim = null,
        avatarThumbhash = null,
        imageHashHex = null,
        encryptedMedia =
            AppGroupEncryptedMediaComponentFfi(
                componentId = 0x8008u,
                component = "marmot.group.encrypted-media.v1",
                required = true,
                version = EncryptedMediaVersionFfi.V1,
                mediaFormat = "encrypted-media-v1",
                allowedLocatorKinds = listOf("blossom-v1"),
                defaultBlobEndpoints =
                    listOf(
                        AppBlobEndpointFfi(
                            locatorKind = "blossom-v1",
                            baseUrl = "https://blossom.example",
                        ),
                    ),
            ),
        disappearingMessageSecs = disappearingMessageSecs,
        archived = false,
        pendingConfirmation = pendingConfirmation,
        unrecoverable = false,
        selfMembership = SelfMembershipFfi.MEMBER,
        leaveRequestPending = false,
        leaveRequestedAtMs = null,
        disbanding = false,
        disbandRequest = null,
        disbanded = false,
        welcomerAccountIdHex = null,
        viaWelcomeMessageIdHex = null,
    )

    private fun chatListRow() =
        ChatListRowFfi(
            selfMembership = SelfMembershipFfi.MEMBER,
            unreadMentionCount = 0uL,
            unreadMention = false,
            groupIdHex = GROUP_ID,
            archived = false,
            pendingConfirmation = false,
            title = "Retry group",
            groupName = "Retry group",
            avatarUrl = null,
            avatar = null,
            lastMessage =
                ChatListMessagePreviewFfi(
                    retentionSeconds = null,
                    retentionExpiresAt = null,
                    messageIdHex = "d4".repeat(32),
                    sender = ACCOUNT_ID,
                    senderDisplayName = null,
                    plaintext = "before send",
                    contentTokens =
                        MarkdownDocumentFfi(
                            truncated = false,
                            blocks = emptyList(),
                            blankLinesBefore = ByteArray(0),
                        ),
                    kind = 9uL,
                    timelineAt = 10uL,
                    deleted = false,
                    deletionSource = DeletionSourceFfi.UNKNOWN,
                    attachmentKind = null,
                    attachmentCount = 0u,
                    groupSystem = null,
                    deliveryState = ChatListMessageDeliveryStateFfi.NOT_APPLICABLE,
                ),
            unreadCount = 0uL,
            hasUnread = false,
            firstUnreadMessageIdHex = null,
            lastReadMessageIdHex = null,
            lastReadTimelineAt = null,
            conversationCreatedAt = 0uL,
            activitySortAt = 10uL,
            updatedAt = 10uL,
            leaveRequestPending = false,
            leaveRequestedAtMs = null,
            manuallyMarkedUnread = false,
            conversationKind = ChatConversationKindFfi.UNKNOWN,
            muted = false,
            mutedUntilMs = null,
            pinned = false,
            pinnedPosition = null,
            lifecycleState = GroupLifecycleStateFfi.STABLE,
            disbanding = false,
            disbandRequest = null,
        )

    private fun projectedMessage(
        recordedAt: ULong,
        retentionSeconds: ULong?,
        retentionExpiresAt: ULong?,
        sourceMessageIdHex: String? = "d4".repeat(32),
    ) = TimelineMessageRecordFfi(
        clientToken = null,
        messageIdHex = CONFIRMED_MESSAGE_ID,
        sourceMessageIdHex = sourceMessageIdHex,
        direction = "sent",
        groupIdHex = GROUP_ID,
        sender = ACCOUNT_ID,
        plaintext = "hello",
        contentTokens =
            MarkdownDocumentFfi(
                truncated = false,
                blocks = emptyList(),
                blankLinesBefore = ByteArray(0),
            ),
        kind = 9uL,
        tags = emptyList(),
        timelineAt = recordedAt,
        receivedAt = recordedAt,
        replyToMessageIdHex = null,
        replyPreview = null,
        mediaJson = null,
        media = emptyList(),
        agentTextStreamJson = null,
        poll = null,
        groupSystem = null,
        hasReports = false,
        edit = null,
        reactions = TimelineReactionSummaryFfi(byEmoji = emptyList(), userReactions = emptyList()),
        deleted = false,
        deletionSource = DeletionSourceFfi.UNKNOWN,
        deletedByMessageIdHex = null,
        invalidationStatus = null,
        sourceEpoch = null,
        retentionSeconds = retentionSeconds,
        retentionExpiresAt = retentionExpiresAt,
    )

    /** Creates the density-independent manual-height record used by send lifecycle assertions. */
    private fun manualExpansion(heightDp: Float) =
        RetainedComposerExpansion(
            mode = RetainedComposerExpansionMode.Manual,
            manualHeightDp = heightDp,
        )

    private fun WhiteNoiseAppState.draftFor(groupIdHex: String): String? = draftFor(ACCOUNT_REF, groupIdHex)

    private fun WhiteNoiseAppState.setDraft(
        groupIdHex: String,
        value: TextFieldValue,
    ) = setDraft(ACCOUNT_REF, groupIdHex, value)

    private class TestDraftPersistence : DraftPersistence {
        override fun read(): Map<String, String> = emptyMap()

        override fun write(
            key: String,
            value: String?,
        ) = Unit
    }

    private companion object {
        const val ACCOUNT_REF = "alice"
        val ACCOUNT_ID = "a1".repeat(32)
        val GROUP_ID = "b2".repeat(32)
        val OTHER_GROUP_ID = "b4".repeat(32)
        const val REPLY_MESSAGE_ID = "reply-message"
        val CONFIRMED_MESSAGE_ID = "c3".repeat(32)
    }
}

private fun attachedChatsController(
    appState: WhiteNoiseAppState,
    accountRef: String,
    row: ChatListRowFfi,
): ChatsController =
    ChatsController(
        appState = appState,
        initialAccountRef = accountRef,
        memberSnapshotLoader = { _, _ -> emptyList() },
    ).also { chatsController ->
        appState.attachChatsController(chatsController)
        chatsController.setChatListVisible(false)
        chatsController.applyChatListRow(row)
    }

private fun applyProjection(
    controller: ConversationController,
    message: TimelineMessageRecordFfi,
) {
    controller.testApplyLiveTimelineChangesAndRegisterStreams(
        listOf(
            TimelineMessageChangeFfi.Upsert(
                trigger = TimelineUpdateTriggerFfi.NEW_MESSAGE,
                message = message,
            ),
        ),
    )
}
