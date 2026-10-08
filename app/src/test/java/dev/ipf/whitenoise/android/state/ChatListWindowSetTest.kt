package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.ChatConversationKindFfi
import dev.ipf.marmotkit.ChatListAnchorOutcomeFfi
import dev.ipf.marmotkit.ChatListPageDirectionFfi
import dev.ipf.marmotkit.ChatListRowFfi
import dev.ipf.marmotkit.ChatListViewFfi
import dev.ipf.marmotkit.ChatListWindowSnapshotFfi
import dev.ipf.marmotkit.ConversationPresentationFfi
import dev.ipf.marmotkit.GroupLifecycleStateFfi
import dev.ipf.marmotkit.MarmotKitException
import dev.ipf.marmotkit.PresentationResolutionFfi
import dev.ipf.marmotkit.PresentationSourceFfi
import dev.ipf.marmotkit.PresentationTextFfi
import dev.ipf.marmotkit.PresentedChatRowFfi
import dev.ipf.marmotkit.SelectedAvatarFfi
import dev.ipf.marmotkit.SelfMembershipFfi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ChatListWindowSetTest {
    /** Rows from every opened view are merged in view order and a newer replacement swaps only its own view. */
    @Test
    fun mergesViewsAndReplacesPerView() =
        runBlocking {
            val handles = CHAT_LIST_WINDOW_VIEWS.associateWith { view -> FakeWindow(view, rows = listOf("$view-a")) }
            val windows = ChatListWindowSet.open("acct") { _, view -> handles.getValue(view) }
            assertEquals(listOf("CHATS-a", "ARCHIVED-a", "LEFT-a"), windows.rows.map { it.row.groupIdHex })

            val replaced = mutableListOf<ChatListViewFfi>()
            val receiver = launch { windows.receive { view, _ -> replaced += view } }
            handles.getValue(ChatListViewFfi.ARCHIVED).emit(sequence = 1uL, rows = listOf("ARCHIVED-b"))
            awaitUntil { replaced.isNotEmpty() }
            assertEquals(listOf("CHATS-a", "ARCHIVED-b", "LEFT-a"), windows.rows.map { it.row.groupIdHex })
            assertEquals(listOf(ChatListViewFfi.ARCHIVED), replaced)
            handles.values.forEach(FakeWindow::close)
            receiver.join()
        }

    /** An equal or older sequence is a duplicate echo and is never installed. */
    @Test
    fun ignoresDuplicateSequences() =
        runBlocking {
            val handles = CHAT_LIST_WINDOW_VIEWS.associateWith { view -> FakeWindow(view, rows = listOf("$view-a")) }
            val windows = ChatListWindowSet.open("acct") { _, view -> handles.getValue(view) }
            var replacements = 0
            val receiver = launch { windows.receive { _, _ -> replacements += 1 } }
            val chats = handles.getValue(ChatListViewFfi.CHATS)
            chats.emit(sequence = 2uL, rows = listOf("CHATS-b"))
            chats.emit(sequence = 2uL, rows = listOf("CHATS-stale"))
            chats.emit(sequence = 1uL, rows = listOf("CHATS-older"))
            awaitUntil { replacements >= 1 }
            delay(50)
            assertEquals(1, replacements)
            assertEquals(
                "CHATS-b",
                windows
                    .installed(ChatListViewFfi.CHATS)
                    ?.rows
                    ?.single()
                    ?.row
                    ?.groupIdHex,
            )
            handles.values.forEach(FakeWindow::close)
            receiver.join()
        }

    /** A foreign generation ends the receive loop so the controller reopens every window. */
    @Test
    fun foreignGenerationEndsReceive() =
        runBlocking {
            val handles = CHAT_LIST_WINDOW_VIEWS.associateWith { view -> FakeWindow(view, rows = emptyList()) }
            val windows = ChatListWindowSet.open("acct") { _, view -> handles.getValue(view) }
            var replacements = 0
            val receiver = launch { windows.receive { _, _ -> replacements += 1 } }
            // Every view must be waiting inside next() before the foreign generation is queued: otherwise a
            // sibling can be cancelled before it ever starts receiving, and the assertion below would pass or
            // fail on coroutine start order rather than on the contract.
            handles.values.forEach { handle -> awaitUntil { handle.awaitingNext } }
            handles.getValue(ChatListViewFfi.LEFT).emit(sequence = 5uL, rows = emptyList(), generation = "other")
            receiver.join()
            assertEquals(0, replacements)
            // The view that saw the foreign generation returned on its own; its siblings were cancelled.
            val siblings = handles.filterKeys { it != ChatListViewFfi.LEFT }.values
            assertTrue(siblings.all { it.cancelledNext })
        }

    /** Paging is gated on MDK's has-more flag, and a stale command is dropped instead of retried. */
    @Test
    fun pagesOnlyWhenMoreRowsAreRetainedAndDropsStaleCommands() =
        runBlocking {
            val chats = FakeWindow(ChatListViewFfi.CHATS, rows = listOf("CHATS-a"), hasMoreAfter = true)
            val handles =
                CHAT_LIST_WINDOW_VIEWS.associateWith { view ->
                    if (view == ChatListViewFfi.CHATS) chats else FakeWindow(view)
                }
            val windows = ChatListWindowSet.open("acct") { _, view -> handles.getValue(view) }

            assertNull(windows.pageForward(ChatListViewFfi.ARCHIVED))
            val paged = windows.pageForward(ChatListViewFfi.CHATS)
            assertEquals(listOf(0uL to ChatListPageDirectionFfi.FORWARD), chats.pageCalls)
            assertEquals(listOf("CHATS-a", "CHATS-page"), paged?.rows?.map { it.row.groupIdHex })

            chats.failNextCommandWith = MarmotKitException.ChatWindowStale()
            assertNull(windows.setVisibleAnchor(ChatListViewFfi.CHATS, "CHATS-a"))
            assertEquals(1uL, windows.installed(ChatListViewFfi.CHATS)?.sequence)
        }

    /**
     * A forward page answered with the same rows parks forward demand (#2926): the next request issues no
     * command, a replacement that only refreshes the rows keeps the park, and the anchor report the page
     * was missing completes it, with the page's rows as the report's result.
     */
    @Test
    fun noProgressForwardPageParksDemandUntilTheAnchorReportCompletesIt() =
        runBlocking {
            val chats = FakeWindow(ChatListViewFfi.CHATS, rows = listOf("CHATS-a"), hasMoreAfter = true)
            chats.forwardPageAppends = false
            val handles =
                CHAT_LIST_WINDOW_VIEWS.associateWith { view ->
                    if (view == ChatListViewFfi.CHATS) chats else FakeWindow(view)
                }
            val windows = ChatListWindowSet.open("acct") { _, view -> handles.getValue(view) }
            var replacements = 0
            val receiver = launch { windows.receive { _, _ -> replacements += 1 } }

            val noProgress = windows.pageForward(ChatListViewFfi.CHATS)
            assertEquals(listOf("CHATS-a"), noProgress?.rows?.map { it.row.groupIdHex })
            assertTrue(windows.isForwardStalled(ChatListViewFfi.CHATS))

            assertNull(windows.pageForward(ChatListViewFfi.CHATS))
            assertEquals(1, chats.pageCalls.size)

            chats.emit(sequence = 2uL, rows = listOf("CHATS-a"))
            awaitUntil { replacements >= 1 }
            assertTrue(windows.isForwardStalled(ChatListViewFfi.CHATS))
            assertNull(windows.pageForward(ChatListViewFfi.CHATS))
            assertEquals(1, chats.pageCalls.size)

            chats.forwardPageAppends = true
            val completed = windows.setVisibleAnchor(ChatListViewFfi.CHATS, "CHATS-a")
            assertEquals(listOf("CHATS-a", "CHATS-page"), completed?.rows?.map { it.row.groupIdHex })
            assertEquals(4uL, completed?.sequence)
            assertEquals(2, chats.pageCalls.size)
            assertFalse(windows.isForwardStalled(ChatListViewFfi.CHATS))
            handles.values.forEach(FakeWindow::close)
            receiver.join()
        }

    /**
     * MDK publishes a command's frame to the stream before replying, so the receive loop may install it
     * first. The anchor report is still a success then, and the parked page it completes still lands.
     */
    @Test
    fun anchorReportCompletesTheParkedPageWhenTheStreamDeliveredItsFrameFirst() =
        runBlocking {
            val chats = FakeWindow(ChatListViewFfi.CHATS, rows = listOf("CHATS-a"), hasMoreAfter = true)
            chats.forwardPageAppends = false
            chats.echoAnswersToStream = true
            val handles =
                CHAT_LIST_WINDOW_VIEWS.associateWith { view ->
                    if (view == ChatListViewFfi.CHATS) chats else FakeWindow(view)
                }
            val windows = ChatListWindowSet.open("acct") { _, view -> handles.getValue(view) }
            val replaced = mutableListOf<ULong>()
            val receiver = launch { windows.receive { _, update -> replaced += update.sequence } }

            windows.pageForward(ChatListViewFfi.CHATS)
            assertTrue(windows.isForwardStalled(ChatListViewFfi.CHATS))

            chats.forwardPageAppends = true
            val completed = windows.setVisibleAnchor(ChatListViewFfi.CHATS, "CHATS-a")
            assertEquals(listOf("CHATS-a", "CHATS-page"), completed?.rows?.map { it.row.groupIdHex })
            assertEquals(listOf(2uL), replaced)
            assertFalse(windows.isForwardStalled(ChatListViewFfi.CHATS))
            handles.values.forEach(FakeWindow::close)
            receiver.join()
        }

    /** Each anchor report completes at most one page; one that again makes no progress parks demand anew. */
    @Test
    fun anchorReportCompletesOnePageAndParksAgainWithoutProgress() =
        runBlocking {
            val chats = FakeWindow(ChatListViewFfi.CHATS, rows = listOf("CHATS-a"), hasMoreAfter = true)
            chats.forwardPageAppends = false
            val handles =
                CHAT_LIST_WINDOW_VIEWS.associateWith { view ->
                    if (view == ChatListViewFfi.CHATS) chats else FakeWindow(view)
                }
            val windows = ChatListWindowSet.open("acct") { _, view -> handles.getValue(view) }

            windows.pageForward(ChatListViewFfi.CHATS)
            assertEquals(3uL, windows.setVisibleAnchor(ChatListViewFfi.CHATS, "CHATS-a")?.sequence)
            assertEquals(2, chats.pageCalls.size)
            assertTrue(windows.isForwardStalled(ChatListViewFfi.CHATS))
            assertNull(windows.pageForward(ChatListViewFfi.CHATS))
            assertEquals(2, chats.pageCalls.size)

            // Returning to the top moves the window, so forward demand is live again without a page.
            assertEquals(4uL, windows.returnToTop(ChatListViewFfi.CHATS)?.sequence)
            assertFalse(windows.isForwardStalled(ChatListViewFfi.CHATS))
            assertEquals(2, chats.pageCalls.size)
        }

    /** A shifted capped window can page back to rows before its retained front. */
    @Test
    fun pagesBackwardOnlyWhenRowsExistBeforeTheWindow() =
        runBlocking {
            val chats =
                FakeWindow(
                    ChatListViewFfi.CHATS,
                    rows = listOf("CHATS-middle"),
                    hasMoreBefore = true,
                )
            val handles =
                CHAT_LIST_WINDOW_VIEWS.associateWith { view ->
                    if (view == ChatListViewFfi.CHATS) chats else FakeWindow(view)
                }
            val windows = ChatListWindowSet.open("acct") { _, view -> handles.getValue(view) }

            assertNull(windows.pageBackward(ChatListViewFfi.ARCHIVED))
            val paged = windows.pageBackward(ChatListViewFfi.CHATS)

            assertEquals(listOf(0uL to ChatListPageDirectionFfi.BACKWARD), chats.pageCalls)
            assertEquals(listOf("CHATS-page", "CHATS-middle"), paged?.rows?.map { it.row.groupIdHex })
        }
}

/** How long an echoing fake lets the stream copy of an answer lead the reply. */
private const val ECHO_LEAD_MS = 50L

/** Polls a condition driven by the IO-dispatched receive loops, failing after five seconds. */
private suspend fun awaitUntil(condition: () -> Boolean) {
    withTimeout(5_000) {
        while (!condition()) delay(5)
    }
}

/** Scripted window: the test controls replacements, command results and termination. */
private class FakeWindow(
    private val view: ChatListViewFfi,
    rows: List<String> = emptyList(),
    private val hasMoreBefore: Boolean = false,
    private val hasMoreAfter: Boolean = false,
) : ChatListWindowHandle {
    private val updates = Channel<ChatListWindowSnapshotFfi>(Channel.UNLIMITED)
    private var current = snapshot(0uL, rows)
    val pageCalls = mutableListOf<Pair<ULong, ChatListPageDirectionFfi>>()
    var failNextCommandWith: Throwable? = null

    /** False scripts a capped window that cannot move forward: the page replies with the same rows. */
    var forwardPageAppends = true
    var closed = false
    var cancelledNext = false

    /** True once this view's receive loop is parked inside [next]; lets a test wait for all views to arrive. */
    @Volatile
    var awaitingNext = false
        private set

    override fun snapshot(): ChatListWindowSnapshotFfi = current

    override suspend fun next(): ChatListWindowSnapshotFfi? =
        try {
            // Inside the try before suspending: a cancellation that lands while this coroutine is running
            // still surfaces at the receive below, so the catch records it either way.
            awaitingNext = true
            updates.receiveCatching().getOrNull()
        } catch (cancel: kotlinx.coroutines.CancellationException) {
            cancelledNext = true
            throw cancel
        } finally {
            awaitingNext = false
        }

    /** Answers a page with one appended row, or with the same rows when [forwardPageAppends] is off. */
    override suspend fun page(
        sequence: ULong,
        direction: ChatListPageDirectionFfi,
        count: UInt,
    ): ChatListWindowSnapshotFfi {
        throwScriptedFailure()
        pageCalls += sequence to direction
        val ids = current.rows.map { it.row.groupIdHex }
        val rows =
            when {
                direction == ChatListPageDirectionFfi.BACKWARD -> listOf("$view-page") + ids
                forwardPageAppends -> ids + "$view-page"
                else -> ids
            }
        current = snapshot(sequence + 1uL, rows)
        return current
    }

    /** True publishes each command's answer to the stream before replying, the way the MDK actor does. */
    var echoAnswersToStream = false

    /** Anchors without changing the rows; with [echoAnswersToStream] the frame reaches the stream first. */
    override suspend fun setVisibleAnchor(
        sequence: ULong,
        groupIdHex: String,
    ): ChatListWindowSnapshotFfi {
        throwScriptedFailure()
        current = snapshot(sequence + 1uL, current.rows.map { it.row.groupIdHex })
        if (echoAnswersToStream) {
            check(updates.trySend(current).isSuccess)
            delay(ECHO_LEAD_MS)
        }
        return current
    }

    override suspend fun returnToTop(sequence: ULong): ChatListWindowSnapshotFfi = setVisibleAnchor(sequence, "")

    private fun throwScriptedFailure() {
        val failure = failNextCommandWith ?: return
        failNextCommandWith = null
        throw failure
    }

    override fun close() {
        closed = true
        updates.close()
    }

    fun emit(
        sequence: ULong,
        rows: List<String>,
        generation: String = "gen",
    ) {
        check(updates.trySend(snapshot(sequence, rows, generation)).isSuccess)
    }

    private fun snapshot(
        sequence: ULong,
        rows: List<String>,
        generation: String = "gen",
    ) = ChatListWindowSnapshotFfi(
        subscriptionGeneration = generation,
        sequence = sequence,
        view = view,
        rows = rows.map(::presentedRow),
        hasMoreBefore = hasMoreBefore,
        hasMoreAfter = hasMoreAfter,
        anchor = ChatListAnchorOutcomeFfi.Top,
    )
}

/** Creates a group-fallback presentation without a native draft version for window tests. */
internal fun presentedRow(groupIdHex: String) =
    PresentedChatRowFfi(
        draftVersion = null,
        preview = emptyChatRowPreview(),
        actions = noChatRowActions(),
        row = chatRow(groupIdHex),
        avatarAsset = null,
        presentation =
            ConversationPresentationFfi(
                title = PresentationTextFfi.Literal(groupIdHex),
                avatar = SelectedAvatarFfi.Placeholder(groupIdHex, PresentationSourceFfi.GROUP_FALLBACK),
                titleSource = PresentationSourceFfi.GROUP_FALLBACK,
                avatarSource = PresentationSourceFfi.GROUP_FALLBACK,
                peerId = null,
                resolution = PresentationResolutionFfi.FALLBACK,
            ),
    )

internal fun chatRow(groupIdHex: String) =
    ChatListRowFfi(
        groupIdHex = groupIdHex,
        pinned = false,
        pinnedPosition = null,
        archived = false,
        pendingConfirmation = false,
        lifecycleState = GroupLifecycleStateFfi.STABLE,
        disbanding = false,
        disbandRequest = null,
        title = groupIdHex,
        groupName = groupIdHex,
        avatarUrl = null,
        avatar = null,
        lastMessage = null,
        unreadCount = 0uL,
        hasUnread = false,
        manuallyMarkedUnread = false,
        unreadMentionCount = 0uL,
        unreadMention = false,
        firstUnreadMessageIdHex = null,
        lastReadMessageIdHex = null,
        lastReadTimelineAt = null,
        conversationCreatedAt = 1uL,
        activitySortAt = 1uL,
        updatedAt = 1uL,
        selfMembership = SelfMembershipFfi.MEMBER,
        conversationKind = ChatConversationKindFfi.GROUP,
        muted = false,
        mutedUntilMs = null,
        leaveRequestPending = false,
        leaveRequestedAtMs = null,
    )
