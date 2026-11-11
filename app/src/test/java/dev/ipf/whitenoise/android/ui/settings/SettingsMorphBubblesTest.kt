package dev.ipf.whitenoise.android.ui.settings

import androidx.compose.ui.geometry.Rect
import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsMorphBubblesTest {
    private val header = Rect(40f, 20f, 300f, 68f)
    private val first = Rect(16f, 120f, 404f, 300f)
    private val second = Rect(16f, 320f, 404f, 500f)

    @Test
    fun anchorFansOutIntoEveryCard() {
        val start = settingsMorphBubbles(listOf(header), listOf(first, second), anchor = null, fan = true, shape = 0f)
        assertEquals(listOf(header to 1f, header to 1f), start)
        val end = settingsMorphBubbles(listOf(header), listOf(first, second), anchor = null, fan = true, shape = 1f)
        assertEquals(listOf(first to 1f, second to 1f), end)
    }

    @Test
    fun cardsConvergeIntoTheAnchor() {
        val start = settingsMorphBubbles(listOf(first, second), emptyList(), anchor = header, fan = false, shape = 0f)
        assertEquals(listOf(first to 1f, second to 1f), start)
        val end = settingsMorphBubbles(listOf(first, second), emptyList(), anchor = header, fan = false, shape = 1f)
        assertEquals(listOf(header to 1f, header to 1f), end)
    }

    @Test
    fun cardsPairByIndexAndUnpairedCardsFade() {
        val half = settingsMorphBubbles(listOf(first, second), listOf(second), anchor = null, fan = false, shape = 0.5f)
        assertEquals(Rect(16f, 220f, 404f, 400f) to 1f, half[0])
        assertEquals(second to 0.5f, half[1])
    }

    @Test
    fun missingEndpointsDrawNothingToTravelTo() {
        assertEquals(emptyList<Pair<Rect, Float>>(), settingsMorphBubbles(emptyList(), listOf(first), null, true, 0.5f))
        val alone = settingsMorphBubbles(listOf(first), emptyList(), anchor = null, fan = false, shape = 1f)
        assertEquals(listOf(first to 0f), alone)
    }
}
