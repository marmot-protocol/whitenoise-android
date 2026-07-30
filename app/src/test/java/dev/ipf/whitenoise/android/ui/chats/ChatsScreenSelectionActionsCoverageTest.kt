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
            "folderHandoff.pickerChatIds" in addToFolderHandler,
        )
        assertTrue(
            "the picker's New-folder entry must hand the targets to the create form",
            "folderHandoff.editorChatIds = targets.toSet()" in source,
        )
        assertTrue(
            "the create form must preload the targets as manual members",
            "initialManualChatIds = folderEditorTargets" in source,
        )
    }

    @Test
    fun folderEditorHandoffPreservesChatListState() {
        val source = chatsScreenSource().readText()
        val handoffStart = source.indexOf("// Folder editor handoff:")
        val handoff =
            source.requiredSection(
                start = "// Folder editor handoff:",
                end = "\n    Scaffold(",
            )

        assertTrue("folder editor handoff must exist", handoffStart >= 0)
        listOf(
            "var searchOpen by remember",
            "var searchQuery by remember",
            "var selectedFolderId by remember",
            "val chatListState = key(showArchived) { rememberLazyListState() }",
        ).forEach { declaration ->
            assertTrue(
                "$declaration must remain outside the editor swap so closing it preserves list state",
                source.indexOf(declaration) in 0 until handoffStart,
            )
        }
        assertTrue(
            "the rendered chat list must use the state preserved across the editor swap",
            "LazyColumn(Modifier.fillMaxSize().clipToBounds(), state = chatListState)" in source,
        )
        assertTrue(
            "a folder-chip edit must activate the in-place editor handoff",
            "onEditFolder = { folderHandoff.editingFolderId = it }" in source,
        )
        assertTrue(
            "closing the folder editor must clear both create and edit handoff state",
            "folderHandoff.editorChatIds = null" in handoff &&
                "folderHandoff.editingFolderId = null" in handoff,
        )
        assertTrue(
            "the editor call must be followed by an unconditional return before the scaffold",
            "ChatFolderEditScreen(" in handoff && "        )\n        return\n    }" in handoff,
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
