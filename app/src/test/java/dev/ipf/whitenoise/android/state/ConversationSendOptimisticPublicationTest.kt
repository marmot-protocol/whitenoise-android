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
import dev.ipf.whitenoise.android.audio.ConversationDictationSendRequest
import dev.ipf.whitenoise.android.ui.conversation.ConversationScrollCoordinator
import dev.ipf.whitenoise.android.ui.conversation.ConversationScrollMode
import dev.ipf.whitenoise.android.ui.conversation.ConversationScrollWriter
import dev.ipf.whitenoise.android.ui.conversation.revealSentAtLiveTail
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
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
class ConversationSendAdmissionTest {
    /** Native acceptance completes independently, but final reconciliation retains the hydrated document. */
    @Test
    fun bubblePublishesAndAcceptsBeforeTheMarkdownParseHopCompletes() =
        runTest {
            val releaseParse = CompletableDeferred<MarkdownDocumentFfi>()
            val publishCompleted = CompletableDeferred<Unit>()
            val durablyAccepted = CompletableDeferred<Unit>()
            val styled = styledDocument()
            var accepted = 0
            var durableCallbacks = 0
            val controller =
                ConversationController(
                    appState = testAppState(),
                    initialGroup = group(),
                    initialMemberSnapshot = memberSnapshot(),
                    markdownParser = { releaseParse.await() },
                    textPublisher = { _, _, _, _ ->
                        sentSummary().also { publishCompleted.complete(Unit) }
                    },
                )

            val send =
                async(start = CoroutineStart.UNDISPATCHED) {
                    controller.send(
                        "hello *world*",
                        onAccepted = { accepted += 1 },
                        onDurablyAccepted = {
                            durableCallbacks += 1
                            durablyAccepted.complete(Unit)
                        },
                    )
                }

            try {
                withTimeout(5_000) {
                    publishCompleted.await()
                    durablyAccepted.await()
                }
                assertFalse("native acceptance must not release the Markdown barrier", releaseParse.isCompleted)
                assertFalse("final reconciliation must still await the presentation", send.isCompleted)
                val pending = controller.timeline.single()
                assertEquals(MessageStatus.Pending, pending.status)
                assertEquals("hello *world*", pending.record.plaintext)
                assertEquals("acceptance must not wait on the parse hop", 1, accepted)
                assertTrue(
                    pending.record.contentTokens.blocks
                        .isEmpty(),
                )

                releaseParse.complete(styled)
                send.await()

                val sent = controller.timeline.single()
                assertSame("hydration must survive reconciliation", styled, sent.record.contentTokens)
                assertEquals(MessageStatus.Sent, sent.status)
                assertEquals(CONFIRMED_MESSAGE_ID, sent.record.messageIdHex)
                assertEquals("durable acceptance must run exactly once", 1, durableCallbacks)
            } finally {
                releaseParse.complete(styled)
                send.cancelAndJoin()
                controller.onCleared()
            }
        }

    /** An ambiguous native result retires host cancellation before the presentation branch resumes. */
    @Test
    fun ambiguousAdmissionDuringParseCannotBeCancelledAndRemainsPending() =
        runTest {
            val parseStarted = CompletableDeferred<Unit>()
            val releaseParse = CompletableDeferred<MarkdownDocumentFfi>()
            val publishFailed = CompletableDeferred<Unit>()
            val styled = styledDocument()
            var publishCalls = 0
            var durableCallbacks = 0
            val controller =
                ConversationController(
                    appState = testAppState(),
                    initialGroup = group(),
                    initialMemberSnapshot = memberSnapshot(),
                    markdownParser = {
                        parseStarted.complete(Unit)
                        releaseParse.await()
                    },
                    textPublisher = { _, _, _, _ ->
                        publishCalls += 1
                        publishFailed.complete(Unit)
                        throw MarmotKitException.Publish("send event timed out")
                    },
                )
            val send =
                async(start = CoroutineStart.UNDISPATCHED) {
                    controller.send("possibly published *message*", onDurablyAccepted = { durableCallbacks += 1 })
                }

            try {
                withTimeout(5_000) {
                    publishFailed.await()
                    parseStarted.await()
                }
                assertFalse("an ambiguous result must not abort the blocked parser", send.isCompleted)
                assertFalse(releaseParse.isCompleted)
                assertFalse(
                    "host cancellation must not discard a message native may already own",
                    controller.deleteMessage(controller.timeline.single().record, presentFailure = false),
                )
                assertEquals(MessageStatus.Pending, controller.timeline.single().status)

                releaseParse.complete(styled)
                send.await()

                assertEquals(1, publishCalls)
                assertEquals(0, durableCallbacks)
                assertEquals(MessageStatus.Pending, controller.timeline.single().status)
                assertSame(
                    styled,
                    controller.timeline
                        .single()
                        .record.contentTokens,
                )
            } finally {
                releaseParse.complete(styled)
                send.cancelAndJoin()
                controller.onCleared()
            }
        }
}

/** Optimistic bubble and chat-list presentation across admission, hydration and reconciliation. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en")
class ConversationSendOptimisticPublicationTest {
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
                                    revealResult.complete(
                                        scrollCoordinator.revealSentAtLiveTail(controller, awaitFrame = {}),
                                    )
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
                // The transcript is reversed, so its origin always denotes the
                // newest row. A reveal can no longer target a stale index that
                // predates the row published after the previous composition.
                assertEquals(
                    "a reader following the tail must be pinned to the newest row, not carried there",
                    listOf(0),
                    writer.snappedIndexes,
                )
                assertTrue(
                    "pinning the tail must not glide across the lines the composer released",
                    writer.animatedIndexes.isEmpty(),
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

    /** Snaps a reader who sent from history to the same live tail as a tail-following reader. */
    @Test
    fun durableCallbackSnapsAHistoryReaderToTheLiveTail() =
        runBlocking {
            val writer = RecordingSendRevealWriter()
            val scrollCoordinator =
                ConversationScrollCoordinator(
                    writer = writer,
                    initialMode = ConversationScrollMode.ReadingHistory("older-row", 0),
                )
            val controller = controller(textPublisher = { _, _, _, _ -> sentSummary() })
            var awaitedFrames = 0

            try {
                controller.send("sent from history")
                assertTrue(
                    scrollCoordinator.revealSentAtLiveTail(
                        controller,
                        awaitFrame = { awaitedFrames += 1 },
                    ),
                )
                assertEquals(1, awaitedFrames)
                assertEquals(
                    "a history reader must snap to the transcript's newest row",
                    listOf(0),
                    writer.snappedIndexes,
                )
                assertTrue("a history reader must not be animated", writer.animatedIndexes.isEmpty())
                assertEquals(ConversationScrollMode.FollowingTail, scrollCoordinator.mode)
            } finally {
                controller.onCleared()
            }
        }

    /** Keeps the prior row visible until the optimistic send owns a completed Markdown document. */
    @Test
    fun chatListKeepsItsPriorPreviewUntilTheOptimisticMarkdownDocumentIsReady() =
        runTest {
            val appState = testAppState()
            val chats = attachedChats(appState)
            val releaseParse = CompletableDeferred<MarkdownDocumentFfi>()
            val publishStarted = CompletableDeferred<Unit>()
            val durablyAccepted = CompletableDeferred<Unit>()
            val styled = styledDocument()
            val controller =
                controller(
                    appState = appState,
                    markdownParser = { releaseParse.await() },
                    textPublisher = { _, _, _, _ ->
                        publishStarted.complete(Unit)
                        sentSummary()
                    },
                )
            try {
                val send =
                    async(start = CoroutineStart.UNDISPATCHED) {
                        controller.send("hello *world*", onDurablyAccepted = { durablyAccepted.complete(Unit) })
                    }

                withTimeout(5_000) {
                    publishStarted.await()
                    durablyAccepted.await()
                }
                assertFalse("the parser must remain blocked after native acceptance", releaseParse.isCompleted)
                assertFalse(send.isCompleted)
                chats.setChatListVisible(false)
                chats.setChatListVisible(true)
                val beforeParse =
                    chats.items
                        .single()
                        .projection
                        ?.lastMessage
                assertEquals(
                    "a parser stall must not expose raw optimistic Markdown in the chat list",
                    "notified body",
                    beforeParse?.plaintext,
                )

                releaseParse.complete(styled)
                send.await()
                // The real list is hidden behind the conversation while a send
                // starts. Returning flushes the completed styled preview into
                // its very first frame, even if native admission finished first.
                chats.setChatListVisible(false)
                chats.setChatListVisible(true)

                val afterParse = chats.items.single()
                assertEquals("hello *world*", afterParse.projection?.lastMessage?.plaintext)
                assertSame(styled, chats.items.single().previewTokens)
                val sent = controller.timeline.single().record
                assertSame(styled, sent.contentTokens)
            } finally {
                releaseParse.complete(styled)
                controller.onCleared()
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
        runTest {
            val appState = testAppState()
            val chats = attachedChats(appState)
            val firstParse = CompletableDeferred<MarkdownDocumentFfi>()
            val secondParse = CompletableDeferred<MarkdownDocumentFfi>()
            val published = mutableListOf<String>()
            val controller =
                controller(
                    appState = appState,
                    markdownParser = { text ->
                        if (text == "first") firstParse.await() else secondParse.await()
                    },
                    textPublisher = { _, _, _, text ->
                        published += text
                        sentSummary(if (text == "first") "33".repeat(32) else "44".repeat(32))
                    },
                )
            try {
                val first = async(start = CoroutineStart.UNDISPATCHED) { controller.send("first") }
                val second = async(start = CoroutineStart.UNDISPATCHED) { controller.send("second") }

                assertEquals("admission must precede parsing", listOf("first", "second"), published)
                secondParse.complete(styledDocument())
                second.await()
                firstParse.complete(styledDocument())
                first.await()
                assertEquals("parsing must not reorder admission", listOf("first", "second"), published)
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
                controller.onCleared()
                appState.attachChatsController(null)
                chats.onCleared()
            }
        }

    /** Transfers a parsed send across live controller replacement and retains terminal failure. */
    @Test
    fun controllerReplacementDuringParseRetainsTerminalFailurePreview() =
        runTest {
            val appState = testAppState()
            val original = attachedChats(appState)
            val releaseParse = CompletableDeferred<MarkdownDocumentFfi>()
            val publishFailed = CompletableDeferred<Unit>()
            val styled = styledDocument()
            val controller =
                controller(
                    appState = appState,
                    markdownParser = { releaseParse.await() },
                    textPublisher = { _, _, _, _ ->
                        publishFailed.complete(Unit)
                        throw MarmotKitException.Publish("relay rejected event")
                    },
                )
            val send = async(start = CoroutineStart.UNDISPATCHED) { controller.send("failed after replacement") }
            val replacement = replaceChats(appState, original)
            try {
                withTimeout(5_000) { publishFailed.await() }
                assertFalse("definite admission failure must not abort Markdown hydration", send.isCompleted)
                assertFalse(releaseParse.isCompleted)
                releaseParse.complete(styled)
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
                assertSame(styled, replacement.items.single().previewTokens)
                assertSame(
                    styled,
                    controller.timeline
                        .single()
                        .record.contentTokens,
                )
            } finally {
                releaseParse.complete(styled)
                send.cancelAndJoin()
                controller.onCleared()
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

    /** Cancelling a send cancels both its delayed parser and admission child waiting for text order. */
    @Test
    fun cancellingDuringParseDoesNotLeaveAnAdmissionChildBehind() =
        runTest {
            val appState = testAppState()
            val releaseSendOrder = CompletableDeferred<Unit>()
            val parseStarted = CompletableDeferred<Unit>()
            val parseStopped = CompletableDeferred<Unit>()
            val releaseParse = CompletableDeferred<MarkdownDocumentFfi>()
            val published = mutableListOf<String>()
            val controller =
                controller(
                    appState = appState,
                    markdownParser = { text ->
                        if (text == "cancelled") {
                            parseStarted.complete(Unit)
                            try {
                                releaseParse.await()
                            } finally {
                                parseStopped.complete(Unit)
                            }
                        } else {
                            styledDocument()
                        }
                    },
                    textPublisher = { _, _, _, text ->
                        published += text
                        sentSummary()
                    },
                )
            val holder =
                launch(start = CoroutineStart.UNDISPATCHED) {
                    appState.withConversationTextSendOrder(ACCOUNT_REF, GROUP_ID) {
                        releaseSendOrder.await()
                    }
                }
            val send = async(start = CoroutineStart.UNDISPATCHED) { controller.send("cancelled") }

            try {
                withTimeout(5_000) { parseStarted.await() }
                send.cancelAndJoin()
                assertTrue("structured cancellation must stop the parser", parseStopped.isCompleted)
                assertFalse(releaseParse.isCompleted)
                assertTrue(published.isEmpty())

                releaseSendOrder.complete(Unit)
                holder.join()
                controller.send("next")

                assertEquals("the cancelled child must never reach native publication", listOf("next"), published)
            } finally {
                releaseParse.complete(emptyMarkdownDocument())
                releaseSendOrder.complete(Unit)
                send.cancelAndJoin()
                holder.cancelAndJoin()
                controller.onCleared()
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

/** Mounted reply and dictation use the same accepted-send reveal as plain text. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en")
class ConversationSendVariantsRevealTest {
    /** A reply uses the same accepted-send owner and snaps rather than gliding across older history. */
    @Test
    fun acceptedReplyFromHistorySnapsToItsPublishedTail() =
        runBlocking {
            var expectedReply: String? = null
            val controller =
                controller(textPublisher = { reply, _, _, _ ->
                    assertEquals(expectedReply, reply)
                    sentSummary(if (expectedReply == null) CONFIRMED_MESSAGE_ID else "reply-confirmed")
                })
            try {
                controller.send("original")
                controller.replyingTo = controller.timeline.single().record
                expectedReply = CONFIRMED_MESSAGE_ID
                controller.send("a reply")
                assertAcceptedHistoryReveal(controller)
                assertEquals(null, controller.replyingTo)
            } finally {
                controller.onCleared()
            }
        }

    /** Exercises the mounted dictation handoff, preserving its captured draft before revealing the tail. */
    @Test
    fun acceptedDictationFromHistorySnapsToItsPublishedTail() =
        runBlocking {
            val appState = testAppState()
            val controller = controller(appState = appState, textPublisher = { _, _, _, _ -> sentSummary() })
            appState.attachConversationController(controller)
            val request =
                ConversationDictationSendRequest(
                    accountRef = ACCOUNT_REF,
                    groupIdHex = GROUP_ID,
                    expectedDraftRevision = appState.composerDraftGeneration(ACCOUNT_REF, GROUP_ID),
                    expectedDraftText = "",
                    payload = "dictated from history",
                )
            try {
                assertTrue(appState.sendDictationTranscriptIfOriginUnchanged(request))
                assertAcceptedHistoryReveal(controller)
                assertEquals(
                    "dictated from history",
                    controller.timeline
                        .single()
                        .record.plaintext,
                )
            } finally {
                appState.detachConversationController(controller)
                controller.onCleared()
            }
        }

    /** Checks the live controller overload rather than a payload-independent simulated tail. */
    private suspend fun assertAcceptedHistoryReveal(controller: ConversationController) {
        val writer = RecordingSendRevealWriter()
        val coordinator = ConversationScrollCoordinator(writer, ConversationScrollMode.ReadingHistory("old-row", 0))
        assertTrue(coordinator.revealSentAtLiveTail(controller, awaitFrame = {}))
        assertEquals(listOf(0), writer.snappedIndexes)
        assertTrue(writer.animatedIndexes.isEmpty())
        assertTrue(coordinator.isFollowingTail)
    }

    /** Creates a member-verified controller without network or protocol fixtures. */
    private fun controller(
        appState: WhiteNoiseAppState = testAppState(),
        textPublisher: suspend (String?, String, String, String) -> SendSummaryFfi,
    ): ConversationController =
        ConversationController(
            appState = appState,
            initialGroup = group(),
            initialMemberSnapshot = memberSnapshot(),
            textPublisher = textPublisher,
            markdownParser = { emptyMarkdownDocument() },
        )
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
    val snappedIndexes = mutableListOf<Int>()

    override val firstVisibleItemIndex: Int = 0

    /** Records a snap, whether it pins the tail or pre-positions near a far target. */
    override suspend fun scrollToItem(
        index: Int,
        scrollOffset: Int,
    ) {
        snappedIndexes += index
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
