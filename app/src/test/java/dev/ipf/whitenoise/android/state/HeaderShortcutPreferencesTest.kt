package dev.ipf.whitenoise.android.state

import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
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

    /** A stored value drops removed or unknown names and duplicates so upgrades stay clean. */
    @Test
    fun storedValueKeepsOnlyKnownDistinctShortcuts() {
        val prefs = preferences("bounded")
        prefs.edit().putString(KEY, "Pin,DisappearingTimer,Search,Search,MuteUnmute,ReadAloud").commit()
        assertEquals(
            listOf(HeaderShortcut.Search, HeaderShortcut.MuteUnmute, HeaderShortcut.ReadAloud),
            HeaderShortcutPreferences(prefs).shortcuts,
        )
    }

    /** Every shortcut can be checked at once, in the order chosen, and survives a cold read. */
    @Test
    fun everyShortcutCanBeCheckedInOrder() {
        val prefs = preferences("all")
        val owner = HeaderShortcutPreferences(prefs)
        val order = listOf(HeaderShortcut.ReadAloud, HeaderShortcut.Search, HeaderShortcut.MuteUnmute)
        order.forEach(owner::toggle)
        assertEquals(order, owner.shortcuts)
        assertEquals(order, HeaderShortcutPreferences(prefs).shortcuts)
    }

    /** Fresh private preferences file for one test case. */
    private fun preferences(name: String): SharedPreferences =
        ApplicationProvider
            .getApplicationContext<Context>()
            .getSharedPreferences("header-shortcut-test-$name", Context.MODE_PRIVATE)
            .also { it.edit().clear().commit() }

    private companion object {
        const val KEY = "conversation_header_shortcuts"
    }
}
