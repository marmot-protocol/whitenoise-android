package dev.ipf.whitenoise.android.ui.common

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    /** A committed morph starts at the exact source before the destination's first layout frame. */
    @Test
    fun firstFrameUsesMeasuredSourceWithoutStretchingOrWaitingForAnimation() {
        val state = MeasuredControlHandoff("pen", morphSurface = true)
        val pen = Rect(280f, 600f, 336f, 656f)
        state.report("pen", Any(), pen)
        state.reportSurface("pen", ControlMorphSurface(Color.Black, Color.White, 16f, 1f))
        state.report("editor", Any(), Rect(12f, 550f, 348f, 656f))
        state.prepare("editor", true)
        assertTrue(state.active)
        assertEquals(pen, state.renderedBounds)
        assertEquals(16f, state.renderedRadius, 0f)
        state.finish()
        assertFalse(state.active)
        assertNull(state.renderedBounds)
    }

    /** Reduced motion and privacy/route exclusions discard the bridge synchronously. */
    @Test
    fun disabledOrMissingDestinationSettlesImmediatelyAndCannotRevive() {
        val state = MeasuredControlHandoff("pen", morphSurface = true)
        state.report("pen", Any(), Rect(280f, 600f, 336f, 656f))
        state.prepare("editor", true)
        state.prepare("pen", false)
        assertFalse(state.active)
        assertNull(state.renderedBounds)
        state.prepare(null, true)
        state.report("editor", Any(), Rect(12f, 550f, 348f, 656f))
        assertNull(state.renderedBounds)
    }
}
