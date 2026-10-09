package dev.ipf.whitenoise.android.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.view.KeyEvent
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
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import dev.ipf.whitenoise.android.PullRequestDeviceSmoke
import dev.ipf.whitenoise.android.ui.common.accountActionColors
import dev.ipf.whitenoise.android.ui.conversation.composer.ComposerPill
import dev.ipf.whitenoise.android.ui.conversation.composer.repairComposerMentionEdit
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import kotlinx.coroutines.awaitCancellation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicReference

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
        render("first second", liveIme = true)
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
            assertEquals("second", value.text.substring(value.selection.min, value.selection.max))
            assertNull(connection.get())
        }
        assertFalse(imeVisible())
        nativeAction("Copy").click()
        composeRule.runOnUiThread {
            val clipboard = composeRule.activity.getSystemService(ClipboardManager::class.java)
            val copiedText =
                clipboard.primaryClip
                    ?.getItemAt(0)
                    ?.text
                    ?.toString()
            assertEquals("second", copiedText)
        }
        assertFalse(imeVisible())
        editor.performTouchInput { click() }
        composeRule.waitUntil(10_000) { connection.get() != null }
        composeRule.waitUntil(10_000) { imeVisible() }
    }

    /** The actual Android Paste action stays available before the editor acquires focus. */
    @Test
    fun emptyHiddenComposerPastesThroughNativeToolbar() {
        render("", liveIme = true)
        composeRule.runOnUiThread {
            composeRule.activity
                .getSystemService(ClipboardManager::class.java)
                .setPrimaryClip(ClipData.newPlainText("composer test", "first second"))
        }
        val editor = composeRule.onNode(hasSetTextAction())
        editor.performTouchInput {
            down(center)
            advanceEventTime(viewConfiguration.longPressTimeoutMillis + 100)
            up()
        }
        nativeAction("Paste").click()
        composeRule.waitUntil(10_000) { value.text == "first second" }
        editor.assertIsNotFocused()
        assertFalse(imeVisible())
        assertValue("first second", TextRange(12), null)
    }

    /** Detects stale surrounding-text queries without giving recomposition a frame to hide the boundary. */
    @Test
    fun deletionQuerySeesAcceptedTextBeforeNextComposeFrame() {
        render("first second")
        composeRule.onNode(hasSetTextAction()).performTouchInput { click() }
        composeRule.waitUntil(10_000) { connection.get() != null }
        edit { setSelection(12, 12) }
        composeRule.runOnIdle {
            val input = checkNotNull(connection.get())
            assertEquals("first second", input.getTextBeforeCursor(100, 0).toString())
            input.deleteSurroundingText(1, 0)
            assertEquals("first secon", value.text)
            assertEquals("first secon", input.getTextBeforeCursor(100, 0).toString())
        }
    }

    /** GrapheneOS resumes composition inside the batch that delivered a hardware Backspace. */
    @Test
    fun hardwareBackspaceQueryPreservesSeparatorBeforeRecomposition() {
        render("First mispelled")
        composeRule.onNode(hasSetTextAction()).performTouchInput { click() }
        composeRule.waitUntil(10_000) { connection.get() != null }
        edit { setSelection(15, 15) }
        composeRule.mainClock.autoAdvance = false
        try {
            composeRule.runOnUiThread {
                val input = checkNotNull(connection.get())
                input.beginBatchEdit()
                input.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL))
                input.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DEL))
            }
            composeRule.waitUntil(5_000) { value.text == "First mispelle" }
            composeRule.runOnUiThread {
                val input = checkNotNull(connection.get())
                val before = input.getTextBeforeCursor(40, InputConnection.GET_TEXT_WITH_STYLES).toString()
                assertEquals("First mispelle", before)
                val wordStart = before.lastIndexOf(' ') + 1
                assertEquals(6, wordStart)
                input.setComposingRegion(wordStart, before.length)
                input.endBatchEdit()
            }
        } finally {
            composeRule.mainClock.autoAdvance = true
        }
        composeRule.waitForIdle()
        assertValue("First mispelle", TextRange(14), TextRange(6, 14))
        for (word in listOf("mispell", "mispel", "mispe")) {
            edit { setComposingText(word, 1) }
            assertValue("First $word", TextRange(6 + word.length), TextRange(6, 6 + word.length))
        }
    }

    /** Before-frame queries preserve the untouched suffix, newline, markup, chip and UTF-16 offsets. */
    @Test
    fun hardwareDeletionQueriesPreserveMiddleMultilineAndMentionDrafts() {
        render("")
        composeRule.onNode(hasSetTextAction()).performTouchInput { click() }
        composeRule.waitUntil(10_000) { connection.get() != null }
        val mention = "@npub1" + "q".repeat(58)
        val drafts =
            listOf(
                "first second| third" to "first secon| third",
                "first\nsecond| third" to "first\nsecon| third",
                "first\n|second" to "first|second",
                "**first** second| _third_" to "**first** secon| _third_",
                "$mention first second|" to "$mention first secon|",
                "😀 first second|" to "😀 first secon|",
            )
        for ((before, after) in drafts) {
            composeRule.runOnIdle { value = valueAtMarkedCaret(before) }
            composeRule.waitForIdle()
            assertBeforeFrameHardwareDeletion(valueAtMarkedCaret(after))
        }
    }

    /** Repeated key events update the queried prefix while the untouched suffix and prior line remain intact. */
    @Test
    fun repeatedHardwareDeletionQueriesPreserveNeighboringWords() {
        render("first\nsecond third")
        composeRule.onNode(hasSetTextAction()).performTouchInput { click() }
        composeRule.waitUntil(10_000) { connection.get() != null }
        edit { setSelection(12, 12) }
        val expectedDrafts = listOf("first\nsecon| third", "first\nseco| third", "first\nsec| third")
        expectedDrafts.forEachIndexed { index, text ->
            assertBeforeFrameHardwareDeletion(valueAtMarkedCaret(text), repeatCount = index + 1)
        }
    }

    private fun valueAtMarkedCaret(text: String): TextFieldValue {
        val caret = TextRange(text.indexOf('|'))
        return TextFieldValue(text.replace("|", ""), selection = caret)
    }

    private fun assertBeforeFrameHardwareDeletion(
        expected: TextFieldValue,
        repeatCount: Int = 0,
    ) {
        composeRule.mainClock.autoAdvance = false
        try {
            composeRule.runOnUiThread {
                val input = checkNotNull(connection.get())
                input.beginBatchEdit()
                input.sendKeyEvent(KeyEvent(0, 0, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL, repeatCount))
                input.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DEL))
            }
            composeRule.waitUntil(5_000) { value.text == expected.text }
            composeRule.runOnUiThread {
                val input = checkNotNull(connection.get())
                val cursor = expected.selection.start
                assertEquals(expected.selection, value.selection)
                assertNull(value.composition)
                assertEquals(expected.text.take(cursor), input.getTextBeforeCursor(500, 0).toString())
                assertEquals(expected.text.substring(cursor), input.getTextAfterCursor(500, 0).toString())
                input.endBatchEdit()
            }
        } finally {
            composeRule.mainClock.autoAdvance = true
        }
        composeRule.waitForIdle()
        assertValue(expected.text, expected.selection, null)
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
        composeRule.runOnIdle { assertTrue(checkNotNull(connection.get()).command()) }
        composeRule.waitForIdle()
    }

    private fun nativeAction(label: String) =
        checkNotNull(
            UiDevice
                .getInstance(InstrumentationRegistry.getInstrumentation())
                .wait(Until.findObject(By.text(label)), 5_000),
        ) { "Native $label action was not displayed" }

    private fun imeVisible(): Boolean =
        composeRule.runOnUiThread {
            checkNotNull(ViewCompat.getRootWindowInsets(composeRule.activity.window.decorView))
                .isVisible(WindowInsetsCompat.Type.ime())
        }

    private fun assertValue(
        text: String,
        selection: TextRange,
        composition: TextRange?,
    ) {
        composeRule.runOnIdle {
            assertEquals(text, value.text)
            assertEquals(selection, value.selection)
            assertEquals(composition, value.composition)
        }
    }

    private fun render(
        text: String,
        liveIme: Boolean = false,
    ) {
        value = TextFieldValue(text)
        composeRule.setContent {
            InterceptPlatformTextInput(
                interceptor = { request, nextHandler ->
                    if (liveIme) {
                        nextHandler.startInputMethod(
                            object : PlatformTextInputMethodRequest {
                                override fun createInputConnection(outAttributes: EditorInfo): InputConnection =
                                    request.createInputConnection(outAttributes).also { connection.set(it) }
                            },
                        )
                    } else {
                        // Exercise the real editor connection without a second IME composing concurrently.
                        connection.set(request.createInputConnection(EditorInfo()))
                        awaitCancellation()
                    }
                },
            ) {
                WhiteNoiseTheme {
                    Surface {
                        ComposerPill(
                            actionColors = accountActionColors(appState = null),
                            textFieldValue = value,
                            readAcceptedValue = { value },
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
