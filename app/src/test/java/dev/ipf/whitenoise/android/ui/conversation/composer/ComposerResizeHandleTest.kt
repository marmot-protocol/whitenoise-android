package dev.ipf.whitenoise.android.ui.conversation.composer

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The composer's resize affordance is visible when there is something to resize. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-w360dp-h780dp-mdpi")
class ComposerResizeHandleTest {
    @get:Rule
    val composeRule = createComposeRule()

    /**
     * An expanded composer draws the grip, so the draggable top border is discoverable. Its 32 x 4dp
     * size is asserted at mdpi, where one density-independent pixel is one pixel.
     */
    @Test
    fun expandedComposerDrawsTheResizeHandle() {
        render(ComposerExpansionMode.Manual, draft = "Line one\nLine two\nLine three")
        composeRule.onNodeWithTag(COMPOSER_RESIZE_HANDLE_TAG).assertIsDisplayed()
        val handle = composeRule.onNodeWithTag(COMPOSER_RESIZE_HANDLE_TAG).fetchSemanticsNode().boundsInRoot
        assertEquals("the grip is 32dp wide", 32f, handle.width, 1f)
        assertEquals("the grip is 4dp thick", 4f, handle.height, 1f)
    }

    /** The grip sits centred on the strip, where a reader looks for a resize affordance. */
    @Test
    fun theResizeHandleIsCentredOnTheStrip() {
        render(ComposerExpansionMode.Manual, draft = "Line one\nLine two\nLine three")
        val strip = composeRule.onNodeWithTag(COMPOSER_RESIZE_GESTURE_TAG).fetchSemanticsNode().boundsInRoot
        val handle = composeRule.onNodeWithTag(COMPOSER_RESIZE_HANDLE_TAG).fetchSemanticsNode().boundsInRoot
        assertEquals("the grip is centred", strip.center.x, handle.center.x, 1f)
    }

    /**
     * Drawing the grip does not replace the resize action a screen reader uses: the pill keeps the
     * custom action that toggles the composer's height without any drag.
     */
    @Test
    fun theExpandedComposerKeepsItsAccessibleResizeAction() {
        render(ComposerExpansionMode.Manual, draft = "Line one\nLine two\nLine three")
        val actions =
            composeRule
                .onNodeWithTag(COMPOSER_PILL_SURFACE_TAG)
                .fetchSemanticsNode()
                .config
                .getOrNull(SemanticsActions.CustomActions)
        assertTrue("the pill must keep an accessible resize action", !actions.isNullOrEmpty())
    }

    /**
     * The one-line composer has nothing to resize, so it draws no grip. The draft is non-empty because
     * that is what a real one-line composer holds, and non-empty text is itself an editing request.
     */
    @Test
    fun oneLineComposerDrawsNoResizeHandle() {
        render(ComposerExpansionMode.Automatic, draft = "Line one")
        composeRule.onNodeWithTag(COMPOSER_RESIZE_HANDLE_TAG).assertDoesNotExist()
    }

    /** The drag target stays the full-width strip, not just the drawn grip. */
    @Test
    fun theDragTargetRemainsTheFullWidthStrip() {
        render(ComposerExpansionMode.Manual, draft = "Line one\nLine two\nLine three")
        val strip = composeRule.onNodeWithTag(COMPOSER_RESIZE_GESTURE_TAG).fetchSemanticsNode().boundsInRoot
        val handle = composeRule.onNodeWithTag(COMPOSER_RESIZE_HANDLE_TAG).fetchSemanticsNode().boundsInRoot
        assertTrue("the strip must stay wider than its grip", strip.width > handle.width)
    }

    private fun render(
        mode: ComposerExpansionMode,
        draft: String,
    ) {
        composeRule.setContent {
            WhiteNoiseTheme {
                Surface {
                    var value by remember { mutableStateOf(TextFieldValue(draft, TextRange(draft.length))) }
                    val focusRequester = remember { FocusRequester() }
                    Box(Modifier.width(300.dp).height(140.dp)) {
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
                            expansionMode = mode,
                            modifier = Modifier.height(140.dp),
                        )
                    }
                }
            }
        }
        composeRule.waitForIdle()
    }
}
