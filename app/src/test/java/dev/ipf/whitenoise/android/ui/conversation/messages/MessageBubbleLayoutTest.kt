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
    }

    @Test
    fun captionWidensFileCardColumnWhileBothModesClampToAvailableSpace() {
        assertEquals(
            240.dp,
            messageBubbleColumnMinWidth(
                hasGeneralFileCard = true,
                attachedToCaption = false,
                maxWidth = 392.dp,
            ),
        )
        assertEquals(
            320.dp,
            messageBubbleColumnMinWidth(
                hasGeneralFileCard = true,
                attachedToCaption = true,
                maxWidth = 392.dp,
            ),
        )
        assertEquals(
            312.dp,
            messageBubbleColumnMinWidth(
                hasGeneralFileCard = true,
                attachedToCaption = true,
                maxWidth = 312.dp,
            ),
        )
        assertEquals(
            132.dp,
            messageBubbleColumnMinWidth(
                hasGeneralFileCard = true,
                attachedToCaption = false,
                maxWidth = 132.dp,
            ),
        )
        assertEquals(
            androidx.compose.ui.unit.Dp.Unspecified,
            messageBubbleColumnMinWidth(
                hasGeneralFileCard = false,
                attachedToCaption = true,
                maxWidth = 392.dp,
            ),
        )
    }
}
