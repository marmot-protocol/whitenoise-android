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

    /** A route change may continue active command motion, but cannot animate an already settled owner. */
    @Test
    fun sameOwnerContinuationPreservesCurrentOriginAndDoesNotReviveSettledMotion() {
        val state = phaseFixture()
        state.prepare("editor", true)
        val origin = state.renderedBounds
        val radius = state.renderedRadius
        val alpha = state.renderedDecorationAlpha
        state.prepare("editor", true, continueActiveMotion = true)
        assertTrue(state.active)
        assertEquals(origin, state.renderedBounds)
        assertEquals(radius, state.renderedRadius, 0f)
        assertEquals(alpha, state.renderedDecorationAlpha, 0f)
        state.finish()
        state.prepare("editor", true, continueActiveMotion = true)
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

    /** Destination actions cannot appear on the source's first frame; reduced motion stays usable. */
    @Test
    fun liveContentUsesContainerClockAndSettlesWithInput() {
        val state = MeasuredControlHandoff("pen", morphSurface = true)
        state.report("pen", Any(), Rect(280f, 600f, 336f, 656f))
        state.report("editor", Any(), Rect(12f, 550f, 348f, 656f))
        state.reportSurface("pen", ControlMorphSurface(Color.Black, Color.White, 16f, 1f, 1f))
        state.reportSurface("editor", ControlMorphSurface(Color.Black, Color.White, 24f, 1f, 0f))
        state.prepare("editor", true)
        assertEquals(0f, state.renderedContentAlpha, 0f)
        state.finish()
        assertEquals(1f, state.renderedContentAlpha, 0f)
        state.prepare("pen", false)
        assertEquals(1f, state.renderedContentAlpha, 0f)
    }

    /** A finish between composition and drawing keeps target chrome until its exact commit. */
    @Test
    fun targetArtworkSurvivesFinishUntilSettledCompositionAcknowledges() {
        val state = phaseFixture()
        state.prepare("editor", true)
        state.finish()
        val token = state.retirementToken
        assertFalse(state.active)
        assertNull(state.renderedBounds)
        assertTrue(state.presentationActive)
        assertEquals(Rect(12f, 550f, 348f, 656f), state.presentationBounds)
        assertEquals(24f, state.presentationRadius, 0f)
        assertEquals(0f, state.presentationDecorationAlpha, 0f)
        assertEquals(1f, state.renderedContentAlpha, 0f)
        state.report("editor", Any(), Rect(12f, 520f, 348f, 626f))
        assertEquals(Rect(12f, 520f, 348f, 626f), state.presentationBounds)
        state.finish()
        assertTrue(token === state.retirementToken)
        state.acknowledgeRetirement(null, "editor")
        assertTrue(state.presentationActive)
        state.acknowledgeRetirement(token, "pen")
        assertTrue(state.presentationActive)
        state.acknowledgeRetirement(token, "editor")
        assertFalse(state.presentationActive)
        assertNull(state.presentationBounds)
    }

    /** Gesture finish targets the new pen, and a stale commit cannot retire a newer reversal. */
    @Test
    fun reversalInvalidatesOldPresentationAcknowledgment() {
        val state = phaseFixture()
        state.prepare("editor", true)
        state.finish()
        val old = state.retirementToken
        state.prepare("pen", true)
        assertNull(state.retirementToken)
        state.acknowledgeRetirement(old, "editor")
        assertTrue(state.active)
        state.finish()
        val current = state.retirementToken
        assertTrue(current !== old)
        assertEquals(Rect(280f, 600f, 336f, 656f), state.presentationBounds)
        assertEquals(16f, state.presentationRadius, 0f)
        assertEquals(1f, state.presentationDecorationAlpha, 0f)
        state.acknowledgeRetirement(old, "pen")
        assertTrue(state.presentationActive)
        state.acknowledgeRetirement(current, "pen")
        assertFalse(state.presentationActive)
    }

    /** Hidden/disabled routes and target removal never leave a retained drawing behind. */
    @Test
    fun removedOrExcludedTargetClearsPendingArtwork() {
        val state = phaseFixture()
        state.prepare("editor", true)
        state.finish()
        state.prepare(null, true)
        assertFalse(state.presentationActive)
        state.prepare("pen", false)
        assertFalse(state.presentationActive)

        val removable = MeasuredControlHandoff("pen", morphSurface = true)
        val token = Any()
        removable.report("pen", Any(), Rect(280f, 600f, 336f, 656f))
        removable.report("editor", token, Rect(12f, 550f, 348f, 656f))
        removable.reportSurface("editor", ControlMorphSurface(Color.Black, Color.White, 24f, 1f))
        removable.prepare("editor", true)
        removable.finish()
        assertTrue(removable.presentationActive)
        removable.remove("editor", token)
        assertFalse(removable.presentationActive)
        assertNull(removable.presentationBounds)
    }

    private fun phaseFixture() =
        MeasuredControlHandoff("pen", morphSurface = true).also { state ->
            state.report("pen", Any(), Rect(280f, 600f, 336f, 656f))
            state.report("editor", Any(), Rect(12f, 550f, 348f, 656f))
            state.reportSurface("pen", ControlMorphSurface(Color.Black, Color.White, 16f, 1f, 1f))
            state.reportSurface("editor", ControlMorphSurface(Color.Black, Color.White, 24f, 1f, 0f))
        }
}
