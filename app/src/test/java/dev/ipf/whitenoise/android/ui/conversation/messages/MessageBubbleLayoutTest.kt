package dev.ipf.whitenoise.android.ui.conversation.messages

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

class MessageBubbleLayoutTest {
    @Test
    fun longMessagesCollapseOnlyAfterFiftyTwoRenderedLines() {
        assertEquals(52, MESSAGE_COLLAPSE_LINE_LIMIT)
    }

    @Test
    fun bubbleColumnLeavesAFixedOppositeGutter() {
        assertEquals(
            352.dp,
            messageBubbleColumnMaxWidth(
                containerWidth = 400.dp,
                selectionGutterWidth = 0.dp,
                senderAvatarSlotWidth = 0.dp,
            ),
        )
        assertEquals(
            312.dp,
            messageBubbleColumnMaxWidth(
                containerWidth = 400.dp,
                selectionGutterWidth = 0.dp,
                senderAvatarSlotWidth = 40.dp,
            ),
        )
        assertEquals(
            312.dp,
            messageBubbleColumnMaxWidth(
                containerWidth = 400.dp,
                selectionGutterWidth = 40.dp,
                senderAvatarSlotWidth = 40.dp,
            ),
        )
        assertEquals(
            212.dp,
            messageBubbleColumnMaxWidth(
                containerWidth = 300.dp,
                selectionGutterWidth = 40.dp,
                senderAvatarSlotWidth = 40.dp,
            ),
        )
    }

    @Test
    fun incomingGroupTextRecoversBothGuttersButKeepsSelectionSpace() {
        assertEquals(400.dp, messageBubbleTextColumnMaxWidth(400.dp, 0.dp, true))
        assertEquals(360.dp, messageBubbleTextColumnMaxWidth(400.dp, 40.dp, true))
        assertEquals(352.dp, messageBubbleTextColumnMaxWidth(400.dp, 0.dp, false))
        assertEquals(320.dp, messageBubbleTextColumnMaxWidth(400.dp, 80.dp, false))
        assertEquals(0.dp, messageBubbleTextColumnMaxWidth(20.dp, 40.dp, true))
    }

    @Test
    fun incomingGroupRichCardsKeepTheirExistingWidthContract() {
        assertEquals(312.dp, messageBubbleRichColumnMaxWidth(400.dp, 0.dp, true))
        assertEquals(312.dp, messageBubbleRichColumnMaxWidth(400.dp, 40.dp, true))
        assertEquals(352.dp, messageBubbleRichColumnMaxWidth(400.dp, 0.dp, false))
        assertEquals(0.dp, messageBubbleRichColumnMaxWidth(20.dp, 40.dp, true))
    }

    @Test
    fun fileCardsUseTheAvailableBubbleWidth() {
        assertEquals(
            392.dp,
            messageBubbleColumnMinWidth(
                hasGeneralFileCard = true,
                maxWidth = 392.dp,
            ),
        )
        assertEquals(
            312.dp,
            messageBubbleColumnMinWidth(
                hasGeneralFileCard = true,
                maxWidth = 312.dp,
            ),
        )
        assertEquals(
            132.dp,
            messageBubbleColumnMinWidth(
                hasGeneralFileCard = true,
                maxWidth = 132.dp,
            ),
        )
        assertEquals(
            androidx.compose.ui.unit.Dp.Unspecified,
            messageBubbleColumnMinWidth(
                hasGeneralFileCard = false,
                maxWidth = 392.dp,
            ),
        )
    }
}
