package dev.ipf.whitenoise.android.ui.conversation.messages

import dev.ipf.whitenoise.android.state.ChatFolder
import org.junit.Assert.assertEquals
import org.junit.Test

class ForwardFolderRowsTest {
    private fun folder(
        id: String,
        name: String,
    ) = ChatFolder(id = id, name = name, description = "", order = 0, isSystem = false, systemKind = null)

    private val rows =
        listOf(
            folder("a", "Work") to listOf("g1", "g2"),
            folder("b", "Family") to listOf("g3", "g4", "g5"),
        )

    @Test
    fun blankQueryShowsEveryFolderRow() {
        assertEquals(rows, visibleForwardFolderRows(rows, ""))
        assertEquals(rows, visibleForwardFolderRows(rows, "   "))
    }

    @Test
    fun queryMatchesFolderNamesCaseInsensitively() {
        assertEquals(listOf(rows[1]), visibleForwardFolderRows(rows, "fam"))
        assertEquals(listOf(rows[0]), visibleForwardFolderRows(rows, "WORK"))
    }

    @Test
    fun queryMatchingNoFolderHidesTheSection() {
        assertEquals(emptyList<Pair<ChatFolder, List<String>>>(), visibleForwardFolderRows(rows, "zzz"))
    }
}
