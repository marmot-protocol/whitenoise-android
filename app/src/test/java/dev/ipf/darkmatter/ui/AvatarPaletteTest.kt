package dev.ipf.darkmatter.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class AvatarPaletteTest {
    @Test
    fun minimumHashStillMapsToNonNegativePaletteIndex() {
        assertEquals(2, avatarPaletteIndex(Int.MIN_VALUE, paletteSize = 5))
    }
}
