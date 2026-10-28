package dev.ipf.whitenoise.android.ui.navigation

import androidx.compose.foundation.ScrollState
import androidx.compose.ui.geometry.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class FloatingDictationAnchorTest {
    @Test
    fun outgoingComposerReservesSpaceUntilItActuallyLeaves() {
        val anchor = FloatingDictationAnchor()
        val outgoing = Any()
        val incoming = Any()
        anchor.report(outgoing, Rect(0f, 500f, 360f, 780f))
        anchor.report(incoming, Rect(0f, 650f, 360f, 780f))
        assertEquals(500f, anchor.topInWindow)
        anchor.remove(outgoing)
        assertEquals(650f, anchor.topInWindow)
        anchor.remove(incoming)
        assertNull(anchor.topInWindow)
    }

    @Test
    fun translatedOffscreenNodeStopsObstructingWithoutWaitingForDisposal() {
        val anchor = FloatingDictationAnchor()
        val outgoing = Any()
        val replacement = Any()
        anchor.report(outgoing, Rect(0f, 500f, 360f, 780f))
        anchor.report(replacement, Rect(0f, 650f, 360f, 780f))
        anchor.report(outgoing, Rect.Zero)
        anchor.remove(outgoing)
        assertEquals(650f, anchor.topInWindow)
    }
    /** A clipped outgoing footer must not win over the visible replacement or its scroll owner. */
    @Test
    fun offscreenFooterCannotClipTheOnlyCapsuleAway() {
        val anchor = FloatingDictationAnchor()
        val outgoing = Any()
        val incoming = Any()
        val currentScroll = ScrollState(0)
        val viewport = Rect(0f, 600f, 360f, 680f)
        val slot = Rect(0f, 600f, 360f, 660f)
        anchor.reportFooter(outgoing, Rect(0f, 400f, 360f, 480f), slot, ScrollState(0))
        anchor.reportFooter(incoming, viewport, slot, currentScroll)
        anchor.reportFooter(outgoing, viewport = Rect.Zero)
        assertEquals(slot to viewport, anchor.scrollingCapsule)
        assertSame(currentScroll, anchor.scrollingState)
        anchor.remove(incoming)
        assertNull(anchor.scrollingCapsule)
        assertNull(anchor.scrollingState)
    }

}
