package dev.ipf.whitenoise.android.ui

import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.core.MessageTextCopy
import dev.ipf.whitenoise.android.ui.conversation.composer.ComposerBar
import dev.ipf.whitenoise.android.ui.conversation.composer.ComposerOverlayBackRegistrar
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.abs

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w360dp-h780dp-mdpi")
class ComposerExpansionBehaviorTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val app = ApplicationProvider.getApplicationContext<android.app.Application>()

    @Test
    fun expandAndCollapseKeepTheSameEditorDraft() {
        val draft = longDraft()
        render(draft)
        val automaticHeight =
            composeRule
                .onNodeWithTag(TAG)
                .fetchSemanticsNode()
                .boundsInRoot.height

        resizeHandle().performClick()
        composeRule.waitForIdle()

        val fullHeight =
            composeRule
                .onNodeWithTag(TAG)
                .fetchSemanticsNode()
                .boundsInRoot.height
        assertTrue("full screen should be materially taller than auto-grow", fullHeight > automaticHeight * 1.5f)
        composeRule.onNodeWithText(draft).assertExists()

        resizeHandle().performClick()
        composeRule.waitForIdle()

        val collapsedHeight =
            composeRule
                .onNodeWithTag(TAG)
                .fetchSemanticsNode()
                .boundsInRoot.height
        assertTrue(
            "collapse should return to the measured auto-grow height",
            abs(collapsedHeight - automaticHeight) <= 1f,
        )
        composeRule.onNodeWithText(draft).assertExists()
    }

    @Test
    fun multilineControlsShareTheBottomEdgeInReadingOrder() {
        render(longDraft())

        val emoji = composerControlBounds(R.string.open_emoji_picker)
        val attach = composerControlBounds(R.string.attach_options)
        val send = composerControlBounds(R.string.send)
        val resize = composerControlBounds(R.string.composer_resize)

        assertTrue(emoji.center.x < attach.center.x)
        assertTrue(attach.center.x < send.center.x)
        assertTrue(abs(emoji.bottom - send.bottom) <= 4f)
        assertTrue(abs(attach.bottom - send.bottom) <= 4f)
        // The semantics and gesture layer meets the accessibility target while
        // the visual handle remains in the compact 36dp top inset.
        assertTrue("resize handle should meet the 48dp touch minimum", resize.height >= 48f)
        assertTrue("resize handle should stay wide", resize.width >= 96f)
        assertResizeHandleToggleLabel(R.string.composer_expand_full_screen)
    }

    @Test
    fun twoLinesKeepTheExistingCompactComposer() {
        render("First line\nSecond line")

        composeRule
            .onNodeWithContentDescription(app.getString(R.string.composer_resize))
            .assertDoesNotExist()
    }

    @Test
    fun automaticGrowthStopsNearHalfOfTheAvailableViewport() {
        render((1..40).joinToString("\n") { "Draft line $it" })

        val height =
            composeRule
                .onNodeWithTag(TAG)
                .fetchSemanticsNode()
                .boundsInRoot.height
        // This mdpi test renders inside a 720dp-tall Surface, so the automatic
        // half-viewport ceiling is 360px; 300px proves the long draft grew.
        assertTrue("a long draft should grow well beyond the compact composer", height >= 300f)
        assertTrue("automatic growth should preserve roughly half the viewport", height <= 360f)
    }

    @Test
    fun dragHandleContinuouslyAddsTheDraggedDistance() {
        render(longDraft())
        val initialHeight =
            composeRule
                .onNodeWithTag(TAG)
                .fetchSemanticsNode()
                .boundsInRoot.height

        composeRule
            .onNodeWithContentDescription(app.getString(R.string.composer_resize))
            .performTouchInput {
                val start = center
                swipe(start, Offset(start.x, start.y - 96f), durationMillis = 320)
            }
        composeRule.waitForIdle()

        val draggedHeight =
            composeRule
                .onNodeWithTag(TAG)
                .fetchSemanticsNode()
                .boundsInRoot.height
        assertTrue("upward drag should grow the composer", draggedHeight > initialHeight + 64f)
        assertTrue("a short drag must not jump directly to full screen", draggedHeight < initialHeight + 140f)
        composeRule.onNodeWithText(longDraft()).assertExists()
    }

    @Test
    fun backFromFocusedFullScreenCollapsesAndClearsFocus() {
        var overlayCallback: OnBackInvokedCallback? = null
        var overlayPriority: Int? = null
        val registrar =
            ComposerOverlayBackRegistrar { priority, callback ->
                overlayPriority = priority
                overlayCallback = callback
                { if (overlayCallback === callback) overlayCallback = null }
            }
        val draft = longDraft()
        render(draft, registrar)
        val editor = composeRule.onNodeWithText(draft)

        editor.performClick()
        editor.assertIsFocused()
        resizeHandle().performClick()
        composeRule.waitForIdle()
        assertResizeHandleToggleLabel(R.string.composer_collapse)
        assertEquals(OnBackInvokedDispatcher.PRIORITY_OVERLAY, overlayPriority)

        composeRule.runOnIdle { checkNotNull(overlayCallback).onBackInvoked() }
        composeRule.waitForIdle()

        assertResizeHandleToggleLabel(R.string.composer_expand_full_screen)
        assertNull("losing focus must release the overlay callback", overlayCallback)
        editor.assertIsNotFocused()
    }

    private fun render(
        draft: String,
        overlayBackRegistrar: ComposerOverlayBackRegistrar? = null,
    ) {
        var value by mutableStateOf(TextFieldValue(draft))
        composeRule.setContent {
            WhiteNoiseTheme {
                Surface(modifier = Modifier.width(360.dp).height(720.dp)) {
                    Box {
                        ComposerBar(
                            replyingTo = null,
                            messageTextCopy = MessageTextCopy.Default,
                            onCancelReply = {},
                            onSend = { _, _ -> },
                            onPickFromGallery = {},
                            onPickDocument = {},
                            initialDraft = value,
                            onDraftChange = { value = it },
                            overlayBackRegistrar = overlayBackRegistrar,
                            modifier = Modifier.testTag(TAG),
                        )
                    }
                }
            }
        }
        composeRule.waitForIdle()
    }

    private fun resizeHandle() = composeRule.onNodeWithContentDescription(app.getString(R.string.composer_resize))

    // The handle is one control for both resize paths; its current tap
    // outcome (expand vs collapse) is exposed as the click action's label.
    private fun assertResizeHandleToggleLabel(labelRes: Int) {
        val label =
            resizeHandle()
                .fetchSemanticsNode()
                .config[SemanticsActions.OnClick]
                .label
        assertEquals(app.getString(labelRes), label)
    }

    private fun composerControlBounds(contentDescriptionRes: Int) =
        composeRule
            .onNodeWithContentDescription(app.getString(contentDescriptionRes))
            .fetchSemanticsNode()
            .boundsInRoot

    private fun longDraft(): String =
        "A thoughtful long message starts here.\n" +
            "It keeps growing naturally line by line.\n" +
            "The controls remain easy to reach.\n" +
            "Nothing in the draft is replaced.\n" +
            "The final paragraph stays visible while editing."

    private companion object {
        const val TAG = "expandable-composer"
    }
}
