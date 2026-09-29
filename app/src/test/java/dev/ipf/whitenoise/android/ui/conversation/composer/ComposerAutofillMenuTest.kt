package dev.ipf.whitenoise.android.ui.conversation.composer

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Magnifier
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.contextmenu.builder.item
import androidx.compose.foundation.text.contextmenu.data.TextContextMenuItem
import androidx.compose.foundation.text.contextmenu.data.TextContextMenuKeys
import androidx.compose.foundation.text.contextmenu.modifier.appendTextContextMenuComponents
import androidx.compose.foundation.text.contextmenu.provider.LocalTextContextMenuToolbarProvider
import androidx.compose.foundation.text.contextmenu.provider.TextContextMenuDataProvider
import androidx.compose.foundation.text.contextmenu.provider.TextContextMenuProvider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalTextToolbar
import androidx.compose.ui.platform.TextToolbar
import androidx.compose.ui.platform.TextToolbarStatus
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import kotlinx.coroutines.awaitCancellation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-rUS-w360dp-h780dp-mdpi")
class ComposerAutofillMenuTest {
    @get:Rule val composeRule = createComposeRule()

    private val menuProvider = CapturingTextContextMenuProvider()
    private val unfocusedToolbar = CapturingTextToolbar()
    private val clipboard = ApplicationProvider.getApplicationContext<Context>().getSystemService(ClipboardManager::class.java)
    private var value by mutableStateOf(TextFieldValue())

    @Test
    fun emptyComposerLongPressHidesAutofillButKeepsPaste() {
        render("")
        composeRule.onNode(hasSetTextAction()).performTouchInput { click() }
        composeRule.onNode(hasSetTextAction()).assertIsFocused()
        longPressEditor()

        val keys = menuKeys()
        assertFalse(keys.contains(TextContextMenuKeys.AutofillKey))
        assertTrue(keys.contains(TextContextMenuKeys.PasteKey))
        composeRule.onNodeWithText("Paste").assertIsDisplayed()
        composeRule.onNodeWithTag(ROOT_TAG).captureRoboImage("src/test/snapshots/composer_empty_long_press_menu.png")
    }

    @Test
    fun unfocusedLongPressOffersPasteWithoutEnteringEditingMode() {
        clipboard.setPrimaryClip(ClipData.newPlainText("test", "Pasted text"))
        render("")
        val editor = composeRule.onNode(hasSetTextAction())
        editor.assertIsNotFocused()
        editor.performTouchInput {
            down(center)
            advanceEventTime(viewConfiguration.longPressTimeoutMillis + 100)
            up()
        }
        editor.assertIsNotFocused()
        assertEquals(null, menuProvider.dataProvider)
        assertEquals(TextToolbarStatus.Shown, unfocusedToolbar.status)
        composeRule.onNodeWithTag(ROOT_TAG).captureRoboImage("src/test/snapshots/composer_unfocused_long_press.png")
        composeRule.runOnIdle { unfocusedToolbar.selectPaste() }
        composeRule.runOnIdle {
            assertEquals("Pasted text", value.text)
            assertEquals(TextRange(value.text.length), value.selection)
        }
        editor.assertIsNotFocused()
        editor.performTouchInput {
            down(center)
            moveBy(Offset(2f, 2f))
            advanceEventTime(viewConfiguration.longPressTimeoutMillis + 100)
            up()
        }
        editor.assertIsNotFocused()
        assertEquals(null, menuProvider.dataProvider)
        editor.performTouchInput {
            down(center)
            moveBy(Offset(0f, -40f))
            up()
        }
        editor.assertIsNotFocused()
        editor.performTouchInput { click() }
        editor.assertIsFocused()
        assertEquals(TextToolbarStatus.Hidden, unfocusedToolbar.status)
    }

    @Test
    fun unfocusedLongPressReplacesOnlySelectedText() {
        clipboard.setPrimaryClip(ClipData.newPlainText("test", "new"))
        render("Old draft")
        composeRule.runOnIdle { value = value.copy(selection = TextRange(0, 3)) }
        composeRule.onNode(hasSetTextAction()).performTouchInput {
            down(center)
            advanceEventTime(viewConfiguration.longPressTimeoutMillis + 100)
            up()
        }
        composeRule.runOnIdle { unfocusedToolbar.selectPaste() }
        composeRule.runOnIdle {
            assertEquals("new draft", value.text)
            assertEquals(TextRange(3), value.selection)
        }
        composeRule.onNode(hasSetTextAction()).assertIsNotFocused()
    }

    @Test
    @Config(shadows = [ComposerMagnifierShadow::class])
    fun focusedDraftLongPressKeepsTheTextMenu() {
        render("Draft message")
        val editor = composeRule.onNode(hasSetTextAction())
        editor.performTouchInput {
            down(center)
            advanceEventTime(viewConfiguration.longPressTimeoutMillis + 100)
            up()
        }
        editor.assertIsNotFocused()
        editor.performTouchInput { click() }
        editor.assertIsFocused()
        longPressEditor()
        assertTrue(menuKeys().contains(TextContextMenuKeys.PasteKey))
    }

    @Test
    fun tapIntoUnfocusedDraftPlacesCaretAtTappedText() {
        render("Draft message")
        val editor = composeRule.onNode(hasSetTextAction())
        editor.assertIsNotFocused()
        editor.performTouchInput { click(Offset(width - 8f, center.y)) }
        editor.assertIsFocused()
        composeRule.runOnIdle { assertEquals(value.text.length, value.selection.start) }
    }

    @Test
    fun draftedComposerStaysEditable() {
        render("Draft message")
        composeRule.onNode(hasSetTextAction()).performTextReplacement("Updated draft")
        composeRule.runOnIdle { assertEquals("Updated draft", value.text) }
    }

    private fun render(initialText: String) {
        value = TextFieldValue(initialText)
        val focusRequester = FocusRequester()
        composeRule.setContent {
            CompositionLocalProvider(
                LocalTextContextMenuToolbarProvider provides menuProvider,
                LocalTextToolbar provides unfocusedToolbar,
            ) {
                WhiteNoiseTheme {
                    Surface {
                        Box(Modifier.width(360.dp).testTag(ROOT_TAG)) {
                            ComposerPill(
                                textFieldValue = value,
                                composerFocus = focusRequester,
                                emojiPickerOpen = false,
                                onValueChange = { value = it },
                                onEmojiPickerToggle = {},
                                onAttachmentsToggle = {},
                                attachmentSheetOpen = false,
                                onPickFromGallery = null,
                                onPickDocument = null,
                                modifier =
                                    Modifier.appendTextContextMenuComponents {
                                        item(TextContextMenuKeys.AutofillKey, "Auto fill") {}
                                        item(TextContextMenuKeys.PasteKey, "Paste") {}
                                    },
                            )
                            // Show the filtered menu data inside the captured root: Robolectric
                            // does not include Android's separate native toolbar window.
                            menuProvider.dataProvider?.let { provider ->
                                Surface(
                                    modifier = Modifier.align(Alignment.TopCenter).padding(top = 48.dp),
                                    shadowElevation = 8.dp,
                                ) {
                                    Row(Modifier.padding(12.dp)) {
                                        provider
                                            .data()
                                            .components
                                            .filterIsInstance<TextContextMenuItem>()
                                            .forEach { item ->
                                                Text(item.label, Modifier.padding(horizontal = 8.dp))
                                            }
                                    }
                                }
                            }
                            if (unfocusedToolbar.status == TextToolbarStatus.Shown) {
                                Surface(
                                    modifier = Modifier.align(Alignment.TopCenter).padding(top = 48.dp),
                                    shadowElevation = 8.dp,
                                ) {
                                    Text("Paste", Modifier.padding(12.dp))
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun longPressEditor() {
        composeRule.onNode(hasSetTextAction()).performTouchInput {
            down(center)
            advanceEventTime(viewConfiguration.longPressTimeoutMillis + 100)
            up()
        }
        composeRule.waitUntil(timeoutMillis = 10_000) { menuProvider.dataProvider != null }
    }

    private fun menuKeys(): List<Any> {
        var keys: List<Any> = emptyList()
        composeRule.runOnIdle {
            keys =
                checkNotNull(menuProvider.dataProvider)
                    .data()
                    .components
                    .filterIsInstance<TextContextMenuItem>()
                    .map { it.key }
        }
        return keys
    }

    private class CapturingTextContextMenuProvider : TextContextMenuProvider {
        var dataProvider by mutableStateOf<TextContextMenuDataProvider?>(null)
            private set

        override suspend fun showTextContextMenu(dataProvider: TextContextMenuDataProvider): Nothing {
            this.dataProvider = dataProvider
            awaitCancellation()
        }
    }

    private class CapturingTextToolbar : TextToolbar {
        private var paste: (() -> Unit)? = null
        override var status by mutableStateOf(TextToolbarStatus.Hidden)

        override fun showMenu(
            rect: Rect,
            onCopyRequested: (() -> Unit)?,
            onPasteRequested: (() -> Unit)?,
            onCutRequested: (() -> Unit)?,
            onSelectAllRequested: (() -> Unit)?,
        ) {
            paste = onPasteRequested
            status = TextToolbarStatus.Shown
        }

        override fun hide() {
            paste = null
            status = TextToolbarStatus.Hidden
        }

        fun selectPaste() {
            check(status == TextToolbarStatus.Shown)
            paste?.invoke()
        }
    }

    private companion object {
        const val ROOT_TAG = "composer-autofill-menu-root"
    }
}

/** Robolectric has no Surface for the platform magnifier's dismiss animation. */
@Implements(Magnifier::class)
class ComposerMagnifierShadow {
    @Implementation
    fun dismiss() = Unit
}
