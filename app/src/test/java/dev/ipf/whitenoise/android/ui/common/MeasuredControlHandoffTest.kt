package dev.ipf.whitenoise.android.ui.common

import androidx.compose.ui.geometry.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Endpoint admission prevents previously hidden controls from reviving a stale decorative origin. */
class MeasuredControlHandoffTest {
    /** Entering from search/selection with no current FAB uses direct presentation. */
    @Test
    fun disposedSourceCannotAdmitALaterTransition() {
        val handoff = MeasuredControlHandoff("fab")
        val token = Any()
        handoff.report("fab", token, Rect(250f, 600f, 306f, 656f))
        handoff.remove("fab", token)
        assertNull(handoff.captureOrigin())
    }

    /** A late disposal callback cannot remove the geometry reported by its replacement. */
    @Test
    fun replacementOwnsItsMeasuredOrigin() {
        val handoff = MeasuredControlHandoff("fab")
        val old = Any()
        val current = Any()
        val bounds = Rect(190f, 480f, 246f, 536f)
        handoff.report("fab", old, Rect(250f, 600f, 306f, 656f))
        handoff.report("fab", current, bounds)
        handoff.remove("fab", old)
        assertEquals(bounds, handoff.captureOrigin())
    }
}
