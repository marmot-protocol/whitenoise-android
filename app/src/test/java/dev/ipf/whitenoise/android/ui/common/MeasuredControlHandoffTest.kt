package dev.ipf.whitenoise.android.ui.common

import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/** Endpoint admission prevents previously hidden controls from reviving a stale decorative origin. */
class MeasuredControlHandoffTest {
    /** A newly created session can emerge from its measured launcher using the existing morph clock. */
    @Test
    fun firstPreparationAcceptsFiniteLauncherOriginOnlyOnce() {
        val state = MeasuredControlHandoff("floating", morphSurface = true)
        val launcher = Rect(280f, 600f, 328f, 648f)
        state.prepare("floating", true, entranceOrigin = launcher)
        assertTrue(state.active)
        assertTrue(state.entranceActive)
        assertEquals(launcher, state.renderedBounds)
        state.finish()
        state.prepare("floating", true, entranceOrigin = launcher)
        assertFalse(state.active)
        assertFalse(state.entranceActive)
    }

    /** A rapid route change keeps a genuine intermediate opacity on the captured shared clock. */
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun activeEntranceContinuationRetainsItsPresentationKindAndOpacity() =
        runTest {
            val state = MeasuredControlHandoff("floating", morphSurface = true)
            val request = state.prepare("floating", true, entranceOrigin = Rect(280f, 600f, 328f, 648f))
            assertEquals(0f, state.renderedDecorationAlpha, 0f)
            state.report("floating", Any(), Rect(160f, 500f, 328f, 548f))
            state.reportSurface("floating", ControlMorphSurface(Color.Black, Color.White, 24f, 1f, 1f))
            val frames = BroadcastFrameClock()
            val animation = launch(frames) { state.animatePrepared(request) }
            runCurrent()
            frames.sendFrame(0L)
            runCurrent()
            frames.sendFrame(16_000_000L)
            runCurrent()
            frames.sendFrame(112_000_000L)
            runCurrent()
            val origin = state.renderedBounds
            val alpha = state.renderedDecorationAlpha
            assertTrue(alpha > 0f && alpha < 1f)
            state.prepare("floating", true, continueActiveMotion = true)
            animation.cancelAndJoin()
            assertTrue(state.entranceActive)
            assertEquals(origin, state.renderedBounds)
            assertEquals(alpha, state.renderedDecorationAlpha, 0f)
            state.finish()
            assertFalse(state.entranceActive)
        }

    /** Provider prompts use the normal inline handoff, not the floating entrance's whole-control fade. */
    @Test
    fun ownerChangeDuringEntranceUsesNormalControlHandoff() {
        val state = MeasuredControlHandoff("floating", morphSurface = true)
        state.prepare("floating", true, entranceOrigin = Rect(280f, 600f, 328f, 648f))
        state.report("floating", Any(), Rect(160f, 500f, 328f, 548f))
        state.reportSurface("floating", ControlMorphSurface(Color.Black, Color.White, 24f, 1f, 1f))
        assertTrue(state.entranceActive)
        val before = state.renderedBounds
        state.report("composer", Any(), Rect(180f, 600f, 328f, 648f))
        state.reportSurface("composer", ControlMorphSurface(Color.Transparent, null, 0f, 0f, 0f))
        state.prepare("composer", true, continueActiveMotion = true)
        assertTrue("Changing owner still uses the measured master handoff", state.active)
        assertFalse("Inline commands do not inherit the floating entrance fade", state.entranceActive)
        assertEquals(before, state.renderedBounds)
    }

    /** Missing geometry, reduced motion and lock exclusions never revive a later launcher entry. */
    @Test
    fun excludedOrMissingLauncherEntrySettlesWithoutRevival() {
        val launcher = Rect(280f, 600f, 328f, 648f)
        val reduced = MeasuredControlHandoff("floating", morphSurface = true)
        reduced.prepare("floating", false, entranceOrigin = launcher)
        assertFalse(reduced.active)
        reduced.prepare("floating", true, entranceOrigin = launcher)
        assertFalse(reduced.active)
        val hidden = MeasuredControlHandoff(null, morphSurface = true)
        hidden.prepare(null, true, entranceOrigin = launcher)
        assertFalse(hidden.active)
        val missing = MeasuredControlHandoff("floating", morphSurface = true)
        missing.prepare("floating", true)
        missing.prepare("floating", true, entranceOrigin = launcher)
        assertFalse(missing.active)
        val invalid = MeasuredControlHandoff("floating", morphSurface = true)
        invalid.prepare("floating", true, entranceOrigin = Rect(Float.NaN, 0f, 48f, 48f))
        assertFalse(invalid.active)
    }

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

    /** Both endpoints ride the page slide; the shape tracks the sliding target and lands on it. */
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun morphFollowsATargetCarriedByThePageSlide() =
        runTest {
            val state = MeasuredControlHandoff("composer", morphSurface = true, followMovingTarget = true)
            state.report("composer", Any(), Rect(0f, 900f, 400f, 960f))
            val members = Any()
            val request = state.prepare("members", true)
            val slide = 48f
            state.report("members", members, Rect(16f + slide, 300f, 384f + slide, 600f))
            val frames = BroadcastFrameClock()
            val animation = launch(frames) { state.animatePrepared(request) }
            runCurrent()
            frames.sendFrame(0L)
            runCurrent()
            var gap = Float.MAX_VALUE
            for (frame in 1..12) {
                frames.sendFrame(frame * 16_000_000L)
                runCurrent()
                val offset = slide * (1f - frame / 12f)
                state.report("members", members, Rect(16f + offset, 300f, 384f + offset, 600f))
                assertTrue("A moving target must not settle the morph early", state.active)
                val trailing = abs(state.renderedBounds!!.top - state.targetBounds!!.top)
                assertTrue("The shape must close on its sliding target every frame", trailing <= gap)
                gap = trailing
            }
            val landing = state.renderedBounds!!
            // The slide has already stopped: the carried edge sits on the target, not 48px behind it.
            assertEquals(16f, landing.left, 1f)
            // Only the last 8ms of the 200ms morph clock remain on the 600px vertical travel.
            assertEquals(300f, landing.top, 8f)
            frames.sendFrame(240_000_000L)
            runCurrent()
            animation.cancelAndJoin()
            assertFalse(state.active)
            assertEquals(Rect(16f, 300f, 384f, 600f), state.targetBounds)
        }

    private fun phaseFixture() =
        MeasuredControlHandoff("pen", morphSurface = true).also { state ->
            state.report("pen", Any(), Rect(280f, 600f, 336f, 656f))
            state.report("editor", Any(), Rect(12f, 550f, 348f, 656f))
            state.reportSurface("pen", ControlMorphSurface(Color.Black, Color.White, 16f, 1f, 1f))
            state.reportSurface("editor", ControlMorphSurface(Color.Black, Color.White, 24f, 1f, 0f))
        }
}
