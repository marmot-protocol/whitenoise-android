package dev.ipf.whitenoise.android.ui.conversation

import androidx.compose.ui.graphics.Color
import dev.ipf.whitenoise.android.ui.common.verticalEdgeFadeOpaqueRange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The arithmetic behind the transcript's dissolve into the bar above it and the composer below. */
class TranscriptEdgeFadeTest {
    /** A transcript with no height yet has nothing to mask, and must not divide by it either. */
    @Test
    fun anUnmeasuredTranscriptHasNoStops() {
        assertNull(transcriptEdgeFadeStops(heightPx = 0f, topFadePx = 28f, bottomFadePx = 28f))
    }

    /** Asking for neither fade leaves the content untouched rather than covering it in opaque black. */
    @Test
    fun noFadeLeavesTheContentAlone() {
        assertNull(transcriptEdgeFadeStops(heightPx = 600f, topFadePx = 0f, bottomFadePx = 0f))
    }

    /** A top-only fade dissolves at the bar and stays solid all the way down. */
    @Test
    fun aTopFadeDissolvesOnlyAtTheBar() {
        assertEquals(
            listOf(0f to Color.Transparent, 0.1f to Color.Black, 1f to Color.Black),
            transcriptEdgeFadeStops(heightPx = 600f, topFadePx = 60f, bottomFadePx = 0f),
        )
    }

    /**
     * The covered strip is the point of the inset: the content must be gone by the time it reaches
     * the composer, so the transparent stop lands at the strip's top edge and not at the transcript's.
     */
    @Test
    fun theBottomFadeFinishesWhereTheCoveredStripBegins() {
        val stops = transcriptEdgeFadeStops(heightPx = 1000f, topFadePx = 0f, bottomFadePx = 100f, bottomInsetPx = 200f)
        assertEquals(
            listOf(
                0f to Color.Black,
                0.7f to Color.Black,
                0.8f to Color.Transparent,
                1f to Color.Transparent,
            ),
            stops,
        )
    }

    /** Both edges fade at once, and the middle of the transcript stays fully opaque. */
    @Test
    fun bothEdgesFadeWhileTheMiddleStaysSolid() {
        val stops =
            transcriptEdgeFadeStops(heightPx = 1000f, topFadePx = 50f, bottomFadePx = 50f, bottomInsetPx = 100f)!!
        assertEquals(0f to Color.Transparent, stops.first())
        assertEquals(0.05f to Color.Black, stops[1])
        assertEquals(0.85f to Color.Black, stops[2])
        assertEquals(0.9f to Color.Transparent, stops[3])
        assertEquals(1f to Color.Transparent, stops.last())
    }

    /**
     * A short transcript cannot give both fades their full height. Sharing what is left keeps the
     * gradient in order; overrunning would put the bottom stop above the top one and invert the mask.
     */
    @Test
    fun fadesTallerThanTheTranscriptShareItRatherThanInvert() {
        val stops = transcriptEdgeFadeStops(heightPx = 100f, topFadePx = 90f, bottomFadePx = 90f)!!
        val fractions = stops.map { it.first }
        // Sharing the height lands both inner stops on the midpoint, where a rounding step in the
        // wrong direction would order them backwards and invert the mask.
        assertEquals(fractions.sorted(), fractions)
        assertTrue(fractions.all { it in 0f..1f })
        assertEquals(0.5f, fractions[1], 1e-4f)
        assertEquals(0.5f, fractions[2], 1e-4f)
        assertEquals(Color.Black, stops[1].second)
        assertEquals(Color.Black, stops[2].second)
    }

    /** A covered strip with no fade asked for still cuts cleanly at the chrome instead of showing through. */
    @Test
    fun aCoveredStripAloneStillCutsAtTheChrome() {
        val stops = transcriptEdgeFadeStops(heightPx = 1000f, topFadePx = 0f, bottomFadePx = 0f, bottomInsetPx = 200f)!!
        assertEquals(0f to Color.Black, stops.first())
        assertEquals(0.8f to Color.Transparent, stops[2])
        assertEquals(1f to Color.Transparent, stops.last())
    }

    /**
     * If chrome ever claimed the whole transcript the mask would have nothing left to reveal. Failing
     * open keeps the messages on screen, which is the recoverable direction to get this wrong.
     */
    @Test
    fun chromeSwallowingTheWholeTranscriptFailsOpen() {
        assertNull(
            transcriptEdgeFadeStops(heightPx = 400f, topFadePx = 28f, bottomFadePx = 28f, bottomInsetPx = 400f),
        )
    }

    /** The opaque interval starts exactly where the painted top stop reaches full opacity. */
    @Test
    fun theOpaqueIntervalBeginsAtThePaintedTopStop() {
        val stops = transcriptEdgeFadeStops(heightPx = 600f, topFadePx = 28f, bottomFadePx = 60f)!!
        val opaque = verticalEdgeFadeOpaqueRange(heightPx = 600f, topFadePx = 28f, bottomFadePx = 60f)!!

        assertEquals(stops[1].first * 600f, opaque.start, 0.001f)
        assertEquals(Color.Black, stops[1].second)
        assertEquals("the bottom band begins at the composer edge", stops[2].first * 600f, opaque.endInclusive, 0.001f)
    }

    /** Without an active top band the whole span above the composer is opaque, with no duplicate inset. */
    @Test
    fun anInactiveTopFadeLeavesTheTopOpaque() {
        assertEquals(0f..540f, verticalEdgeFadeOpaqueRange(heightPx = 600f, topFadePx = 0f, bottomFadePx = 60f))
        assertEquals(0f..600f, verticalEdgeFadeOpaqueRange(heightPx = 600f, topFadePx = 0f, bottomFadePx = 0f))
    }

    /** Bands squeezed into a short window shrink together, and the opaque interval follows the painted stops. */
    @Test
    fun aShortWindowShrinksTheOpaqueEdgeWithTheBands() {
        val stops = transcriptEdgeFadeStops(heightPx = 100f, topFadePx = 90f, bottomFadePx = 90f)!!
        val opaque = verticalEdgeFadeOpaqueRange(heightPx = 100f, topFadePx = 90f, bottomFadePx = 90f)

        assertEquals("the bands meet, leaving nothing at full opacity", stops[1].first, stops[2].first, 0.001f)
        assertNull(opaque)
    }

    /** The ordinary-viewport band cap limits the opaque edge exactly as it limits the paint. */
    @Test
    fun theBandCapLimitsTheOpaqueEdge() {
        val opaque =
            verticalEdgeFadeOpaqueRange(heightPx = 80f, topFadePx = 28f, bottomFadePx = 0f, maxBandFraction = 0.25f)

        assertEquals(20f..80f, opaque)
    }
}
