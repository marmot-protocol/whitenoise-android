package dev.ipf.whitenoise.android.ui.chats

import org.junit.Assert.assertEquals
import org.junit.Test

/** Scroll-boundary contract for the normal, non-reversed chat-list fade. */
class ChatListEdgeFadeTest {
    /** Start, middle, end and short lists expose only the edges with content beyond them. */
    @Test
    fun scrollabilityMapsToVisibleFadeEdges() {
        assertEquals(ChatListEdgeFadeState(top = false, bottom = true), chatListEdgeFadeState(false, true))
        assertEquals(ChatListEdgeFadeState(top = true, bottom = true), chatListEdgeFadeState(true, true))
        assertEquals(ChatListEdgeFadeState(top = true, bottom = false), chatListEdgeFadeState(true, false))
        assertEquals(ChatListEdgeFadeState(top = false, bottom = false), chatListEdgeFadeState(false, false))
    }
}
