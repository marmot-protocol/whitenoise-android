package dev.ipf.whitenoise.android.ui.navigation

import androidx.compose.ui.geometry.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
}
