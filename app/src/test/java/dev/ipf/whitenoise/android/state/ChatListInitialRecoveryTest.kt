package dev.ipf.whitenoise.android.state

import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import dev.ipf.marmotkit.AccountSummaryFfi
import dev.ipf.marmotkit.AppGroupRecordFfi
import dev.ipf.marmotkit.ChatListAnchorOutcomeFfi
import dev.ipf.marmotkit.ChatListPageDirectionFfi
import dev.ipf.marmotkit.ChatListViewFfi
import dev.ipf.marmotkit.ChatListWindowSnapshotFfi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Real controller binds held at initial native boundaries, including non-cooperative synchronous reads. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en")
class ChatListInitialRecoveryTest {
    /** Every initial boundary exposes recovery; repeated Retry retains one attempt and late empty success. */
    @Test
    fun blockedInitialReadsRemainSingleOwnedAndEventuallyPublish() {
        StartupBoundary.entries.forEach { boundary ->
            InitialRecoveryFixture(boundary).use { fixture ->
                fixture.start()
                awaitInitialRecoveryCondition { fixture.entered.count == 0L }
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(CHAT_LIST_ACTIONABLE_START_MILLIS))
                assertNotNull("$boundary must expose recovery", fixture.controller.error)
                assertFalse(fixture.controller.hasLoadedLocalSnapshot)
                val opens = fixture.windowOpens.get() to fixture.chatsOpens.get()

                repeat(5) { fixture.controller.retryLoad() }
                assertNull(fixture.controller.error)
                assertTrue(fixture.controller.isLoading)
                assertEquals(0L, fixture.controller.retryGeneration)
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(CHAT_LIST_ACTIONABLE_START_MILLIS))
                assertNotNull(fixture.controller.error)
                assertEquals(opens, fixture.windowOpens.get() to fixture.chatsOpens.get())

                fixture.release.countDown()
                awaitInitialRecoveryCondition { fixture.controller.hasLoadedLocalSnapshot }
                assertNull(fixture.controller.error)
                assertFalse(fixture.controller.isLoading)
                assertTrue(fixture.controller.items.isEmpty())
                assertEquals(3, fixture.windowOpens.get())
                assertEquals(1, fixture.chatsOpens.get())
            }
        }
    }

    /** Retiring a blocked read prevents its late success and closes partially acquired handles once. */
    @Test
    fun retiredInitialReadCannotPublishAndEventuallyClosesItsHandles() {
        listOf(StartupBoundary.WINDOW_SNAPSHOT, StartupBoundary.GROUP_SNAPSHOT).forEach { boundary ->
            InitialRecoveryFixture(boundary).use { fixture ->
                fixture.start()
                awaitInitialRecoveryCondition { fixture.entered.count == 0L }
                fixture.controller.onCleared()
                fixture.release.countDown()
                awaitInitialRecoveryCondition { fixture.handles.all { it.get() == 1 } }
                assertFalse(fixture.controller.hasLoadedLocalSnapshot)
                assertNull(fixture.controller.error)
                assertNull(fixture.controller.boundAccountRef)
                assertTrue(fixture.controller.items.isEmpty())
            }
        }
    }
}

private enum class StartupBoundary {
    WINDOW_OPEN,
    WINDOW_SNAPSHOT,
    CHATS_OPEN,
    GROUP_SNAPSHOT,
}

/** Owns a synthetic account and holds exactly one selected native boundary until released. */
private class InitialRecoveryFixture(private val boundary: StartupBoundary) : AutoCloseable {
    val entered = CountDownLatch(1)
    val release = CountDownLatch(1)
    val windowOpens = AtomicInteger()
    val chatsOpens = AtomicInteger()
    val handles = CopyOnWriteArrayList<AtomicInteger>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val account = "startup-fixture"
    private val subscriptions =
        ChatListLiveSubscriptions(
            openChatListWindow = { _, view ->
                windowOpens.incrementAndGet()
                if (view == ChatListViewFfi.CHATS) suspendAt(StartupBoundary.WINDOW_OPEN)
                InitialRecoveryWindow(view, handles) {
                    if (view == ChatListViewFfi.CHATS) blockAt(StartupBoundary.WINDOW_SNAPSHOT)
                }
            },
            openChats = { _, _ ->
                chatsOpens.incrementAndGet()
                suspendAt(StartupBoundary.CHATS_OPEN)
                InitialRecoveryGroups(handles) { blockAt(StartupBoundary.GROUP_SNAPSHOT) }
            },
        )
    private val appState =
        WhiteNoiseAppState(
            context = ApplicationProvider.getApplicationContext(),
            draftStore = DraftStore(ConversationTimelineTestDraftPersistence()),
            accountIdHexResolver = { "startup-fixture-id" },
            accounts = listOf(AccountSummaryFfi(account, "startup-fixture-id", true, false, false, true)),
            activeAccountRef = account,
        ).also { it.liveSubscriptionOverrides.chatList = subscriptions }
    val controller = ChatsController(appState, account, { Long.MAX_VALUE }) { _, _ -> emptyList() }

    /** Runs the production bind rather than publishing test-only loading/error state. */
    fun start() {
        scope.launch { controller.bind(account) }
    }

    /** Async native opening suspends off the presentation path. */
    private suspend fun suspendAt(target: StartupBoundary) {
        if (boundary != target) return
        kotlinx.coroutines.withContext(Dispatchers.IO) { blockAt(target) }
    }

    /** Models a native synchronous read that coroutine cancellation cannot interrupt. */
    private fun blockAt(target: StartupBoundary) {
        if (boundary != target) return
        entered.countDown()
        check(release.await(10, TimeUnit.SECONDS)) { "fixture boundary was not released" }
    }

    /** Releases blocking test work before cancelling; no native handle is abandoned. */
    override fun close() {
        release.countDown()
        controller.onCleared()
        scope.cancel()
        awaitInitialRecoveryCondition { handles.all { it.get() == 1 } }
    }
}

/** Supplies authoritative empty windows after the fixture's synchronous snapshot boundary. */
private class InitialRecoveryWindow(
    view: ChatListViewFfi,
    handles: MutableList<AtomicInteger>,
    private val beforeSnapshot: () -> Unit,
) : ChatListWindowHandle {
    private val closes = AtomicInteger().also(handles::add)
    private val ended = CompletableDeferred<Unit>()
    private val frame =
        ChatListWindowSnapshotFfi(
            subscriptionGeneration = "startup-fixture",
            sequence = 0uL,
            view = view,
            rows = emptyList(),
            hasMoreBefore = false,
            hasMoreAfter = false,
            anchor = ChatListAnchorOutcomeFfi.Top,
        )

    /** Returns one complete empty replacement after the selected blocking read. */
    override fun snapshot(): ChatListWindowSnapshotFfi {
        beforeSnapshot()
        return frame
    }

    /** Keeps the successful initial attempt alive until its owner cancels it. */
    override suspend fun next(): ChatListWindowSnapshotFfi? {
        ended.await()
        return null
    }

    /** Unused cursor commands preserve the complete empty replacement. */
    override suspend fun page(
        sequence: ULong,
        direction: ChatListPageDirectionFfi,
        count: UInt,
    ) = frame

    /** Unused anchor commands preserve the complete empty replacement. */
    override suspend fun setVisibleAnchor(sequence: ULong, groupIdHex: String) = frame

    /** Unused top commands preserve the complete empty replacement. */
    override suspend fun returnToTop(sequence: ULong) = frame

    /** Counts every release so duplicate cleanup cannot pass unnoticed. */
    override fun close() {
        closes.incrementAndGet()
        ended.complete(Unit)
    }
}

/** Holds the matching group snapshot without requiring a running native runtime. */
private class InitialRecoveryGroups(
    handles: MutableList<AtomicInteger>,
    private val beforeSnapshot: () -> Unit,
) : ChatsSubscriptionHandle {
    private val closes = AtomicInteger().also(handles::add)
    private val ended = CompletableDeferred<Unit>()

    /** Returns the empty group state only after the test releases its synchronous read. */
    override fun snapshot(): List<AppGroupRecordFfi> {
        beforeSnapshot()
        return emptyList()
    }

    /** Retains the paired stream for the successful bind. */
    override suspend fun next(): AppGroupRecordFfi? {
        ended.await()
        return null
    }

    /** Counts every paired-handle release. */
    override fun close() {
        closes.incrementAndGet()
        ended.complete(Unit)
    }
}

/** Advances Android's main dispatcher while independent IO fixture work finishes. */
private fun awaitInitialRecoveryCondition(condition: () -> Boolean) {
    repeat(250) {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(20))
        if (condition()) return
        Thread.sleep(10)
    }
    throw AssertionError("Initial recovery condition did not complete")
}
