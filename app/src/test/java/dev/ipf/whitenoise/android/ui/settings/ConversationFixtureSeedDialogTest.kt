package dev.ipf.whitenoise.android.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The pure decisions behind the seed dialog: which counts may start a seed, and how targets are titled. */
class ConversationFixtureSeedDialogTest {
    /** Whole numbers from 1 to 1000 start a seed; anything else keeps Start disabled rather than being clamped. */
    @Test
    fun countMustBeAWholeNumberWithinTheCap() {
        assertEquals(1, seedCountOrNull("1"))
        assertEquals(300, seedCountOrNull("300"))
        assertEquals(1000, seedCountOrNull("1000"))
        assertNull(seedCountOrNull("0"))
        assertNull(seedCountOrNull("1001"))
        assertNull(seedCountOrNull("-5"))
        assertNull(seedCountOrNull("30 0"))
        assertNull(seedCountOrNull(""))
    }

    /** A unique name stands alone; a nameless DM shows its short id. */
    @Test
    fun uniqueNamesAndNamelessChatsAreTitledPlainly() {
        val targets = disambiguatedFixtureTargets(listOf("a1b2c3d4e5f6" to "Paging Fixture", "0c003bfa9e8d" to ""))
        assertEquals(listOf("Paging Fixture", "0c003bfa"), targets.map { it.title })
        assertEquals(listOf("a1b2c3d4e5f6", "0c003bfa9e8d"), targets.map { it.groupIdHex })
    }

    /** Two groups with the same name each carry their short id, so a seed cannot be aimed at the wrong one. */
    @Test
    fun duplicateNamesCarryTheirShortId() {
        val targets =
            disambiguatedFixtureTargets(
                listOf("aaaa1111bbbb" to "Test", "cccc2222dddd" to "Test", "eeee3333ffff" to "Other"),
            )
        assertEquals(listOf("Test · aaaa1111", "Test · cccc2222", "Other"), targets.map { it.title })
    }
}
