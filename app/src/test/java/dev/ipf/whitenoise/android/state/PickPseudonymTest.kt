package dev.ipf.whitenoise.android.state

import org.junit.Assert.assertEquals
import org.junit.Test

class PickPseudonymTest {
    /** A suggestion that repeats the current name is redrawn. */
    @Test
    fun redrawsWhileTheSuggestionRepeatsTheCurrentName() {
        val draws = ArrayDeque(listOf("Quiet Otter", "Quiet Otter", "Brave Heron"))
        assertEquals("Brave Heron", pickPseudonym(excluding = "Quiet Otter") { draws.removeFirst() })
    }

    /** Without a name to avoid the first draw is used. */
    @Test
    fun usesTheFirstDrawWhenNothingIsExcluded() {
        assertEquals("Quiet Otter", pickPseudonym(excluding = null) { "Quiet Otter" })
    }

    /** A generator that only ever repeats the excluded name gives up after a bounded number of draws. */
    @Test
    fun givesUpAfterBoundedDraws() {
        var calls = 0
        assertEquals(
            "Same",
            pickPseudonym(excluding = "Same") {
                calls += 1
                "Same"
            },
        )
        assertEquals(8, calls)
    }
}
