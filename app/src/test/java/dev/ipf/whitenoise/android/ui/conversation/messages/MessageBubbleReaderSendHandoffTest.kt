package dev.ipf.whitenoise.android.ui.conversation.messages

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A send accepted from the Read more reader closes that reader session once, and never a newer one (#3268). */
class MessageBubbleReaderSendHandoffTest {
    /** Acceptance in the session that is still showing closes the reader and reveals the send once. */
    @Test
    fun acceptedSendClosesTheShowingReaderAndRevealsOnce() {
        val surfaces = MessageBubbleSurfaceState()
        var reveals = 0
        surfaces.openExpandedFullView()
        val session = surfaces.expandedFullViewSession

        surfaces.finishExpandedReaderSend(session) { reveals++ }
        surfaces.finishExpandedReaderSend(session) { reveals++ }

        assertFalse(surfaces.expandedFullView)
        assertEquals(1, reveals)
    }

    /** A late acceptance from a reader that was closed and reopened leaves the newer reader open. */
    @Test
    fun lateAcceptanceFromAnEarlierReaderDoesNotCloseANewerOne() {
        val surfaces = MessageBubbleSurfaceState()
        var reveals = 0
        surfaces.openExpandedFullView()
        val earlierSession = surfaces.expandedFullViewSession
        surfaces.expandedFullView = false
        surfaces.openExpandedFullView()

        surfaces.finishExpandedReaderSend(earlierSession) { reveals++ }

        assertTrue(surfaces.expandedFullView)
        assertEquals(0, reveals)
    }

    /** Acceptance after the reader was dismissed neither reopens it nor moves the conversation. */
    @Test
    fun acceptanceAfterTheReaderWasDismissedIsIgnored() {
        val surfaces = MessageBubbleSurfaceState()
        var reveals = 0
        surfaces.openExpandedFullView()
        val session = surfaces.expandedFullViewSession
        surfaces.expandedFullView = false

        surfaces.finishExpandedReaderSend(session) { reveals++ }

        assertFalse(surfaces.expandedFullView)
        assertEquals(0, reveals)
    }

    /** Every open starts a new session. */
    @Test
    fun everyOpenStartsANewSession() {
        val surfaces = MessageBubbleSurfaceState()
        surfaces.openExpandedFullView()
        val first = surfaces.expandedFullViewSession
        surfaces.openExpandedFullView()

        assertTrue(surfaces.expandedFullView)
        assertTrue(surfaces.expandedFullViewSession > first)
    }
}
