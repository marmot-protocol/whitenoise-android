package dev.ipf.whitenoise.android.ui

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.core.MessageTextCopy
import dev.ipf.whitenoise.android.ui.conversation.composer.COMPOSER_PILL_SURFACE_TAG
import dev.ipf.whitenoise.android.ui.conversation.composer.ComposerBar
import dev.ipf.whitenoise.android.ui.conversation.composer.ComposerTextState
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Measures the real composer owner; callbacks and accepted-send clearing remain native. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h780dp-mdpi")
class ComposerPrototypeGeometryTest {
    @get:Rule val composeRule = createComposeRule()

    private val app = ApplicationProvider.getApplicationContext<Application>()
    private var accepted: (() -> Unit)? = null
    private var sentText: String? = null

    /** Reading row keeps add emoji and send inside one48dp surface. */
    @Test
    fun readingRowKeepsAddEmojiAndSendInsideOne48dpSurface() {
        render(ComposerTextState(TextFieldValue()))
        val surface = composeRule.onNodeWithTag(COMPOSER_PILL_SURFACE_TAG).fetchSemanticsNode().boundsInRoot
        val add = actionBounds(R.string.attach_options)
        val emoji = actionBounds(R.string.open_emoji_picker)
        val send = actionBounds(R.string.send)

        assertEquals(16f, surface.left, 1f)
        assertEquals(328f, surface.width, 1f)
        assertEquals(48f, surface.height, 1f)
        assertTrue(add.center.x < emoji.center.x && emoji.center.x < send.center.x)
        assertEquals(add.center.y, emoji.center.y, 1f)
        assertEquals(emoji.center.y, send.center.y, 1f)
        assertTrue(send.center.x < surface.right && send.center.x > surface.left)
        composeRule.onNode(hasSetTextAction()).assertIsNotFocused()
        capture("composer_prototype_reading_light")
    }

    /** Editing row uses full width above actions and only clears after acceptance. */
    @Test
    fun editingRowUsesFullWidthAboveActionsAndOnlyClearsAfterAcceptance() {
        val draft = TextFieldValue("Ready to send", TextRange(2, 7))
        val state = ComposerTextState(draft)
        render(state, dark = true)
        val surface = composeRule.onNodeWithTag(COMPOSER_PILL_SURFACE_TAG).fetchSemanticsNode().boundsInRoot
        val editor = composeRule.onNode(hasSetTextAction()).fetchSemanticsNode()
        assertEquals(80f, surface.height, 1f)
        assertEquals(surface.left + 14f, editor.boundsInRoot.left, 1f)
        assertEquals(surface.right - 14f, editor.boundsInRoot.right, 1f)
        assertTrue(editor.boundsInRoot.bottom < actionBounds(R.string.send).center.y)
        assertEquals(draft.selection, editor.config[SemanticsProperties.TextSelectionRange])
        capture("composer_prototype_editing_dark")

        composeRule.onNodeWithContentDescription(app.getString(R.string.send)).performClick()
        composeRule.runOnIdle {
            assertEquals(draft.text, sentText)
            assertEquals(draft, state.valueState.value)
            assertNotNull(accepted)
            accepted?.invoke()
        }
        composeRule.waitForIdle()
        composeRule.runOnIdle { assertEquals("", state.valueState.value.text) }
    }

    /** Full width two line draft does not reserve the multiline resize header. */
    @Test
    fun fullWidthTwoLineDraftDoesNotReserveTheMultilineResizeHeader() {
        render(ComposerTextState(TextFieldValue("This full width draft should use two lines and no resize handle.")))
        val layouts = mutableListOf<TextLayoutResult>()
        composeRule.onNode(hasSetTextAction()).performSemanticsAction(SemanticsActions.GetTextLayoutResult) {
            it(layouts)
        }
        assertEquals(2, layouts.single().lineCount)
        composeRule.onNodeWithContentDescription(app.getString(R.string.composer_resize)).assertDoesNotExist()
        val surface = composeRule.onNodeWithTag(COMPOSER_PILL_SURFACE_TAG).fetchSemanticsNode().boundsInRoot
        assertEquals(104f, surface.height, 1f)
    }

    /** Narrow large text rtl preserves draft and mirrors control order. */
    @Test
    fun narrowLargeTextRtlPreservesDraftAndMirrorsControlOrder() {
        val draft = TextFieldValue("Draft", TextRange(2))
        val state = ComposerTextState(draft)
        render(state, width = 240, fontScale = 2f, direction = LayoutDirection.Rtl)
        val surface = composeRule.onNodeWithTag(COMPOSER_PILL_SURFACE_TAG).fetchSemanticsNode().boundsInRoot
        val editor = composeRule.onNode(hasSetTextAction()).fetchSemanticsNode()
        assertTrue(surface.height > 80f)
        assertTrue(editor.boundsInRoot.width > 0f)
        assertTrue(editor.boundsInRoot.left >= surface.left && editor.boundsInRoot.right <= surface.right)
        assertTrue(actionBounds(R.string.attach_options).center.x > actionBounds(R.string.open_emoji_picker).center.x)
        assertTrue(actionBounds(R.string.open_emoji_picker).center.x > actionBounds(R.string.send).center.x)
        assertEquals(draft.text, editor.config[SemanticsProperties.EditableText].text)
        assertEquals(draft.selection, editor.config[SemanticsProperties.TextSelectionRange])
        capture("composer_prototype_narrow_large_rtl")
    }

    /** Compact mode retains native expansion through the surface accessibility action. */
    @Test
    fun compactSurfaceCanExpandAndCollapseWithoutChangingTheDraftOrSelection() {
        val value = TextFieldValue("Short draft", TextRange(2, 5))
        val state = ComposerTextState(value)
        render(state)
        val originalHeight =
            composeRule
                .onNodeWithTag(COMPOSER_PILL_SURFACE_TAG)
                .fetchSemanticsNode()
                .boundsInRoot.height
        val actions =
            composeRule
                .onNodeWithTag(COMPOSER_PILL_SURFACE_TAG)
                .fetchSemanticsNode()
                .config[SemanticsActions.CustomActions]
        composeRule.runOnIdle { assertTrue(actions.single().action()) }
        composeRule.waitForIdle()
        val expandedHeight =
            composeRule
                .onNodeWithTag(COMPOSER_PILL_SURFACE_TAG)
                .fetchSemanticsNode()
                .boundsInRoot.height
        assertTrue(expandedHeight > originalHeight)
        composeRule
            .onNodeWithContentDescription(app.getString(R.string.composer_resize))
            .performSemanticsAction(SemanticsActions.OnClick) { assertTrue(it()) }
        composeRule.waitForIdle()
        assertEquals(value, state.valueState.value)
        assertEquals(
            originalHeight,
            composeRule
                .onNodeWithTag(COMPOSER_PILL_SURFACE_TAG)
                .fetchSemanticsNode()
                .boundsInRoot.height,
            1f,
        )
    }

    /** Composes the surface under test with the given fixture. */
    private fun render(
        state: ComposerTextState,
        dark: Boolean = false,
        width: Int = 360,
        fontScale: Float = 1f,
        direction: LayoutDirection = LayoutDirection.Ltr,
    ) {
        composeRule.setContent {
            CompositionLocalProvider(
                LocalDensity provides Density(1f, fontScale),
                LocalLayoutDirection provides direction,
            ) {
                WhiteNoiseTheme(darkTheme = dark) {
                    Surface {
                        Box(Modifier.width(width.dp).height(600.dp), contentAlignment = Alignment.BottomCenter) {
                            ComposerBar(
                                replyingTo = null,
                                messageTextCopy = MessageTextCopy.Default,
                                onCancelReply = {},
                                onSend = { text, onAccepted ->
                                    sentText = text
                                    accepted = onAccepted
                                },
                                onPickDocument = {},
                                textState = state,
                                modifier = Modifier.testTag("composer-prototype"),
                            )
                        }
                    }
                }
            }
        }
        composeRule.waitForIdle()
    }

    /** Bounds of the tagged action node. */
    private fun actionBounds(label: Int) =
        composeRule
            .onNodeWithContentDescription(app.getString(label))
            .fetchSemanticsNode()
            .boundsInRoot

    /** Renders the fixture and records its screenshot baseline. */
    private fun capture(name: String) {
        composeRule.onNodeWithTag("composer-prototype").captureRoboImage("src/test/snapshots/$name.png")
    }
}
