package dev.ipf.whitenoise.android.ui.chats

import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.OutgoingMessageIndicator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w360dp-h780dp-mdpi")
class ChatRowDeliveryIndicatorLayoutTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test
    fun outgoingStatusOccupiesTrailingEdgeAndPreviewUsesRemainingWidth() {
        val sentDescription = context.getString(R.string.sent)

        composeRule.setContent {
            MaterialTheme {
                ChatRowPreviewLine(
                    preview = AnnotatedString(SHORT_PREVIEW),
                    fontStyle = FontStyle.Normal,
                    deliveryIndicator = OutgoingMessageIndicator.Sent,
                    modifier = Modifier.width(PREVIEW_WIDTH).testTag(ROW_TAG),
                )
            }
        }

        composeRule.runOnIdle {
            val rowBounds = composeRule.onNodeWithTag(ROW_TAG).fetchSemanticsNode().boundsInRoot
            val previewBounds = composeRule.onNodeWithText(SHORT_PREVIEW).fetchSemanticsNode().boundsInRoot
            val statusBounds =
                composeRule.onNodeWithContentDescription(sentDescription).fetchSemanticsNode().boundsInRoot

            assertEquals(rowBounds.left, previewBounds.left, POSITION_TOLERANCE)
            assertEquals(rowBounds.right, statusBounds.right, POSITION_TOLERANCE)
            assertTrue(previewBounds.right < statusBounds.left)
        }
    }

    @Test
    fun incomingPreviewUsesTheWholeRowWithoutAnEmptyStatusSlot() {
        composeRule.setContent {
            MaterialTheme {
                ChatRowPreviewLine(
                    preview = AnnotatedString(SHORT_PREVIEW),
                    fontStyle = FontStyle.Normal,
                    deliveryIndicator = null,
                    modifier = Modifier.width(PREVIEW_WIDTH).testTag(ROW_TAG),
                )
            }
        }

        composeRule.runOnIdle {
            val rowBounds = composeRule.onNodeWithTag(ROW_TAG).fetchSemanticsNode().boundsInRoot
            val previewBounds = composeRule.onNodeWithText(SHORT_PREVIEW).fetchSemanticsNode().boundsInRoot

            assertEquals(rowBounds.left, previewBounds.left, POSITION_TOLERANCE)
            assertEquals(rowBounds.right, previewBounds.right, POSITION_TOLERANCE)
        }
    }

    @Test
    fun rtlPlacesStatusAtSemanticEnd() {
        val sentDescription = context.getString(R.string.sent)

        composeRule.setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    ChatRowPreviewLine(
                        preview = AnnotatedString(SHORT_PREVIEW),
                        fontStyle = FontStyle.Normal,
                        deliveryIndicator = OutgoingMessageIndicator.Sent,
                        modifier = Modifier.width(PREVIEW_WIDTH).testTag(ROW_TAG),
                    )
                }
            }
        }

        composeRule.runOnIdle {
            val rowBounds = composeRule.onNodeWithTag(ROW_TAG).fetchSemanticsNode().boundsInRoot
            val previewBounds = composeRule.onNodeWithText(SHORT_PREVIEW).fetchSemanticsNode().boundsInRoot
            val statusBounds =
                composeRule.onNodeWithContentDescription(sentDescription).fetchSemanticsNode().boundsInRoot

            assertEquals(rowBounds.left, statusBounds.left, POSITION_TOLERANCE)
            assertEquals(rowBounds.right, previewBounds.right, POSITION_TOLERANCE)
            assertTrue(statusBounds.right < previewBounds.left)
        }
    }

    @Test
    fun pendingToDeliveredKeepsRowPreviewAndStatusBoundsStable() {
        val sendingDescription = context.getString(R.string.sending)
        val sentDescription = context.getString(R.string.sent)
        val indicator = mutableStateOf(OutgoingMessageIndicator.Sending)

        composeRule.setContent {
            MaterialTheme {
                ChatRowPreviewLine(
                    preview = AnnotatedString(LONG_PREVIEW),
                    fontStyle = FontStyle.Normal,
                    deliveryIndicator = indicator.value,
                    modifier = Modifier.width(PREVIEW_WIDTH).testTag(ROW_TAG),
                )
            }
        }

        val before = bounds(sendingDescription)

        composeRule.runOnIdle { indicator.value = OutgoingMessageIndicator.Sent }
        composeRule.waitForIdle()

        val after = bounds(sentDescription)
        assertRectEquals(before.row, after.row)
        assertRectEquals(before.preview, after.preview)
        assertRectEquals(before.status, after.status)
    }

    @Test
    fun pendingToDeliveredTransitionExposesOnlyTheTargetStatusSemantics() {
        val sendingDescription = context.getString(R.string.sending)
        val sentDescription = context.getString(R.string.sent)
        val indicator = mutableStateOf(OutgoingMessageIndicator.Sending)

        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            MaterialTheme {
                ChatRowPreviewLine(
                    preview = AnnotatedString(SHORT_PREVIEW),
                    fontStyle = FontStyle.Normal,
                    deliveryIndicator = indicator.value,
                    modifier = Modifier.width(PREVIEW_WIDTH),
                )
            }
        }
        composeRule.onAllNodesWithContentDescription(sendingDescription).assertCountEquals(1)

        composeRule.runOnIdle { indicator.value = OutgoingMessageIndicator.Sent }
        composeRule.waitForIdle()
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.mainClock.advanceTimeBy(75)

        composeRule.onAllNodesWithContentDescription(sendingDescription).assertCountEquals(0)
        composeRule.onAllNodesWithContentDescription(sentDescription).assertCountEquals(1)
    }

    private fun bounds(statusDescription: String): PreviewBounds =
        PreviewBounds(
            row = composeRule.onNodeWithTag(ROW_TAG).fetchSemanticsNode().boundsInRoot,
            preview = composeRule.onNodeWithText(LONG_PREVIEW).fetchSemanticsNode().boundsInRoot,
            status = composeRule.onNodeWithContentDescription(statusDescription).fetchSemanticsNode().boundsInRoot,
        )

    private fun assertRectEquals(
        expected: Rect,
        actual: Rect,
    ) {
        assertEquals(expected.left, actual.left, POSITION_TOLERANCE)
        assertEquals(expected.top, actual.top, POSITION_TOLERANCE)
        assertEquals(expected.right, actual.right, POSITION_TOLERANCE)
        assertEquals(expected.bottom, actual.bottom, POSITION_TOLERANCE)
    }

    private data class PreviewBounds(
        val row: Rect,
        val preview: Rect,
        val status: Rect,
    )

    private companion object {
        const val ROW_TAG = "chat-row-preview"
        const val SHORT_PREVIEW = "Short outgoing preview"
        const val LONG_PREVIEW =
            "A deliberately long outgoing preview that must ellipsize before the fixed delivery status slot"
        const val POSITION_TOLERANCE = 1f
        val PREVIEW_WIDTH = 240.dp
    }
}
