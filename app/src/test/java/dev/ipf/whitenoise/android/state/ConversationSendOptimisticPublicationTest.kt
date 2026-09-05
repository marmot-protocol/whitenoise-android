package dev.ipf.whitenoise.android.state

import android.content.Context
import androidx.compose.ui.text.input.TextFieldValue
import androidx.test.core.app.ApplicationProvider
import dev.ipf.marmotkit.AccountSummaryFfi
import dev.ipf.marmotkit.AppBlobEndpointFfi
import dev.ipf.marmotkit.AppGroupEncryptedMediaComponentFfi
import dev.ipf.marmotkit.AppGroupMemberRecordFfi
import dev.ipf.marmotkit.AppGroupRecordFfi
import dev.ipf.marmotkit.AppProtocolProfileFfi
import dev.ipf.marmotkit.ChatListMessageDeliveryStateFfi
import dev.ipf.marmotkit.EncryptedMediaVersionFfi
import dev.ipf.marmotkit.MarkdownBlockFfi
import dev.ipf.marmotkit.MarkdownDocumentFfi
import dev.ipf.marmotkit.MarmotKitException
import dev.ipf.marmotkit.SelfMembershipFfi
import dev.ipf.marmotkit.SendAcceptDispositionFfi
import dev.ipf.marmotkit.SendMaintenanceDispositionFfi
import dev.ipf.marmotkit.SendSummaryFfi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The accepted plain-text send contract: the optimistic bubble is published —
 * and acceptance handed back to the composer — before the Markdown parse hop,
 * the group commit lock, and the network publish can suspend the mutation.
 * Delivery progress belongs on the bubble; nothing here may wait on I/O to
 * paint it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en")
class ConversationSendOptimisticPublicationTest {
    @Test
    fun bubblePublishesAndAcceptsBeforeTheMarkdownParseHopCompletes() =
        runBlocking {
            val releaseParse = CompletableDeferred<MarkdownDocumentFfi>()
            var accepted = 0
            val controller =
                controller(
                    markdownParser = { releaseParse.await() },
                    textPublisher = { _, _, _, _ -> sentSummary() },
                )

            val send =
                async(start = CoroutineStart.UNDISPATCHED) {
                    controller.send("hello *world*", onAccepted = { accepted += 1 })
                }

            assertEquals("the bubble must be visible while the parse hop is still pending", 1, controller.timeline.size)
            assertEquals(MessageStatus.Pending, controller.timeline.single().status)
            assertEquals(
                "hello *world*",
                controller.timeline
                    .single()
                    .record.plaintext,
            )
            assertEquals("acceptance must not wait on the parse hop", 1, accepted)
            assertTrue(
                controller.timeline
                    .single()
                    .record.contentTokens.blocks
                    .isEmpty(),
            )

            releaseParse.complete(styledDocument())
            send.await()

            assertTrue(
                "the parsed document must rebind onto the published bubble",
                controller.timeline
                    .single()
                    .record.contentTokens.blocks
                    .isNotEmpty(),
            )
            assertEquals(MessageStatus.Sent, controller.timeline.single().status)
        }

    @Test
    fun chatListKeepsItsPriorPreviewUntilTheOptimisticMarkdownDocumentIsReady() =
        runBlocking {
            val appState = testAppState()
            val chats = attachedChats(appState)
            val releaseParse = CompletableDeferred<MarkdownDocumentFfi>()
            val publishStarted = CompletableDeferred<Unit>()
            val releasePublish = CompletableDeferred<Unit>()
            val styled = styledDocument()
            val controller =
                controller(
                    appState = appState,
                    markdownParser = { releaseParse.await() },
                    textPublisher = { _, _, _, _ ->
                        publishStarted.complete(Unit)
                        releasePublish.await()
                        sentSummary()
                    },
                )
            try {
                val send =
                    async(start = CoroutineStart.UNDISPATCHED) {
                        controller.send("hello *world*")
                    }

                assertEquals(
                    "a parser stall must not expose raw optimistic Markdown in the chat list",
                    "notified body",
                    chats.items
                        .single()
                        .projection
                        ?.lastMessage
                        ?.plaintext,
                )

                releaseParse.complete(styled)
                publishStarted.await()
                // The real list is hidden behind the conversation while a send
                // starts. Returning flushes the already-folded preview into its
                // very first frame without waiting for the network publish.
                chats.setChatListVisible(false)
                chats.setChatListVisible(true)

                assertEquals(
                    "hello *world*",
                    chats.items
                        .single()
                        .projection
                        ?.lastMessage
                        ?.plaintext,
                )
                assertSame(styled, chats.items.single().previewTokens)

                releasePublish.complete(Unit)
                send.await()
            } finally {
                releasePublish.complete(Unit)
                appState.attachChatsController(null)
                chats.onCleared()
            }
        }

    @Test
    fun emptyMarkdownParsePublishesOnePlaintextFallbackOnlyAfterTheAttemptCompletes() =
        runBlocking {
            val appState = testAppState()
            val chats = attachedChats(appState)
            val releaseParse = CompletableDeferred<MarkdownDocumentFfi>()
            val controller =
                controller(
                    appState = appState,
                    markdownParser = { releaseParse.await() },
                    textPublisher = { _, _, _, _ -> sentSummary() },
                )
            try {
                val send =
                    async(start = CoroutineStart.UNDISPATCHED) {
                        controller.send("**stable fallback**")
                    }

                assertEquals(
                    "notified body",
                    chats.items
                        .single()
                        .projection
                        ?.lastMessage
                        ?.plaintext,
                )

                releaseParse.complete(emptyMarkdownDocument())
                send.await()
                chats.setChatListVisible(false)
                chats.setChatListVisible(true)

                assertEquals(
                    "**stable fallback**",
                    chats.items
                        .single()
                        .projection
                        ?.lastMessage
                        ?.plaintext,
                )
            } finally {
                appState.attachChatsController(null)
                chats.onCleared()
            }
        }

    @Test
    fun incomingActivityDuringParseKeepsTheNewerChatListPreview() =
        runBlocking {
            val appState = testAppState()
            val chats = attachedChats(appState)
            val releaseParse = CompletableDeferred<MarkdownDocumentFfi>()
            val controller =
                controller(
                    appState = appState,
                    markdownParser = { releaseParse.await() },
                    textPublisher = { _, _, _, _ -> sentSummary() },
                )
            try {
                val send =
                    async(start = CoroutineStart.UNDISPATCHED) {
                        controller.send("older local send")
                    }
                chats.applyChatListRow(incomingRow("newer incoming", "22".repeat(32), 50uL))

                releaseParse.complete(styledDocument())
                send.await()
                chats.setChatListVisible(false)
                chats.setChatListVisible(true)

                assertEquals(
                    "newer incoming",
                    chats.items
                        .single()
                        .projection
                        ?.lastMessage
                        ?.plaintext,
                )
            } finally {
                appState.attachChatsController(null)
                chats.onCleared()
            }
        }

    @Test
    fun reverseParseCompletionKeepsTheLaterAcceptedSendOnTheChatList() =
        runBlocking {
            val appState = testAppState()
            val chats = attachedChats(appState)
            val firstParse = CompletableDeferred<MarkdownDocumentFfi>()
            val secondParse = CompletableDeferred<MarkdownDocumentFfi>()
            val controller =
                controller(
                    appState = appState,
                    markdownParser = { text ->
                        if (text == "first") firstParse.await() else secondParse.await()
                    },
                    textPublisher = { _, _, _, text ->
                        sentSummary(if (text == "first") "33".repeat(32) else "44".repeat(32))
                    },
                )
            try {
                val first = async(start = CoroutineStart.UNDISPATCHED) { controller.send("first") }
                val second = async(start = CoroutineStart.UNDISPATCHED) { controller.send("second") }

                secondParse.complete(styledDocument())
                second.await()
                firstParse.complete(styledDocument())
                first.await()
                chats.setChatListVisible(false)
                chats.setChatListVisible(true)

                assertEquals(
                    "second",
                    chats.items
                        .single()
                        .projection
                        ?.lastMessage
                        ?.plaintext,
                )
                assertEquals(
                    setOf("33".repeat(32), "44".repeat(32)),
                    controller.timeline.map { it.record.messageIdHex }.toSet(),
                )
            } finally {
                firstParse.complete(emptyMarkdownDocument())
                secondParse.complete(emptyMarkdownDocument())
                appState.attachChatsController(null)
                chats.onCleared()
            }
        }

    @Test
    fun controllerReplacementDuringParseRetainsTerminalFailurePreview() =
        runBlocking {
            val appState = testAppState()
            val original = attachedChats(appState)
            val releaseParse = CompletableDeferred<MarkdownDocumentFfi>()
            val controller =
                controller(
                    appState = appState,
                    markdownParser = { releaseParse.await() },
                    textPublisher = { _, _, _, _ ->
                        throw MarmotKitException.Publish("relay rejected event")
                    },
                )
            val send = async(start = CoroutineStart.UNDISPATCHED) { controller.send("failed after replacement") }
            val replacement = replaceChats(appState, original)
            try {
                releaseParse.complete(styledDocument())
                send.await()
                replacement.setChatListVisible(false)
                replacement.setChatListVisible(true)

                assertEquals(
                    "failed after replacement",
                    replacement.items
                        .single()
                        .projection
                        ?.lastMessage
                        ?.plaintext,
                )
                assertEquals(
                    ChatListMessageDeliveryStateFfi.FAILED,
                    replacement.items
                        .single()
                        .projection
                        ?.lastMessage
                        ?.deliveryState,
                )
            } finally {
                appState.attachChatsController(null)
                replacement.onCleared()
            }
        }

    @Test
    fun controllerReplacementDuringParseRetainsAmbiguousPendingPreview() =
        runBlocking {
            val appState = testAppState()
            val original = attachedChats(appState)
            val releaseParse = CompletableDeferred<MarkdownDocumentFfi>()
            val controller =
                controller(
                    appState = appState,
                    markdownParser = { releaseParse.await() },
                    textPublisher = { _, _, _, _ ->
                        throw MarmotKitException.Publish("send event timed out")
                    },
                )
            val send = async(start = CoroutineStart.UNDISPATCHED) { controller.send("pending after replacement") }
            val replacement = replaceChats(appState, original)
            try {
                releaseParse.complete(styledDocument())
                send.await()
                replacement.setChatListVisible(false)
                replacement.setChatListVisible(true)

                assertEquals(
                    "pending after replacement",
                    replacement.items
                        .single()
                        .projection
                        ?.lastMessage
                        ?.plaintext,
                )
                assertEquals(
                    ChatListMessageDeliveryStateFfi.PENDING,
                    replacement.items
                        .single()
                        .projection
                        ?.lastMessage
                        ?.deliveryState,
                )
            } finally {
                appState.attachChatsController(null)
                replacement.onCleared()
            }
        }

    @Test
    fun permanentDetachDuringParseDoesNotTransferPrivateRows() =
        runBlocking {
            val appState = testAppState()
            val original = attachedChats(appState)
            val releaseParse = CompletableDeferred<MarkdownDocumentFfi>()
            val controller =
                controller(
                    appState = appState,
                    markdownParser = { releaseParse.await() },
                    textPublisher = { _, _, _, _ ->
                        throw MarmotKitException.Publish("send event timed out")
                    },
                )
            val send = async(start = CoroutineStart.UNDISPATCHED) { controller.send("must not survive detach") }

            appState.attachChatsController(null)
            original.onCleared()
            val replacement =
                ChatsController(
                    appState = appState,
                    initialAccountRef = ACCOUNT_REF,
                    memberSnapshotLoader = { _, _ -> emptyList() },
                ).also(appState::attachChatsController)
            try {
                releaseParse.complete(styledDocument())
                send.await()

                assertTrue("permanent detach must not retain or restore private rows", replacement.items.isEmpty())
            } finally {
                appState.attachChatsController(null)
                replacement.onCleared()
            }
        }

    @Test
    fun bubbleIsPublishedBeforeTheNetworkPublishRuns() =
        runBlocking {
            var bubbleVisibleAtPublish = false
            val controller =
                controller(
                    textPublisher = { _, _, _, _ ->
                        bubbleVisibleAtPublish =
                            controller().timeline.singleOrNull()?.status == MessageStatus.Pending
                        sentSummary()
                    },
                )

            controller.send("hello")

            assertTrue("the optimistic bubble must precede the first publish attempt", bubbleVisibleAtPublish)
            assertEquals(MessageStatus.Sent, controller.timeline.single().status)
        }

    @Test
    fun bubblePublishesWhileTheGroupCommitLockIsHeldElsewhere() =
        runBlocking {
            val appState = testAppState()
            val releaseLock = CompletableDeferred<Unit>()
            val lockHeld = CompletableDeferred<Unit>()
            var accepted = 0
            val controller =
                controller(
                    appState = appState,
                    textPublisher = { _, _, _, _ -> sentSummary() },
                )
            val holder =
                launch(start = CoroutineStart.UNDISPATCHED) {
                    appState.withGroupCommitLock(ACCOUNT_REF, GROUP_ID) {
                        lockHeld.complete(Unit)
                        releaseLock.await()
                    }
                }
            lockHeld.await()

            val send =
                async(start = CoroutineStart.UNDISPATCHED) {
                    controller.send("hello", onAccepted = { accepted += 1 })
                }

            assertEquals("a held commit lock must not delay the bubble", 1, controller.timeline.size)
            assertEquals(MessageStatus.Pending, controller.timeline.single().status)
            assertEquals(1, accepted)

            releaseLock.complete(Unit)
            holder.join()
            send.await()

            assertEquals(MessageStatus.Sent, controller.timeline.single().status)
        }

    @Test
    fun backToBackSendsEachPublishTheirBubbleImmediately() =
        runBlocking {
            val releaseFirstPublish = CompletableDeferred<Unit>()
            var publishes = 0
            val controller =
                controller(
                    textPublisher = { _, _, _, _ ->
                        publishes += 1
                        if (publishes == 1) releaseFirstPublish.await()
                        sentSummary("c$publishes".padEnd(2, '0').repeat(32).take(64))
                    },
                )

            val first =
                async(start = CoroutineStart.UNDISPATCHED) {
                    controller.send("first")
                }
            val second =
                async(start = CoroutineStart.UNDISPATCHED) {
                    controller.send("second")
                }

            assertEquals(
                "the second bubble must not wait for the first send's round-trip",
                listOf("first", "second"),
                controller.timeline.map { it.record.plaintext },
            )
            assertTrue(controller.timeline.all { it.status == MessageStatus.Pending })

            releaseFirstPublish.complete(Unit)
            first.await()
            second.await()

            assertTrue(controller.timeline.all { it.status == MessageStatus.Sent })
        }

    @Test
    fun rejectedHandoffKeepsTheDraftAndPublishesNothing() =
        runBlocking {
            val appState = testAppState()
            appState.setDraft(ACCOUNT_REF, GROUP_ID, TextFieldValue("kept draft"))
            var accepted = 0
            // A projected non-member cannot hand a send off; the guard must
            // fake nothing and leave the draft in place.
            val controller =
                ConversationController(
                    appState = appState,
                    initialGroup = group(selfMembership = SelfMembershipFfi.REMOVED),
                    textPublisher = { _, _, _, _ -> error("must not publish") },
                )

            appState.sendConversationText(controller, "kept draft") { accepted += 1 }

            assertEquals(0, accepted)
            assertTrue(controller.timeline.isEmpty())
            assertEquals("kept draft", appState.draftFor(ACCOUNT_REF, GROUP_ID))
        }

    private var builtController: ConversationController? = null

    private fun attachedChats(appState: WhiteNoiseAppState): ChatsController =
        ChatsController(
            appState = appState,
            initialAccountRef = ACCOUNT_REF,
            memberSnapshotLoader = { _, _ -> emptyList() },
        ).also { chats ->
            chats.setChatListVisible(false)
            chats.applyChatListRow(notificationChatListRow().copy(groupIdHex = GROUP_ID))
            chats.setChatListVisible(true)
            appState.attachChatsController(chats)
        }

    private fun replaceChats(
        appState: WhiteNoiseAppState,
        original: ChatsController,
    ): ChatsController =
        ChatsController(
            appState = appState,
            initialAccountRef = ACCOUNT_REF,
            memberSnapshotLoader = { _, _ -> emptyList() },
        ).also { replacement ->
            appState.replaceChatsController(original, replacement)
            original.onCleared()
        }

    private fun incomingRow(
        plaintext: String,
        messageIdHex: String,
        timelineAt: ULong,
    ) = notificationChatListRow().copy(
        groupIdHex = GROUP_ID,
        lastMessage =
            notifiedMessagePreview().copy(
                messageIdHex = messageIdHex,
                plaintext = plaintext,
                timelineAt = timelineAt,
            ),
        activitySortAt = timelineAt,
        updatedAt = timelineAt,
    )

    private fun controller(): ConversationController = requireNotNull(builtController)

    private fun controller(
        appState: WhiteNoiseAppState = testAppState(),
        markdownParser: suspend (String) -> MarkdownDocumentFfi = {
            MarkdownDocumentFfi(truncated = false, blocks = emptyList(), blankLinesBefore = ByteArray(0))
        },
        textPublisher: suspend (String?, String, String, String) -> SendSummaryFfi,
    ): ConversationController =
        ConversationController(
            appState = appState,
            initialGroup = group(),
            initialMemberSnapshot = memberSnapshot(),
            textPublisher = textPublisher,
            markdownParser = markdownParser,
        ).also { builtController = it }

    private fun styledDocument() =
        MarkdownDocumentFfi(
            truncated = false,
            blocks = listOf(MarkdownBlockFfi.Paragraph(inlines = emptyList())),
            blankLinesBefore = ByteArray(1),
        )

    private fun emptyMarkdownDocument(): MarkdownDocumentFfi {
        val emptyBlocks = emptyList<MarkdownBlockFfi>()
        return MarkdownDocumentFfi(truncated = false, blocks = emptyBlocks, blankLinesBefore = ByteArray(0))
    }

    private fun sentSummary(messageId: String = CONFIRMED_MESSAGE_ID) =
        SendSummaryFfi(
            published = 1u,
            messageIds = listOf(messageId),
            acceptDisposition = SendAcceptDispositionFfi.PUBLISHED,
            maintenanceDisposition = SendMaintenanceDispositionFfi.READY,
        )

    private fun testAppState(): WhiteNoiseAppState =
        WhiteNoiseAppState(
            context = ApplicationProvider.getApplicationContext<Context>(),
            draftStore = DraftStore(sendTestDraftPersistence()),
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

    private fun group(selfMembership: SelfMembershipFfi = SelfMembershipFfi.MEMBER) =
        AppGroupRecordFfi(
            groupIdHex = GROUP_ID,
            protocolProfile = AppProtocolProfileFfi.LEGACY,
            endpoint = "wss://relay.example",
            profilePresent = true,
            name = "Send group",
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
            disappearingMessageSecs = 0uL,
            archived = false,
            pendingConfirmation = false,
            unrecoverable = false,
            selfMembership = selfMembership,
            leaveRequestPending = false,
            leaveRequestedAtMs = null,
            disbanding = false,
            disbandRequest = null,
            disbanded = false,
            welcomerAccountIdHex = null,
            viaWelcomeMessageIdHex = null,
        )

    private companion object {
        const val ACCOUNT_REF = "alice"
        val ACCOUNT_ID = "a1".repeat(32)
        val GROUP_ID = "b2".repeat(32)
        val CONFIRMED_MESSAGE_ID = "c3".repeat(32)
    }
}

private fun sendTestDraftPersistence(): DraftPersistence =
    object : DraftPersistence {
        override fun read(): Map<String, String> = emptyMap()

        override fun write(
            key: String,
            value: String?,
        ) = Unit
    }
