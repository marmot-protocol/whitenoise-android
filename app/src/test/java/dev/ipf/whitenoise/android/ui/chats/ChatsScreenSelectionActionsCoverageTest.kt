package dev.ipf.whitenoise.android.ui.chats

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ChatsScreenSelectionActionsCoverageTest {
    @Test
    fun singleSelectionOverflowWiresMarkReadAndMute() {
        val source = chatsScreenSource().readText()
        val selectionBar =
            source.requiredSection(
                start = "ChatListSelectionBar(",
                end = "\n                    )\n                } else {",
            )
        val markReadHandler =
            selectionBar.requiredSection(
                start = "onMarkRead = {",
                end = "\n                        onMarkUnread = {",
            )
        val markUnreadHandler =
            selectionBar.requiredSection(
                start = "onMarkUnread = {",
                end = "\n                        onMuteToggle = {",
            )
        val muteHandler =
            selectionBar.requiredSection(
                start = "onMuteToggle = {",
                end = "\n                        onSelectAll = {",
            )

        assertTrue(
            "selection bar must expose mark-read only for an effective unread selection",
            "singleSelectedItem?.effectiveHasUnread" in selectionBar,
        )
        assertTrue(
            "selection bar must expose mute toggle for a single selection",
            "showMuteToggle = singleSelectedItem != null" in selectionBar,
        )
        assertTrue(
            "mark-read overflow must route to controller.markAllRead",
            "controller.markAllRead(item)" in markReadHandler,
        )
        assertTrue(
            "mark-read overflow must exit selection mode",
            "clearSelection()" in markReadHandler,
        )
        assertTrue(
            "mark-unread overflow must route to controller.markUnread",
            "controller.markUnread(item)" in markUnreadHandler,
        )
        assertTrue(
            "mark-unread overflow must exit selection mode",
            "clearSelection()" in markUnreadHandler,
        )
        assertTrue(
            "mute overflow must route to appState.setConversationMuted",
            "appState.setConversationMuted" in muteHandler,
        )
        assertTrue(
            "mute overflow must exit selection mode",
            "clearSelection()" in muteHandler,
        )
    }

    @Test
    fun singleSelectionOverflowWiresPinAndManualOrder() {
        val source = chatsScreenSource().readText()
        val selectionBar =
            source.requiredSection(
                start = "ChatListSelectionBar(",
                end = "\n                    )\n                } else {",
            )
        val pinHandler =
            selectionBar.requiredSection(
                start = "onPinToggle = {",
                end = "\n                        onMovePinned = {",
            )
        val moveHandler =
            selectionBar.requiredSection(
                start = "onMovePinned = {",
                end = "\n                        onSelectAll = {",
            )

        assertTrue(
            "the engine only pins unarchived chats, so archived selections must not offer the toggle",
            "showPinToggle = singleSelectedItem?.group?.archived == false" in selectionBar,
        )
        assertTrue(
            "pin overflow must route to controller.setPinned",
            "controller.setPinned(item, nextPinned)" in pinHandler,
        )
        assertTrue(
            "pin overflow must exit selection mode",
            "clearSelection()" in pinHandler,
        )
        assertTrue(
            "manual order must route the full pinned set to controller.setPinnedOrder",
            "controller.setPinnedOrder(reordered)" in moveHandler,
        )
        assertTrue(
            "a move must stay inside the pinned block",
            "if (target !in pinnedOrderedIds.indices) return@ChatListSelectionBar" in moveHandler,
        )
        assertTrue(
            "move overflow must exit selection mode",
            "clearSelection()" in moveHandler,
        )
    }

    @Test
    fun selectionBarWiresAddToFolderPickerAndCreateHandoff() {
        val source = chatsScreenSource().readText()
        val selectionBar =
            source.requiredSection(
                start = "ChatListSelectionBar(",
                end = "\n                    )\n                } else {",
            )
        val addToFolderHandler =
            selectionBar.requiredSection(
                start = "onAddToFolder = {",
                end = "\n                        onMarkRead = {",
            )

        assertTrue(
            "add-to-folder must capture the selected chats as picker targets",
            "folderPickerChatIds" in addToFolderHandler,
        )
        assertTrue(
            "the picker's New-folder entry must hand the targets to the create form",
            "folderEditorChatIds = targets.toSet()" in source,
        )
        assertTrue(
            "the create form must preload the targets as manual members",
            "initialManualChatIds = folderEditorTargets" in source,
        )
    }

    @Test
    fun groupDetailsGatesProtocolMutationsForTerminalGroups() {
        val source = groupDetailsSource().readText()

        assertTrue(
            "disbanding/disbanded groups must not advertise protocol mutations; leave is engine-refused too",
            "val groupTerminal = controller.group.disbanding || controller.group.disbanded" in source &&
                "controller.isSelfAdmin && !groupTerminal" in source &&
                "!mutationsBlocked && controller.membersLoaded && !groupTerminal" in source,
        )
    }

    private fun chatsScreenSource(): File =
        listOf(
            File("src/main/java/dev/ipf/whitenoise/android/ui/chats/ChatsScreen.kt"),
            File("app/src/main/java/dev/ipf/whitenoise/android/ui/chats/ChatsScreen.kt"),
        ).firstOrNull { it.exists() }
            ?: error("Missing ChatsScreen.kt source file")

    private fun groupDetailsSource(): File =
        listOf(
            File("src/main/java/dev/ipf/whitenoise/android/ui/group/GroupDetailsScreen.kt"),
            File("app/src/main/java/dev/ipf/whitenoise/android/ui/group/GroupDetailsScreen.kt"),
        ).firstOrNull { it.exists() }
            ?: error("Missing GroupDetailsScreen.kt source file")

    private fun String.requiredSection(
        start: String,
        end: String,
    ): String {
        val startIndex = indexOf(start)
        require(startIndex >= 0) { "Missing section start: $start" }
        val endIndex = indexOf(end, startIndex + start.length)
        require(endIndex >= 0) { "Missing section end: $end" }
        return substring(startIndex, endIndex)
    }
}
