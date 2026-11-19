package dev.ipf.whitenoise.android.ui

import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import dev.ipf.whitenoise.android.BackOptInFixtureActivity
import dev.ipf.whitenoise.android.PullRequestDeviceSmoke
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.ui.common.Explanation
import dev.ipf.whitenoise.android.ui.common.ExplanationHost
import dev.ipf.whitenoise.android.ui.common.explainOnLongPress
import dev.ipf.whitenoise.android.ui.conversation.composer.ExplainAboveComposer
import dev.ipf.whitenoise.android.ui.conversation.composer.composerExplanationEdge
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * System Back and the accessibility Explain action on a composer control, in a host that opts in to Back callbacks as
 * MainActivity does. Android 13 ignores that activity-level opt-in and drops the app's callbacks, so there the
 * explanation's Back has to come through the activity, or Back leaves the conversation with the explanation open.
 */
@PullRequestDeviceSmoke
@RunWith(AndroidJUnit4::class)
class ComposerExplanationBackAndroidTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<BackOptInFixtureActivity>()

    /** With no editor focused and no keyboard open, Back closes only the explanation, and the screen stays open. */
    @Test
    fun systemBackClosesOnlyTheExplanation() {
        render()
        composeRule.onNodeWithTag(CONTROL).performTouchInput { longClick() }
        composeRule.onNodeWithTag(SHEET).assertIsDisplayed()
        composeRule.waitForIdle()

        val activity = composeRule.activity
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        UiDevice.getInstance(instrumentation).pressBack()
        SystemClock.sleep(SETTLE_MS)

        var left = false
        instrumentation.runOnMainSync { left = activity.isFinishing || activity.isDestroyed }
        assertFalse("Back left the screen instead of closing only the explanation", left)
        composeRule.waitUntil(TIMEOUT_MS) { composeRule.onAllNodesWithTag(SHEET).fetchSemanticsNodes().isEmpty() }
        composeRule.onNodeWithTag(COMPOSER).assertIsDisplayed()
    }

    /**
     * The accessibility Explain action, invoked through the platform accessibility API as TalkBack invokes it, opens
     * the explanation above the composer and runs nothing else.
     */
    @Test
    fun explainAccessibilityActionOpensTheExplanationAboveTheComposer() {
        render()
        composeRule.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val label = instrumentation.targetContext.getString(R.string.explanation_action_explain)
        val (node, action) = awaitCustomAction(label)

        assertTrue("the $label action was refused", node.performAction(action))

        composeRule.onNodeWithTag(SHEET).assertIsDisplayed()
        val sheet = composeRule.onNodeWithTag(SHEET).fetchSemanticsNode()
        val composer = composeRule.onNodeWithTag(COMPOSER).fetchSemanticsNode()
        assertTrue(
            "the explanation covers the composer",
            sheet.positionOnScreen.y + sheet.size.height <= composer.positionOnScreen.y + 1f,
        )
    }

    /** A composer at the bottom of the screen holding one explained control. */
    private fun render() {
        composeRule.setContent {
            WhiteNoiseTheme {
                ExplanationHost {
                    Surface(Modifier.fillMaxSize()) {
                        Column(Modifier.fillMaxSize()) {
                            Spacer(Modifier.weight(1f))
                            ExplainAboveComposer {
                                Box(
                                    Modifier
                                        .fillMaxWidth()
                                        .height(96.dp)
                                        .testTag(COMPOSER)
                                        .composerExplanationEdge(),
                                ) {
                                    Box(Modifier.size(48.dp).testTag(CONTROL).explainOnLongPress(EXPLANATION))
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * The node offering the custom accessibility action labelled [label], with that action's id. The active window's
     * accessibility root can be missing until the platform's accessibility connection has it, so this waits for it.
     */
    private fun awaitCustomAction(label: String): Pair<AccessibilityNodeInfo, Int> {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val deadline = SystemClock.uptimeMillis() + TIMEOUT_MS
        while (SystemClock.uptimeMillis() < deadline) {
            automation.rootInActiveWindow?.findCustomAction(label)?.let { return it }
            SystemClock.sleep(POLL_MS)
        }
        error("no node offered the $label action within $TIMEOUT_MS ms")
    }

    /** This node or a descendant offering the custom accessibility action labelled [label], with that action's id. */
    private fun AccessibilityNodeInfo.findCustomAction(label: String): Pair<AccessibilityNodeInfo, Int>? {
        actionList.firstOrNull { it.label?.toString() == label }?.let { return this to it.id }
        return (0 until childCount).firstNotNullOfOrNull { getChild(it)?.findCustomAction(label) }
    }

    private companion object {
        const val COMPOSER = "composer"
        const val CONTROL = "composer.control"
        const val SHEET = "explanation.sheet"
        const val TIMEOUT_MS = 5_000L
        const val SETTLE_MS = 1_000L
        const val POLL_MS = 100L
        val EXPLANATION = Explanation("Camera", "Takes a photo or video to send in this chat.")
    }
}
