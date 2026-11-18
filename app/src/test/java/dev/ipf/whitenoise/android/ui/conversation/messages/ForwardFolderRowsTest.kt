package dev.ipf.whitenoise.android.ui.conversation.messages

import org.junit.Assert.assertEquals
import org.junit.Test

class ForwardFolderRowsTest {
    @Test
    fun folderToggleAddsMissingMembersWithoutDuplicatingExistingSelections() {
        assertEquals(
            listOf("individual", "folder-a", "folder-b"),
            forwardSelectionAfterFolderToggle(
                selected = listOf("individual", "FOLDER-A"),
                memberIds = listOf("folder-a", "folder-b", "folder-b"),
            ),
        )
    }

    @Test
    fun folderToggleRemovesOnlyThatFoldersMembersWhenFullySelected() {
        assertEquals(
            listOf("individual"),
            forwardSelectionAfterFolderToggle(
                selected = listOf("individual", "folder-a", "folder-b"),
                memberIds = listOf("FOLDER-A", "folder-b"),
            ),
        )
    }

    @Test
    fun forwardRecipientsExcludeOriginAndDuplicateGroupIds() {
        assertEquals(
            listOf("individual", "folder-a"),
            forwardRecipientGroupIds(
                selected = listOf("individual", "origin", "folder-a", "FOLDER-A", "ORIGIN"),
                originGroupIdHex = "origin",
            ),
        )
    }

    @Test
    fun repeatedRowTapsAlwaysToggleTheSameChat() {
        var selected = emptyList<String>()
        repeat(10) { tap ->
            selected = toggleForwardTargetSelection(selected, "CHAT-A")
            assertEquals(if (tap % 2 == 0) listOf("chat-a") else emptyList(), selected)
        }
    }
}
