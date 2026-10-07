package dev.ipf.whitenoise.android.ui.conversation.composer

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTextInputSelection
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.ui.common.accountActionColors
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Selection-only changes must not move a settled narrow editor or remove its separate draft navigation row. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-w360dp-h780dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ComposerSelectionSettlementTest {
    @get:Rule val composeRule = createComposeRule()
    private val draft = (1..32).joinToString("\n") { "Synthetic line $it in this long draft" }
    private var value by mutableStateOf(TextFieldValue(draft, TextRange(draft.length)))
    private val focusRequester = FocusRequester()
    private var extraHeight = 0.dp
    private var sends = 0

    /** Moving the caret and extending selection retain navigation and editor bounds on every following frame. */
    @Test
    fun selectionChangesPreserveTheSettledNarrowComposer() {
        render()
        composeRule.runOnIdle { focusRequester.requestFocus() }
        composeRule.waitForIdle()
        val field = composeRule.onNode(hasSetTextAction())
        val initialBounds = field.fetchSemanticsNode().boundsInRoot
        composeRule.onNodeWithTag(COMPOSER_DRAFT_TOP_TAG).assertIsDisplayed()
        assertEquals("fixture must exercise the separate navigation row", 48.dp, extraHeight)
        composeRule.mainClock.autoAdvance = false
        try {
            for (selection in listOf(TextRange(draft.length - 1), TextRange(draft.length - 3, draft.length - 1))) {
                field.performTextInputSelection(selection)
                repeat(4) {
                    composeRule.runOnUiThread { Snapshot.sendApplyNotifications() }
                    composeRule.mainClock.advanceTimeByFrame()
                    composeRule.waitForIdle()
                    composeRule.onNodeWithTag(COMPOSER_DRAFT_TOP_TAG).assertIsDisplayed()
                    assertEquals(initialBounds, field.fetchSemanticsNode().boundsInRoot)
                    assertEquals(48.dp, extraHeight)
                    assertEquals(draft, value.text)
                    assertEquals(selection, value.selection)
                    assertEquals(0, sends)
                }
            }
        } finally {
            composeRule.mainClock.autoAdvance = true
        }
    }

    /** Dictation occupies the narrow toolbar so draft navigation must reserve its independent 48dp row. */
    private fun render() {
        composeRule.setContent {
            WhiteNoiseTheme {
                Surface {
                    Box(Modifier.width(240.dp).height(260.dp)) {
                        ComposerPill(
                            textFieldValue = value,
                            composerFocus = focusRequester,
                            emojiPickerOpen = false,
                            onValueChange = { value = it },
                            onEmojiPickerToggle = {},
                            onAttachmentsToggle = {},
                            attachmentSheetOpen = false,
                            onPickFromGallery = {},
                            onPickDocument = null,
                            actionColors = accountActionColors(appState = null),
                            dictationControls = { Box(Modifier.width(168.dp).height(40.dp)) },
                            onImeSend = { sends++ },
                            expansionMode = ComposerExpansionMode.Manual,
                            compactMeasurementWidth = 240.dp,
                            onExtraControlsHeightChanged = { extraHeight = it },
                            modifier = Modifier.height(200.dp),
                        )
                    }
                }
            }
        }
        composeRule.waitForIdle()
    }
}
