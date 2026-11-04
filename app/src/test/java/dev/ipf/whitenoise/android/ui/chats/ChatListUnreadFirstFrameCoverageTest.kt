package dev.ipf.whitenoise.android.ui.chats

import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.ui.common.FULL_SCREEN_LOADING_TEST_TAG
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.TestScope
import org.junit.After
import org.junit.Assert.assertEquals
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
        openOnOneFrame()
        assertFirstFrameResult(model, readsAtActivation = model.snapshotReads)
    }

    /** Dismissing and re-entering keeps the prepared result instead of reading the account again. */
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

    /** Mounts production ChatsScreen, waits for its background read, then holds every later read. */
    private fun prepared(): OffWindowActionFixture {
        val model = OffWindowActionFixture(scope).also { fixture = it }
        rule.setContent {
            if (mounted.value) {
                WhiteNoiseTheme { ChatsScreen(model.state, model.controller, {}, { _, _, _, _ -> }) }
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
        assertEquals("opening must not wait on a new account-wide read", readsAtActivation, model.snapshotReads)
        rule.mainClock.autoAdvance = true
    }
}
