package dev.ipf.whitenoise.android.ui.conversation.composer

import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextInputSelection
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import org.junit.Assert.assertEquals

/** Checks actual text edits on every frame, including editor and button space rather than final visibility alone. */
internal fun assertDraftTopStableDuringTextEdits(
    rule: ComposeContentTestRule,
    currentValue: () -> TextFieldValue,
    sends: () -> Int,
    onFrame: () -> Unit = {},
) {
    val field = rule.onNode(hasSetTextAction())
    field.performClick().performTextInputSelection(TextRange(currentValue().text.length))
    rule.waitForIdle()
    val fieldBounds = field.fetchSemanticsNode().boundsInRoot
    val top = rule.onNodeWithTag(COMPOSER_DRAFT_TOP_TAG)
    val topBounds = top.assertIsDisplayed().fetchSemanticsNode().boundsInRoot
    val bounds = DraftTopBounds(fieldBounds, topBounds)
    rule.mainClock.autoAdvance = false
    try {
        repeat(3) {
            field.performTextInput("x")
            assertStableDraftTopFrames(rule, currentValue, sends, bounds, onFrame)
        }
        field.performTextReplacement(currentValue().text.dropLast(1))
        assertStableDraftTopFrames(rule, currentValue, sends, bounds, onFrame)
    } finally {
        rule.mainClock.autoAdvance = true
    }
}

/** Advances four separate draw frames without a final-idle shortcut that could conceal a transient missing row. */
internal fun assertStableDraftTopFrames(
    rule: ComposeContentTestRule,
    currentValue: () -> TextFieldValue,
    sends: () -> Int,
    bounds: DraftTopBounds,
    onFrame: () -> Unit = {},
) {
    val expected = currentValue()
    repeat(4) {
        rule.runOnUiThread { Snapshot.sendApplyNotifications() }
        rule.mainClock.advanceTimeByFrame()
        rule.waitForIdle()
        val field = rule.onNode(hasSetTextAction())
        val top = rule.onNodeWithTag(COMPOSER_DRAFT_TOP_TAG)
        assertEquals(bounds.editor, field.fetchSemanticsNode().boundsInRoot)
        assertEquals(bounds.action, top.assertIsDisplayed().fetchSemanticsNode().boundsInRoot)
        assertEquals(expected, currentValue())
        assertEquals(0, sends())
        onFrame()
    }
}
