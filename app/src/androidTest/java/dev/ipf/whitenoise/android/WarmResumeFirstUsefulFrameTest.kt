package dev.ipf.whitenoise.android

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.ipf.whitenoise.android.state.AppPhase
import dev.ipf.whitenoise.android.state.WarmResumeRenderedSurface
import dev.ipf.whitenoise.android.state.WarmResumeTrace
import dev.ipf.whitenoise.android.ui.common.FULL_SCREEN_LOADING_TEST_TAG
import dev.ipf.whitenoise.android.ui.common.STARTUP_LOADING_TEST_TAG
import dev.ipf.whitenoise.android.ui.common.WARM_RESUME_USEFUL_SURFACE_TEST_TAG
import dev.ipf.whitenoise.android.ui.conversation.CONVERSATION_INITIAL_LOADING_TEST_TAG
import dev.ipf.whitenoise.android.ui.testing.PerformanceTestTags
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WarmResumeFirstUsefulFrameTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun sameActivityStopStartKeepsTheCurrentUsefulSurfaceWithoutFullScreenLoading() {
        waitForInitialUsefulSurface()
        val originalActivity = composeRule.activity
        WarmResumeTrace.resetRenderedSurfaceFrames()

        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync { originalActivity.moveTaskToBack(true) }
        waitForLifecycleState(originalActivity, Lifecycle.State.CREATED)

        val targetContext = ApplicationProvider.getApplicationContext<Context>()
        val launchIntent =
            checkNotNull(targetContext.packageManager.getLaunchIntentForPackage(targetContext.packageName))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        targetContext.startActivity(launchIntent)
        waitForLifecycleState(originalActivity, Lifecycle.State.RESUMED)
        composeRule.waitForIdle()

        assertSame(originalActivity, composeRule.activity)
        assertFirstRenderedFrameIsCurrentUsefulSurface()
        assertUsefulSurfaceWithoutFullScreenLoading()
    }

    @Test
    fun recreateKeepsTheCurrentUsefulSurfaceWithoutConversationLoadingRegression() {
        waitForInitialUsefulSurface()
        val conversationWasVisible =
            composeRule
                .onAllNodesWithTag(PerformanceTestTags.CONVERSATION_TRANSCRIPT_VISIBLE)
                .fetchSemanticsNodes()
                .isNotEmpty()
        WarmResumeTrace.resetRenderedSurfaceFrames()

        composeRule.activityRule.scenario.recreate()
        composeRule.waitForIdle()

        assertFirstRenderedFrameIsCurrentUsefulSurface()
        assertUsefulSurfaceWithoutFullScreenLoading()
        if (conversationWasVisible) {
            composeRule.onNodeWithTag(PerformanceTestTags.CONVERSATION_TRANSCRIPT_VISIBLE).assertExists()
            composeRule.onNodeWithTag(CONVERSATION_INITIAL_LOADING_TEST_TAG).assertDoesNotExist()
        }
    }

    private fun assertFirstRenderedFrameIsCurrentUsefulSurface() {
        composeRule.waitUntil(timeoutMillis = 5_000L) {
            WarmResumeTrace.renderedSurfaceFrames().isNotEmpty()
        }
        val frames = WarmResumeTrace.renderedSurfaceFrames()
        val application = composeRule.activity.application as WhiteNoiseApplication
        val expected =
            when {
                application.appState.appLockScreenVisible -> WarmResumeRenderedSurface.AppLock
                application.appState.phase == AppPhase.Onboarding -> WarmResumeRenderedSurface.Onboarding
                application.appState.phase is AppPhase.Failed -> WarmResumeRenderedSurface.Error
                application.mainShellProcessState.selectedChat.value != null ->
                    WarmResumeRenderedSurface.Conversation
                else -> WarmResumeRenderedSurface.ChatList
            }
        assertEquals("Unexpected rendered surface sequence: $frames", expected, frames.first().surface)
    }

    private fun waitForInitialUsefulSurface() {
        val application = composeRule.activity.application as WhiteNoiseApplication
        composeRule.waitUntil(timeoutMillis = 20_000L) {
            when {
                application.appState.appLockScreenVisible -> true
                application.appState.phase == AppPhase.Bootstrapping -> false
                application.appState.phase != AppPhase.Ready -> true
                else ->
                    application.mainShellProcessState.localProjectionAvailable(
                        activeAccountRef = application.appState.activeAccountRef,
                        runtimeGeneration = application.appState.runtimeGeneration,
                    )
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(WARM_RESUME_USEFUL_SURFACE_TEST_TAG).assertExists()
    }

    private fun assertUsefulSurfaceWithoutFullScreenLoading() {
        composeRule.onRoot().assertExists()
        composeRule.onNodeWithTag(WARM_RESUME_USEFUL_SURFACE_TEST_TAG).assertExists()
        composeRule.onNodeWithTag(FULL_SCREEN_LOADING_TEST_TAG).assertDoesNotExist()
        composeRule.onNodeWithTag(STARTUP_LOADING_TEST_TAG).assertDoesNotExist()
    }

    private fun waitForLifecycleState(
        activity: MainActivity,
        expected: Lifecycle.State,
    ) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val deadline = SystemClock.uptimeMillis() + LIFECYCLE_TIMEOUT_MILLIS
        var actual = Lifecycle.State.INITIALIZED
        while (SystemClock.uptimeMillis() < deadline) {
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync { actual = activity.lifecycle.currentState }
            if (actual == expected) return
            SystemClock.sleep(LIFECYCLE_POLL_MILLIS)
        }
        error("Activity never reached $expected (last lifecycle state = $actual)")
    }

    private companion object {
        const val LIFECYCLE_TIMEOUT_MILLIS = 20_000L
        const val LIFECYCLE_POLL_MILLIS = 50L
    }
}
