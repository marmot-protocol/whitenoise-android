package dev.ipf.whitenoise.android.ui.conversation

import android.view.ViewTreeObserver
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicInteger

/** Device-host coverage for the focused-composer foreground draw release. */
@RunWith(AndroidJUnit4::class)
class ConversationForegroundDrawGateAndroidTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    /**
     * Replays pause/resume around a real [BasicTextField] and proves that gate
     * release schedules a useful root draw without external input.
     */
    @Test
    fun focusedComposerResumeSchedulesAUsefulFrameAndKeepsEditingState() {
        val fixture = ForegroundResumeFixture()
        composeRule.setContent { fixture.Content() }

        composeRule.runOnIdle { fixture.startAndFocus() }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(COMPOSER_TAG).assertIsFocused()
        val framesBeforeResume = fixture.usefulFrames.get()

        composeRule.runOnIdle { fixture.pauseAndResume() }
        composeRule.waitUntil(timeoutMillis = 5_000L) {
            fixture.usefulFrames.get() > framesBeforeResume
        }

        composeRule.onNodeWithTag(COMPOSER_TAG).assertIsFocused()
        assertEquals(fixture.expectedSelection, fixture.value.selection)
        assertEquals("draft text", fixture.value.text)
    }

    /** State and production-effect wiring for the real-window regression. */
    private class ForegroundResumeFixture {
        private val lifecycleOwner = TestLifecycleOwner()
        private val focusRequester = FocusRequester()
        val usefulFrames = AtomicInteger()
        val expectedSelection = TextRange(2, 7)
        var value by mutableStateOf(TextFieldValue("draft text", expectedSelection))
        private var composerFocused by mutableStateOf(false)
        private var gateBlocked by mutableStateOf(false)

        /** Mounts the production draw gate and lifecycle effect around a real editor. */
        @Composable
        fun Content() {
            val rootView = LocalView.current
            DisposableEffect(rootView) {
                val observer = rootView.viewTreeObserver
                val drawListener = ViewTreeObserver.OnDrawListener { usefulFrames.incrementAndGet() }
                observer.addOnDrawListener(drawListener)
                onDispose {
                    if (observer.isAlive) observer.removeOnDrawListener(drawListener)
                }
            }
            ConversationForegroundDrawGateEffect(
                isBlocked = gateBlocked,
                onPreDraw = {},
            )
            ConversationComposerLifecycleEffect(
                observerKey = lifecycleOwner,
                lifecycleOwner = lifecycleOwner,
                composerFocused = composerFocused,
                searchOpen = false,
                hasActiveEditOrReplySession = false,
                onPause = { gateBlocked = true },
                onResume = { restoreFocus, _ ->
                    if (restoreFocus) focusRequester.requestFocus()
                    gateBlocked = false
                    requestConversationForegroundFrame(rootView)
                },
            )
            Box(Modifier.fillMaxSize()) {
                BasicTextField(
                    value = value,
                    onValueChange = { value = it },
                    modifier =
                        Modifier
                            .testTag(COMPOSER_TAG)
                            .focusRequester(focusRequester)
                            .onFocusChanged { composerFocused = it.isFocused },
                )
            }
        }

        /** Starts the fixture in the focused, IME-requesting conversation state. */
        fun startAndFocus() {
            lifecycleOwner.handle(Lifecycle.Event.ON_START)
            lifecycleOwner.handle(Lifecycle.Event.ON_RESUME)
            focusRequester.requestFocus()
        }

        /** Delivers the app-switch lifecycle edges without any intervening user input. */
        fun pauseAndResume() {
            lifecycleOwner.handle(Lifecycle.Event.ON_PAUSE)
            lifecycleOwner.handle(Lifecycle.Event.ON_RESUME)
        }
    }

    /** Minimal owner that exposes explicit lifecycle edges to the host test. */
    private class TestLifecycleOwner : LifecycleOwner {
        private val registry = LifecycleRegistry(this)

        init {
            registry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        }

        override val lifecycle: Lifecycle = registry

        /** Dispatches one lifecycle edge synchronously on the test UI thread. */
        fun handle(event: Lifecycle.Event) {
            registry.handleLifecycleEvent(event)
        }
    }

    private companion object {
        const val COMPOSER_TAG = "foreground-resume-composer"
    }
}
