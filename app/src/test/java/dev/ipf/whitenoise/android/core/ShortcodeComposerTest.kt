package dev.ipf.whitenoise.android.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ShortcodeComposerTest {
    @Test
    fun tokenOpensAtStartOrAfterWhitespace() {
        assertEquals(ShortcodeComposer.ActiveQuery(0, "pa"), ShortcodeComposer.activeQuery(":pa", 3))
        assertEquals(ShortcodeComposer.ActiveQuery(3, "par-t_y"), ShortcodeComposer.activeQuery("hi\n:par-t_y", 11))
        // Only the part before the caret is the query.
        assertEquals(ShortcodeComposer.ActiveQuery(3, "pa"), ShortcodeComposer.activeQuery("hi :party", 6))
    }

    @Test
    fun noTokenForTimesClosedCodesOrShortQueries() {
        assertNull(ShortcodeComposer.activeQuery("at 12:30", 8))
        assertNull(ShortcodeComposer.activeQuery("x:pa", 4))
        assertNull(ShortcodeComposer.activeQuery(":party:", 7))
        assertNull(ShortcodeComposer.activeQuery(":p", 2))
        assertNull(ShortcodeComposer.activeQuery(":pa b", 5))
        assertNull(ShortcodeComposer.activeQuery(":" + "a".repeat(65), 66))
        assertNull(ShortcodeComposer.activeQuery(":pa", 4))
    }

    @Test
    fun insertionReplacesTheTokenAndKeepsTheRest() {
        val active = ShortcodeComposer.ActiveQuery(3, "pa")
        assertEquals(
            ShortcodeComposer.Insertion("hi :party: rest", 11),
            ShortcodeComposer.insert("hi :pa rest", active, 6, ":party:"),
        )
        // A surrogate-pair emoji at the end gains a trailing space; the caret lands after it.
        assertEquals(
            ShortcodeComposer.Insertion("\uD83D\uDE00 ", 3),
            ShortcodeComposer.insert(":sm", ShortcodeComposer.ActiveQuery(0, "sm"), 3, "\uD83D\uDE00"),
        )
    }

    @Test
    fun prefixMatchesLeadThenOtherMatchesInGivenOrder() {
        val codes = listOf(":happy_cat:", ":cat:", ":dog:", ":Catnip:")
        assertEquals(listOf(":cat:", ":Catnip:", ":happy_cat:"), ShortcodeComposer.matchingShortcodes(codes, "CAT"))
    }
}
