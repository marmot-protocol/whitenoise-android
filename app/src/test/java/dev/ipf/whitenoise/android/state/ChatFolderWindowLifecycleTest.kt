package dev.ipf.whitenoise.android.state

import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import dev.ipf.marmotkit.AccountSummaryFfi
import dev.ipf.marmotkit.AppGroupRecordFfi
import dev.ipf.marmotkit.ChatListAnchorOutcomeFfi
import dev.ipf.marmotkit.ChatListPageDirectionFfi
import dev.ipf.marmotkit.ChatListQueryErrorFfi
import dev.ipf.marmotkit.ChatListViewFfi
import dev.ipf.marmotkit.ChatListViewQueryFfi
import dev.ipf.marmotkit.ChatListWindowSnapshotFfi
import dev.ipf.marmotkit.MarmotKitException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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

/** The selected folder's own windows over a real controller bind: they open, retry and switch beside the plain ones. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en")
class ChatFolderWindowLifecycleTest {
    private val pinned = requireNotNull(chatFolderQuery(ChatFolderRule(pinnedOnly = true), emptySet(), emptySet()))
    private val groups = requireNotNull(chatFolderQuery(ChatFolderRule(groupsOnly = true), emptySet(), emptySet()))

    /** A not-ready folder retries on its own, and switching or clearing folders never reopens the plain windows. */
    @Test
    fun folderWindowsRetryAndSwitchWithoutReopeningThePlainWindows() {
        FolderLifecycleFixture(notReadyOpens = 1).use { fixture ->
            fixture.start()
            awaitFolderCondition { fixture.controller.hasLoadedLocalSnapshot }
            assertEquals(3, fixture.plainOpens.get())

            fixture.controller.folderSource.folder.value = pinned
            awaitFolderCondition { fixture.controller.hasFolderWindows() }
            assertEquals(pinned.views.size + 1, fixture.folderOpens.size)
            assertEquals(pinned.views.map(pinned::forView), fixture.folderOpens.takeLast(pinned.views.size))

            val first = fixture.folderHandles.toList()
            fixture.controller.folderSource.folder.value = groups
            awaitFolderCondition { fixture.folderOpens.lastOrNull() == groups.forView(groups.views.last()) }
            awaitFolderCondition { first.all { it.closes.get() == 1 } }
            awaitFolderCondition { fixture.controller.hasFolderWindows() }

            fixture.controller.folderSource.folder.value = null
            awaitFolderCondition { !fixture.controller.hasFolderWindows() }
            assertTrue(fixture.folderHandles.all { it.closes.get() == 1 })
            assertEquals(3, fixture.plainOpens.get())
            assertTrue(fixture.plainHandles.none { it.closes.get() > 0 })
        }
    }

    /** A folder MarmotKit refuses stays on loaded rows and leaves the plain windows untouched. */
    @Test
    fun refusedFolderStaysOnLoadedRows() {
        FolderLifecycleFixture(refuse = true).use { fixture ->
            fixture.start()
            awaitFolderCondition { fixture.controller.hasLoadedLocalSnapshot }
            fixture.controller.folderSource.folder.value = pinned
            awaitFolderCondition { fixture.folderOpens.isNotEmpty() }
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
            assertFalse(fixture.controller.hasFolderWindows())
            assertEquals(1, fixture.folderOpens.size)
            assertTrue(fixture.plainHandles.none { it.closes.get() > 0 })
        }
    }
}

/** One synthetic account whose plain and folder windows are empty and count their opens and closes. */
private class FolderLifecycleFixture(
    private val notReadyOpens: Int = 0,
    private val refuse: Boolean = false,
) : AutoCloseable {
    val plainOpens = AtomicInteger()
    val plainHandles = CopyOnWriteArrayList<LifecycleWindow>()
    val folderOpens = CopyOnWriteArrayList<ChatListViewQueryFfi>()
    val folderHandles = CopyOnWriteArrayList<LifecycleWindow>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val account = "folder-fixture"
    private val subscriptions =
        ChatListLiveSubscriptions(
            openChatListWindow = { _, view ->
                plainOpens.incrementAndGet()
                LifecycleWindow(view).also(plainHandles::add)
            },
            openChats = { _, _ -> LifecycleGroups() },
            openChatListQueryWindow = { _, query ->
                folderOpens += query
                when {
                    refuse -> throw MarmotKitException.InvalidChatListQuery(ChatListQueryErrorFfi.TOO_MANY_NODES)
                    folderOpens.size <= notReadyOpens -> throw MarmotKitException.ChatPresentationNotReady()
                    else -> LifecycleWindow(query.view).also(folderHandles::add)
                }
            },
        )
    private val appState =
        WhiteNoiseAppState(
            context = ApplicationProvider.getApplicationContext(),
            draftStore = DraftStore(ConversationTimelineTestDraftPersistence()),
            accountIdHexResolver = { "folder-fixture-id" },
            accounts = listOf(AccountSummaryFfi(account, "folder-fixture-id", true, false, false, true, false)),
            activeAccountRef = account,
        ).also { it.liveSubscriptionOverrides.chatList = subscriptions }
    val controller = ChatsController(appState, account, { Long.MAX_VALUE }) { _, _ -> emptyList() }

    /** Runs the production bind. */
    fun start() {
        scope.launch { controller.bind(account) }
    }

    /** Clears the controller and its scope. */
    override fun close() {
        controller.onCleared()
        scope.cancel()
    }
}

/** An empty, complete window that stays live until closed, and counts its closes. */
private class LifecycleWindow(
    view: ChatListViewFfi,
) : ChatListWindowHandle {
    val closes = AtomicInteger()
    private val ended = CompletableDeferred<Unit>()
    private val frame =
        ChatListWindowSnapshotFfi(
            subscriptionGeneration = "folder-fixture",
            sequence = 0uL,
            view = view,
            rows = emptyList(),
            hasMoreBefore = false,
            hasMoreAfter = false,
            anchor = ChatListAnchorOutcomeFfi.Top,
        )

    /** The complete empty replacement. */
    override fun snapshot(): ChatListWindowSnapshotFfi = frame

    /** Stays live until closed. */
    override suspend fun next(): ChatListWindowSnapshotFfi? {
        ended.await()
        return null
    }

    /** Unused, keeps the empty replacement. */
    override suspend fun page(
        sequence: ULong,
        direction: ChatListPageDirectionFfi,
        count: UInt,
    ) = frame

    /** Unused, keeps the empty replacement. */
    override suspend fun setVisibleAnchor(
        sequence: ULong,
        groupIdHex: String,
    ) = frame

    /** Unused, keeps the empty replacement. */
    override suspend fun returnToTop(sequence: ULong) = frame

    /** Counts the release and ends the stream. */
    override fun close() {
        closes.incrementAndGet()
        ended.complete(Unit)
    }
}

/** An empty group stream that stays live until closed. */
private class LifecycleGroups : ChatsSubscriptionHandle {
    private val ended = CompletableDeferred<Unit>()

    /** No groups. */
    override fun snapshot(): List<AppGroupRecordFfi> = emptyList()

    /** Stays live until closed. */
    override suspend fun next(): AppGroupRecordFfi? {
        ended.await()
        return null
    }

    /** Ends the stream. */
    override fun close() {
        ended.complete(Unit)
    }
}

/** Advances Android's main dispatcher, retry delays included, until [condition] holds. */
private fun awaitFolderCondition(condition: () -> Boolean) {
    repeat(250) {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50))
        if (condition()) return
        Thread.sleep(10)
    }
    throw AssertionError("Folder window condition did not hold")
}
