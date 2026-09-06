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
import dev.ipf.whitenoise.android.ui.conversation.ConversationScrollCoordinator
import dev.ipf.whitenoise.android.ui.conversation.ConversationScrollWriter
import dev.ipf.whitenoise.android.ui.conversation.revealSentAtLiveTail
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    /** Keeps the pending bubble visible while Markdown hydration is deliberately held. */
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

    /** Resolves the durable Send target after its optimistic row grows the timeline during delayed transport. */
    @Test
    fun durableCallbackRevealsTheLiveTailAfterDelayedTransport() =
        runBlocking {
            val releasePublish = CompletableDeferred<Unit>()
            val revealResult = CompletableDeferred<Boolean>()
            val writer = RecordingSendRevealWriter()
            val scrollCoordinator = ConversationScrollCoordinator(writer)
            var revealJob: Job? = null
            val controller =
                controller(
                    textPublisher = { _, _, _, _ ->
                        releasePublish.await()
                        sentSummary()
                    },
                )

            val send =
                async(start = CoroutineStart.UNDISPATCHED) {
                    controller.send(
                        text = "delayed send",
                        onDurablyAccepted = {
                            revealJob =
                                launch(start = CoroutineStart.UNDISPATCHED) {
                                    revealResult.complete(scrollCoordinator.revealSentAtLiveTail(controller))
                                }
                        },
                    )
                }

            try {
                assertEquals("the optimistic row must publish before transport completes", 1, controller.timeline.size)
                assertFalse("the transport remains deliberately blocked", send.isCompleted)
                assertFalse("the durable callback must await typed acceptance", revealResult.isCompleted)
                releasePublish.complete(Unit)
                assertTrue(
                    "the durable callback must complete the Send-owned reveal",
                    withTimeout(5_000) { revealResult.await() },
                )
                assertEquals(
                    "the reveal must include the row published after the previous composition",
                    listOf(1),
                    writer.animatedIndexes,
                )
                send.await()
            } finally {
                releasePublish.complete(Unit)
                try {
                    revealJob?.cancel()
                    send.cancel()
                    withContext(NonCancellable) {
                        revealJob?.join()
                        send.join()
                    }
                } finally {
                    controller.onCleared()
                }
            }
        }

    /** Keeps the prior row visible until the optimistic send owns a completed Markdown document. */
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

    /** Publishes one stable plaintext fallback only after an empty parse result is known. */
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

    /** Prevents a delayed local parse from replacing newer incoming chat-list activity. */
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

    /** Preserves acceptance order when two optimistic Markdown parses finish in reverse order. */
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

    /** Transfers a parsed send across live controller replacement and retains terminal failure. */
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

    /** Transfers a parsed send across live replacement while preserving ambiguous pending state. */
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

    /** Verifies permanent detach never hands private rows to a later unrelated controller. */
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

    /** Makes the optimistic row observable before the transport publisher begins. */
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

    /** Proves an unrelated group commit lock cannot delay optimistic publication. */
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

    /** Keeps both optimistic rows visible while the first transport completion is held. */
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

    /** Retains the draft when membership rejects the send before optimistic publication. */
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

    /** Returns the controller exposed to a publisher assertion during construction. */
    private fun controller(): ConversationController = requireNotNull(builtController)

    /** Builds one member-verified controller with injectable parse and publish boundaries. */
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
}

/** Attaches a one-row chat list so optimistic bridge behavior is observable. */
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

/** Replaces the mounted controller through the production handoff path. */
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

/** Creates a newer authoritative row for parse-versus-incoming ordering races. */
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

/** Supplies a non-empty document so the delayed hydration rebind is observable. */
private fun styledDocument() =
    MarkdownDocumentFfi(
        truncated = false,
        blocks = listOf(MarkdownBlockFfi.Paragraph(inlines = emptyList())),
        blankLinesBefore = ByteArray(1),
    )

/** Represents a completed parser attempt that produced no structured blocks. */
private fun emptyMarkdownDocument(): MarkdownDocumentFfi {
    val emptyBlocks = emptyList<MarkdownBlockFfi>()
    return MarkdownDocumentFfi(truncated = false, blocks = emptyBlocks, blankLinesBefore = ByteArray(0))
}

/** Returns a successful typed disposition for the requested confirmed message id. */
private fun sentSummary(messageId: String = CONFIRMED_MESSAGE_ID) =
    SendSummaryFfi(
        published = 1u,
        messageIds = listOf(messageId),
        acceptDisposition = SendAcceptDispositionFfi.PUBLISHED,
        maintenanceDisposition = SendMaintenanceDispositionFfi.READY,
    )

/** Provides the account-pinned state required by optimistic conversation sends. */
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

/** Seeds verified local membership so send guards admit the fixture account. */
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

/** Creates the stable group generation shared by the controller and member fixture. */
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

/** Records the logical list row selected by the production Send reveal command. */
private class RecordingSendRevealWriter : ConversationScrollWriter {
    val animatedIndexes = mutableListOf<Int>()

    override val firstVisibleItemIndex: Int = 0

    /** Records non-animated pre-positioning when a target is far from the current viewport. */
    override suspend fun scrollToItem(
        index: Int,
        scrollOffset: Int,
    ) {
        animatedIndexes += index
    }

    /** Records the final animated target selected from the controller's live timeline. */
    override suspend fun animateScrollToItem(
        index: Int,
        scrollOffset: Int,
    ) {
        animatedIndexes += index
    }
}

private const val ACCOUNT_REF = "alice"
private val ACCOUNT_ID = "a1".repeat(32)
private val GROUP_ID = "b2".repeat(32)
private val CONFIRMED_MESSAGE_ID = "c3".repeat(32)

/** Keeps send-test drafts process-local while honoring the production persistence boundary. */
private fun sendTestDraftPersistence(): DraftPersistence =
    object : DraftPersistence {
        /** Starts every fixture without persisted composer text. */
        override fun read(): Map<String, String> = emptyMap()

        /** Accepts fixture writes without touching disk. */
        override fun write(
            key: String,
            value: String?,
        ) = Unit
    }
