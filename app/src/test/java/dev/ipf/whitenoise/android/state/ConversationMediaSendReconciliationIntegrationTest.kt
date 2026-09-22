package dev.ipf.whitenoise.android.state

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
import dev.ipf.marmotkit.MessageTagFfi
import dev.ipf.marmotkit.ProductRecordResultFfi
import dev.ipf.marmotkit.SelfMembershipFfi
import dev.ipf.marmotkit.SendAcceptDispositionFfi
import dev.ipf.marmotkit.SendMaintenanceDispositionFfi
import dev.ipf.marmotkit.SendSummaryFfi
import dev.ipf.marmotkit.TimelineMessageChangeFfi
import dev.ipf.marmotkit.TimelineMessageRecordFfi
import dev.ipf.marmotkit.TimelineReactionSummaryFfi
import dev.ipf.marmotkit.TimelineUpdateTriggerFfi
import dev.ipf.whitenoise.android.core.MessageAttachments
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.lang.reflect.Proxy

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en")
class ConversationMediaSendReconciliationIntegrationTest {
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
                    appState = appState(),
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
            val appState = appState()
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

    /** Exercises the default production path: no injected uploader or publisher test seam. */
    private fun assertDraftlessMediaUsesTokenBoundNativeAdmission(
        mediaType: String,
        fileName: String,
        cancelBeforeUpload: Boolean = false,
    ) = runTest {
        val calls = mutableListOf<String>()
        var uploadedRequest: MediaUploadRequestFfi? = null
        val reference = mediaReference().copy(fileName = fileName, mediaType = mediaType)
        val marmot =
            Proxy.newProxyInstance(
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
                    "selectedMessageDraft" -> null
                    "uploadMediaWithClientToken" -> {
                        val request = args!![2] as MediaUploadRequestFfi
                        val token = args[3] as String
                        uploadedRequest = request
                        MediaUploadSubmissionFfi(
                            upload = MediaUploadResultFfi(listOf(MediaUploadAttachmentResultFfi(reference, 4uL)), null),
                            acceptance = LocalSendAcceptanceFfi(token, CONFIRMED_MESSAGE_ID),
                        )
                    }
                    else -> error("Unexpected Marmot call: $name")
                }
            } as MarmotInterface
        val appState =
            appState().also { state ->
                WhiteNoiseAppState::class.java
                    .getDeclaredField("marmotRuntime")
                    .apply { isAccessible = true }
                    .set(state, AppMarmotRuntime("test", marmot))
            }
        val controller =
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
                ),
            )
        val pending = controller.timeline.single().record

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
        assertFalse(calls.contains("sendMessageDraftWithClientToken"))
        assertEquals(true, uploadedRequest?.send)
        assertEquals(mediaType, uploadedRequest?.attachments?.single()?.mediaType)
        assertEquals(MessageStatus.Pending, controller.timeline.single().status)
        assertFalse(controller.deleteMessage(pending, presentFailure = false))
    }

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

    private fun acceptedPendingSummary() =
        SendSummaryFfi(
            published = 0u,
            messageIds = listOf(CONFIRMED_MESSAGE_ID),
            acceptDisposition = SendAcceptDispositionFfi.ACCEPTED_PENDING,
            maintenanceDisposition = SendMaintenanceDispositionFfi.READY,
        )

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

    private companion object {
        const val ACCOUNT_REF = "alice"
        val ACCOUNT_ID = "a1".repeat(32)
        val GROUP_ID = "b2".repeat(32)
        val CONFIRMED_MESSAGE_ID = "c3".repeat(32)
    }
}
