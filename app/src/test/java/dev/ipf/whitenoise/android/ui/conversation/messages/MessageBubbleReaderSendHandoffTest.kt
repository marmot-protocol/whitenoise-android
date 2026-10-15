package dev.ipf.whitenoise.android.ui.conversation.messages

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A send accepted from the Read more reader closes that reader session once, and never a newer one (#3268). */
class MessageBubbleReaderSendHandoffTest {
    /** Acceptance marks the showing session without closing it in the same frame, and the deferred close runs once. */
    @Test
    fun acceptedSendClosesTheShowingReaderOnceAfterTheDeferredDismissal() {
        val surfaces = MessageBubbleSurfaceState()
        surfaces.openExpandedFullView()
        val session = surfaces.expandedFullViewSession

        surfaces.finishExpandedReaderSend(session)

        assertTrue("the field must outlive the clear for one more frame", surfaces.expandedFullView)
        assertEquals(session, surfaces.readerSendAcceptedSession)
        assertTrue(surfaces.closeReaderAfterAcceptedSend(session))
        assertFalse(surfaces.expandedFullView)
        assertNull(surfaces.readerSendAcceptedSession)
        assertFalse("a second close is a no-op", surfaces.closeReaderAfterAcceptedSend(session))
    }

    /** A late acceptance from a reader that was closed and reopened leaves the newer reader open. */
    @Test
    fun lateAcceptanceFromAnEarlierReaderDoesNotCloseANewerOne() {
        val surfaces = MessageBubbleSurfaceState()
        surfaces.openExpandedFullView()
        val earlierSession = surfaces.expandedFullViewSession
        surfaces.expandedFullView = false
        surfaces.openExpandedFullView()

        surfaces.finishExpandedReaderSend(earlierSession)

        assertNull(surfaces.readerSendAcceptedSession)
        assertFalse(surfaces.closeReaderAfterAcceptedSend(earlierSession))
        assertTrue(surfaces.expandedFullView)
    }

    /** Reopening the reader between acceptance and the deferred close keeps the newer reader open. */
    @Test
    fun reopeningBeforeTheDeferredCloseKeepsTheNewerReader() {
        val surfaces = MessageBubbleSurfaceState()
        surfaces.openExpandedFullView()
        val session = surfaces.expandedFullViewSession
        surfaces.finishExpandedReaderSend(session)
        surfaces.expandedFullView = false
        surfaces.openExpandedFullView()

        assertNull("a new reader starts without a pending close", surfaces.readerSendAcceptedSession)
        assertFalse(surfaces.closeReaderAfterAcceptedSend(session))
        assertTrue(surfaces.expandedFullView)
    }

    /** Acceptance after the reader was dismissed neither reopens it nor asks the conversation to move. */
    @Test
    fun acceptanceAfterTheReaderWasDismissedIsIgnored() {
        val surfaces = MessageBubbleSurfaceState()
        surfaces.openExpandedFullView()
        val session = surfaces.expandedFullViewSession
        surfaces.expandedFullView = false

        surfaces.finishExpandedReaderSend(session)

        assertNull(surfaces.readerSendAcceptedSession)
        assertFalse(surfaces.closeReaderAfterAcceptedSend(session))
        assertFalse(surfaces.expandedFullView)
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
