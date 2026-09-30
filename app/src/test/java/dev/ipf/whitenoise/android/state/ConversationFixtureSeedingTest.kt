package dev.ipf.whitenoise.android.state

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The synthetic bodies a paging fixture is seeded with, and the progress record a seed reports. */
class ConversationFixtureSeedingTest {
    /** Every body names its position so a row on screen can be matched to a place in history. */
    @Test
    fun everyMessageCarriesItsZeroPaddedOrdinal() {
        for (index in 1..300) {
            val body = conversationFixtureMessage(index, 300)
            assertTrue(body, body.startsWith("Fixture ${index.toString().padStart(3, '0')}/300"))
        }
    }

    /** Six shapes cycle so one 50-row page mixes one-liners, prose, Markdown and multi-line rows. */
    @Test
    fun bodiesCycleThroughDistinctShapes() {
        val bodies = (1..6).map { conversationFixtureMessage(it, 6) }
        assertEquals(6, bodies.map { it.substringAfter("/6") }.toSet().size)
        assertTrue(bodies.any { it.contains("**bold**") })
        assertTrue(bodies.any { it.contains("\n- ") })
        assertTrue(bodies.any { it.lines().size >= 3 })
        assertEquals(bodies.first(), conversationFixtureMessage(7, 6).replace("7/6", "1/6"))
    }

    /** Padding follows the total, so a ten-message seed is not padded to three digits. */
    @Test
    fun ordinalPaddingFollowsTheTotal() {
        assertTrue(conversationFixtureMessage(7, 10).startsWith("Fixture 07/10"))
        assertTrue(conversationFixtureMessage(7, 1000).startsWith("Fixture 0007/1000"))
    }

    /** A seed is finished once every message was either accepted or rejected. */
    @Test
    fun progressFinishesWhenEveryMessageWasAttempted() {
        assertFalse(ConversationFixtureSeedProgress(sent = 299, failed = 0, total = 300).finished)
        assertTrue(ConversationFixtureSeedProgress(sent = 298, failed = 2, total = 300).finished)
        assertTrue(ConversationFixtureSeedProgress(sent = 0, failed = 0, total = 0).finished)
    }
}
