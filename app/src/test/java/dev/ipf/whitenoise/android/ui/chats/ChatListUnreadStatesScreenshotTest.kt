package dev.ipf.whitenoise.android.ui.chats

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.ui.common.FULL_SCREEN_LOADING_TEST_TAG
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.TestScope
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper
import java.util.TimeZone
import java.util.concurrent.TimeUnit

/** Real temporary unread views preserve the current theme and accessible exit in every read state. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-w360dp-h950dp-mdpi")
@OptIn(ExperimentalCoroutinesApi::class)
class ChatListUnreadStatesScreenshotTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val scope = TestScope()
    private val mounted = mutableStateOf(true)
    private var fixture: OffWindowActionFixture? = null
    private val releaseRead = CompletableDeferred<Unit>()
    private var originalTimeZone: TimeZone? = null

    @Before fun stableTimeZone() {
        originalTimeZone = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
    }

    @After fun restoreTimeZone() {
        originalTimeZone?.let(TimeZone::setDefault)
    }

    /** A held native read shows pending content and keeps Show all usable. */
    @Test fun inactiveLight() = capture("inactive_light", State.Inactive)

    @Test fun heldPullDark() = capture("held_pull_dark", State.HeldPull, dark = true)

    @Test fun pendingLight() = capture("pending_light", State.Pending)

    /** A failed native read offers explicit recovery in the existing dark palette. */
    @Test fun failedDark() = capture("failed_dark", State.Failed, dark = true)

    /** Successful off-window matches use the pure-black theme and actual list rows. */
    @Test fun matchingAmoled() = capture("matching_amoled", State.Matching, dark = true, amoled = true)

    /** A completed empty read keeps caught-up copy and Show all legible with large RTL text. */
    @Test fun emptyLargeRtl() = capture("empty_large_rtl", State.Empty, dark = true, amoled = true, rtl = true)

    @After fun close() {
        releaseRead.complete(Unit)
        rule.runOnIdle { mounted.value = false }
        rule.waitForIdle()
        val model = fixture
        rule.runOnIdle { model?.state?.mutationsScope?.cancel() }
        try {
            awaitProgress {
                model
                    ?.state
                    ?.mutationsScope
                    ?.coroutineContext
                    ?.get(Job)
                    ?.isCompleted != false
            }
        } finally {
            rule.runOnIdle { model?.controller?.onCleared() }
            scope.cancel()
        }
    }

    @Suppress("CyclomaticComplexMethod", "LongMethod")
    private fun capture(
        name: String,
        state: State,
        dark: Boolean = false,
        amoled: Boolean = false,
        rtl: Boolean = false,
    ) {
        val model = OffWindowActionFixture(scope).also { fixture = it }
        when (state) {
            State.Pending -> model.beforeSnapshotRead = { releaseRead.await() }
            State.Failed -> model.beforeSnapshotRead = { error("Synthetic unread read refusal") }
            State.Empty -> {
                model.row.updateAndGet { it.copy(manuallyMarkedUnread = false) }
                model.retainedRow.updateAndGet { it.copy(manuallyMarkedUnread = false) }
                model.controller.applyChatListRow(model.retainedRow.get())
                scope.testScheduler.runCurrent()
            }
            State.Matching, State.Inactive, State.HeldPull -> Unit
        }
        rule.setContent {
            if (mounted.value) {
                CompositionLocalProvider(
                    LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
                ) {
                    WhiteNoiseTheme(darkTheme = dark, amoled = amoled, fontScale = if (rtl) 2f else 1f) {
                        ChatsScreen(model.state, model.controller, {}, { _, _, _, _ -> })
                    }
                }
            }
        }
        if (state == State.Inactive || state == State.HeldPull) {
            rule.onNodeWithTag("chats.unread_filter").assertDoesNotExist()
            if (state == State.HeldPull) {
                rule.runOnIdle { model.state.swipePreferences.setPullUnread(true) }
                rule.waitForIdle()
                val distance = with(rule.density) { 60.dp.toPx() }
                rule.onNodeWithTag("chats.unread_surface").performTouchInput {
                    down(Offset(width * 0.6f, height * 0.3f))
                    moveBy(Offset(0f, distance), 120)
                }
            }
            rule.onRoot().captureRoboImage("src/test/snapshots/chat_list_pull_unread_$name.png")
            if (state == State.HeldPull) rule.onNodeWithTag("chats.unread_surface").performTouchInput { cancel() }
            return
        }
        rule.onNodeWithTag("chats.unread_surface").performUnreadFilterAction(rule)
        awaitProgress { model.snapshotReads >= 1 }
        rule.waitForIdle()
        rule.onNodeWithText(rule.activity.getString(R.string.pull_unread_filtered)).assertIsDisplayed()
        when (state) {
            State.Pending -> rule.onNodeWithTag(FULL_SCREEN_LOADING_TEST_TAG).assertIsDisplayed()
            State.Failed -> {
                rule.onNodeWithText(rule.activity.getString(R.string.pull_unread_load_failed)).assertIsDisplayed()
                assertEquals(
                    "A first-read failure needs one recovery action",
                    1,
                    rule
                        .onAllNodesWithText(rule.activity.getString(R.string.pull_unread_retry))
                        .fetchSemanticsNodes()
                        .size,
                )
            }
            State.Empty ->
                rule.onNodeWithText(rule.activity.getString(R.string.chat_rows_no_unread_title)).assertIsDisplayed()
            State.Matching -> rule.onNodeWithTag("chat.row.$ACTION_TARGET").assertIsDisplayed()
            State.Inactive, State.HeldPull -> error("Captured before activating unread")
        }
        rule.onRoot().captureRoboImage("src/test/snapshots/chat_list_pull_unread_$name.png")
    }

    private fun awaitProgress(ready: () -> Boolean) {
        rule.waitUntil {
            scope.testScheduler.runCurrent()
            ShadowLooper.idleMainLooper(20, TimeUnit.MILLISECONDS)
            scope.testScheduler.runCurrent()
            ready()
        }
    }

    private enum class State { Pending, Failed, Empty, Matching, Inactive, HeldPull }
}
