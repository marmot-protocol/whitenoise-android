package dev.ipf.whitenoise.android.ui.navigation

import androidx.compose.ui.geometry.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Stacking rule shared by the jump controls, the floating dictation capsule and the pen. */
class FloatingControlStackTest {
    private val jump = Rect(300f, 700f, 348f, 748f)

    @Test
    fun jumpControlRestsOnTheBottomWhenNothingIsBelowIt() {
        assertEquals(0, floatingControlStackClearancePx(jump, capsule = null, obstacleTop = null, gapPx = GAP))
    }

    @Test
    fun jumpControlStacksAboveTheCapsuleWhenItIsPresent() {
        val capsule = Rect(200f, 720f, 360f, 760f)
        assertEquals(36, floatingControlStackClearancePx(jump, capsule, obstacleTop = 770f, gapPx = GAP))
    }

    @Test
    fun jumpControlDropsToThePenWhenTheCapsuleLeaves() {
        assertEquals(26, floatingControlStackClearancePx(jump, capsule = null, obstacleTop = 730f, gapPx = GAP))
    }

    @Test
    fun capsuleInAnotherColumnDoesNotLiftTheJumpControl() {
        val capsule = Rect(0f, 720f, 120f, 760f)
        assertEquals(0, floatingControlStackClearancePx(jump, capsule, obstacleTop = null, gapPx = GAP))
    }

    @Test
    fun liftNeverCarriesTheControlAboveItsParent() {
        val capsule = Rect(200f, 100f, 360f, 760f)
        assertEquals(
            50,
            floatingControlStackClearancePx(jump, capsule, obstacleTop = null, gapPx = GAP, topLimit = 650f),
        )
    }

    @Test
    fun columnObstacleReadIgnoresOtherColumnsAndCountsOnlyAppearAndLeave() {
        val anchor = FloatingDictationAnchor()
        val pen = Any()
        val notice = Any()
        assertNull(anchor.topInColumn(300f, 348f))
        anchor.report(pen, Rect(300f, 760f, 348f, 808f))
        anchor.report(notice, Rect(0f, 600f, 120f, 640f))
        assertEquals(760f, anchor.topInColumn(300f, 348f))
        val registered = anchor.obstacleGeneration
        anchor.report(pen, Rect(300f, 740f, 348f, 788f))
        assertEquals("Moving does not restart the settle glide", registered, anchor.obstacleGeneration)
        anchor.remove(pen)
        assertNotEquals(registered, anchor.obstacleGeneration)
        assertNull(anchor.topInColumn(300f, 348f))
    }

    private companion object {
        const val GAP = 8f
    }

    @Test
    fun onlyGroupDetailsReceiveTheComposerAsTheMembersSection() {
        assertEquals(ComposerFabEndpoint.GroupMembers, openChatComposerFabEndpoint(isDm = false, showDetails = true))
        assertEquals(ComposerFabEndpoint.Composer, openChatComposerFabEndpoint(isDm = true, showDetails = true))
        assertEquals(ComposerFabEndpoint.Composer, openChatComposerFabEndpoint(isDm = false, showDetails = false))
    }
}
