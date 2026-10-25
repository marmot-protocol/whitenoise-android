package dev.ipf.whitenoise.android.ui.screenshot

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Surface
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.whitenoise.android.core.MessageTextCopy
import dev.ipf.whitenoise.android.ui.conversation.composer.COMPOSER_EDITOR_VIEWPORT_TAG
import dev.ipf.whitenoise.android.ui.conversation.composer.COMPOSER_PILL_SURFACE_TAG
import dev.ipf.whitenoise.android.ui.conversation.composer.ComposerBar
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Native frame coverage for long clipboard Paste expansion and moving action clearance. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-rUS-w360dp-h780dp-mdpi")
class ComposerLongPasteScreenshotTest {
    @get:Rule
    val composeRule = createComposeRule()

    /** The actual collapsed Paste action grows to its visible ceiling over the complete geometry tween. */
    @Test
    fun collapsedLongClipboardPasteExpandsThroughWholeTransition() = captureLongClipboardPaste(narrow = false)

    /** A separately mounted navigation row never invalidates the settled geometry that admits it. */
    @Test
    fun narrowLongClipboardPasteKeepsNavigationAdmitted() = captureLongClipboardPaste(narrow = true)

    private fun captureLongClipboardPaste(narrow: Boolean) {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val clipboard = context.getSystemService(android.content.ClipboardManager::class.java)
        val replacement = (1..18).joinToString("\n") { "Clipboard line $it stays in the draft. ".repeat(4) }
        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("sample", replacement))
        renderBulkPasteComposer(initialDraft = "", viewportWidth = if (narrow) 200.dp else 360.dp)
        val root = composeRule.onNodeWithTag(BULK_PASTE_TAG)
        val pill = composeRule.onNodeWithTag(COMPOSER_PILL_SURFACE_TAG)
        val pasteAction =
            requireNotNull(
                composeRule
                    .onNodeWithTag("composer.paste")
                    .fetchSemanticsNode()
                    .config[SemanticsActions.OnClick]
                    .action,
            )
        val heights = mutableListOf<Float>()
        composeRule.mainClock.autoAdvance = false
        try {
            composeRule.runOnIdle { assertTrue(pasteAction()) }
            repeat(16) { frame ->
                composeRule.mainClock.advanceTimeByFrame()
                composeRule.runOnIdle { }
                heights += pill.fetchSemanticsNode().boundsInRoot.height
                assertEditorClearsPasteAndSend(frame)
                if (frame in listOf(0, 3, 7, 15)) {
                    val prefix = if (narrow) "composer_narrow_long_clipboard_paste" else "composer_long_clipboard_paste"
                    root.captureRoboImage("src/test/snapshots/${prefix}_frame_${frame + 1}.png")
                }
            }
            println("COMPOSER_LONG_CLIPBOARD_PASTE_HEIGHTS_16MS=$heights")
            assertTrue("Long Paste must still grow after 128 ms: $heights", heights[7] < heights.last() - 2f)
            if (!narrow) {
                composeRule.mainClock.advanceTimeBy(400)
                composeRule.runOnIdle { }
                println("COMPOSER_LONG_CLIPBOARD_PASTE_SETTLED_HEIGHT=${pill.fetchSemanticsNode().boundsInRoot.height}")
                root.captureRoboImage("src/test/snapshots/composer_long_clipboard_paste_settled.png")
            }
            if (narrow) {
                composeRule.mainClock.advanceTimeBy(400)
                composeRule.runOnIdle { }
                repeat(20) {
                    composeRule.mainClock.advanceTimeByFrame()
                    composeRule.onNodeWithTag("composer-draft-top").assertIsDisplayed()
                }
                root.captureRoboImage("src/test/snapshots/composer_narrow_long_clipboard_paste_navigation_settled.png")
            }
            composeRule.onNodeWithTag("composer.paste").assertIsDisplayed()
            composeRule.onNodeWithContentDescription("Send").assertIsDisplayed()
            composeRule.runOnIdle {
                val field = composeRule.onNode(hasSetTextAction()).fetchSemanticsNode().config
                assertEquals(replacement, field[SemanticsProperties.EditableText].text)
                assertEquals(TextRange(replacement.length), field[SemanticsProperties.TextSelectionRange])
            }
        } finally {
            composeRule.mainClock.autoAdvance = true
        }
    }

    private fun assertEditorClearsPasteAndSend(frame: Int) {
        val editorBounds =
            composeRule.onNodeWithTag(COMPOSER_EDITOR_VIEWPORT_TAG).fetchSemanticsNode().boundsInRoot
        val pasteBounds = composeRule.onNodeWithTag("composer.paste").fetchSemanticsNode().boundsInRoot
        val sendBounds = composeRule.onNodeWithContentDescription("Send").fetchSemanticsNode().boundsInRoot
        assertTrue(
            "Editor overlaps Paste at frame $frame: $editorBounds / $pasteBounds",
            !editorBounds.overlaps(pasteBounds),
        )
        assertTrue(
            "Editor overlaps Send at frame $frame: $editorBounds / $sendBounds",
            !editorBounds.overlaps(sendBounds),
        )
    }

    /** Renders a composer at the bottom of a fixed phone viewport, holding [initialDraft]. */
    private fun renderBulkPasteComposer(
        initialDraft: String = "Short",
        viewportWidth: Dp = 360.dp,
    ) {
        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = false) {
                Surface(Modifier.width(viewportWidth).height(720.dp).testTag(BULK_PASTE_TAG)) {
                    Box(contentAlignment = Alignment.BottomCenter) {
                        ComposerBar(
                            replyingTo = null,
                            messageTextCopy = MessageTextCopy.Default,
                            onCancelReply = {},
                            onSend = { _, _ -> },
                            initialDraft = TextFieldValue(initialDraft),
                        )
                    }
                }
            }
        }
        composeRule.waitForIdle()
    }

    private companion object {
        const val BULK_PASTE_TAG = "bulk-paste-composer"
    }
}
