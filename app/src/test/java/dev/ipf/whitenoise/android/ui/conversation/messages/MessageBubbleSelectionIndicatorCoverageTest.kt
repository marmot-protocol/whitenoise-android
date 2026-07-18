package dev.ipf.whitenoise.android.ui.conversation.messages

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertLeftPositionInRootIsEqualTo
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.ui.chats.chatRowSelectionIcon
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class MessageBubbleSelectionIndicatorCoverageTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val selectDescription by lazy { context.getString(R.string.select) }
    private val selectedDescription by lazy { context.getString(R.string.selected) }

    @Test
    fun selectionIndicatorUsesOutlinedAndFilledIcons() {
        assertSame(Icons.Default.RadioButtonUnchecked, messageBubbleSelectionIcon(selected = false))
        assertSame(Icons.Default.CheckCircle, messageBubbleSelectionIcon(selected = true))
        assertSame(chatRowSelectionIcon(selected = false), messageBubbleSelectionIcon(selected = false))
        assertSame(chatRowSelectionIcon(selected = true), messageBubbleSelectionIcon(selected = true))
    }

    @Test
    fun selectedAndUnselectedRowsKeepSemanticsWithoutDuplicateAnnouncements() {
        val selected = mutableStateOf(false)
        renderSelectionRow(batchSelectable = true, selected = selected)

        composeRule.onNode(hasClickAction()).assertIsNotSelected()
        assertNoSelectionIconAnnouncements()

        selected.value = true
        composeRule.waitForIdle()

        composeRule.onNode(hasClickAction()).assertIsSelected()
        assertNoSelectionIconAnnouncements()
    }

    @Test
    fun selectionTapTargetWinsOverNestedBubbleClicks() {
        var rowToggles = 0
        var nestedClicks = 0
        composeRule.setContent {
            MaterialTheme {
                Box {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .messageBubbleSelectionRow(
                                selectionMode = true,
                                selected = false,
                            ),
                    ) {
                        Box(
                            Modifier
                                .testTag(BUBBLE_BODY_TAG)
                                .clickable { nestedClicks += 1 },
                        ) {
                            Text(BUBBLE_BODY)
                        }
                    }
                    MessageBubbleSelectionTapTarget(
                        selected = false,
                        batchSelectable = true,
                        onToggleSelection = { rowToggles += 1 },
                    )
                }
            }
        }

        composeRule.onNodeWithTag(BUBBLE_BODY_TAG).performTouchInput { click() }

        composeRule.runOnIdle {
            assertEquals(1, rowToggles)
            assertEquals(0, nestedClicks)
        }
    }

    @Test
    fun nonBatchSelectableRowsDisableToggle() {
        renderSelectionRow(batchSelectable = false, selected = mutableStateOf(false))

        composeRule.onNode(hasClickAction()).assertIsNotEnabled()
    }

    @Test
    fun nonBatchSelectableGutterKeepsSlotWithoutIcon() {
        renderGutter(batchSelectable = false, selected = mutableStateOf(false))

        composeRule.onNodeWithTag(SELECTION_GUTTER_TAG).assertWidthIsEqualTo(messageBubbleSelectionGutterWidth)
    }

    @Test
    fun batchSelectableGutterReservesFixedLeadingSlot() {
        renderGutter(batchSelectable = true, selected = mutableStateOf(false))

        composeRule.onNodeWithTag(SELECTION_GUTTER_TAG).assertWidthIsEqualTo(messageBubbleSelectionGutterWidth)
    }

    @Test
    fun selectionGutterReservesLeadingSlotWithoutOverlayingBubbleContent() {
        composeRule.setContent {
            MaterialTheme {
                Row(Modifier.fillMaxWidth()) {
                    MessageBubbleSelectionGutter(
                        batchSelectable = true,
                        selected = false,
                        modifier = Modifier.testTag(SELECTION_GUTTER_TAG),
                    )
                    Text(BUBBLE_BODY)
                }
            }
        }

        composeRule.onNodeWithTag(SELECTION_GUTTER_TAG).assertLeftPositionInRootIsEqualTo(0.dp)
        composeRule.onNodeWithText(BUBBLE_BODY).assertLeftPositionInRootIsEqualTo(messageBubbleSelectionGutterWidth)
    }

    @Test
    fun incomingSelectionRowKeepsGutterLeadingAndBubbleAdjacent() {
        renderDirectionalSelectionRow(mine = false, selected = false)

        composeRule.onNodeWithTag(SELECTION_GUTTER_TAG).assertLeftPositionInRootIsEqualTo(0.dp)
        composeRule.onNodeWithTag(BUBBLE_BODY_TAG).assertLeftPositionInRootIsEqualTo(messageBubbleSelectionGutterWidth)
    }

    @Test
    fun outgoingSelectionRowKeepsGutterLeadingAndBubbleTrailing() {
        renderDirectionalSelectionRow(mine = true, selected = false)

        composeRule.onNodeWithTag(SELECTION_GUTTER_TAG).assertLeftPositionInRootIsEqualTo(0.dp)
        composeRule.runOnIdle {
            val gutterBounds = composeRule.onNodeWithTag(SELECTION_GUTTER_TAG).fetchSemanticsNode().boundsInRoot
            val bubbleBounds = composeRule.onNodeWithTag(BUBBLE_BODY_TAG).fetchSemanticsNode().boundsInRoot
            assertTrue(bubbleBounds.left > gutterBounds.right)
            assertTrue(bubbleBounds.right > gutterBounds.right)
        }
    }

    @Test
    fun selectionGutterStaysVerticallyCenteredInTallRowsAcrossStates() {
        val selected = mutableStateOf(false)
        composeRule.setContent {
            MaterialTheme {
                Row(
                    modifier = Modifier.fillMaxWidth().testTag(SELECTION_ROW_TAG),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    MessageBubbleSelectionGutter(
                        batchSelectable = true,
                        selected = selected.value,
                        modifier = Modifier.testTag(SELECTION_GUTTER_TAG),
                    )
                    Column {
                        Text(TALL_BODY_LINE_ONE)
                        Text(TALL_BODY_LINE_TWO)
                        Text(TALL_BODY_LINE_THREE)
                    }
                }
            }
        }
        assertGutterVerticallyCenteredInRow()

        selected.value = true
        composeRule.waitForIdle()
        assertGutterVerticallyCenteredInRow()
    }

    private fun assertGutterVerticallyCenteredInRow() {
        composeRule.runOnIdle {
            val rowBounds = composeRule.onNodeWithTag(SELECTION_ROW_TAG).fetchSemanticsNode().boundsInRoot
            val gutterBounds = composeRule.onNodeWithTag(SELECTION_GUTTER_TAG).fetchSemanticsNode().boundsInRoot
            val rowCenterY = rowBounds.top + rowBounds.height / 2f
            val gutterCenterY = gutterBounds.top + gutterBounds.height / 2f
            assertEquals(rowCenterY, gutterCenterY, 1f)
        }
    }

    private fun renderDirectionalSelectionRow(
        mine: Boolean,
        selected: Boolean,
    ) {
        composeRule.setContent {
            MaterialTheme {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    MessageBubbleSelectionGutter(
                        batchSelectable = true,
                        selected = selected,
                        modifier = Modifier.testTag(SELECTION_GUTTER_TAG),
                    )
                    if (mine) Spacer(Modifier.weight(1f))
                    Surface(modifier = Modifier.testTag(BUBBLE_BODY_TAG)) {
                        Text(BUBBLE_BODY, modifier = Modifier.width(180.dp))
                    }
                }
            }
        }
    }

    private fun assertNoSelectionIconAnnouncements() {
        composeRule.onAllNodesWithContentDescription(selectDescription).assertCountEquals(0)
        composeRule.onAllNodesWithContentDescription(selectedDescription).assertCountEquals(0)
    }

    private fun renderGutter(
        batchSelectable: Boolean,
        selected: MutableState<Boolean>,
    ) {
        composeRule.setContent {
            MaterialTheme {
                MessageBubbleSelectionGutter(
                    batchSelectable = batchSelectable,
                    selected = selected.value,
                    modifier = Modifier.testTag(SELECTION_GUTTER_TAG),
                )
            }
        }
    }

    private fun renderSelectionRow(
        batchSelectable: Boolean,
        selected: MutableState<Boolean>,
    ) {
        composeRule.setContent {
            MaterialTheme {
                Box {
                    Row(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .messageBubbleSelectionRow(
                                    selectionMode = true,
                                    selected = selected.value,
                                ),
                    ) {
                        MessageBubbleSelectionGutter(
                            batchSelectable = batchSelectable,
                            selected = selected.value,
                        )
                        Box(Modifier.testTag(BUBBLE_BODY_TAG)) {
                            Text(BUBBLE_BODY)
                        }
                    }
                    MessageBubbleSelectionTapTarget(
                        selected = selected.value,
                        batchSelectable = batchSelectable,
                        onToggleSelection = { selected.value = !selected.value },
                    )
                }
            }
        }
    }

    private companion object {
        const val SELECTION_GUTTER_TAG = "message-selection-gutter"
        const val SELECTION_ROW_TAG = "message-selection-row"
        const val BUBBLE_BODY_TAG = "message-bubble-body"
        const val BUBBLE_BODY = "Hello from the transcript"
        const val TALL_BODY_LINE_ONE = "First line of a tall message row"
        const val TALL_BODY_LINE_TWO = "Second line keeps the row height well above the icon"
        const val TALL_BODY_LINE_THREE = "Third line anchors the gutter centering assertion"
    }
}
