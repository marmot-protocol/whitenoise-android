package dev.ipf.whitenoise.android.state

import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import dev.ipf.marmotkit.AccountSummaryFfi
import dev.ipf.marmotkit.AppGroupRecordFfi
import dev.ipf.marmotkit.ChatConversationKindFfi
import dev.ipf.marmotkit.ChatListAnchorOutcomeFfi
import dev.ipf.marmotkit.ChatListMessageDeliveryStateFfi
import dev.ipf.marmotkit.ChatListPageDirectionFfi
import dev.ipf.marmotkit.ChatListRowFfi
import dev.ipf.marmotkit.ChatListSubscriptionUpdateFfi
import dev.ipf.marmotkit.ChatListUpdateTriggerFfi
import dev.ipf.marmotkit.ChatListViewFfi
import dev.ipf.marmotkit.ChatListWindowSnapshotFfi
import dev.ipf.marmotkit.ConversationPresentationFfi
import dev.ipf.marmotkit.PresentationResolutionFfi
import dev.ipf.marmotkit.PresentationSourceFfi
import dev.ipf.marmotkit.PresentationTextFfi
import dev.ipf.marmotkit.PresentedChatRowFfi
import dev.ipf.marmotkit.SelectedAvatarFfi
import dev.ipf.whitenoise.android.diagnostics.PerformancePhase
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/** End-to-end JVM coverage for recovery attribution through the chat-list projection. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en")
class ChatListReconnectIntegrationTest {
    /** A recovered subscription row reaches the authoritative list under one generation. */
    @Test
    fun recoveryGenerationReachesTheAuthoritativeChatListRow() {
        val diagnostics = testRecoveryDiagnostics()
        val initialRow = notificationChatListRow().copy(lastMessage = null, unreadCount = 0uL, hasUnread = false)
        val subscription = ScriptedChatListSubscription(initialRow)
        val groupSubscription = ScriptedChatsSubscription()
        val appState = chatListTestAppState(diagnostics, subscription, groupSubscription)
        val controller =
            ChatsController(
                appState = appState,
                initialAccountRef = ConversationTimelineTestIds.ACCOUNT_REF,
                memberSnapshotRetryDelay = { Long.MAX_VALUE },
                memberSnapshotLoader = { _, _ -> conversationTimelineMemberSnapshot().members },
            )
        val bindScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        bindScope.launch { controller.bind(ConversationTimelineTestIds.ACCOUNT_REF) }
        try {
            awaitChatListCondition { subscription.nextUpdateStarted.isCompleted }
            diagnostics.networkRestored(7L)
            diagnostics.attemptStarted(7L, 1)
            diagnostics.catchUpSucceeded(7L, 1)
            subscription.emit(
                ChatListSubscriptionUpdateFfi.Row(
                    row = notificationChatListRow(),
                    trigger = ChatListUpdateTriggerFfi.NEW_LAST_MESSAGE,
                ),
            )

            awaitChatListCondition {
                controller.items
                    .singleOrNull()
                    ?.latest
                    ?.messageIdHex == ConversationTimelineTestIds.MESSAGE_B &&
                    controller.recoveryProjectionGeneration == 7L
            }
            val phases = diagnostics.samples().filter { it.generation == 7L }.map { it.phase }
            assertEquals(1uL, controller.items.single().unreadCount)
            assertTrue(
                phases.indexOf(PerformancePhase.CURRENT_REPLAY_COMPLETE) <
                    phases.indexOf(PerformancePhase.CHAT_LIST_SUBSCRIPTION_RECEIVED),
            )
            assertTrue(
                phases.indexOf(PerformancePhase.CHAT_LIST_SUBSCRIPTION_RECEIVED) <
                    phases.indexOf(PerformancePhase.CHAT_LIST_PROJECTION_PUBLISHED),
            )
        } finally {
            controller.onCleared()
            subscription.close()
            groupSubscription.close()
            bindScope.cancel()
            shadowOf(Looper.getMainLooper()).idle()
        }
    }

    /** An incomplete live replacement keeps a pinned DM visible until a reopened coherent frame arrives. */
    @Test
    fun missingPinnedChatKeepsLastFrameAndReopensTheWindows() {
        val pinned =
            notificationChatListRow().copy(
                groupIdHex = "aa".repeat(32),
                pinned = true,
                pinnedPosition = 0u,
                conversationKind = ChatConversationKindFfi.DIRECT,
            )
        val group = notificationChatListRow().copy(groupIdHex = "bb".repeat(32), pinned = false)
        val subscriptions = DroppedChatSubscriptions(pinned, group)
        val appState = chatListTestAppState(testRecoveryDiagnostics(), subscriptions.liveSubscriptions)
        val controller = testChatsController(appState)
        val bindScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        bindScope.launch { controller.bind(ConversationTimelineTestIds.ACCOUNT_REF) }
        try {
            awaitChatListCondition {
                subscriptions.first.nextUpdateStarted.isCompleted && controller.items.size == 2
            }
            subscriptions.first.emitRows(listOf(group))

            awaitChatListCondition { subscriptions.first.closed }
            assertEquals(setOf(pinned.groupIdHex, group.groupIdHex), controller.items.map { it.id }.toSet())

            controller.retryLoad()
            awaitChatListCondition { subscriptions.second.nextUpdateStarted.isCompleted }
            assertEquals(setOf(pinned.groupIdHex, group.groupIdHex), controller.items.map { it.id }.toSet())
            assertEquals(2, subscriptions.activeOpenCount.get())
        } finally {
            controller.onCleared()
            subscriptions.closeAll()
            bindScope.cancel()
            shadowOf(Looper.getMainLooper()).idle()
        }
    }

    @Test
    fun authoritativeArchiveLeaveAndDeleteCanRemoveTheChat() {
        val pinned =
            notificationChatListRow().copy(
                groupIdHex = "aa".repeat(32),
                pinned = true,
                pinnedPosition = 0u,
                conversationKind = ChatConversationKindFfi.DIRECT,
            )
        val group = notificationChatListRow().copy(groupIdHex = "bb".repeat(32), pinned = false)
        val removedRows =
            listOf(
                pinned.copy(archived = true),
                pinned.copy(selfMembership = dev.ipf.marmotkit.SelfMembershipFfi.LEFT),
                null,
            )
        removedRows.forEach { authoritative ->
            val subscriptions = DroppedChatSubscriptions(pinned, group)
            subscriptions.pinnedProjection = authoritative
            val appState = chatListTestAppState(testRecoveryDiagnostics(), subscriptions.liveSubscriptions)
            val controller = testChatsController(appState)
            val bindScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
            bindScope.launch { controller.bind(ConversationTimelineTestIds.ACCOUNT_REF) }
            try {
                awaitChatListCondition {
                    subscriptions.first.nextUpdateStarted.isCompleted && controller.items.size == 2
                }
                subscriptions.first.emitRows(listOf(group))

                awaitChatListCondition { controller.items.map { it.id } == listOf(group.groupIdHex) }
                assertEquals(1, subscriptions.activeOpenCount.get())
            } finally {
                controller.onCleared()
                subscriptions.closeAll()
                bindScope.cancel()
                shadowOf(Looper.getMainLooper()).idle()
            }
        }
    }

    /** A rejected stale replay cannot regress the row held by a mounted conversation. */
    @Test
    fun staleChatListReplayKeepsAttachedConversationOnTheAcceptedRow() {
        val fixture = attachedChatListFixture()
        fixture.appState.attachConversationController(fixture.conversation)
        try {
            fixture.chats.publishTestRow(fixture.directRow, ChatListUpdateTriggerFfi.SNAPSHOT_REFRESH)
            assertTrue(fixture.conversation.isDm)
            val replay = advanceThroughNewerGroupRow(fixture)

            assertFalse(fixture.conversation.isDm)
            assertEquals(ChatConversationKindFfi.GROUP, fixture.conversation.latestChatListRow?.conversationKind)

            fixture.chats.publishTestRow(replay.staleRow, ChatListUpdateTriggerFfi.NEW_LAST_MESSAGE)

            assertFalse(fixture.conversation.isDm)
            assertEquals(ChatConversationKindFfi.GROUP, fixture.conversation.latestChatListRow?.conversationKind)
            assertEquals(
                replay.acceptedMessageId,
                fixture.conversation.latestChatListRow
                    ?.lastMessage
                    ?.messageIdHex,
            )
        } finally {
            fixture.appState.detachConversationController(fixture.conversation)
            fixture.conversation.onCleared()
            fixture.chats.onCleared()
        }
    }

    /** Creates the attached controllers and their initial direct-conversation row. */
    private fun attachedChatListFixture(): AttachedChatListFixture {
        val liveSubscriptions =
            ScriptedConversationLiveSubscriptions(
                timelineScripts = emptyList(),
                group = conversationTimelineTestGroup(),
            )
        val appState = conversationTimelineTestAppState(liveSubscriptions.subscriptions)
        val directRow =
            notificationChatListRow().copy(
                conversationKind = ChatConversationKindFfi.DIRECT,
                lastMessage =
                    notifiedMessagePreview().copy(
                        messageIdHex = "10".repeat(32),
                        plaintext = "initial",
                        timelineAt = 10uL,
                    ),
                activitySortAt = 10uL,
                updatedAt = 10uL,
            )
        val conversation =
            ConversationController(
                appState = appState,
                initialGroup = conversationTimelineTestGroup(),
                initialMemberSnapshot = conversationTimelineMemberSnapshot(),
                initialChatListRow = directRow,
            )
        val chats =
            ChatsController(
                appState = appState,
                initialAccountRef = ConversationTimelineTestIds.ACCOUNT_REF,
                memberSnapshotLoader = { _, _ -> emptyList() },
            )
        return AttachedChatListFixture(appState, conversation, chats, directRow)
    }

    /** Accepts a newer group row, then returns the older confirmed row for replay. */
    private fun advanceThroughNewerGroupRow(fixture: AttachedChatListFixture): StaleRowReplay {
        val optimisticId = "optimistic"
        val confirmedId = "ff".repeat(32)
        val incomingId = "0a".repeat(32)
        val optimistic =
            notifiedMessagePreview().copy(
                messageIdHex = optimisticId,
                plaintext = "sent",
                timelineAt = 20uL,
                deliveryState = ChatListMessageDeliveryStateFfi.PENDING,
            )
        assertTrue(fixture.chats.applyOptimisticSentPreview(fixture.directRow.groupIdHex, optimistic))
        fixture.chats.commitOptimisticSentPreview(fixture.directRow.groupIdHex, optimisticId, confirmedId)
        val confirmedDirectRow =
            fixture.directRow.copy(
                lastMessage =
                    optimistic.copy(
                        messageIdHex = confirmedId,
                        deliveryState = ChatListMessageDeliveryStateFfi.DELIVERED,
                    ),
                activitySortAt = 20uL,
                updatedAt = 20uL,
            )
        fixture.chats.publishTestRow(confirmedDirectRow, ChatListUpdateTriggerFfi.NEW_LAST_MESSAGE)
        fixture.chats.publishTestRow(
            confirmedDirectRow.copy(
                conversationKind = ChatConversationKindFfi.GROUP,
                lastMessage =
                    optimistic.copy(
                        messageIdHex = incomingId,
                        plaintext = "incoming",
                        deliveryState = ChatListMessageDeliveryStateFfi.NOT_APPLICABLE,
                    ),
            ),
            ChatListUpdateTriggerFfi.NEW_LAST_MESSAGE,
        )
        return StaleRowReplay(confirmedDirectRow, incomingId)
    }

    /** Publishes one row through the production subscription reducer. */
    private fun ChatsController.publishTestRow(
        row: ChatListRowFfi,
        trigger: ChatListUpdateTriggerFfi,
    ) {
        applyChatListSubscriptionUpdate(
            accountRef = ConversationTimelineTestIds.ACCOUNT_REF,
            update = ChatListSubscriptionUpdateFfi.Row(row = row, trigger = trigger),
        )
    }

    private data class AttachedChatListFixture(
        val appState: WhiteNoiseAppState,
        val conversation: ConversationController,
        val chats: ChatsController,
        val directRow: ChatListRowFfi,
    )

    private data class StaleRowReplay(
        val staleRow: ChatListRowFfi,
        val acceptedMessageId: String,
    )

    /** Reopening terminated local streams does not repeat the bind's one full catch-up. */
    @Test
    fun repeatedStreamTerminationPerformsOnlyOneFullCatchUp() {
        val marmotCalls = AtomicInteger()
        val subscriptions = RestartingChatListSubscriptions()
        val appState =
            chatListTestAppState(
                diagnostics = testRecoveryDiagnostics(),
                liveSubscriptions = subscriptions.liveSubscriptions,
                marmotAccessObserver = { marmotCalls.incrementAndGet() },
            )
        val controller = testChatsController(appState)
        val bindScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        bindScope.launch { controller.bind(ConversationTimelineTestIds.ACCOUNT_REF) }
        try {
            awaitChatListCondition {
                subscriptions.hasStarted(0) && marmotCalls.get() == EXPECTED_BIND_MARMOT_CALLS
            }
            subscriptions.terminate(0)
            controller.retryLoad()

            awaitChatListCondition { subscriptions.hasStarted(1) }
            subscriptions.terminate(1)
            controller.retryLoad()

            awaitChatListCondition { subscriptions.hasStarted(2) }
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500))

            assertEquals(
                "stream reopening must not repeat full catch-up",
                EXPECTED_BIND_MARMOT_CALLS,
                marmotCalls.get(),
            )
        } finally {
            controller.onCleared()
            subscriptions.closeAll()
            bindScope.cancel()
            shadowOf(Looper.getMainLooper()).idle()
        }
    }

    /** An early open failure retains the already-started catch-up for the successful reopen. */
    @Test
    fun earlyOpenFailureRetainsPendingCatchUpUntilReopen() {
        val marmotCalls = AtomicInteger()
        val subscriptions = FailFirstChatListSubscriptions()
        val appState =
            chatListTestAppState(
                diagnostics = testRecoveryDiagnostics(),
                liveSubscriptions = subscriptions.liveSubscriptions,
                marmotAccessObserver = { marmotCalls.incrementAndGet() },
            )
        val controller =
            ChatsController(
                appState = appState,
                initialAccountRef = ConversationTimelineTestIds.ACCOUNT_REF,
                initialLocalSnapshot = emptyLocalSnapshot(),
                memberSnapshotRetryDelay = { Long.MAX_VALUE },
                memberSnapshotLoader = { _, _ -> emptyList() },
            )
        val bindScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        bindScope.launch { controller.bind(ConversationTimelineTestIds.ACCOUNT_REF) }
        try {
            awaitChatListCondition {
                subscriptions.openAttempts.get() == 1 && marmotCalls.get() == EXPECTED_BIND_MARMOT_CALLS
            }
            controller.retryLoad()

            awaitChatListCondition {
                subscriptions.hasStableStreamStarted() &&
                    controller.connectionState.phase == ChatListConnectionPhase.Idle
            }

            assertEquals(
                "the reopen must observe the original catch-up",
                EXPECTED_BIND_MARMOT_CALLS,
                marmotCalls.get(),
            )
            assertEquals(2, subscriptions.openAttempts.get())
        } finally {
            controller.onCleared()
            subscriptions.closeAll()
            bindScope.cancel()
            shadowOf(Looper.getMainLooper()).idle()
        }
    }

    /** Builds a numeric-only diagnostics collector without enabling logcat output. */
    private fun testRecoveryDiagnostics(): NotificationNetworkRecoveryDiagnostics =
        NotificationNetworkRecoveryDiagnostics(
            traceFactory = { null },
            traceRecorder = { _, _, _, _, _, _, _ -> },
        )

    /** Builds an app state whose live chat-list sources are fully controlled by this test. */
    private fun chatListTestAppState(
        diagnostics: NotificationNetworkRecoveryDiagnostics,
        chatList: ChatListWindowHandle,
        chats: ChatsSubscriptionHandle,
    ): WhiteNoiseAppState =
        chatListTestAppState(
            diagnostics = diagnostics,
            liveSubscriptions =
                ChatListLiveSubscriptions(
                    openChatListWindow = { _, view ->
                        if (view == ChatListViewFfi.CHATS) chatList else TerminatingChatListSubscription(view)
                    },
                    openChats = { _, _ -> chats },
                ),
        )

    /** Builds an app state with lifecycle-aware live sources and optional catch-up observation. */
    private fun chatListTestAppState(
        diagnostics: NotificationNetworkRecoveryDiagnostics,
        liveSubscriptions: ChatListLiveSubscriptions,
        marmotAccessObserver: (() -> Unit)? = null,
    ): WhiteNoiseAppState =
        WhiteNoiseAppState(
            context = ApplicationProvider.getApplicationContext<Context>(),
            draftStore = DraftStore(ConversationTimelineTestDraftPersistence()),
            accountIdHexResolver = { ConversationTimelineTestIds.ACCOUNT_ID },
            accounts =
                listOf(
                    AccountSummaryFfi(
                        label = ConversationTimelineTestIds.ACCOUNT_REF,
                        accountIdHex = ConversationTimelineTestIds.ACCOUNT_ID,
                        localSigning = true,
                        externalSigning = false,
                        signedOut = false,
                        running = true,
                    ),
                ),
            activeAccountRef = ConversationTimelineTestIds.ACCOUNT_REF,
            notificationNetworkRecoveryDiagnostics = diagnostics,
            marmotAccessObserver = marmotAccessObserver,
        ).also { state ->
            state.liveSubscriptionOverrides.chatList = liveSubscriptions
        }

    /** Builds the controller used by lifecycle-only subscription tests. */
    private fun testChatsController(appState: WhiteNoiseAppState): ChatsController =
        ChatsController(
            appState = appState,
            initialAccountRef = ConversationTimelineTestIds.ACCOUNT_REF,
            memberSnapshotRetryDelay = { Long.MAX_VALUE },
            memberSnapshotLoader = { _, _ -> emptyList() },
        )

    /** Supplies a rendered empty target projection before live streams open. */
    private fun emptyLocalSnapshot(): AccountSwitchLocalSnapshot =
        AccountSwitchLocalSnapshot(
            accountRef = ConversationTimelineTestIds.ACCOUNT_REF,
            activeAccountIdHex = ConversationTimelineTestIds.ACCOUNT_ID,
            rows = emptyList(),
            groups = emptyList(),
            memberIds = emptyList(),
            profiles = emptyList(),
        )

    private companion object {
        // Binding performs one draft-summary read plus the one full catch-up under test.
        const val EXPECTED_BIND_MARMOT_CALLS = 2
    }
}

/** Advances Robolectric frames and coroutine delays while waiting for a chat-list projection. */
private fun awaitChatListCondition(condition: () -> Boolean) {
    repeat(250) {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(20))
        if (condition()) return
        Thread.sleep(10)
    }
    throw AssertionError("Chat-list condition not met within 5 simulated seconds")
}

/** Controllable chat-list subscription used by the recovery integration test. */
private class ScriptedChatListSubscription(
    private val initialRows: List<ChatListRowFfi>,
) : ChatListWindowHandle {
    constructor(initialRow: ChatListRowFfi) : this(listOf(initialRow))

    private val updates = Channel<ChatListWindowSnapshotFfi>(Channel.UNLIMITED)
    private var sequence = 0uL
    private var current = windowSnapshot(initialRows, sequence)
    val nextUpdateStarted = CompletableDeferred<Unit>()
    @Volatile var closed = false

    /** Returns the local projection present before reconnect. */
    override fun snapshot(): ChatListWindowSnapshotFfi = current

    /** Waits for the test-controlled authoritative recovery replacement. */
    override suspend fun next(): ChatListWindowSnapshotFfi? {
        nextUpdateStarted.complete(Unit)
        return updates.receiveCatching().getOrNull()?.also { current = it }
    }

    /** Commands echo the installed replacement; the recovery test never pages. */
    override suspend fun page(
        sequence: ULong,
        direction: ChatListPageDirectionFfi,
        count: UInt,
    ): ChatListWindowSnapshotFfi = current

    /** Anchor reports echo the installed replacement. */
    override suspend fun setVisibleAnchor(
        sequence: ULong,
        groupIdHex: String,
    ): ChatListWindowSnapshotFfi = current

    /** Return-to-top echoes the installed replacement. */
    override suspend fun returnToTop(sequence: ULong): ChatListWindowSnapshotFfi = current

    /** Delivers one authoritative update without blocking the test thread. */
    fun emit(update: ChatListSubscriptionUpdateFfi) {
        val rows =
            when (update) {
                is ChatListSubscriptionUpdateFfi.Row -> listOf(update.row)
                is ChatListSubscriptionUpdateFfi.Snapshot -> update.rows
                is ChatListSubscriptionUpdateFfi.RemoveRow -> emptyList()
            }
        emitRows(rows)
    }

    fun emitRows(rows: List<ChatListRowFfi>) {
        sequence += 1uL
        check(updates.trySend(windowSnapshot(rows, sequence)).isSuccess)
    }

    /** Ends the scripted stream. */
    override fun close() {
        closed = true
        updates.close()
    }
}

/** Two successive complete windows with a keyed MDK row that can confirm or disprove a removal. */
private class DroppedChatSubscriptions(
    pinned: ChatListRowFfi,
    group: ChatListRowFfi,
) {
    val first = ScriptedChatListSubscription(listOf(pinned, group))
    val second = ScriptedChatListSubscription(listOf(pinned, group))
    val activeOpenCount = AtomicInteger()
    @Volatile var pinnedProjection: ChatListRowFfi? = pinned
    private val groupId = group.groupIdHex
    private val pinnedId = pinned.groupIdHex
    private val otherWindows = CopyOnWriteArrayList<TerminatingChatListSubscription>()
    private val groupStreams = CopyOnWriteArrayList<ScriptedChatsSubscription>()

    val liveSubscriptions =
        ChatListLiveSubscriptions(
            openChatListWindow = { _, view ->
                if (view == ChatListViewFfi.CHATS) {
                    if (activeOpenCount.getAndIncrement() == 0) first else second
                } else {
                    TerminatingChatListSubscription(view).also(otherWindows::add)
                }
            },
            openChats = { _, _ -> ScriptedChatsSubscription().also(groupStreams::add) },
            presentedRowByGroup = { _, id ->
                when (id) {
                    pinnedId -> pinnedProjection?.let(::presentedRow)
                    groupId -> presentedRow(group)
                    else -> null
                }
            },
        )

    fun closeAll() {
        first.close()
        second.close()
        otherWindows.forEach(TerminatingChatListSubscription::close)
        groupStreams.forEach(ScriptedChatsSubscription::close)
    }
}

/** Stable companion group subscription that lives until the controller closes it. */
private class ScriptedChatsSubscription : ChatsSubscriptionHandle {
    private val closed = CompletableDeferred<Unit>()

    /** No group-state row is needed for the named chat-list fixture. */
    override fun snapshot(): List<AppGroupRecordFfi> = emptyList()

    /** Holds the paired stream open until teardown. */
    override suspend fun next(): AppGroupRecordFfi? {
        closed.await()
        return null
    }

    /** Releases the paired stream. */
    override fun close() {
        closed.complete(Unit)
    }
}

/** Factory that exposes each controller reopen as a separately terminable stream. */
private class RestartingChatListSubscriptions {
    private val chatListStreams = CopyOnWriteArrayList<TerminatingChatListSubscription>()
    private val chatStreams = CopyOnWriteArrayList<ScriptedChatsSubscription>()

    val liveSubscriptions =
        ChatListLiveSubscriptions(
            openChatListWindow = { _, view ->
                TerminatingChatListSubscription(view).also { stream ->
                    if (view == ChatListViewFfi.CHATS) chatListStreams.add(stream)
                }
            },
            openChats = { _, _ ->
                ScriptedChatsSubscription().also(chatStreams::add)
            },
        )

    /** Reports whether the numbered active-chats stream reached its consumer loop. */
    fun hasStarted(index: Int): Boolean = chatListStreams.getOrNull(index)?.nextUpdateStarted?.isCompleted == true

    /** Ends the numbered chat-list stream to force a controller reopen. */
    fun terminate(index: Int) {
        checkNotNull(chatListStreams.getOrNull(index)).terminate()
    }

    /** Releases every stream created by this factory. */
    fun closeAll() {
        chatListStreams.forEach(TerminatingChatListSubscription::close)
        chatStreams.forEach(ScriptedChatsSubscription::close)
    }
}

/** Window whose termination is explicitly controlled by its test. */
private class TerminatingChatListSubscription(
    private val view: ChatListViewFfi = ChatListViewFfi.CHATS,
) : ChatListWindowHandle {
    private val terminated = CompletableDeferred<Unit>()
    val nextUpdateStarted = CompletableDeferred<Unit>()

    /** Returns the empty initial replacement for this handle. */
    override fun snapshot(): ChatListWindowSnapshotFfi = windowSnapshot(emptyList(), 0uL, view)

    /** Waits until the test ends this stream normally. */
    override suspend fun next(): ChatListWindowSnapshotFfi? {
        nextUpdateStarted.complete(Unit)
        terminated.await()
        return null
    }

    /** Commands echo the empty replacement. */
    override suspend fun page(
        sequence: ULong,
        direction: ChatListPageDirectionFfi,
        count: UInt,
    ): ChatListWindowSnapshotFfi = snapshot()

    /** Anchor reports echo the empty replacement. */
    override suspend fun setVisibleAnchor(
        sequence: ULong,
        groupIdHex: String,
    ): ChatListWindowSnapshotFfi = snapshot()

    /** Return-to-top echoes the empty replacement. */
    override suspend fun returnToTop(sequence: ULong): ChatListWindowSnapshotFfi = snapshot()

    /** Completes the stream normally. */
    fun terminate() {
        terminated.complete(Unit)
    }

    /** Ends any pending wait during fixture teardown. */
    override fun close() {
        terminate()
    }
}

/** Complete window replacement matching MarmotKit 0.10.0's chat-list window contract. */
private fun windowSnapshot(
    rows: List<ChatListRowFfi>,
    sequence: ULong,
    view: ChatListViewFfi = ChatListViewFfi.CHATS,
) = ChatListWindowSnapshotFfi(
    subscriptionGeneration = "test-generation",
    sequence = sequence,
    view = view,
    rows = rows.map(::presentedRow),
    hasMoreBefore = false,
    hasMoreAfter = false,
    anchor = ChatListAnchorOutcomeFfi.Top,
)

/** Pairs a chat row with deterministic selected title and avatar values. */
private fun presentedRow(row: ChatListRowFfi) =
    PresentedChatRowFfi(
        preview = emptyChatRowPreview(),
        actions = noChatRowActions(),
        row = row,
        avatarAsset = null,
        presentation =
            ConversationPresentationFfi(
                title = PresentationTextFfi.Literal(row.title.ifBlank { "Chat" }),
                avatar = SelectedAvatarFfi.Placeholder(row.groupIdHex, PresentationSourceFfi.GROUP_FALLBACK),
                titleSource = PresentationSourceFfi.GROUP_FALLBACK,
                avatarSource = PresentationSourceFfi.GROUP_FALLBACK,
                peerId = null,
                resolution = PresentationResolutionFfi.FALLBACK,
            ),
    )

/** Live-source fixture that fails its first chat-list open and holds the second open. */
private class FailFirstChatListSubscriptions {
    val openAttempts = AtomicInteger()
    private val stableChatList = TerminatingChatListSubscription()
    private val chatStreams = CopyOnWriteArrayList<ScriptedChatsSubscription>()

    val liveSubscriptions =
        ChatListLiveSubscriptions(
            openChatListWindow = { _, view ->
                if (view == ChatListViewFfi.CHATS && openAttempts.incrementAndGet() == 1) {
                    error("scripted initial open failure")
                }
                if (view == ChatListViewFfi.CHATS) stableChatList else TerminatingChatListSubscription(view)
            },
            openChats = { _, _ ->
                ScriptedChatsSubscription().also(chatStreams::add)
            },
        )

    /** Reports whether the successful reopen reached its consumer loop. */
    fun hasStableStreamStarted(): Boolean = stableChatList.nextUpdateStarted.isCompleted

    /** Releases all successfully opened streams. */
    fun closeAll() {
        stableChatList.close()
        chatStreams.forEach(ScriptedChatsSubscription::close)
    }
}
