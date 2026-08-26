package dev.ipf.whitenoise.android

import android.content.Intent
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FreshActivitySameProcessFirstUsefulFrameTest {
    @get:Rule
    val composeRule = createEmptyComposeRule()

    @Test
    fun freshViewModelStoreRendersTheRetainedRouteBeforeAnyLoadingFrame() {
        val initialActivity = launchMainActivity()
        val expectedSurface =
            try {
                val application = ApplicationProvider.getApplicationContext<WhiteNoiseApplication>()
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
                if (processState.selectedChat.value == null) {
                    WarmResumeRenderedSurface.ChatList
                } else {
                    WarmResumeRenderedSurface.Conversation
                }
            } finally {
                initialActivity.close()
            }

        WarmResumeTrace.resetRenderedSurfaceFrames()
        val freshActivity = launchMainActivity()
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

    @Test
    fun taskRemovalDropsTheRetainedConversationBeforeAFreshActivity() {
        val initialActivity = launchMainActivity()
        val application = ApplicationProvider.getApplicationContext<WhiteNoiseApplication>()
        val appState = application.appState
        val processState = application.mainShellProcessState
        try {
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
            assumeTrue(
                "Requires an authenticated local projection on the device fixture",
                appState.phase == AppPhase.Ready &&
                    !appState.appLockScreenVisible &&
                    processState.localProjectionAvailable(
                        activeAccountRef = appState.activeAccountRef,
                        runtimeGeneration = appState.runtimeGeneration,
                    ),
            )
            val chats = processState.chatsController(appState.activeAccountRef, appState.runtimeGeneration)
            assumeTrue("Requires at least one local chat on the device fixture", chats.items.isNotEmpty())
            composeRule.runOnIdle {
                processState.selectedChat.value = chats.items.first()
            }
            assertTrue(processState.selectedChat.value != null)

            composeRule.runOnIdle {
                application.onTaskRemoved()
            }
            assertNull(processState.selectedChat.value)
            assertTrue(
                processState.localProjectionAvailable(
                    activeAccountRef = appState.activeAccountRef,
                    runtimeGeneration = appState.runtimeGeneration,
                ),
            )
        } finally {
            initialActivity.close()
        }

        WarmResumeTrace.resetRenderedSurfaceFrames()
        val freshActivity = launchMainActivity()
        try {
            composeRule.waitUntil(timeoutMillis = 5_000L) {
                WarmResumeTrace.renderedSurfaceFrames().isNotEmpty()
            }
            val frames = WarmResumeTrace.renderedSurfaceFrames()

            assertEquals(WarmResumeRenderedSurface.ChatList, frames.first().surface)
            assertNull(processState.selectedChat.value)
            composeRule.onNodeWithTag(WARM_RESUME_USEFUL_SURFACE_TEST_TAG).assertExists()
            composeRule.onNodeWithTag(FULL_SCREEN_LOADING_TEST_TAG).assertDoesNotExist()
            composeRule.onNodeWithTag(STARTUP_LOADING_TEST_TAG).assertDoesNotExist()
        } finally {
            freshActivity.close()
        }
    }

    private fun launchMainActivity(): ActivityScenario<MainActivity> =
        ActivityScenario.launch(
            Intent(ApplicationProvider.getApplicationContext(), MainActivity::class.java),
        )
}
