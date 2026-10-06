package dev.ipf.whitenoise.android.ui.conversation

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Transition and load completion race without flashing progress or withholding slow-load feedback. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ConversationInitialLoadingTransitionTest {
    @get:Rule val rule = createComposeRule()
    private val loading = mutableStateOf(true)
    private val transitioning = mutableStateOf(true)

    /** A delayed animation cannot consume the indicator's grace period before the page lands. */
    @Test fun slowLoadStartsItsGraceOnlyAfterRouteSettlement() {
        render()
        rule.mainClock.advanceTimeBy(1_000L)
        rule.onNodeWithTag(CONVERSATION_INITIAL_LOADING_TEST_TAG).assertDoesNotExist()
        rule.runOnIdle { transitioning.value = false }
        rule.mainClock.advanceTimeByFrame()
        rule.runOnIdle { }
        rule.mainClock.advanceTimeBy(100L)
        rule.onNodeWithTag(CONVERSATION_INITIAL_LOADING_TEST_TAG).assertDoesNotExist()
        rule.mainClock.advanceTimeBy(100L)
        rule.onNodeWithTag(CONVERSATION_INITIAL_LOADING_TEST_TAG).assertExists()
    }

    /** A cached transcript finishing within the settled grace never briefly mounts the loader. */
    @Test fun fastLoadAfterRouteSettlementNeverFlashesProgress() {
        render()
        rule.mainClock.advanceTimeBy(1_000L)
        rule.runOnIdle { transitioning.value = false }
        rule.mainClock.advanceTimeByFrame()
        rule.runOnIdle { }
        rule.mainClock.advanceTimeBy(64L)
        rule.runOnIdle { loading.value = false }
        rule.mainClock.advanceTimeBy(1_000L)
        rule.onNodeWithTag(CONVERSATION_INITIAL_LOADING_TEST_TAG).assertDoesNotExist()
    }

    /** Back removes already-visible loading chrome immediately and a later open receives a fresh budget. */
    @Test fun newRouteMotionRetiresAnAlreadyVisibleIndicator() {
        transitioning.value = false
        render()
        rule.mainClock.advanceTimeBy(300L)
        rule.onNodeWithTag(CONVERSATION_INITIAL_LOADING_TEST_TAG).assertExists()
        rule.runOnIdle { transitioning.value = true }
        rule.mainClock.advanceTimeByFrame()
        rule.onNodeWithTag(CONVERSATION_INITIAL_LOADING_TEST_TAG).assertDoesNotExist()
        rule.mainClock.advanceTimeBy(1_000L)
        rule.runOnIdle { transitioning.value = false }
        rule.mainClock.advanceTimeByFrame()
        rule.runOnIdle { }
        rule.mainClock.advanceTimeBy(64L)
        rule.onNodeWithTag(CONVERSATION_INITIAL_LOADING_TEST_TAG).assertDoesNotExist()
        rule.mainClock.advanceTimeBy(200L)
        rule.onNodeWithTag(CONVERSATION_INITIAL_LOADING_TEST_TAG).assertExists()
    }

    /** Uses the production overlay and real Compose frame clock, without native account or timeline work. */
    private fun render() {
        rule.mainClock.autoAdvance = false
        rule.setContent {
            WhiteNoiseTheme {
                ConversationInitialLoadingOverlay(
                    visible = loading.value,
                    routeTransitionInProgress = transitioning.value,
                )
            }
        }
    }
}
