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
import dev.ipf.marmotkit.EncryptedMediaVersionFfi
import dev.ipf.marmotkit.GroupLifecycleStateFfi
import dev.ipf.marmotkit.MarkdownDocumentFfi
import dev.ipf.marmotkit.MarmotKitException
import dev.ipf.marmotkit.SelfMembershipFfi
import dev.ipf.marmotkit.SendAcceptDispositionFfi
import dev.ipf.marmotkit.SendMaintenanceDispositionFfi
import dev.ipf.marmotkit.SendSummaryFfi
import dev.ipf.marmotkit.TimelineMessageChangeFfi
import dev.ipf.marmotkit.TimelineMessageRecordFfi
import dev.ipf.marmotkit.TimelinePageFfi
import dev.ipf.marmotkit.TimelineReactionSummaryFfi
import dev.ipf.marmotkit.TimelineUpdateTriggerFfi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Integration boundary for optimistic send state plus the shared relay retry policy (#2016). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en")
class ConversationSendRetryIntegrationTest {
    @Test
    fun durableAcceptanceClearsTheCapturedComposerDraft() {
        val appState = appState()
        appState.setDraft(GROUP_ID, TextFieldValue("hello"))
        val pendingClear = requireNotNull(appState.captureDraftForSend(GROUP_ID))

        appState.clearDraftAfterSuccessfulSend(pendingClear)

        assertEquals(null, appState.draftFor(GROUP_ID))
    }

    @Test
    fun durableAcceptanceDoesNotClearANewerComposerDraft() {
        val appState = appState()
        appState.setDraft(GROUP_ID, TextFieldValue("first message"))
        val pendingClear = requireNotNull(appState.captureDraftForSend(GROUP_ID))
        appState.setDraft(GROUP_ID, TextFieldValue("next message"))

        appState.clearDraftAfterSuccessfulSend(pendingClear)

        assertEquals("next message", appState.draftFor(GROUP_ID))
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
    fun discardingAnInFlightRetryStillClearsTheDraftAfterDurableAcceptance() =
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

            controller.discardFailedSend(failedItem)
            assertEquals(emptyList<TimelineMessage>(), controller.timeline)
            assertEquals("discard while retrying", appState.draftFor(GROUP_ID))

            acceptRetry.complete(Unit)
            retry.await()

            assertEquals(null, appState.draftFor(GROUP_ID))
            assertEquals(emptyList<TimelineMessage>(), controller.timeline)
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
                        assertEquals("queued safely", appState.draftFor(GROUP_ID))
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
                ChatsController(
                    appState = appState,
                    initialAccountRef = ACCOUNT_REF,
                    memberSnapshotLoader = { _, _ -> emptyList() },
                )
            appState.attachChatsController(chatsController)
            chatsController.setChatListVisible(false)
            chatsController.applyChatListRow(chatListRow())
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
            controller.testApplyLiveTimelineChangesAndRegisterStreams(
                listOf(
                    TimelineMessageChangeFfi.Upsert(
                        trigger = TimelineUpdateTriggerFfi.NEW_MESSAGE,
                        message = projectedMessage(recordedAt = 20uL, retentionSeconds = null, retentionExpiresAt = null),
                    ),
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

    private fun appState() =
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

    private fun group(disappearingMessageSecs: ULong = 0uL) =
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
                    messageIdHex = "d4".repeat(32),
                    sender = ACCOUNT_ID,
                    senderDisplayName = null,
                    plaintext = "before send",
                    contentTokens = MarkdownDocumentFfi(truncated = false, blocks = emptyList(), blankLinesBefore = ByteArray(0)),
                    kind = 9uL,
                    timelineAt = 10uL,
                    deleted = false,
                    attachmentKind = null,
                    attachmentCount = 0u,
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
    ) = TimelineMessageRecordFfi(
        messageIdHex = CONFIRMED_MESSAGE_ID,
        sourceMessageIdHex = "d4".repeat(32),
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
        groupSystem = null,
        reactions = TimelineReactionSummaryFfi(byEmoji = emptyList(), userReactions = emptyList()),
        deleted = false,
        deletedByMessageIdHex = null,
        invalidationStatus = null,
        sourceEpoch = null,
        retentionSeconds = retentionSeconds,
        retentionExpiresAt = retentionExpiresAt,
    )

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
        val CONFIRMED_MESSAGE_ID = "c3".repeat(32)
    }
}
