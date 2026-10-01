package dev.ipf.whitenoise.android.ui.common

import org.junit.Assert.assertEquals
import org.junit.Test

class GroupAvatarPreparationWindowTest {
    @Test
    fun errorSearchHeadersAndPinnedBoundaryDoNotSkipVisibleRows() {
        val rows = (0 until 30).map { "group-$it" }
        val keys = listOf("load-error", "search-header", "pinned-boundary", "group-4", "group-5")
        assertEquals(rows.subList(4, 20), visibleGroupAvatarWindow(rows, keys) { it })
    }

    @Test
    fun recentChatsHeaderDoesNotSkipTheFirstVisibleShareTarget() {
        val rows = listOf("first", "second", "third")
        assertEquals(rows, visibleGroupAvatarWindow(rows, listOf(0, "first", "second")) { it })
        assertEquals(listOf("third"), visibleGroupAvatarWindow(rows, listOf("third")) { it })
    }

    @Test
    fun emptyLayoutPrimesOnlyTheInitialBoundedWindow() {
        val rows = (0 until 30).map { "group-$it" }
        assertEquals(rows.take(16), visibleGroupAvatarWindow(rows, emptyList()) { it })
        assertEquals(emptyList<String>(), visibleGroupAvatarWindow(emptyList<String>(), listOf("header")) { it })
    }
}
