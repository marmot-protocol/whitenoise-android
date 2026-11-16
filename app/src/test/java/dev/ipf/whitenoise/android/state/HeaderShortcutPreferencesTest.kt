package dev.ipf.whitenoise.android.state

import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
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

    /** A stored value can never put more than two shortcuts, duplicates or unknown names in the header. */
    @Test
    fun storedValueIsBoundedToTwoKnownDistinctShortcuts() {
        val prefs = preferences("bounded")
        prefs.edit().putString(KEY, "Pin,Search,Search,MuteUnmute,ReadAloud").commit()
        val owner = HeaderShortcutPreferences(prefs)
        assertEquals(listOf(HeaderShortcut.Search, HeaderShortcut.MuteUnmute), owner.shortcuts)
        assertTrue(owner.shortcuts.size <= HeaderShortcutPreferences.MAX)
    }

    /** A full header refuses another shortcut instead of replacing one; freeing a slot lets it in. */
    @Test
    fun fullHeaderRefusesAThirdShortcutUntilOneIsTurnedOff() {
        assertEquals(2, HeaderShortcutPreferences.MAX)
        val prefs = preferences("limit")
        val owner = HeaderShortcutPreferences(prefs)
        HeaderShortcut.entries.forEach(owner::toggle)
        assertEquals(listOf(HeaderShortcut.Search, HeaderShortcut.MuteUnmute), owner.shortcuts)
        assertEquals(owner.shortcuts, HeaderShortcutPreferences(prefs).shortcuts)
        owner.toggle(HeaderShortcut.Search)
        owner.toggle(HeaderShortcut.ReadAloud)
        assertEquals(listOf(HeaderShortcut.MuteUnmute, HeaderShortcut.ReadAloud), owner.shortcuts)
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
