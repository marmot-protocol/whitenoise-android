package dev.ipf.whitenoise.android.ui.chats

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTouchInput
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import dev.ipf.marmotkit.AccountSummaryFfi
import dev.ipf.marmotkit.ChatListRowFfi
import dev.ipf.marmotkit.ChatPinStateFfi
import dev.ipf.marmotkit.MarmotInterface
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.AppMarmotRuntime
import dev.ipf.whitenoise.android.state.ChatListLiveSubscriptions
import dev.ipf.whitenoise.android.state.ChatsController
import dev.ipf.whitenoise.android.state.ConversationTimelineTestIds
import dev.ipf.whitenoise.android.state.DraftStore
import dev.ipf.whitenoise.android.state.SwipeAction
import dev.ipf.whitenoise.android.state.SwipeBinding
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.state.chatRow
import dev.ipf.whitenoise.android.state.hasEarlierChats
import dev.ipf.whitenoise.android.state.noChatRowActions
import dev.ipf.whitenoise.android.state.presentedRow
import dev.ipf.whitenoise.android.ui.navigation.MainShell
import dev.ipf.whitenoise.android.ui.navigation.MainShellStateHolder
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import java.lang.reflect.Proxy
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit

/**
 * Invariant gate for #3555: a confirmed explicit pin on the mounted production chat list returns the
 * viewport to its absolute top (first lazy item, zero offset) with the new pin visible, through every
 * pin entry point, exactly once; a failed pin leaves a deep reader where they were.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-w360dp-h640dp-mdpi")
@OptIn(ExperimentalCoroutinesApi::class)
class ChatPinFollowToTopCoverageTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val scope = TestScope()
    private var fixture: PinFollowFixture? = null

    /** Cancels the app's mutation jobs and the controller so no pin completion outlives the test. */
    @After fun close() {
        val model = fixture
        try {
            settle()
        } finally {
            rule.runOnIdle {
                model?.state?.mutationsScope?.cancel()
                model?.controller?.onCleared()
            }
            rule.waitUntil(WAIT_MILLIS) {
                scope.testScheduler.runCurrent()
                model
                    ?.state
                    ?.mutationsScope
                    ?.coroutineContext
                    ?.get(Job)
                    ?.isCompleted != false
            }
            scope.cancel()
        }
    }

    /** Swipe-pinning a deep row lands at the true top with that row shown in the pinned block. */
    @Test fun swipePinReturnsDeepListToTopOnce() {
        val model = mount(SwipeAction.PinUnpin)
        val target = scrollDeep()
        swipe(target)
        awaitProgress { "pin:true:$target" in model.calls }
        assertFollowedToTop(target)
        assertFollowIsConsumed(model, target)
    }

    /** Pinning from the row's context menu follows the same way. */
    @Test fun rowMenuPinReturnsDeepListToTopOnce() {
        val model = mount()
        val target = scrollDeep()
        openMenu(target)
        rule.onNodeWithTag("chat.action.Pin").performClick()
        awaitProgress { "pin:true:$target" in model.calls }
        assertFollowedToTop(target)
        assertFollowIsConsumed(model, target)
    }

    /** A single-row selection pins through the batch path and follows on its confirmed PIN outcome. */
    @Test fun singleSelectionPinReturnsDeepListToTopOnce() {
        val model = mount()
        val target = scrollDeep()
        openMenu(target)
        rule.onNodeWithTag("chat.action.Select").performClick()
        rule.onNodeWithContentDescription(rule.activity.getString(R.string.chat_row_action_pin)).performClick()
        awaitProgress { "pin:true:$target" in model.calls && !model.controller.chatListBatchInProgress }
        assertFollowedToTop(target)
        assertFollowIsConsumed(model, target)
    }

    /** A pin the native layer rejects invents no success, so the deep reader is not moved. */
    @Test fun failedPinLeavesTheDeepViewportInPlace() {
        val model = mount(SwipeAction.PinUnpin)
        model.failPins = true
        val target = scrollDeep()
        swipe(target)
        awaitProgress { "pin-rejected:$target" in model.calls }
        settle()
        assertTrue("a failed pin must not jump", scrollPosition() > 0f)
        rule.onNodeWithTag("chat.row.$target").assertIsDisplayed()
    }

    /** Unpinning a deep row of a long pinned block keeps its established in-place behaviour: no follow. */
    @Test fun unpinDoesNotFollowToTop() {
        val model = mount(SwipeAction.PinUnpin, prePinned = DEEP_INDEX + 6)
        val target = scrollDeep()
        assertTrue(model.rows.getValue(target).pinned)
        swipe(target)
        awaitProgress { "pin:false:$target" in model.calls }
        settle()
        assertTrue("unpin must not return the list to the top", scrollPosition() > 0f)
    }

    /**
     * A pin made while the bound native window is shifted below the head returns the window to its head
     * (no earlier rows remain), and the list settles at its absolute first item with zero offset, with the
     * restored pinned block and the new pin shown. The restored head row differs from the shifted window's
     * first row, so this fails if the final snap runs before the debounced head projection lands.
     */
    @Test fun pinInShiftedWindowRestoresHeadWindowAndSettlesAtZero() {
        val shifted = ChatPinFollowShiftedWindowFixture()
        val mounted = mutableStateOf(true)
        val holder = MainShellStateHolder(shifted.appState, SavedStateHandle())
        val controller =
            holder.chatsController(ConversationTimelineTestIds.ACCOUNT_REF, shifted.appState.runtimeGeneration)
        shifted.appState.swipePreferences.set(SwipeBinding.ChatRight, SwipeAction.PinUnpin)
        shifted.appState.setAppInForeground(true)
        rule.setContent {
            if (mounted.value) WhiteNoiseTheme { MainShell(appState = shifted.appState, stateHolder = holder) }
        }
        try {
            val retained = ChatPinFollowShiftedWindowFixture.ROW_COUNT - ChatPinFollowShiftedWindowFixture.SHIFT
            awaitProgress { controller.items.size == retained && !controller.isLoading }
            rule.mainClock.advanceTimeBy(CHAT_LIST_HEAD_INPUT_GATE_MILLIS + 500L)
            rule.waitForIdle()
            assertTrue("the window must start shifted", controller.hasEarlierChats())
            val target = shifted.initialRowId(ChatPinFollowShiftedWindowFixture.SHIFT + 2)
            awaitInteractive(target)
            swipe(target)
            awaitProgress { "pin:true:$target" in shifted.calls }
            awaitProgress {
                shifted.window.returnedToTop && controller.items.size == ChatPinFollowShiftedWindowFixture.ROW_COUNT
            }
            awaitProgress { scrollPosition() == 0f }
            settle()
            assertFalse("the head window must be installed", controller.hasEarlierChats())
            assertEquals(0f, scrollPosition())
            assertEquals(shifted.initialRowId(0), controller.items.first().id)
            rule.onNodeWithTag("chat.row.${shifted.initialRowId(0)}").assertIsDisplayed()
            rule.onNodeWithTag("chat.row.$target").assertIsDisplayed()
            assertTrue(controller.items.first { it.id == target }.pinned())
        } finally {
            rule.runOnIdle {
                mounted.value = false
                holder.release()
            }
            rule.waitForIdle()
        }
    }

    /** Scrolls programmatically (no user drag) well below the top and returns a fully visible unpinned row. */
    private fun scrollDeep(): String {
        rule.onNodeWithTag("chats.list").performScrollToIndex(DEEP_INDEX)
        settle()
        assertTrue(scrollPosition() > 0f)
        val target = rowId(DEEP_INDEX + 1)
        awaitInteractive(target)
        return target
    }

    /** The list reports its absolute scroll position; zero only at item zero with zero offset. */
    private fun scrollPosition(): Float =
        rule
            .onNodeWithTag("chats.list")
            .fetchSemanticsNode()
            .config[SemanticsProperties.VerticalScrollAxisRange]
            .value()

    /** Waits for the follow to settle at the absolute top with the pinned target displayed. */
    private fun assertFollowedToTop(target: String) {
        awaitProgress { scrollPosition() == 0f }
        settle()
        assertEquals(0f, scrollPosition())
        rule.onNodeWithTag("chat.row.$target").assertIsDisplayed()
        assertTrue(
            fixture!!
                .controller.items
                .first { it.id == target }
                .pinned(),
        )
    }

    /** After the follow, a projection redelivery of the same pin must not pull the reader back up. */
    private fun assertFollowIsConsumed(
        model: PinFollowFixture,
        target: String,
    ) {
        rule.onNodeWithTag("chats.list").performScrollToIndex(DEEP_INDEX)
        settle()
        val deep = scrollPosition()
        assertTrue(deep > 0f)
        rule.runOnIdle { model.controller.applyChatListRow(model.rows.getValue(target)) }
        settle()
        assertEquals("a consumed follow replayed", deep, scrollPosition())
    }

    /** Drags [target]'s row body right, past the swipe threshold, as a finger would. */
    private fun swipe(target: String) {
        rule.onNodeWithTag("chat.row.$target").performTouchInput {
            // Start on the row body, clear of the independently clickable avatar.
            down(Offset(width * 0.35f, height / 2f))
            moveTo(Offset(width * 0.8f, height / 2f), 250)
            up()
        }
        rule.waitForIdle()
    }

    /** Long-presses [target]'s row until its context menu shows, then lifts. */
    private fun openMenu(target: String) {
        val row = rule.onNodeWithTag("chat.row.$target")
        row.performTouchInput {
            down(center)
            advanceEventTime(viewConfiguration.longPressTimeoutMillis + 100)
            moveTo(center)
        }
        rule.onNodeWithTag("chat.menu.$target").assertIsDisplayed()
        row.performTouchInput { up() }
    }

    /** Waits until [target]'s row accepts long presses again after list motion, and is on screen. */
    private fun awaitInteractive(target: String) {
        val row = rule.onNodeWithTag("chat.row.$target")
        awaitProgress { row.fetchSemanticsNode().config.contains(SemanticsActions.OnLongClick) }
        row.assertIsDisplayed()
    }

    /** Mounts the production chat list over a fresh fixture with [action] bound to a right swipe. */
    private fun mount(
        action: SwipeAction = SwipeAction.Off,
        prePinned: Int = 0,
    ): PinFollowFixture {
        val model = PinFollowFixture(scope, prePinned)
        fixture = model
        model.state.swipePreferences.set(SwipeBinding.ChatRight, action)
        rule.setContent {
            WhiteNoiseTheme {
                ChatsScreen(model.state, model.controller, {}, { _, _, _, _ -> })
            }
        }
        settle()
        rule.onNodeWithTag("chat.row.${rowId(0)}").assertIsDisplayed()
        return model
    }

    /** Drives the test scheduler and Android Main until [ready] holds or [WAIT_MILLIS] passes. */
    private fun awaitProgress(ready: () -> Boolean) {
        rule.waitUntil(WAIT_MILLIS) {
            scope.testScheduler.runCurrent()
            ShadowLooper.idleMainLooper(20, TimeUnit.MILLISECONDS)
            scope.testScheduler.runCurrent()
            ready()
        }
    }

    /** Runs pending test-scheduler work and Android Main twice, then waits for Compose to idle. */
    private fun settle() {
        repeat(2) {
            scope.testScheduler.runCurrent()
            ShadowLooper.idleMainLooper(20, TimeUnit.MILLISECONDS)
            rule.waitForIdle()
        }
    }

    private companion object {
        const val WAIT_MILLIS = 10_000L
        const val ROW_COUNT = 40
        const val DEEP_INDEX = 24
    }

    /**
     * A populated account whose native pin command updates the rows every later read returns; the
     * first [prePinned] rows start pinned in list order.
     */
    private class PinFollowFixture(
        scope: TestScope,
        prePinned: Int,
    ) {
        val calls = ConcurrentLinkedQueue<String>()
        val rows = ConcurrentHashMap<String, ChatListRowFfi>()
        private val pinnedOrder = mutableListOf<String>()

        @Volatile var failPins = false
        val state: WhiteNoiseAppState
        val controller: ChatsController

        init {
            repeat(ROW_COUNT) { index ->
                val id = rowId(index)
                rows[id] = chatRow(id).copy(activitySortAt = (ROW_COUNT - index).toULong() * 1_000uL)
                if (index < prePinned) {
                    pinnedOrder += id
                    rows[id] = rows.getValue(id).copy(pinned = true, pinnedPosition = index.toUInt())
                }
            }
            val context = ApplicationProvider.getApplicationContext<Context>()
            state =
                WhiteNoiseAppState(
                    context = context,
                    draftStore = DraftStore.forContext(context),
                    accountIdHexResolver = { ACCOUNT_ID },
                    accounts = listOf(AccountSummaryFfi(ACCOUNT, ACCOUNT_ID, true, false, false, true, false)),
                    activeAccountRef = ACCOUNT,
                    initialMarmotRuntime = AppMarmotRuntime("pin-follow-test", native()),
                    marmotIoDispatcher = StandardTestDispatcher(scope.testScheduler),
                )
            state.liveSubscriptionOverrides.chatList =
                ChatListLiveSubscriptions(
                    openChatListWindow = { _, _ -> error("unused window") },
                    openChats = { _, _ -> error("unused groups") },
                    presentedChatList = { _, _ -> rows.values.map(::presented) },
                    presentedRowByGroup = { _, id -> rows[id.lowercase()]?.let(::presented) },
                )
            controller =
                ChatsController(
                    appState = state,
                    initialAccountRef = ACCOUNT,
                    memberSnapshotLoader = { _, _ -> emptyList() },
                )
            controller.setChatListVisible(false)
            rows.values.forEach(controller::applyChatListRow)
            controller.setChatListVisible(true)
        }

        /** The presented row for [value] with pin, unpin and mark-read allowed. */
        private fun presented(value: ChatListRowFfi) =
            presentedRow(value.groupIdHex).copy(
                row = value,
                actions = noChatRowActions().copy(canPin = true, canUnpin = true, canMarkRead = true),
            )

        /** Native `setChatPinned`: moves the chat to the front of the pin order, or fails when [failPins]. */
        private fun pinCommand(args: Array<out Any?>?): ChatPinStateFfi {
            check(args!![0] == ACCOUNT)
            val id = args[1] as String
            val pinned = args[2] as Boolean
            if (failPins) {
                calls += "pin-rejected:$id"
                error("pin rejected")
            }
            calls += "pin:$pinned:$id"
            synchronized(pinnedOrder) {
                pinnedOrder.remove(id)
                if (pinned) pinnedOrder.add(0, id)
                rows.keys.forEach { key ->
                    val position = pinnedOrder.indexOf(key)
                    rows.computeIfPresent(key) { _, row ->
                        row.copy(pinned = position >= 0, pinnedPosition = position.takeIf { it >= 0 }?.toUInt())
                    }
                }
                return ChatPinStateFfi(pinnedOrder.toList())
            }
        }

        /** The native runtime: pin commands and row reads; any other call fails loudly. */
        private fun native(): MarmotInterface =
            Proxy.newProxyInstance(
                MarmotInterface::class.java.classLoader,
                arrayOf(MarmotInterface::class.java),
            ) { proxy, method, args ->
                when (method.name.substringBefore('-')) {
                    "setChatPinned" -> pinCommand(args)
                    "chatListRow" -> rows[(args!![1] as String).lowercase()]
                    "listMedia" -> emptyList<Any>()
                    "catchUpAccounts" -> Unit
                    "toString" -> "PinFollowNative"
                    "hashCode" -> System.identityHashCode(proxy)
                    "equals" -> proxy === args?.firstOrNull()
                    else -> error("Unexpected pin-follow native call: ${method.name}")
                }
            } as MarmotInterface
    }
}

private const val ACCOUNT = "pin-follow-account"
private val ACCOUNT_ID = "a8".repeat(32)

/** Lowercase 32-hex group id for row [index]; higher indices sort lower in the recent list. */
private fun rowId(index: Int): String = "%032x".format(0xd000 + index)
