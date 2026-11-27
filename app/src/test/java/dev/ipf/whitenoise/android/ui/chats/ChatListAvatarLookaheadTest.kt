package dev.ipf.whitenoise.android.ui.chats

import org.junit.Assert.assertEquals
import org.junit.Test

class ChatListAvatarLookaheadTest {
    @Test
    fun reversalMovesTheLargerWindowToTheOtherSideOfTheViewport() {
        val forward = avatarLookaheadIndices(40, 49, 100, forward = true)
        val backward = avatarLookaheadIndices(40, 49, 100, forward = false)

        assertEquals((50..81).toList() + (39 downTo 28), forward)
        assertEquals((39 downTo 8).toList() + (50..61), backward)
    }

    @Test
    fun boundsExcludeVisibleRowsAndNeverReachBeyondTheDataset() {
        assertEquals(listOf(5, 6), avatarLookaheadIndices(0, 4, 7, forward = true))
        assertEquals(listOf(1, 0), avatarLookaheadIndices(2, 6, 7, forward = false))
        assertEquals(emptyList<Int>(), avatarLookaheadIndices(0, 6, 7, forward = true))
    }
}
