package dev.ipf.whitenoise.android.ui.conversation

import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseSpacing
import org.junit.Assert.assertEquals
import org.junit.Test

/** The cluster gap that opens a sender run, and the cases that must not add it. */
class ConversationRowSpacingTest {
    /** A new sender after another sender's bubble opens the prototype's cluster gap. */
    @Test
    fun newSenderOpensTheClusterGap() {
        assertEquals(
            WhiteNoiseSpacing.ConversationCluster,
            conversationClusterTopGap(sameSenderAsOlderBubble = false, followsUnreadDivider = false),
        )
    }

    /** Rows inside one sender's run keep only the list spacing. */
    @Test
    fun sameSenderKeepsOnlyTheListSpacing() {
        assertEquals(
            0.dp,
            conversationClusterTopGap(sameSenderAsOlderBubble = true, followsUnreadDivider = false),
        )
    }

    /** The first unread row keeps the divider's own interval instead of stacking the cluster gap on it. */
    @Test
    fun firstUnreadRowDoesNotStackTheClusterGapOnTheDivider() {
        assertEquals(
            0.dp,
            conversationClusterTopGap(sameSenderAsOlderBubble = false, followsUnreadDivider = true),
        )
    }

    /** A same-sender first unread row is unaffected: the divider still owns the interval. */
    @Test
    fun sameSenderFirstUnreadRowKeepsTheDividerInterval() {
        assertEquals(
            0.dp,
            conversationClusterTopGap(sameSenderAsOlderBubble = true, followsUnreadDivider = true),
        )
    }
}
