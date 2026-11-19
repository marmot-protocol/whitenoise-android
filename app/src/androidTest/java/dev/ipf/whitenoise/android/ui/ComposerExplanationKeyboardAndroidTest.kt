package dev.ipf.whitenoise.android.ui

import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import dev.ipf.whitenoise.android.PullRequestDeviceSmoke
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.ui.common.Explanation
import dev.ipf.whitenoise.android.ui.common.ExplanationHost
import dev.ipf.whitenoise.android.ui.common.accountActionColors
import dev.ipf.whitenoise.android.ui.common.explainOnLongPress
import dev.ipf.whitenoise.android.ui.conversation.composer.ComposerPill
import dev.ipf.whitenoise.android.ui.conversation.composer.ExplainAboveComposer
import dev.ipf.whitenoise.android.ui.conversation.composer.composerExplanationClose
import dev.ipf.whitenoise.android.ui.conversation.composer.composerExplanationEdge
import dev.ipf.whitenoise.android.ui.conversation.composer.preImeHandlesBack
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Holding a composer control explains it above the composer while the real keyboard stays open. A modal sheet's
 * focusable window takes window focus from the editor, and Android then closes the keyboard; a fake keyboard
 * controller cannot see that, so this runs against the device's input method.
 */
@PullRequestDeviceSmoke
@RunWith(AndroidJUnit4::class)
class ComposerExplanationKeyboardAndroidTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private var draft by mutableStateOf(TextFieldValue("Draft", selection = TextRange(2)))
    private var cameraCalls = 0
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    /**
     * The explanation shows above the composer and closes with its Close button, leaving the keyboard, focus and
     * draft as they were. Back runs through the composer's own pre-IME routing, which this bare editor does not have.
     */
    @Test
    fun explainingAComposerControlKeepsTheKeyboardOpen() {
        render()
        composeRule.onNodeWithTag(EDITOR).performClick()
        composeRule.waitUntil(KEYBOARD_TIMEOUT_MS) { imeVisible() }
        val draftBefore = composeRule.runOnIdle { draft }

        composeRule.onNodeWithTag(CONTROL).performTouchInput { longClick() }
        composeRule.onNodeWithTag(SHEET).assertIsDisplayed()
        settle()
        assertTrue("the keyboard closed while the explanation showed", imeVisible())
        assertTrue("the explanation took window focus from the editor", activityHasWindowFocus())
        val sheet = composeRule.onNodeWithTag(SHEET).fetchSemanticsNode()
        val composer = composeRule.onNodeWithTag(COMPOSER).fetchSemanticsNode()
        assertTrue(
            "the explanation covers the composer",
            sheet.positionOnScreen.y + sheet.size.height <= composer.positionOnScreen.y + 1f,
        )

        composeRule.onNodeWithContentDescription(context.getString(R.string.close)).performClick()
        composeRule.waitUntil(KEYBOARD_TIMEOUT_MS) {
            composeRule.onAllNodesWithTag(SHEET).fetchSemanticsNodes().isEmpty()
        }
        settle()
        assertTrue("closing the explanation also closed the keyboard", imeVisible())
        composeRule.onNodeWithTag(EDITOR).assertIsFocused()
        composeRule.runOnIdle {
            assertEquals(draftBefore, draft)
            assertEquals(0, cameraCalls)
        }
    }

    /**
     * Back from the composer's own editor closes only the explanation, keeping the keyboard: the editor's pre-IME
     * catch takes Back before the keyboard does, which before Android 16 would otherwise hide itself first.
     */
    @Test
    fun backFromTheComposerEditorClosesOnlyTheExplanation() {
        renderComposerPill()
        val editor = composeRule.onNode(hasSetTextAction())
        editor.performClick()
        composeRule.waitUntil(KEYBOARD_TIMEOUT_MS) { imeVisible() }

        composeRule.onNodeWithTag(CONTROL).performTouchInput { longClick() }
        composeRule.onNodeWithTag(SHEET).assertIsDisplayed()
        settle()
        assertTrue("the keyboard closed while the explanation showed", imeVisible())

        UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).pressBack()
        composeRule.waitUntil(KEYBOARD_TIMEOUT_MS) {
            composeRule.onAllNodesWithTag(SHEET).fetchSemanticsNodes().isEmpty()
        }
        settle()
        assertTrue("Back closed the keyboard instead of only the explanation", imeVisible())
        editor.assertIsFocused()
    }

    /** Lets a focus change, and the keyboard hiding it would trigger, reach the window before asserting. */
    private fun settle() {
        SystemClock.sleep(SETTLE_MS)
        composeRule.waitForIdle()
    }

    /** Whether the activity window's insets report the input method as visible. */
    private fun imeVisible(): Boolean =
        composeRule.runOnUiThread {
            checkNotNull(ViewCompat.getRootWindowInsets(composeRule.activity.window.decorView))
                .isVisible(WindowInsetsCompat.Type.ime())
        }

    /** Whether the activity window, and not a focusable sheet window, holds window focus. */
    private fun activityHasWindowFocus(): Boolean =
        composeRule.runOnUiThread {
            composeRule.activity.window.decorView
                .hasWindowFocus()
        }

    /** An editor and one explained control in a composer at the bottom, above the keyboard, as in a conversation. */
    private fun render() {
        composeRule.setContent {
            WhiteNoiseTheme {
                ExplanationHost {
                    Surface(Modifier.fillMaxSize()) {
                        Column(Modifier.fillMaxSize().imePadding()) {
                            Spacer(Modifier.weight(1f))
                            ExplainAboveComposer {
                                Row(Modifier.fillMaxWidth().testTag(COMPOSER).composerExplanationEdge()) {
                                    BasicTextField(
                                        value = draft,
                                        onValueChange = { draft = it },
                                        modifier = Modifier.weight(1f).testTag(EDITOR),
                                    )
                                    Box(
                                        Modifier
                                            .size(48.dp)
                                            .testTag(CONTROL)
                                            .explainOnLongPress(EXPLANATION)
                                            .clickable { cameraCalls++ },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * The real composer editor with an explained control, wired to the explanation's pre-IME Back as ComposerBar
     * wires it, inside a composer above the keyboard.
     */
    private fun renderComposerPill() {
        composeRule.setContent {
            WhiteNoiseTheme {
                ExplanationHost {
                    Surface(Modifier.fillMaxSize()) {
                        Column(Modifier.fillMaxSize().imePadding()) {
                            Spacer(Modifier.weight(1f))
                            ExplainAboveComposer {
                                val closeExplanation = composerExplanationClose()
                                Column(Modifier.fillMaxWidth().testTag(COMPOSER).composerExplanationEdge()) {
                                    Box(Modifier.size(48.dp).testTag(CONTROL).explainOnLongPress(EXPLANATION))
                                    ComposerPill(
                                        actionColors = accountActionColors(appState = null),
                                        textFieldValue = draft,
                                        readAcceptedValue = { draft },
                                        composerFocus = remember { FocusRequester() },
                                        emojiPickerOpen = false,
                                        onValueChange = { draft = it },
                                        onEmojiPickerToggle = {},
                                        onAttachmentsToggle = {},
                                        attachmentSheetOpen = false,
                                        onPickFromGallery = null,
                                        onPickDocument = null,
                                        preImeBackEnabled = preImeHandlesBack(null, closeExplanation),
                                        onPreImeBack = { closeExplanation?.invoke() },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private companion object {
        const val COMPOSER = "composer"
        const val EDITOR = "composer.editor"
        const val CONTROL = "composer.camera"
        const val SHEET = "explanation.sheet"
        const val KEYBOARD_TIMEOUT_MS = 10_000L
        const val SETTLE_MS = 1_000L
        val EXPLANATION = Explanation("Camera", "Takes a photo or video to send in this chat.")
    }
}
