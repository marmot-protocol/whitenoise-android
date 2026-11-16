package dev.ipf.whitenoise.android.state

import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class HeaderShortcutPreferencesTest {
    /** The header is plain until the person chooses shortcuts, and choices survive a fresh reader in order. */
    @Test
    fun defaultIsEmptyAndChoicesRoundTripInOrder() {
        val prefs = preferences("round-trip")
        val owner = HeaderShortcutPreferences(prefs)
        assertEquals(emptyList<HeaderShortcut>(), owner.shortcuts)
        owner.toggle(HeaderShortcut.MuteUnmute)
        owner.toggle(HeaderShortcut.Search)
        val expected = listOf(HeaderShortcut.MuteUnmute, HeaderShortcut.Search)
        assertEquals(expected, owner.shortcuts)
        assertEquals(expected, HeaderShortcutPreferences(prefs).shortcuts)
        owner.toggle(HeaderShortcut.MuteUnmute)
        assertEquals(listOf(HeaderShortcut.Search), HeaderShortcutPreferences(prefs).shortcuts)
    }

    /** Reset restores the plain header for this and later readers. */
    @Test
    fun resetRemovesEveryShortcut() {
        val prefs = preferences("reset")
        val owner = HeaderShortcutPreferences(prefs)
        owner.toggle(HeaderShortcut.Search)
        owner.reset()
        assertEquals(emptyList<HeaderShortcut>(), owner.shortcuts)
        assertFalse(prefs.contains(KEY))
        assertEquals(emptyList<HeaderShortcut>(), HeaderShortcutPreferences(prefs).shortcuts)
    }

    /** A stored value can never put more than two shortcuts, duplicates or unknown names in the header. */
    @Test
    fun storedValueIsBoundedToTwoKnownDistinctShortcuts() {
        val prefs = preferences("bounded")
        prefs.edit().putString(KEY, "ReadAloud,Search,Search,MuteUnmute,Pin").commit()
        val owner = HeaderShortcutPreferences(prefs)
        assertEquals(listOf(HeaderShortcut.Search, HeaderShortcut.MuteUnmute), owner.shortcuts)
        assertTrue(owner.shortcuts.size <= HeaderShortcutPreferences.MAX)
    }

    /** The limit is two and every current choice fits, so the settings switches never need to refuse one. */
    @Test
    fun limitIsTwoAndEveryChoiceFits() {
        assertEquals(2, HeaderShortcutPreferences.MAX)
        val owner = HeaderShortcutPreferences(preferences("limit"))
        HeaderShortcut.entries.forEach(owner::toggle)
        assertEquals(HeaderShortcut.entries, owner.shortcuts)
        assertTrue(owner.shortcuts.size <= HeaderShortcutPreferences.MAX)
    }

    private fun preferences(name: String): SharedPreferences =
        ApplicationProvider
            .getApplicationContext<Context>()
            .getSharedPreferences("header-shortcut-test-$name", Context.MODE_PRIVATE)
            .also { it.edit().clear().commit() }

    private companion object {
        const val KEY = "conversation_header_shortcuts"
    }
}
