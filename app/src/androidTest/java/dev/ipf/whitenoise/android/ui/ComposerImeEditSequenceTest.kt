package dev.ipf.whitenoise.android.ui

import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import androidx.activity.ComponentActivity
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.InterceptPlatformTextInput
import androidx.compose.ui.platform.PlatformTextInputMethodRequest
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.ipf.whitenoise.android.PullRequestDeviceSmoke
import dev.ipf.whitenoise.android.ui.common.accountActionColors
import dev.ipf.whitenoise.android.ui.conversation.composer.ComposerPill
import dev.ipf.whitenoise.android.ui.conversation.composer.repairComposerMentionEdit
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Representative legal commands through the actual composer input connection, not an IME reproduction claim. */
@OptIn(ExperimentalComposeUiApi::class)
@PullRequestDeviceSmoke
@RunWith(AndroidJUnit4::class)
class ComposerImeEditSequenceTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val connection = AtomicReference<InputConnection?>()
    private var value by mutableStateOf(TextFieldValue())

    /** Native selection acquires focus without starting an input method; a subsequent tap enables typing. */
    @Test
    fun hiddenKeyboardSelectionDefersInputConnectionUntilTap() {
        render("first second")
        val editor = composeRule.onNode(hasSetTextAction())
        val layouts = mutableListOf<TextLayoutResult>()
        editor.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val position = layouts.single().getBoundingBox(7).center
        editor.performTouchInput {
            down(position)
            advanceEventTime(viewConfiguration.longPressTimeoutMillis + 100)
            up()
        }
        editor.assertIsFocused()
        composeRule.runOnIdle {
            assertFalse(value.selection.collapsed)
            assertNull(connection.get())
        }
        editor.performTouchInput { click() }
        composeRule.waitUntil(10_000) { connection.get() != null }
    }

    /** Detects stale surrounding-text queries without giving recomposition a frame to hide the boundary. */
    @Test
    fun deletionQuerySeesAcceptedTextBeforeNextComposeFrame() {
        render("first second")
        composeRule.onNode(hasSetTextAction()).performTouchInput { click() }
        composeRule.waitUntil(10_000) { connection.get() != null }
        composeRule.runOnIdle {
            val input = checkNotNull(connection.get())
            assertEquals("first second", input.getTextBeforeCursor(100, 0).toString())
            input.deleteSurroundingText(1, 0)
            assertEquals("first secon", value.text)
            assertEquals("first secon", input.getTextBeforeCursor(100, 0).toString())
        }
    }

    /** A trailing-space delete and a composing replacement preserve the earlier separator at each accepted value. */
    @Test
    fun composingReplacementAndBackspacePreserveAdjacentWords() {
        render("")
        composeRule.onNode(hasSetTextAction()).performTouchInput { click() }
        composeRule.waitUntil(10_000) { connection.get() != null }
        edit { commitText("first ", 1) }
        assertValue("first ", TextRange(6), null)
        edit { setComposingText("mispelled", 1) }
        assertValue("first mispelled", TextRange(15), TextRange(6, 15))
        edit { commitText("misspelled ", 1) }
        assertValue("first misspelled ", TextRange(17), null)
        edit { deleteSurroundingText(1, 0) }
        assertValue("first misspelled", TextRange(16), null)
        edit { setSelection(6, 16) }
        edit { setComposingText("mispelled", 1) }
        assertValue("first mispelled", TextRange(15), TextRange(6, 15))
        edit { finishComposingText() }
        assertValue("first mispelled", TextRange(15), null)
        repeat(3) { index ->
            edit { deleteSurroundingText(1, 0) }
            val text = "first mispelled".dropLast(index + 1)
            assertValue(text, TextRange(text.length), null)
        }
    }

    private fun edit(command: InputConnection.() -> Boolean) {
        composeRule.runOnIdle { checkNotNull(connection.get()).command() }
        composeRule.waitForIdle()
    }

    private fun assertValue(text: String, selection: TextRange, composition: TextRange?) {
        composeRule.runOnIdle {
            assertEquals(text, value.text)
            assertEquals(selection, value.selection)
            assertEquals(composition, value.composition)
        }
    }

    private fun render(text: String) {
        value = TextFieldValue(text)
        composeRule.setContent {
            InterceptPlatformTextInput(
                interceptor = { request, nextHandler ->
                    nextHandler.startInputMethod(
                        object : PlatformTextInputMethodRequest {
                            override fun createInputConnection(outAttributes: EditorInfo): InputConnection =
                                request.createInputConnection(outAttributes).also { connection.set(it) }
                        },
                    )
                },
            ) {
                WhiteNoiseTheme {
                    Surface {
                        ComposerPill(
                            actionColors = accountActionColors(appState = null),
                            textFieldValue = value,
                            composerFocus = remember { FocusRequester() },
                            emojiPickerOpen = false,
                            onValueChange = { value = repairComposerMentionEdit(value, it, true) },
                            onEmojiPickerToggle = {},
                            onAttachmentsToggle = {},
                            attachmentSheetOpen = false,
                            onPickFromGallery = null,
                            onPickDocument = null,
                        )
                    }
                }
            }
        }
    }
}
