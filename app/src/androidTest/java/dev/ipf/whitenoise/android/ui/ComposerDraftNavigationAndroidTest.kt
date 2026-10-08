package dev.ipf.whitenoise.android.ui

import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextInputSelection
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.ipf.whitenoise.android.PullRequestDeviceSmoke
import dev.ipf.whitenoise.android.core.MessageTextCopy
import dev.ipf.whitenoise.android.ui.conversation.composer.COMPOSER_DRAFT_TOP_TAG
import dev.ipf.whitenoise.android.ui.conversation.composer.ComposerBar
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Uses the production bar, real resized Android window and docked IME with a synthetic, unsent draft. */
@PullRequestDeviceSmoke
@RunWith(AndroidJUnit4::class)
class ComposerDraftNavigationAndroidTest {
    @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()
    private var value by mutableStateOf(TextFieldValue(longDraft, TextRange(longDraft.length)))
    private var sends = 0

    /** Character edits remain stable with the actual keyboard, and hide/show reflow preserves draft navigation. */
    @Test
    fun typingAndRealImeReflowPreserveNavigation() {
        renderWithKeyboard()
        val field = composeRule.onNode(hasSetTextAction())
        val bounds = field.fetchSemanticsNode().boundsInRoot
        val action = composeRule.onNodeWithTag(COMPOSER_DRAFT_TOP_TAG)
        val actionBounds = action.fetchSemanticsNode().boundsInRoot
        composeRule.mainClock.autoAdvance = false
        try {
            repeat(3) {
                field.performTextInput("x")
                repeat(4) {
                    composeRule.mainClock.advanceTimeByFrame()
                    composeRule.waitForIdle()
                    action.assertIsDisplayed()
                    assertEquals(bounds, field.fetchSemanticsNode().boundsInRoot)
                    assertEquals(actionBounds, action.fetchSemanticsNode().boundsInRoot)
                    field.assertIsFocused()
                    assertTrue(imeVisible())
                }
            }
        } finally {
            composeRule.mainClock.autoAdvance = true
        }
        val edited = value
        changeKeyboardVisibility(show = false)
        action.assertIsDisplayed()
        changeKeyboardVisibility(show = true)
        action.assertIsDisplayed()
        assertEquals(edited, value)
        assertEquals(0, sends)
    }

    /** The top action interrupts active reading momentum without changing focus, keyboard, draft or selection. */
    @Test
    fun topActionCancelsFlingWithoutEditingOrKeyboardChanges() {
        renderWithKeyboard()
        val field = composeRule.onNode(hasSetTextAction())
        val before = value
        composeRule.onNodeWithTag(COMPOSER_DRAFT_TOP_TAG).performClick()
        composeRule.waitForIdle()
        composeRule.mainClock.autoAdvance = false
        try {
            field.performTouchInput {
                down(Offset(center.x, height - 8f))
                moveTo(Offset(center.x, height / 2f), delayMillis = 16)
                moveTo(Offset(center.x, 4f), delayMillis = 16)
                up()
            }
            val releaseScroll = scroll()
            composeRule.mainClock.advanceTimeByFrame()
            composeRule.waitForIdle()
            assertTrue("fixture must have active momentum", scroll() > releaseScroll)
            composeRule.onNodeWithTag(COMPOSER_DRAFT_TOP_TAG).assertIsDisplayed().performClick()
            composeRule.mainClock.advanceTimeByFrame()
            composeRule.waitForIdle()
            assertEquals(0f, scroll(), 1f)
            composeRule.mainClock.advanceTimeBy(FLING_OBSERVATION_MS)
            composeRule.waitForIdle()
            assertEquals("cancelled momentum must not resume", 0f, scroll(), 1f)
            composeRule.onNodeWithTag(COMPOSER_DRAFT_TOP_TAG).assertDoesNotExist()
            field.assertIsFocused()
            assertTrue(imeVisible())
            assertEquals(before, value)
            assertEquals(0, sends)
        } finally {
            composeRule.mainClock.autoAdvance = true
        }
    }

    /** Owns no account, draft store or send transport; only real platform geometry and the production composer. */
    @Suppress("DEPRECATION")
    private fun renderWithKeyboard() {
        composeRule.runOnUiThread {
            composeRule.activity.enableEdgeToEdge()
            composeRule.activity.window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
        composeRule.setContent {
            WhiteNoiseTheme {
                Surface(Modifier.fillMaxSize()) {
                    Box(Modifier.fillMaxSize().imePadding(), contentAlignment = Alignment.BottomCenter) {
                        ComposerBar(
                            replyingTo = null,
                            messageTextCopy = MessageTextCopy.Default,
                            onCancelReply = {},
                            onSend = { _, _ -> sends++ },
                            onPickFromGallery = {},
                            onPickDocument = {},
                            initialDraft = value,
                            onDraftChange = { value = it },
                        )
                    }
                }
            }
        }
        val field = composeRule.onNode(hasSetTextAction())
        field.performClick().performTextInputSelection(TextRange(value.text.length))
        composeRule.waitUntil(KEYBOARD_TIMEOUT_MS) { imeVisible() }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(COMPOSER_DRAFT_TOP_TAG).assertIsDisplayed()
    }

    /** Reads the actual platform inset rather than substituting a test-only available-height change. */
    private fun imeVisible(): Boolean =
        composeRule.runOnUiThread {
            ViewCompat.getRootWindowInsets(composeRule.activity.window.decorView)
                ?.isVisible(WindowInsetsCompat.Type.ime()) == true
        }

    /** Exercises actual keyboard reflow while retaining the same editor owner and selection. */
    private fun changeKeyboardVisibility(show: Boolean) {
        composeRule.runOnUiThread {
            val controller = ViewCompat.getWindowInsetsController(composeRule.activity.window.decorView)
            checkNotNull(controller)
            if (show) controller.show(WindowInsetsCompat.Type.ime()) else controller.hide(WindowInsetsCompat.Type.ime())
        }
        composeRule.waitUntil(KEYBOARD_TIMEOUT_MS) { imeVisible() == show }
        composeRule.waitForIdle()
    }

    /** Uses the production editor's exported scroll owner to distinguish a live fling from a final-idle result. */
    private fun scroll(): Float =
        composeRule.onNode(hasSetTextAction()).fetchSemanticsNode()
            .config[SemanticsProperties.VerticalScrollAxisRange].value()

    private companion object {
        const val KEYBOARD_TIMEOUT_MS = 10_000L
        const val FLING_OBSERVATION_MS = 1000L
        val longDraft = (1..80).joinToString("\n") { "Synthetic device draft line $it" } + "\na"
    }
}
