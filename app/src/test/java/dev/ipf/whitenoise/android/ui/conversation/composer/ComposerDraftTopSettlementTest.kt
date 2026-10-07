package dev.ipf.whitenoise.android.ui.conversation.composer

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.whitenoise.android.core.MessageTextCopy
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Frame-by-frame navigation visibility while the real owning composer geometry changes. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-w360dp-h780dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ComposerDraftTopSettlementTest {
    @get:Rule val composeRule = createComposeRule()
    private var observed = TextFieldValue()
    private var windowHeight by mutableStateOf(600)
    private var sends = 0

    /** Every automatic growth frame suppresses navigation until the measured editor has reached its endpoint. */
    @Test
    fun draftTopIsAbsentOnEveryIntermediateGrowthFrame() {
        render("Short")
        composeRule.mainClock.autoAdvance = false
        composeRule.onNode(hasSetTextAction()).performTextReplacement(longDraft)
        repeat(COMPOSER_EXPANSION_ANIMATION_MILLIS / FRAME_STEP_MS) {
            advanceRenderedFrame()
            composeRule.onNodeWithTag(COMPOSER_DRAFT_TOP_TAG).assertDoesNotExist()
        }
        composeRule.mainClock.advanceTimeBy(COMPOSER_EXPANSION_ANIMATION_MILLIS.toLong())
        composeRule.mainClock.autoAdvance = true
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(COMPOSER_DRAFT_TOP_TAG).assertIsDisplayed()
        assertEquals("the animation must not send", 0, sends)
    }

    /** Window/IME bounds can clamp a settled manual composer without changing its expansion mode. */
    @Test
    fun draftTopWaitsForUnchangedDraftWindowReflow() {
        render(longDraft)
        drag(-400f)
        val before = observed
        composeRule.onNodeWithTag(COMPOSER_DRAFT_TOP_TAG).assertIsDisplayed()
        composeRule.mainClock.autoAdvance = false
        composeRule.runOnUiThread { windowHeight = 280 }
        advanceRenderedFrame()
        composeRule.onNodeWithTag(COMPOSER_DRAFT_TOP_TAG).assertDoesNotExist()
        advanceRenderedFrame()
        composeRule.mainClock.autoAdvance = true
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(COMPOSER_DRAFT_TOP_TAG).assertIsDisplayed()
        assertEquals(before, observed)
        assertEquals(0, sends)
        composeRule.runOnUiThread { windowHeight = 600 }
        composeRule.waitForIdle()
        assertEquals(before, observed)
    }

    /** Pointer-rate resize and reversal cannot mount a control or change the current selection. */
    @Test
    fun draftTopIsAbsentDuringLiveResizeAndReversal() {
        render(longDraft)
        val original = observed
        composeRule.mainClock.autoAdvance = false
        composeRule.onNodeWithTag(COMPOSER_RESIZE_GESTURE_TAG).performTouchInput {
            down(center)
            moveBy(Offset(0f, -60f), delayMillis = FRAME_STEP_MS.toLong())
        }
        advanceRenderedFrame()
        composeRule.onNodeWithTag(COMPOSER_DRAFT_TOP_TAG).assertDoesNotExist()
        composeRule.onNodeWithTag(COMPOSER_RESIZE_GESTURE_TAG).performTouchInput {
            moveBy(Offset(0f, 30f), delayMillis = FRAME_STEP_MS.toLong())
        }
        advanceRenderedFrame()
        composeRule.onNodeWithTag(COMPOSER_DRAFT_TOP_TAG).assertDoesNotExist()
        composeRule.onNodeWithTag(COMPOSER_RESIZE_GESTURE_TAG).performTouchInput { cancel() }
        composeRule.mainClock.autoAdvance = true
        composeRule.waitForIdle()
        assertEquals(original, observed)
        composeRule.onNodeWithTag(COMPOSER_DRAFT_TOP_TAG).assertIsDisplayed()
    }

    /** Clearing a scrolled manual draft must hide navigation even when its scroll owner retains an offset. */
    @Test
    fun clearingScrolledManualDraftRemovesTopAction() {
        render(longDraft, dark = true)
        drag(600f)
        composeRule.onNodeWithTag(COMPOSER_DRAFT_TOP_TAG).assertIsDisplayed()
        composeRule.onNode(hasSetTextAction()).performClick().performTextReplacement("")
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(COMPOSER_DRAFT_TOP_TAG).assertDoesNotExist()
        assertEquals("", observed.text)
        assertEquals(TextRange.Zero, observed.selection)
        assertEquals(0, sends)
        composeRule.onNodeWithTag(TAG).captureRoboImage("src/test/snapshots/composer_cleared_manual_dark.png")
    }

    /** Publish external/pointer snapshot writes before ticking; draw completion does not tick another frame. */
    private fun advanceRenderedFrame() {
        composeRule.runOnUiThread { Snapshot.sendApplyNotifications() }
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.waitForIdle()
    }

    /** Uses the production bar so its animation/IME/window constraints own the editor. */
    private fun render(
        draft: String,
        dark: Boolean = false,
    ) {
        observed = TextFieldValue(draft, TextRange(draft.length))
        composeRule.setContent {
            var value by remember { mutableStateOf(observed) }
            WhiteNoiseTheme(darkTheme = dark) {
                Surface(Modifier.width(360.dp).height(windowHeight.dp)) {
                    Box(contentAlignment = Alignment.BottomCenter) {
                        ComposerBar(
                            replyingTo = null,
                            messageTextCopy = MessageTextCopy.Default,
                            onCancelReply = {},
                            onSend = { _, _ -> sends++ },
                            onPickFromGallery = {},
                            onPickDocument = {},
                            initialDraft = value,
                            onDraftChange = {
                                value = it
                                observed = it
                            },
                            modifier = Modifier.testTag(TAG),
                        )
                    }
                }
            }
        }
        composeRule.waitForIdle()
    }

    /** Exercises the actual pointer-rate resize gesture. */
    private fun drag(delta: Float) {
        composeRule.onNodeWithTag(COMPOSER_RESIZE_GESTURE_TAG).performTouchInput {
            swipe(center, center + Offset(0f, delta), durationMillis = 320)
        }
        composeRule.waitForIdle()
    }

    private companion object {
        const val FRAME_STEP_MS = 16
        const val TAG = "composer-settlement-test"
        val longDraft = (1..80).joinToString("\n") { "Synthetic line $it in this long draft" }
    }
}
