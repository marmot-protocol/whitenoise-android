package dev.ipf.whitenoise.android.state

import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import dev.ipf.marmotkit.AccountSummaryFfi
import dev.ipf.marmotkit.AppBlobEndpointFfi
import dev.ipf.marmotkit.AppGroupEncryptedMediaComponentFfi
import dev.ipf.marmotkit.AppGroupMemberRecordFfi
import dev.ipf.marmotkit.AppGroupRecordFfi
import dev.ipf.marmotkit.AppProtocolProfileFfi
import dev.ipf.marmotkit.AttachmentLocalAssetFfi
import dev.ipf.marmotkit.AttachmentTransferSnapshotFfi
import dev.ipf.marmotkit.AttachmentTransferStateFfi
import dev.ipf.marmotkit.AttachmentTransferStatusFfi
import dev.ipf.marmotkit.ChatConversationKindFfi
import dev.ipf.marmotkit.ChatListMessageDeliveryStateFfi
import dev.ipf.marmotkit.ChatListMessagePreviewFfi
import dev.ipf.marmotkit.ChatListRowFfi
import dev.ipf.marmotkit.DeletionSourceFfi
import dev.ipf.marmotkit.EncryptedMediaVersionFfi
import dev.ipf.marmotkit.GroupLifecycleStateFfi
import dev.ipf.marmotkit.GroupMemberDetailsFfi
import dev.ipf.marmotkit.GroupRosterFfi
import dev.ipf.marmotkit.LocalSendAcceptanceFfi
import dev.ipf.marmotkit.MarkdownDocumentFfi
import dev.ipf.marmotkit.MarmotInterface
import dev.ipf.marmotkit.MediaAttachmentReferenceFfi
import dev.ipf.marmotkit.MediaLocatorFfi
import dev.ipf.marmotkit.MediaUploadAttachmentResultFfi
import dev.ipf.marmotkit.MediaUploadRequestFfi
import dev.ipf.marmotkit.MediaUploadResultFfi
import dev.ipf.marmotkit.MediaUploadSubmissionFfi
import dev.ipf.marmotkit.MessageDraftRevisionFfi
import dev.ipf.marmotkit.MessageTagFfi
import dev.ipf.marmotkit.ProductRecordResultFfi
import dev.ipf.marmotkit.SelectedMessageDraftFfi
import dev.ipf.marmotkit.SelfMembershipFfi
import dev.ipf.marmotkit.SendAcceptDispositionFfi
import dev.ipf.marmotkit.SendMaintenanceDispositionFfi
import dev.ipf.marmotkit.SendSummaryFfi
import dev.ipf.marmotkit.TimelineMessageChangeFfi
import dev.ipf.marmotkit.TimelineMessageRecordFfi
import dev.ipf.marmotkit.TimelinePageFfi
import dev.ipf.marmotkit.TimelineReactionSummaryFfi
import dev.ipf.marmotkit.TimelineUpdateTriggerFfi
import dev.ipf.whitenoise.android.core.MessageAttachments
import dev.ipf.whitenoise.android.core.MessageProjector
import dev.ipf.whitenoise.android.core.TimelineProjector
import dev.ipf.whitenoise.android.ui.conversation.ConversationMediaSender
import dev.ipf.whitenoise.android.ui.conversation.ConversationScrollCoordinator
import dev.ipf.whitenoise.android.ui.conversation.ConversationScrollMode
import dev.ipf.whitenoise.android.ui.conversation.ConversationScrollWriter
import dev.ipf.whitenoise.android.ui.conversation.composer.VoiceRecordingReview
import dev.ipf.whitenoise.android.ui.conversation.revealSentAtLiveTail
import java.lang.reflect.Proxy
import java.time.Duration
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en")
class ConversationMediaSendReconciliationIntegrationTest {
    /** A timed-out test still drains asynchronous mutation cleanup before releasing the Main dispatcher. */
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun cancelledFixtureOwnerWaitsForMutationCleanup() =
        runTest {
            Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
            val state = mediaSendReconciliationAppState()
            val controller = ConversationController(state, group(), initialMemberSnapshot = memberSnapshot())
            var mutationFinished = false
            var ownerObservedCompletion = false
            state.mutationsScope.launch {
                try {
                    awaitCancellation()
                } finally {
                    withContext(NonCancellable) {
                        yield()
                        mutationFinished = true
                    }
                }
            }
            val owner =
                launch(start = CoroutineStart.UNDISPATCHED) {
                    try {
                        awaitCancellation()
                    } finally {
                        finishMediaFixture(controller, state)
                        ownerObservedCompletion = mutationFinished
                    }
                }
            try {
                owner.cancelAndJoin()
                assertTrue(ownerObservedCompletion)
                assertTrue(state.mutationsScope.coroutineContext.job.isCompleted)
            } finally {
                try {
                    finishMediaFixture(controller, state)
                } finally {
                    Dispatchers.resetMain()
                }
            }
        }

    /** Group-details viewers can retry terminal native work without a platform-open destination. */
    @Test
    fun mediaLibraryRetryWithNoOpenDestinationAdmitsNativeWork() = assertLibraryRetry(accepted = true)

    /** Refused native admission reaches the library failure callback exactly once, without a platform open. */
    @Test
    fun mediaLibraryRetryFailureWithNoOpenDestinationReportsOnce() = assertLibraryRetry(accepted = false)

    /** Exercises the shipping controller through the generated native boundary with no conversation route. */
    @OptIn(ExperimentalCoroutinesApi::class)
    @Suppress("LongMethod") // One ordered native admission retains both null-destination outcomes and cleanup.
    private fun assertLibraryRetry(accepted: Boolean) =
        runTest {
            Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
            val state = mediaSendReconciliationAppState()
            var nativeState = AttachmentTransferStateFfi.FAILED
            var admissions = 0
            val engine =
                Proxy.newProxyInstance(
                    MarmotInterface::class.java.classLoader,
                    arrayOf(MarmotInterface::class.java),
                ) { proxy, method, args ->
                    when (val name = method.name.substringBefore('-')) {
                        "toString" -> "library-retry-boundary"
                        "hashCode" -> System.identityHashCode(proxy)
                        "equals" -> proxy === args?.firstOrNull()
                        "recordHostTiming" -> ProductRecordResultFfi.IGNORED_DISABLED
                        "attachmentLocalAssets" -> listOf(AttachmentLocalAssetFfi(null, 0u))
                        "attachmentTransferSnapshot" ->
                            AttachmentTransferSnapshotFfi(
                                listOf(AttachmentTransferStatusFfi("native-job", nativeState, 1u, 0u, null, null)),
                            )
                        "downloadAttachmentAgain" -> {
                            admissions++
                            if (accepted) {
                                nativeState = AttachmentTransferStateFfi.QUEUED
                                "new-job"
                            } else {
                                null
                            }
                        }
                        else -> error("Unexpected library retry call: $name")
                    }
                } as MarmotInterface
            WhiteNoiseAppState::class.java
                .getDeclaredField("marmotRuntime")
                .apply { isAccessible = true }
                .set(state, AppMarmotRuntime("test", engine))
            val controller =
                ConversationController(
                    appState = state,
                    initialGroup = group(),
                    initialMemberSnapshot = memberSnapshot(),
                    markdownParser = { emptyMarkdownDocument() },
                )
            try {
                applyProjection(controller, projectedMediaMessage(1u, mediaReference()))
                assertNull(controller.attachmentOpenRequest(CONFIRMED_MESSAGE_ID, 0))
                var acceptedCallbacks = 0
                var failedCallbacks = 0
                val result = CompletableDeferred<Unit>()
                assertTrue(
                    controller.retryAttachmentTransfer(
                        CONFIRMED_MESSAGE_ID,
                        0,
                        onAccepted = {
                            acceptedCallbacks++
                            result.complete(Unit)
                        },
                        onFailure = {
                            failedCallbacks++
                            result.complete(Unit)
                        },
                    ),
                )
                result.await()
                assertEquals(1, admissions)
                assertEquals(if (accepted) 1 else 0, acceptedCallbacks)
                assertEquals(if (accepted) 0 else 1, failedCallbacks)
                if (accepted) {
                    assertNull(state.toast)
                } else {
                    assertEquals(
                        AppText.Resource(dev.ipf.whitenoise.android.R.string.media_couldnt_load),
                        state.toast?.title,
                    )
                }
                assertFalse(controller.hasAttachmentOpenIntent(CONFIRMED_MESSAGE_ID, 0))
                controller.onCleared()
                assertFalse(controller.retryAttachmentTransfer(CONFIRMED_MESSAGE_ID, 0, {}, {}))
            } finally {
                try {
                    finishMediaFixture(controller, state)
                } finally {
                    Dispatchers.resetMain()
                }
            }
        }

    /** Batched and overlapping separate file sends retain their visible order through each canonical echo. */
    @Test
    fun pendingFileSiblingsKeepConfirmedSendPositions() = assertPendingFileOrder(failSecond = false)

    /** A failed sibling releases the transient bridge instead of pinning completed rows forever. */
    @Test
    fun failedFileSiblingReleasesConfirmedSendPosition() = assertPendingFileOrder(failSecond = true)

    /** Drives real queue, publish and projection handoffs, including both scheduling patterns. */
    @OptIn(ExperimentalCoroutinesApi::class)
    @Suppress("LongMethod") // The intermediate and settled frames are essential to the ordering regression.
    private fun assertPendingFileOrder(failSecond: Boolean) =
        runTest {
            Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
            try {
                for (queueTogether in listOf(true, false)) {
                    val state = mediaSendReconciliationAppState()
                    val reference = mediaReference().copy(mediaType = "application/zip", fileName = "file.zip")
                    var publishes = 0
                    var second: ConversationController.QueuedAttachmentSend? = null
                    val secondId = "e5".repeat(32)
                    val controller =
                        ConversationController(
                            appState = state,
                            initialGroup = group(),
                            initialMemberSnapshot = memberSnapshot(),
                            groupRosterReader = { _, _ -> authoritativeRoster() },
                            clockMillis = { 100_000L },
                            mediaUploader = { _, _, _ ->
                                if (failSecond && publishes == 1) error("second file upload failed")
                                uploadResult(reference).copy(sent = null)
                            },
                            mediaImetaTagsBuilder = { _, _, _ -> listOf(mediaImetaTag()) },
                            mediaPublisher = { _, _, _, _ ->
                                val id = if (publishes++ == 0) CONFIRMED_MESSAGE_ID else secondId
                                acceptedPendingSummary().copy(messageIds = listOf(id))
                            },
                        )

                    suspend fun queueFile(): ConversationController.QueuedAttachmentSend {
                        val attachment =
                            PendingAttachment(byteArrayOf(1, 2, 3, 4), reference.mediaType, reference.fileName)
                        return checkNotNull(controller.queueAttachments(listOf(attachment), null))
                    }
                    try {
                        controller.retryMembers()
                        val first = queueFile()
                        if (queueTogether) second = queueFile()
                        controller.uploadQueued(first)
                        // Separate sends can queue after upload returns but before its relay echo arrives.
                        if (!queueTogether) second = queueFile()
                        val pendingId =
                            controller.timeline
                                .last()
                                .record.messageIdHex
                        applyProjection(controller, projectedMediaMessage(200u, reference))
                        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
                        assertEquals(
                            listOf(CONFIRMED_MESSAGE_ID, pendingId),
                            controller.timeline.map { it.record.messageIdHex },
                        )
                        assertTrue(controller.timeline.first().timelineOrder < controller.timeline.last().timelineOrder)
                        controller.uploadQueued(checkNotNull(second))
                        if (!failSecond) {
                            val projection = projectedMediaMessage(201u, reference).copy(messageIdHex = secondId)
                            applyProjection(controller, projection)
                        }
                        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
                        val overrides =
                            ConversationController::class.java
                                .getDeclaredField("localTimelineTimestampOverrides")
                                .apply { isAccessible = true }
                                .get(controller) as Map<*, *>
                        assertFalse(
                            "completed sends must return to MDK positions after sibling settlement",
                            overrides.containsKey(CONFIRMED_MESSAGE_ID),
                        )
                    } finally {
                        finishMediaFixture(controller, state)
                    }
                }
            } finally {
                Dispatchers.resetMain()
            }
        }

    /** Both a file and a multi-image album reveal after durable acceptance, including a canonical echo. */
    @Test
    fun acceptedFileAndAlbumFromHistorySnapAfterCanonicalReplacement() =
        runTest {
            for (count in listOf(1, 2)) {
                val reference =
                    if (count == 1) {
                        mediaReference().copy(mediaType = "application/pdf", fileName = "file.pdf")
                    } else {
                        mediaReference()
                    }
                val controller =
                    ConversationController(
                        appState = mediaSendReconciliationAppState(),
                        initialGroup = group(),
                        initialMemberSnapshot = memberSnapshot(),
                        groupRosterReader = { _, _ -> authoritativeRoster() },
                        mediaUploader = { _, _, _ ->
                            uploadResult(reference).copy(
                                attachments = List(count) { MediaUploadAttachmentResultFfi(reference, 4uL) },
                                sent = null,
                            )
                        },
                        mediaImetaTagsBuilder = { _, _, _ -> listOf(mediaImetaTag()) },
                        mediaPublisher = { _, _, _, _ -> acceptedPendingSummary() },
                    )
                controller.retryMembers()
                try {
                    assertMediaHistoryReveal(controller, count, reference)
                } finally {
                    controller.onCleared()
                }
            }
        }

    /** Uses the production queue/upload callback and replaces its row during the first layout frame. */
    private suspend fun kotlinx.coroutines.CoroutineScope.assertMediaHistoryReveal(
        controller: ConversationController,
        count: Int,
        reference: MediaAttachmentReferenceFfi,
    ) {
        val snaps = mutableListOf<Int>()
        val writer = recordingMediaWriter(snaps)
        val coordinator = ConversationScrollCoordinator(writer, ConversationScrollMode.ReadingHistory("old", 0))
        val queued =
            checkNotNull(
                controller.queueAttachments(
                    List(count) { PendingAttachment(byteArrayOf(1, 2, 3, 4), reference.mediaType, reference.fileName) },
                    "hello",
                ),
            )
        var reveal: kotlinx.coroutines.Job? = null
        controller.uploadQueued(queued, onDurablyAccepted = {
            reveal =
                launch {
                    assertTrue(
                        coordinator.revealSentAtLiveTail(controller, awaitFrame = {
                            val pending = controller.timeline.single().record
                            applyProjection(
                                controller,
                                projectedMediaMessage(pending.recordedAt, reference).copy(
                                    media = MessageAttachments.acceptedOutcomes(List(count) { reference }),
                                ),
                            )
                        }),
                    )
                }
        })
        checkNotNull(reveal).join()
        assertEquals(listOf(0), snaps)
        assertTrue(coordinator.isFollowingTail)
        assertEquals(
            CONFIRMED_MESSAGE_ID,
            controller.timeline
                .single()
                .record.messageIdHex,
        )
    }

    /** Rejects animation so an intermediate glide cannot masquerade as a correct final position. */
    private fun recordingMediaWriter(snaps: MutableList<Int>): ConversationScrollWriter =
        object : ConversationScrollWriter {
            override val firstVisibleItemIndex: Int = 0

            override suspend fun scrollToItem(
                index: Int,
                scrollOffset: Int,
            ) {
                snaps += index
            }

            override suspend fun animateScrollToItem(
                index: Int,
                scrollOffset: Int,
            ) = error("send must snap")
        }

    @Test
    fun cancellationBeforeNativeMediaAdmissionSkipsMarmotCall() =
        assertDraftlessMediaUsesTokenBoundNativeAdmission(
            mediaType = "image/jpeg",
            fileName = "photo.jpg",
            cancelBeforeUpload = true,
        )

    @Test
    fun cancellationDuringUploadPreventsMediaPublication() =
        runTest {
            val uploadStarted = CompletableDeferred<Unit>()
            val finishUpload = CompletableDeferred<Unit>()
            var publishCalls = 0
            val reference = mediaReference()
            val controller =
                ConversationController(
                    appState = mediaSendReconciliationAppState(),
                    initialGroup = group(),
                    initialMemberSnapshot = memberSnapshot(),
                    groupRosterReader = { _, _ -> authoritativeRoster() },
                    mediaUploader = { _, _, _ ->
                        uploadStarted.complete(Unit)
                        finishUpload.await()
                        uploadResult(reference)
                    },
                    mediaImetaTagsBuilder = { _, _, _ -> listOf(mediaImetaTag()) },
                    mediaPublisher = { _, _, _, _ ->
                        publishCalls += 1
                        acceptedPendingSummary()
                    },
                )
            controller.retryMembers()

            val send =
                async(start = CoroutineStart.UNDISPATCHED) {
                    controller.sendAttachments(
                        attachments =
                            listOf(
                                PendingAttachment(
                                    plaintextBytes = byteArrayOf(1, 2, 3, 4),
                                    mediaType = "image/jpeg",
                                    fileName = "photo.jpg",
                                ),
                            ),
                        caption = "cancel upload",
                    )
                }
            uploadStarted.await()
            val pending = controller.timeline.single().record

            assertEquals(true, controller.deleteMessage(pending, presentFailure = false))
            assertEquals(emptyList<TimelineMessage>(), controller.timeline)

            finishUpload.complete(Unit)
            send.await()

            assertEquals(0, publishCalls)
            assertEquals(emptyList<TimelineMessage>(), controller.timeline)
            assertEquals(emptyList<PendingAttachment>(), controller.pendingAttachmentsList(pending.messageIdHex))
        }

    /** Exercises the production voice-note path without an injected publisher or uploader. */
    @Test
    fun draftlessVoiceNoteUsesTokenBoundNativeAdmission() =
        assertDraftlessMediaUsesTokenBoundNativeAdmission(
            mediaType = "audio/ogg",
            fileName = "voice-note.ogg",
        )

    /** Exercises the production contact-share path without an injected publisher or uploader. */
    @Test
    fun draftlessContactShareUsesTokenBoundNativeAdmission() =
        assertDraftlessMediaUsesTokenBoundNativeAdmission(
            mediaType = "text/vcard",
            fileName = "contact.vcf",
        )

    @Test
    fun acceptedPendingReturnSettlesAProjectionThatArrivedFirstAndReleasesUploadState() =
        runTest {
            val appState = mediaSendReconciliationAppState()
            val chatsController = attachedChatsController(appState)
            val reference = mediaReference()
            lateinit var controller: ConversationController
            lateinit var optimisticKey: String
            controller =
                ConversationController(
                    appState = appState,
                    initialGroup = group(),
                    initialMemberSnapshot = memberSnapshot(),
                    groupRosterReader = { _, _ -> authoritativeRoster() },
                    mediaUploader = { _, _, _ -> uploadResult(reference) },
                    mediaImetaTagsBuilder = { _, _, _ -> listOf(mediaImetaTag()) },
                    mediaPublisher = { _, _, _, _ ->
                        val optimistic = controller.timeline.single()
                        optimisticKey = optimistic.id
                        applyProjection(
                            controller,
                            projectedMediaMessage(
                                recordedAt = optimistic.record.recordedAt,
                                reference = reference,
                            ),
                        )
                        acceptedPendingSummary()
                    },
                )

            controller.retryMembers()
            controller.sendAttachments(
                attachments =
                    listOf(
                        PendingAttachment(
                            plaintextBytes = byteArrayOf(1, 2, 3, 4),
                            mediaType = "image/jpeg",
                            fileName = "photo.jpg",
                        ),
                    ),
                caption = "hello",
            )
            chatsController.setChatListVisible(true)

            val confirmedPreview =
                chatsController.items
                    .single()
                    .projection
                    ?.lastMessage
            assertEquals(
                CONFIRMED_MESSAGE_ID,
                controller.timeline
                    .single()
                    .record.messageIdHex,
            )
            assertEquals(
                emptyList<PendingAttachment>(),
                controller.pendingAttachmentsList(optimisticKey.removePrefix("msg:")),
            )
            assertFalse(optimisticKey in appState.activeUploadKeys(ACCOUNT_REF, GROUP_ID))
            assertEquals(CONFIRMED_MESSAGE_ID, confirmedPreview?.messageIdHex)
            assertEquals(ChatListMessageDeliveryStateFfi.DELIVERED, confirmedPreview?.deliveryState)
        }

    /**
     * A queued video keeps the bytes its poster is drawn from until the confirmed bubble owns it.
     *
     * The optimistic bubble has no materialized file to read a frame out of, so the retained upload
     * bytes are the only poster source it has. They must survive the whole send — including a failed
     * upload and its retry — and the confirmed key must be seeded from them before they are released,
     * so the poster never blinks out at the handover (#2732).
     */
    @Test
    @Suppress("LongMethod") // One send, one failure and one retry belong in a single ordered flow.
    fun pendingVideoKeepsItsPosterSourceAcrossRetryUntilTheConfirmedBubbleTakesOver() =
        runTest {
            val appState = mediaSendReconciliationAppState()
            attachedChatsController(appState)
            val reference = mediaReference()
            var uploadAttempts = 0
            lateinit var controller: ConversationController
            var retainedDuringPublish: List<PendingAttachment> = emptyList()
            controller =
                ConversationController(
                    appState = appState,
                    initialGroup = group(),
                    initialMemberSnapshot = memberSnapshot(),
                    groupRosterReader = { _, _ -> authoritativeRoster() },
                    mediaUploader = { _, _, _ ->
                        uploadAttempts += 1
                        if (uploadAttempts == 1) error("upload interrupted") else uploadResult(reference)
                    },
                    mediaImetaTagsBuilder = { _, _, _ -> listOf(mediaImetaTag()) },
                    mediaPublisher = { _, _, _, _ ->
                        val optimistic = controller.timeline.single()
                        retainedDuringPublish = controller.pendingAttachmentsList(optimistic.record.messageIdHex)
                        applyProjection(
                            controller,
                            projectedMediaMessage(
                                recordedAt = optimistic.record.recordedAt,
                                reference = reference,
                            ),
                        )
                        acceptedPendingSummary()
                    },
                )

            controller.retryMembers()
            controller.sendAttachments(attachments = listOf(pendingVideo()), caption = null)

            val failedMessage = controller.timeline.single()
            assertEquals(MessageStatus.Failed, failedMessage.status)
            assertEquals(
                "a failed upload must keep the bytes the poster is drawn from",
                listOf(VIDEO_MEDIA_TYPE),
                controller.pendingAttachmentsList(failedMessage.record.messageIdHex).map { it.mediaType },
            )

            controller.retryFailedSend(failedMessage)

            assertEquals(2, uploadAttempts)
            assertEquals(
                "the retry must reuse the retained bytes rather than the picker's Uri",
                listOf(VIDEO_MEDIA_TYPE),
                retainedDuringPublish.map { it.mediaType },
            )
            assertEquals(
                CONFIRMED_MESSAGE_ID,
                controller.timeline
                    .single()
                    .record.messageIdHex,
            )
            assertEquals(
                "the confirmed bubble must own the poster source before the pending copy is released",
                VIDEO_BYTES.toList(),
                appState
                    .cachedMediaPlaintext(mediaCacheKey(ACCOUNT_REF, GROUP_ID, CONFIRMED_MESSAGE_ID, 0))
                    ?.toList(),
            )
            assertEquals(
                emptyList<PendingAttachment>(),
                controller.pendingAttachmentsList(CONFIRMED_MESSAGE_ID),
            )
        }

    /** One queued video whose retained bytes stand in for a real clip's poster source. */
    private fun pendingVideo() =
        PendingAttachment(
            plaintextBytes = VIDEO_BYTES,
            mediaType = VIDEO_MEDIA_TYPE,
            fileName = "clip.mp4",
            dim = "1280x720",
        )

    /** A voice reply carries one exact target in its pending row and token-bound native draft admission. */
    @Test
    fun voiceReplyKeepsOriginalTargetWhenSelectionChangesDuringUpload() =
        assertDraftlessMediaUsesTokenBoundNativeAdmission("audio/mp4", "voice.m4a", replyTarget = "poll-original")


}

/** Covers native echo ordering while host-only thumbnail decoding suspends. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en")
class ConversationMediaSendReconciliationThumbnailTest {
    /** A native echo during asynchronous thumbnail preparation must inherit the pending row identity. */
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun nativeEchoDuringThumbnailDecodeKeepsPendingPresentationIdentity() =
        runTest {
            Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
            val state = mediaSendReconciliationAppState()
            val reference = mediaReference()
            lateinit var controller: ConversationController
            var pendingId = ""
            controller =
                ConversationController(
                    appState = state,
                    initialGroup = group(),
                    initialMemberSnapshot = memberSnapshot(),
                    groupRosterReader = { _, _ -> authoritativeRoster() },
                    mediaUploader = { _, _, _ -> uploadResult(reference) },
                    mediaImetaTagsBuilder = { _, _, _ -> listOf(mediaImetaTag()) },
                    mediaPublisher = { _, _, _, _ -> requireNotNull(uploadResult(reference).sent) },
                    mediaThumbnailDecoder = {
                        val original = controller.timeline.single()
                        pendingId = original.presentationId
                        val projection =
                            projectedMediaMessage(original.record.recordedAt, reference)
                                .copy(clientToken = original.record.messageIdHex)
                        controller.testRefreshCurrentTimeline(ACCOUNT_REF) {
                            TimelinePageFfi(listOf(projection), false, false)
                        }
                        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
                        assertEquals(pendingId, controller.timeline.single().presentationId)
                        null
                    },
                )
            try {
                controller.retryMembers()
                controller.sendAttachments(
                    listOf(PendingAttachment(VIDEO_BYTES, VIDEO_MEDIA_TYPE, "clip.mp4", "1280x720")),
                    null,
                )
                assertTrue(pendingId.isNotEmpty())
                assertEquals(
                    CONFIRMED_MESSAGE_ID,
                    controller.timeline
                        .single()
                        .record.messageIdHex,
                )
                assertEquals(pendingId, controller.timeline.single().presentationId)
            } finally {
                finishMediaFixture(controller, state)
                Dispatchers.resetMain()
            }
        }
}

/** Drains cancelled cache IO even after a test timeout, before its process-global Main dispatcher is reset. */
private suspend fun finishMediaFixture(
    controller: ConversationController,
    state: WhiteNoiseAppState,
) = withContext(NonCancellable) {
    controller.onCleared()
    state.mutationsScope.coroutineContext.job
        .cancelAndJoin()
}

/** Mounts the existing chat-list bridge so accepted media preview replacement remains observable. */
private fun attachedChatsController(appState: WhiteNoiseAppState): ChatsController =
    ChatsController(
        appState = appState,
        initialAccountRef = ACCOUNT_REF,
        memberSnapshotLoader = { _, _ -> emptyList() },
    ).also { chatsController ->
        appState.attachChatsController(chatsController)
        chatsController.setChatListVisible(false)
        chatsController.applyChatListRow(chatListRow())
    }

/** Delivers a canonical native timeline upsert through the production live-change path. */
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

/** Returns a native upload result referencing one validated synthetic attachment. */
private fun uploadResult(reference: MediaAttachmentReferenceFfi) =
    MediaUploadResultFfi(
        attachments =
            listOf(
                MediaUploadAttachmentResultFfi(
                    reference = reference,
                    encryptedSizeBytes = 4uL,
                ),
            ),
        sent =
            SendSummaryFfi(
                published = 1u,
                messageIds = listOf(CONFIRMED_MESSAGE_ID),
                acceptDisposition = SendAcceptDispositionFfi.PUBLISHED,
                maintenanceDisposition = SendMaintenanceDispositionFfi.READY,
            ),
    )

/** Represents durable acceptance without waiting for relay publication. */
private fun acceptedPendingSummary() =
    SendSummaryFfi(
        published = 0u,
        messageIds = listOf(CONFIRMED_MESSAGE_ID),
        acceptDisposition = SendAcceptDispositionFfi.ACCEPTED_PENDING,
        maintenanceDisposition = SendMaintenanceDispositionFfi.READY,
    )

/** Pins one immutable encrypted-media identity independent of its optimistic message ID. */
private fun mediaReference() =
    MediaAttachmentReferenceFfi(
        locators =
            listOf(
                MediaLocatorFfi(
                    kind = "blossom-v1",
                    value = "https://blossom.example/photo.jpg",
                ),
            ),
        ciphertextSha256 = "a".repeat(64),
        plaintextSha256 = "b".repeat(64),
        nonceHex = "c".repeat(24),
        fileName = "photo.jpg",
        mediaType = "image/jpeg",
        version = EncryptedMediaVersionFfi.V1,
        sourceEpoch = 1uL,
        dim = null,
        thumbhash = null,
    )

/** Supplies the legacy media hint used by existing native-boundary fixtures. */
private fun mediaImetaTag() = MessageTagFfi(listOf("imeta", "m image/jpeg"))

/** Creates the authoritative media projection used to reconcile one optimistic send token. */
private fun projectedMediaMessage(
    recordedAt: ULong,
    reference: MediaAttachmentReferenceFfi,
) = TimelineMessageRecordFfi(
    clientToken = null,
    messageIdHex = CONFIRMED_MESSAGE_ID,
    sourceMessageIdHex = "d4".repeat(32),
    direction = "sent",
    groupIdHex = GROUP_ID,
    sender = ACCOUNT_ID,
    plaintext = "hello",
    contentTokens = emptyMarkdownDocument(),
    kind = 9uL,
    tags = listOf(mediaImetaTag()),
    timelineAt = recordedAt,
    receivedAt = recordedAt,
    replyToMessageIdHex = null,
    replyPreview = null,
    mediaJson = null,
    media = MessageAttachments.acceptedOutcomes(listOf(reference)),
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
    retentionSeconds = null,
    retentionExpiresAt = null,
)

/** Builds a single account owner with process-local drafts and no real relay connection. */
internal fun mediaSendReconciliationAppState() =
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
            ),
        activeAccountRef = ACCOUNT_REF,
    )

/** Provides authoritative self-membership for send-admission guards. */
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

/** Returns the native roster used to admit this account to media mutations. */
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

/** Provides a stable group generation for accepted sends and canonical replacement. */
private fun group() =
    AppGroupRecordFfi(
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
        encryptedMedia = encryptedMediaComponent(),
        disappearingMessageSecs = 0uL,
        archived = false,
        pendingConfirmation = false,
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

/** Matches the existing encrypted-media capability expected by the upload path. */
private fun encryptedMediaComponent() =
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
    )

/** Supplies the pre-send authoritative row for optimistic chat-list reconciliation. */
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
                contentTokens = emptyMarkdownDocument(),
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

/** Keeps media fixtures independent of asynchronous Markdown hydration. */
private fun emptyMarkdownDocument() =
    MarkdownDocumentFfi(
        truncated = false,
        blocks = emptyList(),
        blankLinesBefore = ByteArray(0),
    )

private class TestDraftPersistence : DraftPersistence {
    override fun read(): Map<String, String> = emptyMap()

    override fun write(
        key: String,
        value: String?,
    ) = Unit
}

private const val ACCOUNT_REF = "alice"
private val ACCOUNT_ID = "a1".repeat(32)
private val GROUP_ID = "b2".repeat(32)
private val CONFIRMED_MESSAGE_ID = "c3".repeat(32)
private const val VIDEO_MEDIA_TYPE = "video/mp4"
private val VIDEO_BYTES = byteArrayOf(0, 0, 0, 24, 102, 116, 121, 112, 105, 115, 111, 109)

/** Allocates a test-only opaque native draft token without loading the device ABI. */
private fun mediaReplyRevisionStub(): MessageDraftRevisionFfi {
    val unsafeClass = Class.forName("sun.misc.Unsafe")
    val unsafe = unsafeClass.getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null)
    return unsafeClass
        .getMethod("allocateInstance", Class::class.java)
        .invoke(unsafe, MessageDraftRevisionFfi::class.java)
        as MessageDraftRevisionFfi
}

/** Exercises the default production path: no injected uploader or publisher test seam. */
@Suppress("LongMethod") // The native proxy and both cancellation/admission outcomes share one fixture.
private fun assertDraftlessMediaUsesTokenBoundNativeAdmission(
    mediaType: String,
    fileName: String,
    cancelBeforeUpload: Boolean = false,
    replyTarget: String? = null,
) = runTest {
    val calls = mutableListOf<String>()
    var uploadedRequest: MediaUploadRequestFfi? = null
    lateinit var controller: ConversationController
    val reference = mediaReference().copy(fileName = fileName, mediaType = mediaType)
    val marmot =
        mediaAdmissionBoundary(replyTarget, reference, calls) { request ->
            uploadedRequest = request
            if (replyTarget != null) {
                controller.replyingTo = controller.timeline.single().record.copy(messageIdHex = "newer-poll")
            }
        }
    val appState =
        mediaSendReconciliationAppState().also { state ->
            WhiteNoiseAppState::class.java
                .getDeclaredField("marmotRuntime")
                .apply { isAccessible = true }
                .set(state, AppMarmotRuntime("test", marmot))
        }
    controller =
        ConversationController(
            appState = appState,
            initialGroup = group(),
            initialMemberSnapshot = memberSnapshot(),
            groupRosterReader = { _, _ -> authoritativeRoster() },
            mediaImetaTagsBuilder = { _, _, _ -> listOf(mediaImetaTag()) },
            markdownParser = { emptyMarkdownDocument() },
        )

    controller.retryMembers()
    assertEquals(true, controller.canSendMessages)
    val seeded =
        requireNotNull(
            controller.queueAttachments(
                listOf(PendingAttachment(byteArrayOf(1, 2, 3, 4), mediaType, fileName)),
                caption = null,
                replyTarget = replyTarget,
            ),
        )
    val pending = controller.timeline.single().record
    assertEquals(replyTarget, MessageProjector.replyTargetMessageId(pending))

    if (cancelBeforeUpload) {
        assertTrue(controller.deleteMessage(pending, presentFailure = false))
        controller.uploadQueued(seeded)
        assertEquals(0, calls.count { it == "uploadMediaWithClientToken" })
        assertEquals(emptyList<TimelineMessage>(), controller.timeline)
        return@runTest
    }

    controller.uploadQueued(seeded)

    assertEquals(1, calls.count { it == "uploadMediaWithClientToken" })
    assertFalse(calls.contains("sendMediaAttachments"))
    assertEquals(replyTarget != null, calls.contains("sendMessageDraftWithClientToken"))
    assertEquals(replyTarget == null, uploadedRequest?.send)
    assertEquals(replyTarget, MessageProjector.replyTargetMessageId(controller.timeline.single().record))
    if (replyTarget != null) assertEquals("newer-poll", controller.replyingTo?.messageIdHex)
    assertEquals(mediaType, uploadedRequest?.attachments?.single()?.mediaType)
    assertEquals(MessageStatus.Pending, controller.timeline.single().status)
    assertFalse(controller.deleteMessage(pending, presentFailure = false))
}

/** Scripts native draft staging and token admission separately from the controller assertions. */
private fun mediaAdmissionBoundary(
    replyTarget: String?,
    reference: MediaAttachmentReferenceFfi,
    calls: MutableList<String>,
    onUpload: (MediaUploadRequestFfi) -> Unit,
): MarmotInterface {
    var stagedTarget: String? = null
    val draftRevision = mediaReplyRevisionStub()
    return Proxy.newProxyInstance(
        MarmotInterface::class.java.classLoader,
        arrayOf(MarmotInterface::class.java),
    ) { proxy, method, args ->
        val name = method.name.substringBefore('-')
        calls += name
        when (name) {
            "toString" -> "draftless-media-boundary"
            "hashCode" -> System.identityHashCode(proxy)
            "equals" -> proxy === args?.firstOrNull()
            "recordHostTiming" -> ProductRecordResultFfi.IGNORED_DISABLED
            "localSendStatus" -> null
            "selectedMessageDraft" ->
                if (replyTarget == null) null else SelectedMessageDraftFfi(draftRevision, null)
            "saveMessageDraftIfRevision" -> {
                stagedTarget = args!![3] as String
                SelectedMessageDraftFfi(draftRevision, null)
            }
            "sendMessageDraftWithClientToken" -> {
                assertTrue(args!![1] === draftRevision)
                assertEquals(replyTarget, stagedTarget)
                LocalSendAcceptanceFfi(args[3] as String, CONFIRMED_MESSAGE_ID)
            }
            "uploadMediaWithClientToken" -> {
                val request = args!![2] as MediaUploadRequestFfi
                val token = args[3] as String
                onUpload(request)
                MediaUploadSubmissionFfi(
                    upload = MediaUploadResultFfi(listOf(MediaUploadAttachmentResultFfi(reference, 4uL)), null),
                    acceptance =
                        if (replyTarget == null) LocalSendAcceptanceFfi(token, CONFIRMED_MESSAGE_ID) else null,
                )
            }
            else -> error("Unexpected Marmot call: $name")
        }
    } as MarmotInterface
}

/** Exercises retry settlement through the shipping media sender and reviewed recording owner together. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en")
class ConversationVoiceReplyRetryTest {
    /** A failed upload releases preparation, and bubble Retry consumes only the original reviewed take. */
    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun failedVoiceReplySettlesReviewAfterBubbleRetry() =
        runTest {
            Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
            val state = mediaSendReconciliationAppState()
            val calls = mutableListOf<String>()
            val target = "e1".repeat(32)
            val reference = mediaReference().copy(fileName = "voice-1000ms.m4a", mediaType = "audio/mp4")
            var uploads = 0
            val engine = mediaAdmissionBoundary(target, reference, calls) {
                uploads++
                if (uploads == 1) error("controlled upload failure")
            }
            WhiteNoiseAppState::class.java.getDeclaredField("marmotRuntime").apply { isAccessible = true }
                .set(state, AppMarmotRuntime("test", engine))
            val controller = voiceRetryController(state)
            val sender = ConversationMediaSender(state, controller, ApplicationProvider.getApplicationContext()) {}
            val rejected = CompletableDeferred<Unit>()
            val review =
                VoiceRecordingReview(
                    this,
                    { true },
                    { file, duration, guard, complete ->
                        sender.sendVoiceAttachment(file, duration, guard) { accepted ->
                            complete(accepted)
                            if (!accepted) rejected.complete(Unit)
                        }
                    },
                )
            val recording =
                java.io.File.createTempFile("voice-reply", ".m4a").apply { writeBytes(byteArrayOf(1, 2, 3)) }
            try {
                controller.retryMembers()
                controller.replyingTo =
                    TimelineProjector.toAppMessageRecord(projectedMediaMessage(1uL, reference))
                        .copy(messageIdHex = target)
                review.offer(recording, 1_000L)
                val clip = checkNotNull(review.clip)
                review.send(clip)
                rejected.await()
                assertTrue(review.clip === clip)
                assertTrue(recording.exists())
                val failed = controller.timeline.single()
                assertEquals(MessageStatus.Failed, failed.status)
                controller.retryFailedSend(failed)
                assertNull(review.clip)
                assertFalse(recording.exists())
                assertFalse(review.send(clip))
                assertEquals(2, uploads)
                assertEquals(1, calls.count { it == "sendMessageDraftWithClientToken" })
            } finally {
                review.release()
                finishMediaFixture(controller, state)
                Dispatchers.resetMain()
            }
        }
}

/** Supplies authoritative send permission and a non-blocking Markdown fixture for the real voice sender. */
private fun voiceRetryController(state: WhiteNoiseAppState) =
    ConversationController(
        appState = state,
        initialGroup = group(),
        initialMemberSnapshot = memberSnapshot(),
        groupRosterReader = { _, _ -> authoritativeRoster() },
        markdownParser = { emptyMarkdownDocument() },
    )
