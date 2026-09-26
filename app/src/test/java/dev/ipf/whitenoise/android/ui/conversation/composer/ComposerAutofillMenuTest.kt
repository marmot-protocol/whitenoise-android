package dev.ipf.whitenoise.android.ui.conversation.composer

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.contextmenu.builder.item
import androidx.compose.foundation.text.contextmenu.data.TextContextMenuItem
import androidx.compose.foundation.text.contextmenu.data.TextContextMenuKeys
import androidx.compose.foundation.text.contextmenu.modifier.appendTextContextMenuComponents
import androidx.compose.foundation.text.contextmenu.provider.LocalTextContextMenuToolbarProvider
import androidx.compose.foundation.text.contextmenu.provider.TextContextMenuDataProvider
import androidx.compose.foundation.text.contextmenu.provider.TextContextMenuProvider
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
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

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-rUS-w360dp-h780dp-mdpi")
class ComposerAutofillMenuTest {
    @get:Rule val composeRule = createComposeRule()

    private val menuProvider = CapturingTextContextMenuProvider()
    private var value by mutableStateOf(TextFieldValue())

    @Test
    fun emptyComposerLongPressHidesAutofillButKeepsPaste() {
        render("")
        longPressEditor()

        val keys = menuKeys()
        assertFalse(keys.contains(TextContextMenuKeys.AutofillKey))
        assertTrue(keys.contains(TextContextMenuKeys.PasteKey))
        composeRule.onNodeWithTag(ROOT_TAG).captureRoboImage("src/test/snapshots/composer_empty_long_press_menu.png")
    }

    @Test
    fun draftedComposerStaysEditable() {
        render("Draft message")
        composeRule.onNode(hasSetTextAction()).performTextReplacement("Updated draft")
        composeRule.runOnIdle { assertEquals("Updated draft", value.text) }
    }

    private fun render(initialText: String) {
        value = TextFieldValue(initialText)
        composeRule.setContent {
            CompositionLocalProvider(LocalTextContextMenuToolbarProvider provides menuProvider) {
                WhiteNoiseTheme {
                    Surface {
                        Box(Modifier.width(360.dp).testTag(ROOT_TAG)) {
                            ComposerPill(
                                textFieldValue = value,
                                composerFocus = FocusRequester(),
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
        @Volatile var dataProvider: TextContextMenuDataProvider? = null

        override suspend fun showTextContextMenu(dataProvider: TextContextMenuDataProvider): Nothing {
            this.dataProvider = dataProvider
            awaitCancellation()
        }
    }

    private companion object {
        const val ROOT_TAG = "composer-autofill-menu-root"
    }
}
