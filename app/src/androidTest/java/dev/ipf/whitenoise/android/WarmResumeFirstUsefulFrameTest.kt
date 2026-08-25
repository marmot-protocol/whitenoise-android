package dev.ipf.whitenoise.android

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.lifecycle.Lifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.ipf.whitenoise.android.ui.common.FULL_SCREEN_LOADING_TEST_TAG
import dev.ipf.whitenoise.android.ui.common.STARTUP_LOADING_TEST_TAG
import dev.ipf.whitenoise.android.ui.conversation.CONVERSATION_INITIAL_LOADING_TEST_TAG
import dev.ipf.whitenoise.android.ui.testing.PerformanceTestTags
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
        composeRule.waitForIdle()
        val originalActivity = composeRule.activity

        composeRule.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        composeRule.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        composeRule.waitForIdle()

        assertSame(originalActivity, composeRule.activity)
        assertUsefulSurfaceWithoutFullScreenLoading()
    }

    @Test
    fun recreateKeepsTheCurrentUsefulSurfaceWithoutConversationLoadingRegression() {
        composeRule.waitForIdle()
        val conversationWasVisible =
            composeRule
                .onAllNodesWithTag(PerformanceTestTags.CONVERSATION_TRANSCRIPT_VISIBLE)
                .fetchSemanticsNodes()
                .isNotEmpty()

        composeRule.activityRule.scenario.recreate()
        composeRule.waitForIdle()

        assertUsefulSurfaceWithoutFullScreenLoading()
        if (conversationWasVisible) {
            composeRule.onNodeWithTag(PerformanceTestTags.CONVERSATION_TRANSCRIPT_VISIBLE).assertExists()
            composeRule.onNodeWithTag(CONVERSATION_INITIAL_LOADING_TEST_TAG).assertDoesNotExist()
        }
    }

    private fun assertUsefulSurfaceWithoutFullScreenLoading() {
        composeRule.onRoot().assertExists()
        composeRule.onNodeWithTag(FULL_SCREEN_LOADING_TEST_TAG).assertDoesNotExist()
        composeRule.onNodeWithTag(STARTUP_LOADING_TEST_TAG).assertDoesNotExist()
    }
}
