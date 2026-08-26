package dev.ipf.whitenoise.android

import android.content.Intent
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.ipf.whitenoise.android.state.AppPhase
import dev.ipf.whitenoise.android.state.WarmResumeRenderedSurface
import dev.ipf.whitenoise.android.state.WarmResumeTrace
import dev.ipf.whitenoise.android.ui.common.FULL_SCREEN_LOADING_TEST_TAG
import dev.ipf.whitenoise.android.ui.common.STARTUP_LOADING_TEST_TAG
import dev.ipf.whitenoise.android.ui.common.WARM_RESUME_USEFUL_SURFACE_TEST_TAG
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FreshActivitySameProcessFirstUsefulFrameTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun freshViewModelStoreRendersTheRetainedRouteBeforeAnyLoadingFrame() {
        val application = composeRule.activity.application as WhiteNoiseApplication
        val appState = application.appState
        val processState = application.mainShellProcessState
        composeRule.waitUntil(timeoutMillis = 20_000L) {
            appState.phase != AppPhase.Bootstrapping &&
                (
                    appState.phase != AppPhase.Ready ||
                        appState.appLockScreenVisible ||
                        processState.localProjectionAvailable(
                            activeAccountRef = appState.activeAccountRef,
                            runtimeGeneration = appState.runtimeGeneration,
                        )
                )
        }
        composeRule.waitForIdle()
        assumeTrue(
            "Requires an authenticated local projection on the device fixture",
            appState.phase == AppPhase.Ready &&
                !appState.appLockScreenVisible &&
                processState.localProjectionAvailable(
                    activeAccountRef = appState.activeAccountRef,
                    runtimeGeneration = appState.runtimeGeneration,
                ),
        )
        val expectedSurface =
            if (processState.selectedChat.value == null) {
                WarmResumeRenderedSurface.ChatList
            } else {
                WarmResumeRenderedSurface.Conversation
            }

        WarmResumeTrace.resetRenderedSurfaceFrames()
        composeRule.activityRule.scenario.close()
        val freshActivity =
            ActivityScenario.launch<MainActivity>(
                Intent(ApplicationProvider.getApplicationContext(), MainActivity::class.java),
            )
        try {
            composeRule.waitUntil(timeoutMillis = 5_000L) {
                WarmResumeTrace.renderedSurfaceFrames().isNotEmpty()
            }
            val frames = WarmResumeTrace.renderedSurfaceFrames()

            assertEquals(expectedSurface, frames.first().surface)
            assertTrue(
                "No loading frame may precede the retained useful route: $frames",
                frames
                    .takeWhile { it.surface != expectedSurface }
                    .none {
                        it.surface == WarmResumeRenderedSurface.StartupLoading ||
                            it.surface == WarmResumeRenderedSurface.FullScreenLoading
                    },
            )
            composeRule.onNodeWithTag(WARM_RESUME_USEFUL_SURFACE_TEST_TAG).assertExists()
            composeRule.onNodeWithTag(FULL_SCREEN_LOADING_TEST_TAG).assertDoesNotExist()
            composeRule.onNodeWithTag(STARTUP_LOADING_TEST_TAG).assertDoesNotExist()
        } finally {
            freshActivity.close()
        }
    }
}
