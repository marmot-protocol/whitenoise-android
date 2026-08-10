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
        // Incoming group rows no longer reserve a standalone avatar lane; at 400dp
        // width they recover 352dp max (48dp opposite gutter only).
        assertEquals(
            352.dp,
            messageBubbleColumnMaxWidth(
                containerWidth = 400.dp,
                selectionGutterWidth = 0.dp,
            ),
        )
        assertEquals(
            312.dp,
            messageBubbleColumnMaxWidth(
                containerWidth = 400.dp,
                selectionGutterWidth = 40.dp,
            ),
        )
    }
}
