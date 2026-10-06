package dev.ipf.whitenoise.android.ui.conversation.composer

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Surface
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Real touches at the compact toolbar edges remain distinct from border resizing. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-w360dp-h780dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ComposerCompactResizeTargetsTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private lateinit var focusManager: FocusManager

    @Test
    fun downwardGroupEntryUsesToolsAndExplicitEditorFocusStillWorks() {
        render(onAction = {}, onResize = {}, onDelta = {})
        composeRule.onNodeWithTag("synthetic-transcript").performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        composeRule.onNodeWithTag("synthetic-transcript").assertIsFocused()
        // An onEnter redirect cancels the original search; assert the actual target, not its Boolean.
        composeRule.runOnIdle { focusManager.moveFocus(FocusDirection.Down) }
        composeRule.onNodeWithContentDescription(context.getString(R.string.open_emoji_picker)).assertIsFocused()
        composeRule.onNode(hasSetTextAction()).performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        composeRule.onNode(hasSetTextAction()).assertIsFocused()
    }

    @Test
    fun topEdgeToolTapsWorkAndTheGripStillResizes() {
        var actions = 0
        var resizeStarts = 0
        var delta = 0f
        render(onAction = { actions++ }, onResize = { resizeStarts++ }, onDelta = { delta += it })
        for (id in listOf(R.string.attach_options, R.string.open_emoji_picker, R.string.dictate_text)) {
            composeRule.onNodeWithContentDescription(context.getString(id)).performTouchInput {
                click(Offset(center.x, 2f))
            }
        }
        assertEquals("the top of each 48dp tool remains actionable", 3, actions)
        composeRule.onNodeWithTag(COMPOSER_DRAFT_TOP_TAG).performTouchInput {
            click(Offset(center.x, 2f))
        }
        composeRule.waitForIdle()
        assertEquals(0f, scroll(), 1f)
        assertEquals("taps must not begin resizing", 0, resizeStarts)
        composeRule.onNodeWithTag(COMPOSER_RESIZE_GESTURE_TAG).performTouchInput {
            swipe(center, center - Offset(0f, 90f), durationMillis = 160)
        }
        composeRule.waitForIdle()
        assertEquals(1, resizeStarts)
        assertTrue(delta < -40f)
    }

    @Test
    fun draggingTheEditorReadsWithoutResizing() {
        var resizeStarts = 0
        render(onAction = {}, onResize = { resizeStarts++ }, onDelta = {})
        composeRule.onNodeWithTag(COMPOSER_DRAFT_TOP_TAG).performClick()
        composeRule.waitForIdle()
        composeRule.onNode(hasSetTextAction()).performTouchInput {
            down(Offset(center.x, height - 2f))
            moveTo(Offset(center.x, 2f), delayMillis = 16)
            up()
        }
        composeRule.waitForIdle()
        assertTrue("the editor must still scroll", scroll() > 0f)
        assertEquals(0, resizeStarts)
    }

    private fun render(
        onAction: () -> Unit,
        onResize: () -> Unit,
        onDelta: (Float) -> Unit,
    ) {
        val draft = (1..40).joinToString("\n") { "Synthetic compact line $it" }
        composeRule.setContent {
            focusManager = LocalFocusManager.current
            WhiteNoiseTheme {
                Surface(Modifier.width(360.dp).height(96.dp)) {
                    Column {
                        Box(Modifier.fillMaxWidth().height(48.dp).focusable().testTag("synthetic-transcript"))
                        ComposerPill(
                            modifier = Modifier.height(48.dp),
                            textFieldValue = TextFieldValue(draft, TextRange(draft.length)),
                            composerFocus = remember { FocusRequester() },
                            emojiPickerOpen = false,
                            onValueChange = {},
                            onEmojiPickerToggle = onAction,
                            onAttachmentsToggle = onAction,
                            attachmentSheetOpen = false,
                            onPickFromGallery = {},
                            onPickDocument = {},
                            onDictation = onAction,
                            expansionMode = ComposerExpansionMode.Manual,
                            compactMeasurementWidth = 360.dp,
                            multilineControlsSuppressed = true,
                            onHeightDragStarted = onResize,
                            onHeightDrag = onDelta,
                        )
                    }
                }
            }
        }
        composeRule.waitForIdle()
    }

    private fun scroll() =
        composeRule.onNode(hasSetTextAction()).fetchSemanticsNode()
            .config[SemanticsProperties.VerticalScrollAxisRange].value()
}
