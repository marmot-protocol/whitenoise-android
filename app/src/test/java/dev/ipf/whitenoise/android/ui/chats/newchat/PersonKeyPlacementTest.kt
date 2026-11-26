package dev.ipf.whitenoise.android.ui.chats.newchat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PersonKeyPlacementTest {
    private val npub = "npub1qqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqsg9z4k"
    private val short = "${npub.take(12)}…${npub.takeLast(6)}"

    @Test
    fun keyTitleOwnsTheKeyAndDropsARepeatedKeySubtitle() {
        val placement = personKeyPlacement(short, short, npub)

        assertTrue(placement.inTitle)
        assertEquals(npub, placement.publicKey)
        assertNull(placement.subtitle)
    }

    @Test
    fun keySubtitleUnderANameOwnsTheKey() {
        val placement = personKeyPlacement("Ada", short, npub)

        assertFalse(placement.inTitle)
        assertEquals(npub, placement.publicKey)
        assertEquals(short, placement.subtitle)
    }

    @Test
    fun plainNameAndSubtitleCarryNoKey() {
        val placement = personKeyPlacement("Ada", "You follow", npub)

        assertFalse(placement.inTitle)
        assertNull(placement.publicKey)
        assertEquals("You follow", placement.subtitle)
    }

    @Test
    fun aNameThatMerelyStartsWithNpub1StaysAName() {
        val placement = personKeyPlacement("npub1 fan", null, npub)

        assertFalse(placement.inTitle)
        assertNull(placement.publicKey)
    }
}
