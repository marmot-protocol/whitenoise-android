package dev.ipf.whitenoise.android.ui.conversation.composer

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.SoftwareKeyboardController
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInputSelection
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.whitenoise.android.core.MessageTextCopy
import dev.ipf.whitenoise.android.state.ComposerShortcut
import dev.ipf.whitenoise.android.state.ComposerToolbarSettings
import dev.ipf.whitenoise.android.state.composerDraftJumpShortcuts
import dev.ipf.whitenoise.android.ui.common.ExplanationHost
import dev.ipf.whitenoise.android.ui.share.emptyAppState
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The draft jumps live in the configured toolbar row and follow the draft's scroll edges. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-w360dp-h780dp-mdpi")
class ComposerDraftJumpShortcutTest {
    @get:Rule val composeRule = createComposeRule()
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val keyboard =
        object : SoftwareKeyboardController {
            override fun show() = Unit

            override fun hide() = Unit
        }
    private val longDraft = (1..40).joinToString("\n") { "Line $it remains editable" }

    @Test
    fun jumpsShareTheToolbarRowAndFollowTheScrollEdges() {
        renderLongDraft(ComposerToolbarSettings())
        composeRule.onNodeWithTag(COMPOSER_DRAFT_TOP_TAG).assertIsDisplayed()
        composeRule.onNodeWithTag(COMPOSER_DRAFT_BOTTOM_TAG).assertDoesNotExist()
        assertJumpInsideToolbarRow(COMPOSER_DRAFT_TOP_TAG)
        composeRule.onRoot().captureRoboImage("src/test/snapshots/composer_draft_jumps_at_bottom.png")

        composeRule.onNodeWithTag(COMPOSER_DRAFT_TOP_TAG).performClick()
        composeRule.waitForIdle()
        assertEquals(0f, editorScroll().value(), 0f)
        composeRule.onNodeWithTag(COMPOSER_DRAFT_TOP_TAG).assertDoesNotExist()
        composeRule.onNodeWithTag(COMPOSER_DRAFT_BOTTOM_TAG).assertIsDisplayed()
        assertJumpInsideToolbarRow(COMPOSER_DRAFT_BOTTOM_TAG)
        composeRule.onRoot().captureRoboImage("src/test/snapshots/composer_draft_jumps_at_top.png")

        composeRule.onNodeWithTag(COMPOSER_DRAFT_BOTTOM_TAG).performClick()
        composeRule.waitForIdle()
        val scroll = editorScroll()
        assertTrue("the draft overflows", scroll.maxValue() > 0f)
        assertEquals(scroll.maxValue(), scroll.value(), 0f)
        composeRule.onNodeWithTag(COMPOSER_DRAFT_BOTTOM_TAG).assertDoesNotExist()
        composeRule.onNodeWithTag(COMPOSER_DRAFT_TOP_TAG).assertIsDisplayed()
    }

    @Test
    fun shortDraftKeepsTheJumpSlotsForText() {
        render(ComposerToolbarSettings(), TextFieldValue("Short draft", TextRange(11)))
        composeRule.onNode(hasSetTextAction()).performClick()
        composeRule.mainClock.advanceTimeBy(1_000)
        composeRule.waitForIdle()
        composerDraftJumpShortcuts.forEach { shortcut ->
            composeRule.onNodeWithTag("composer.shortcut.${shortcut.id}").assertDoesNotExist()
        }
        composeRule.onNodeWithTag(COMPOSER_DRAFT_TOP_TAG).assertDoesNotExist()
        composeRule.onNodeWithTag(COMPOSER_DRAFT_BOTTOM_TAG).assertDoesNotExist()
    }

    @Test
    fun removedJumpsNeverAppearForLongDrafts() {
        val settings = ComposerToolbarSettings().without(ComposerShortcut.JumpTop).without(ComposerShortcut.JumpBottom)
        renderLongDraft(settings)
        assertTrue("the draft is scrolled away from its start", editorScroll().value() > 0f)
        composeRule.onNodeWithTag(COMPOSER_DRAFT_TOP_TAG).assertDoesNotExist()
        composeRule.onNodeWithTag(COMPOSER_DRAFT_BOTTOM_TAG).assertDoesNotExist()
    }

    private fun assertJumpInsideToolbarRow(tag: String) {
        val jump = composeRule.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
        val rows = composeRule.onAllNodesWithTag("composer.shortcuts").fetchSemanticsNodes().map { it.boundsInRoot }
        val row = rows.firstOrNull { it.contains(jump.center) }
        assertTrue("the jump belongs to a composer shortcut row", row != null)
        assertEquals(with(composeRule.density) { ComposerShortcutTarget.toPx() }, row!!.height, 1f)
        assertTrue("the jump stays within its row", jump.top >= row.top - 1f && jump.bottom <= row.bottom + 1f)
    }

    private fun editorScroll() =
        composeRule
            .onNodeWithTag(COMPOSER_EDITOR_VIEWPORT_TAG)
            .fetchSemanticsNode()
            .config[SemanticsProperties.VerticalScrollAxisRange]

    private fun renderLongDraft(settings: ComposerToolbarSettings) {
        render(settings, TextFieldValue(longDraft, TextRange(longDraft.length)))
        composeRule.onNode(hasSetTextAction()).performClick().performTextInputSelection(TextRange(longDraft.length))
        composeRule.mainClock.advanceTimeBy(1_000)
        composeRule.waitForIdle()
    }

    private fun render(
        settings: ComposerToolbarSettings,
        value: TextFieldValue,
    ) {
        context
            .getSharedPreferences("whitenoise", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .putString("composer_toolbar_v1", settings.encode())
            .commit()
        val appState = emptyAppState()
        val text = ComposerTextState(value)
        composeRule.setContent {
            CompositionLocalProvider(LocalSoftwareKeyboardController provides keyboard) {
                WhiteNoiseTheme {
                    ExplanationHost {
                        Surface {
                            Box(Modifier.width(360.dp).height(600.dp), contentAlignment = Alignment.BottomCenter) {
                                ComposerBar(
                                    appState = appState,
                                    onCustomizeToolbar = {},
                                    draftAccountRef = null,
                                    replyingTo = null,
                                    messageTextCopy = MessageTextCopy.Default,
                                    onCancelReply = {},
                                    onSend = { _, _ -> },
                                    hasPendingAttachments = false,
                                    onSendAttachments = null,
                                    softwareKeyboardController = keyboard,
                                    onComposerFocusChanged = {},
                                    overlayBackRegistrar = { _, _ -> {} },
                                    onCaptureFromCamera = {},
                                    onPickFromGallery = {},
                                    onPickDocument = {},
                                    textState = text,
                                    voiceRecordingController = null,
                                )
                            }
                        }
                    }
                }
            }
        }
        composeRule.waitForIdle()
    }
}
