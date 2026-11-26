package dev.ipf.whitenoise.android.ui.common

import org.junit.Assert.assertEquals
import org.junit.Test

class GroupAvatarPreparationWindowTest {
    @Test
    fun visibleRowsComeBeforeNeighborsInBothDirections() {
        val viewport = avatarViewport(40, listOf(20, 21, 22, 23))
        assertEquals(listOf(20, 21, 22, 23), viewport.visible)
        assertEquals(
            listOf(20, 21, 22, 23, 24, 19, 25, 18, 26, 17, 27, 16, 28, 15, 29, 14),
            viewport.nearby,
        )
    }

    @Test
    fun preparationUsesTheRemainingDirectionAtEitherEnd() {
        assertEquals((0..15).toList(), avatarViewport(40, listOf(0, 1)).nearby)
        assertEquals(listOf(38, 39) + (37 downTo 24), avatarViewport(40, listOf(38, 39)).nearby)
    }

    @Test
    fun emptyLayoutPrimesOnlyTheInitialBoundedWindow() {
        assertEquals((0..15).toList(), avatarViewport(30, emptyList()).nearby)
        assertEquals(emptyList<Int>(), avatarViewport(0, emptyList()).nearby)
    }
}
