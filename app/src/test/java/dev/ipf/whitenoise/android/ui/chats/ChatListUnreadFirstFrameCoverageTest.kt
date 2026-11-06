package dev.ipf.whitenoise.android.ui.chats

import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.chatRow
import dev.ipf.whitenoise.android.ui.common.FULL_SCREEN_LOADING_TEST_TAG
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.TestScope
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import java.util.concurrent.TimeUnit

/**
 * Invariant gate for #3586: the mounted chat list prepares its complete unread snapshot, so opening the
 * filter shows every folder-scoped unread chat, including one beyond the retained window, on the very
 * next frame. Every later native read is held, so a result that waited for activation could not appear.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en")
@OptIn(ExperimentalCoroutinesApi::class)
class ChatListUnreadFirstFrameCoverageTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val scope = TestScope()
    private val mounted = mutableStateOf(true)
    private val holdReads = CompletableDeferred<Unit>()
    private val search = mutableStateOf(GlobalSearchState())
    private var fixture: OffWindowActionFixture? = null

    /** Releases held reads and disposes the screen before the controller and native scope. */
    @After fun close() {
        holdReads.complete(Unit)
        rule.mainClock.autoAdvance = true
        rule.runOnIdle { mounted.value = false }
        rule.waitForIdle()
        rule.runOnIdle { fixture?.controller?.onCleared() }
        scope.cancel()
    }

    /** The accessible action opens a complete result on its first frame with no filter loading state. */
    @Test fun accessibleActionShowsPreparedOffWindowUnreadOnTheFirstFrame() {
        val model = prepared()
        val reads = model.snapshotReads
        openOnOneFrame()
        assertFirstFrameResult(model, readsAtActivation = reads)
    }

    /** Dismissing and re-entering keeps the prepared result, and repeated openings share one reconciling read. */
    @Test fun dismissalAndReentryReuseThePreparedResult() {
        val model = prepared()
        val reads = model.snapshotReads
        openOnOneFrame()
        assertFirstFrameResult(model, reads)
        openOnOneFrame()
        rule.mainClock.autoAdvance = true
        rule.waitForIdle()
        rule.onNodeWithTag("chat.row.$ACTION_TARGET").assertDoesNotExist()
        openOnOneFrame()
        assertFirstFrameResult(model, reads)
    }

    /** A search round trip keeps the prepared result, so Unread still opens complete on its first frame. */
    @Test fun aSearchRoundTripKeepsThePreparedResult() {
        val model = prepared()
        rule.runOnIdle { search.value = search.value.copy(isOpen = true) }
        rule.waitForIdle()
        rule.runOnIdle { search.value = search.value.copy(isOpen = false) }
        rule.waitForIdle()
        scope.testScheduler.runCurrent()
        // Closing search restarts preparation with one held read. The filter must not wait for it.
        val reads = model.snapshotReads
        openOnOneFrame()
        assertFirstFrameResult(model, reads)
    }

    /**
     * A chat that arrives after preparation joins the window, which overrides the snapshot. The filter
     * still opens complete on its first frame, with the new chat and the off-window one, and no loading.
     */
    @Test fun aChatAddedAfterPreparationOpensWithoutLoading() {
        val model = prepared()
        rule.runOnIdle {
            model.controller.applyChatListRow(chatRow(ARRIVED_CHAT).copy(manuallyMarkedUnread = true))
        }
        rule.waitUntil {
            scope.testScheduler.runCurrent()
            ShadowLooper.idleMainLooper(20, TimeUnit.MILLISECONDS)
            model.controller.items.any { it.id == ARRIVED_CHAT } && model.snapshotReads >= 2
        }
        // The arrival starts one held follow-up read. The filter must not wait for it.
        val reads = model.snapshotReads
        openOnOneFrame()
        rule.onNodeWithTag("chat.row.$ARRIVED_CHAT").assertExists()
        assertFirstFrameResult(model, reads)
    }

    /**
     * With the filter open, a row update brings one off-window unread chat into the window. That is an
     * addition, so the other off-window unread chat stays and no progress node appears.
     */
    @Test fun anOffWindowChatEnteringTheWindowKeepsTheOtherOffWindowRows() {
        val model = prepared(extras = listOf(chatRow(ENTERING_CHAT).copy(manuallyMarkedUnread = true)))
        openOnOneFrame()
        rule.mainClock.autoAdvance = true
        rule.waitForIdle()
        rule.onNodeWithTag("chat.row.$ENTERING_CHAT").assertExists()
        rule.runOnIdle {
            model.controller.applyChatListRow(chatRow(ENTERING_CHAT).copy(manuallyMarkedUnread = true))
        }
        rule.waitUntil {
            scope.testScheduler.runCurrent()
            ShadowLooper.idleMainLooper(20, TimeUnit.MILLISECONDS)
            model.controller.items.any { it.id == ENTERING_CHAT }
        }
        rule.waitForIdle()
        rule.onNodeWithTag("chat.row.$ENTERING_CHAT").assertExists()
        rule.onNodeWithTag("chat.row.$ACTION_TARGET").assertExists()
        rule.onNodeWithTag("chat.row.$ACTION_SURVIVOR").assertExists()
        rule.onNodeWithTag("chats.unread_refresh").assertDoesNotExist()
        rule.onNodeWithTag(FULL_SCREEN_LOADING_TEST_TAG).assertDoesNotExist()
    }

    /**
     * An off-window chat read on another device sends no membership event. Opening still shows the prepared
     * snapshot at once, and the reconciling read started by the opening replaces it, with no progress node.
     */
    @Test fun openingReconcilesOffWindowRowStateBehindThePreparedResult() {
        val model = prepared()
        val reads = model.snapshotReads
        model.row.updateAndGet { it.copy(manuallyMarkedUnread = false) }
        openOnOneFrame()
        assertFirstFrameResult(model, reads)
        assertNoProgress()
        holdReads.complete(Unit)
        rule.waitUntil {
            scope.testScheduler.runCurrent()
            ShadowLooper.idleMainLooper(20, TimeUnit.MILLISECONDS)
            assertNoProgress()
            val reconciled = rule.onAllNodesWithTag("chat.row.$ACTION_TARGET").fetchSemanticsNodes().isEmpty()
            model.snapshotReads > reads && reconciled
        }
        rule.onNodeWithTag("chat.row.$ACTION_SURVIVOR").assertExists()
        assertNoProgress()
    }

    /** Neither the filter's progress indicator nor its loading screen is shown. */
    private fun assertNoProgress() {
        rule.onNodeWithTag("chats.unread_refresh").assertDoesNotExist()
        rule.onNodeWithTag(FULL_SCREEN_LOADING_TEST_TAG).assertDoesNotExist()
    }

    /** Mounts production ChatsScreen, waits for its background read, then holds every later read. */
    private fun prepared(extras: List<dev.ipf.marmotkit.ChatListRowFfi> = emptyList()): OffWindowActionFixture {
        val model = OffWindowActionFixture(scope).also { fixture = it }
        model.snapshotExtras = extras
        rule.setContent {
            if (mounted.value) {
                WhiteNoiseTheme {
                    ChatsScreen(
                        model.state,
                        model.controller,
                        {},
                        { _, _, _, _ -> },
                        globalSearchState = search.value,
                        onGlobalSearchStateChange = { update -> search.value = update(search.value) },
                    )
                }
            }
        }
        rule.waitUntil {
            scope.testScheduler.runCurrent()
            ShadowLooper.idleMainLooper(20, TimeUnit.MILLISECONDS)
            model.snapshotReads >= 1
        }
        scope.testScheduler.runCurrent()
        rule.waitForIdle()
        model.beforeSnapshotRead = { holdReads.await() }
        // The off-window chat is absent until the filter opens, so the result cannot come from the window.
        rule.onNodeWithTag("chat.row.$ACTION_TARGET").assertDoesNotExist()
        return model
    }

    /** Invokes the production accessible toggle and advances exactly one frame. */
    private fun openOnOneFrame() {
        rule.waitForIdle()
        rule.mainClock.autoAdvance = false
        val action =
            rule
                .onNodeWithTag("chats.unread_surface")
                .fetchSemanticsNode()
                .config[SemanticsActions.CustomActions]
                .single()
                .action
        rule.runOnUiThread {
            check(action())
            // Publish the toggle now, as the platform does before its next frame, so only one frame runs.
            Snapshot.sendApplyNotifications()
        }
        rule.mainClock.advanceTimeByFrame()
    }

    /** The filtered list holds both unread chats with no progress node and no read since preparation. */
    private fun assertFirstFrameResult(
        model: OffWindowActionFixture,
        readsAtActivation: Int,
    ) {
        rule.onNodeWithText(rule.activity.getString(R.string.pull_unread_filtered)).assertExists()
        rule.onNodeWithTag("chat.row.$ACTION_TARGET").assertExists()
        rule.onNodeWithTag("chat.row.$ACTION_SURVIVOR").assertExists()
        rule.onNodeWithTag("chats.unread_refresh").assertDoesNotExist()
        rule.onNodeWithTag(FULL_SCREEN_LOADING_TEST_TAG).assertDoesNotExist()
        scope.testScheduler.runCurrent()
        // Opening starts at most one reconciling read behind the prepared result, and never waits for it.
        assertTrue(
            "opening must not stack account-wide reads: ${model.snapshotReads} after $readsAtActivation",
            model.snapshotReads <= readsAtActivation + 1,
        )
        rule.mainClock.autoAdvance = true
    }
}

private val ARRIVED_CHAT = "d7".repeat(16)
private val ENTERING_CHAT = "e7".repeat(16)
