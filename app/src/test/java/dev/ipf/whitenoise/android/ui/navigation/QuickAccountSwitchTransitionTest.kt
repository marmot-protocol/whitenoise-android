package dev.ipf.whitenoise.android.ui.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w360dp-h640dp-mdpi")
class QuickAccountSwitchTransitionTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun locallyReadyTargetOwnsTheFirstFrameAndRevealsWithinTheBound() {
        var transition by mutableStateOf<QuickAccountSwitchTransition?>(animatedRequest(1L, ACCOUNT_B))
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            WhiteNoiseTheme {
                Surface(Modifier.fillMaxSize()) {
                    Box(Modifier.fillMaxSize()) {
                        Text(TARGET_PRIVATE_CHAT)
                        QuickAccountSwitchTransitionOverlay(
                            transition = transition,
                            visible = transition?.phase == QuickAccountSwitchPhase.AwaitingTarget,
                            onFinished = { requestId ->
                                if (transition?.requestId == requestId) transition = null
                            },
                        )
                    }
                }
            }
        }

        composeRule.onNodeWithTag(QUICK_ACCOUNT_SWITCH_CUE_TAG).assertIsDisplayed()
        composeRule.onNodeWithText("Switching to Work").assertIsDisplayed()
        composeRule.onNodeWithText(TARGET_PRIVATE_CHAT).assertExists()

        composeRule.runOnUiThread {
            transition = transition?.copy(phase = QuickAccountSwitchPhase.RevealingTarget)
        }
        composeRule.waitForIdle()
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.mainClock.advanceTimeBy(QUICK_ACCOUNT_SWITCH_TRANSITION_MILLIS.toLong() - 1L)
        composeRule.onNodeWithTag(QUICK_ACCOUNT_SWITCH_CUE_TAG).assertExists()
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(QUICK_ACCOUNT_SWITCH_CUE_TAG).assertDoesNotExist()
        composeRule.onNodeWithText(TARGET_PRIVATE_CHAT).assertIsDisplayed()
        assertNull(transition)
        assertTrue(
            QUICK_ACCOUNT_SWITCH_TRANSITION_MILLIS + (3 * TEST_FRAME_MILLIS) <=
                QUICK_ACCOUNT_SWITCH_MAX_PRESENTATION_MILLIS,
        )
    }

    @Test
    fun reducedMotionCommitsTargetWithoutDecorativeDuration() {
        var transition by
            mutableStateOf<QuickAccountSwitchTransition?>(
                animatedRequest(2L, ACCOUNT_B).copy(motion = QuickAccountSwitchMotion.Reduced),
            )
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            WhiteNoiseTheme {
                Box(Modifier.fillMaxSize()) {
                    Text(TARGET_PRIVATE_CHAT)
                    QuickAccountSwitchTransitionOverlay(
                        transition = transition,
                        visible = false,
                        onFinished = { transition = null },
                    )
                }
            }
        }
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.runOnIdle { }

        composeRule.onNodeWithTag(QUICK_ACCOUNT_SWITCH_CUE_TAG).assertDoesNotExist()
        composeRule.onNodeWithText(TARGET_PRIVATE_CHAT).assertIsDisplayed()
        assertTrue(transition?.motion == QuickAccountSwitchMotion.Reduced)
    }

    @Test
    fun rapidAtoBtoARejectsTheSupersededBActivationAndCue() {
        val staleB = animatedRequest(3L, ACCOUNT_B)
        val currentA = animatedRequest(4L, ACCOUNT_A).copy(sourceAccountRef = ACCOUNT_B)

        assertTrue(
            quickAccountSwitchRequestDisposition(ACCOUNT_A, staleB, ACCOUNT_A) ==
                QuickAccountSwitchRequestDisposition.CancelPendingToCurrent,
        )
        assertTrue(
            quickAccountSwitchRequestDisposition(ACCOUNT_A, staleB, ACCOUNT_B) ==
                QuickAccountSwitchRequestDisposition.Ignore,
        )
        assertTrue(quickAccountSwitchRequestIsCurrent(staleB, 3L, ACCOUNT_B))
        assertFalse(quickAccountSwitchRequestIsCurrent(currentA, 3L, ACCOUNT_B))
        assertTrue(quickAccountSwitchRequestIsCurrent(currentA, 4L, ACCOUNT_A))
        assertFalse(quickAccountSwitchOwnsTargetFrame(currentA, ACCOUNT_B, targetLocallyReady = true))
        assertTrue(quickAccountSwitchOwnsTargetFrame(currentA, ACCOUNT_A, targetLocallyReady = true))
        assertFalse(quickAccountSwitchShouldShowCue(currentA, ACCOUNT_A, targetLocallyReady = false))
        val revealComplete = currentA.copy(phase = QuickAccountSwitchPhase.RevealComplete)
        assertTrue(quickAccountSwitchOwnsTargetFrame(revealComplete, ACCOUNT_A, targetLocallyReady = true))
        assertFalse(quickAccountSwitchShouldShowCue(revealComplete, ACCOUNT_A, targetLocallyReady = true))
    }

    @Test
    fun onlyAnAccountOwnedCompletedLocalSnapshotCanUseTheDecorativeTransition() {
        assertTrue(
            quickAccountSwitchTargetLocallyReady(
                controllerAccountRef = ACCOUNT_B,
                activeAccountRef = ACCOUNT_B,
                hasLoadedLocalSnapshot = true,
            ),
        )
        assertFalse(
            quickAccountSwitchTargetLocallyReady(
                controllerAccountRef = ACCOUNT_A,
                activeAccountRef = ACCOUNT_B,
                hasLoadedLocalSnapshot = true,
            ),
        )
        assertFalse(
            quickAccountSwitchTargetLocallyReady(
                controllerAccountRef = ACCOUNT_B,
                activeAccountRef = ACCOUNT_B,
                hasLoadedLocalSnapshot = false,
            ),
        )
    }

    private fun animatedRequest(
        requestId: Long,
        target: String,
    ) = QuickAccountSwitchTransition(
        requestId = requestId,
        sourceAccountRef = ACCOUNT_A,
        targetAccountRef = target,
        targetTitle = if (target == ACCOUNT_B) "Work" else "Personal",
        targetSeed = target,
        targetPictureUrl = null,
        motion = QuickAccountSwitchMotion.Animated,
    )

    private companion object {
        const val ACCOUNT_A = "account-a"
        const val ACCOUNT_B = "account-b"
        const val TARGET_PRIVATE_CHAT = "Target work private chat"
        const val TEST_FRAME_MILLIS = 16
    }
}
