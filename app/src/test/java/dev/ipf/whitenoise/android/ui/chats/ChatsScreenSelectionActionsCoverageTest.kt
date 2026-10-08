package dev.ipf.whitenoise.android.ui.chats

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ChatsScreenSelectionActionsCoverageTest {
    @Test
    fun pendingBodySearchNeverMeasuresAShortTitleOnlyReplacementList() {
        val source = chatsScreenSource().readText()
        assertTrue("pending search always keeps the loading fence", "bodySearchLoading ->" in source)
        assertFalse("title hits cannot remove that fence", "bodySearchLoading && visibleItems.isEmpty()" in source)
        assertTrue("the viewport expires off-window return focus", "globalSearchReturnFocusExpiryEffect(" in source)
    }

    /** Error presentation retries only its lookup and cannot settle or expire the results viewport. */
    @Test
    fun lookupFailureKeepsTheViewportUnmountedAndOffersRequestOnlyRetry() {
        val source = chatsScreenSource().readText()
        assertTrue("body failures have recoverable presentation", "bodySearchFailure != null ->" in source)
        assertTrue(
            "attachment failures have recoverable presentation",
            "browsingAttachments && libraryFailure != null ->" in source,
        )
        assertTrue("body retry renews the lookup only", "onRetry = { bodySearchRetry++ }" in source)
        assertTrue("attachment retry renews the lookup only", "onRetry = { libraryRetry++ }" in source)
        assertTrue(
            "body lookup uses the cancellation-safe presenter",
            "rememberGlobalSearchLookup(bodySearchRequest" in source,
        )
        assertTrue(
            "library lookup uses the cancellation-safe presenter",
            "rememberGlobalSearchLookup(libraryRequest" in source,
        )
        assertTrue(
            "failed lookups cannot expire return focus as settled data",
            "&& bodySearchFailure == null" in source,
        )
    }

    /** Single selection overflow wires mark read. */
    @Test
    fun singleSelectionOverflowWiresMarkRead() {
        val source = chatsScreenSource().readText()
        val selectionBar =
            source.requiredSection(
                start = "ChatListSelectionControls(",
                end = "\n                    )\n                }\n            }\n        },",
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
        val markReadHelper =
            source.requiredSection(
                start = "fun markChatRead(",
                end = "\n    fun toggleChatMute(",
            )

        assertTrue(
            "selection bar must expose mark-read only for an effective unread selection",
            "singleSelectedItem?.effectiveHasUnread" in selectionBar,
        )
        assertTrue(
            "mark-read overflow must route to controller.markAllRead",
            "markChatRead(item, unread = false)" in markReadHandler &&
                "controller.markAllRead(item)" in markReadHelper,
        )
        assertTrue(
            "mark-read overflow must exit selection mode",
            "clearSelection()" in markReadHelper,
        )
        assertTrue(
            "mark-unread overflow must route to controller.markUnread",
            "markChatRead(item, unread = true)" in markUnreadHandler &&
                "controller.markUnread(item)" in markReadHelper,
        )
        assertTrue(
            "mark-unread overflow must exit selection mode",
            "clearSelection()" in markReadHelper,
        )
    }

    /** Single selection overflow wires mute. */
    @Test
    fun singleSelectionOverflowWiresMute() {
        val source = chatsScreenSource().readText()
        val selectionBar =
            source.requiredSection(
                start = "ChatListSelectionControls(",
                end = "\n                    )\n                }\n            }\n        },",
            )
        val muteHandler =
            selectionBar.requiredSection(
                start = "onMuteToggle = {",
                end = "\n                        onSelectAll = {",
            )
        val muteHelper =
            source.requiredSection(
                start = "fun toggleChatMute(",
                end = "\n    // Hoisted list state",
            )

        assertTrue(
            "selection bar must expose mute toggle for a single selection",
            "showMuteToggle = singleSelectedItem != null" in selectionBar,
        )
        assertTrue(
            "mute overflow must route to appState.setConversationMuted",
            "toggleChatMute(item, singleSelectionMuted)" in muteHandler &&
                "appState.setConversationMuted" in muteHelper,
        )
        assertTrue("mute overflow must exit selection mode", "clearSelection()" in muteHelper)
    }

    /** Single selection overflow wires pin and manual order. */
    @Test
    fun singleSelectionOverflowWiresPinAndManualOrder() {
        val source = chatsScreenSource().readText()
        val selectionBar =
            source.requiredSection(
                start = "ChatListSelectionControls(",
                end = "\n                    )\n                }\n            }\n        },",
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
        val pinHelper =
            source.requiredSection(
                start = "fun toggleChatPin(",
                end = "\n    fun movePinnedChat(",
            )
        val moveHelper =
            source.requiredSection(
                start = "fun movePinnedChat(",
                end = "\n    // Hoisted list state",
            )

        assertTrue(
            "the engine only pins unarchived chats, so archived selections must not offer the toggle",
            "showPinToggle = singleSelectedItem?.group?.archived == false" in selectionBar,
        )
        assertTrue(
            "pin overflow must route to controller.setPinned",
            "toggleChatPin(item)" in pinHandler &&
                "controller.setPinned(item, nextPinned)" in pinHelper,
        )
        assertTrue(
            "pin overflow must exit selection mode",
            "clearSelection()" in pinHelper,
        )
        assertTrue(
            "manual order must route the full pinned set to controller.setPinnedOrder",
            "movePinnedChat(item, delta)" in moveHandler &&
                "controller.setPinnedOrder(reordered)" in moveHelper,
        )
        assertTrue(
            "a move must stay inside the pinned block",
            "if (target !in pinnedOrderedIds.indices) return" in moveHelper,
        )
        assertTrue(
            "move overflow must exit selection mode",
            "clearSelection()" in moveHelper,
        )
    }

    /** Long press sheet reuses pin and manual order mutations. */
    @Test
    fun longPressSheetReusesPinAndManualOrderMutations() {
        val source = chatsScreenSource().readText()

        val actionSheet =
            source.requiredSection(
                start = "ChatContextMenu(",
                end = "\n                        )\n                    }",
            )
        assertTrue(
            "the long-press sheet must expose the same unarchived pin toggle",
            "showPinToggle = !item.group.archived" in actionSheet &&
                "onPinToggle = { toggleChatPin(item) }" in actionSheet,
        )
        assertTrue(
            "the long-press sheet must reuse the same pinned-order mutation path",
            "showMovePinnedUp" in actionSheet &&
                "showMovePinnedDown" in actionSheet &&
                "onMovePinned = { delta -> movePinnedChat(item, delta) }" in actionSheet,
        )
    }

    /** Selection bar wires add to folder picker and create handoff. */
    @Test
    fun selectionBarWiresAddToFolderPickerAndCreateHandoff() {
        val source = chatsScreenSource().readText()
        val selectionBar =
            source.requiredSection(
                start = "ChatListSelectionControls(",
                end = "\n                    )\n                }\n            }\n        },",
            )
        val addToFolderHandler =
            selectionBar.requiredSection(
                start = "onAddToFolder = {",
                end = "\n                        onMarkRead = {",
            )
        val folderPickerHelper =
            source.requiredSection(
                start = "fun openFolderPicker(",
                end = "\n    fun markChatRead(",
            )

        assertTrue(
            "add-to-folder must capture the selected chats as picker targets",
            "openFolderPicker(selectedVisibleItems)" in addToFolderHandler &&
                "folderHandoff.pickerChatIds" in folderPickerHelper,
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

    /**
     * Folder editing preserves the chat-list state and returns before the list Scaffold, including its playback
     * wrapper.
     */
    @Test
    fun folderEditorHandoffPreservesChatListState() {
        val source = chatsScreenSource().readText()
        val handoffStart = source.indexOf("// Folder editor handoff:")
        val handoff =
            source.requiredSection(
                start = "// Folder editor handoff:",
                end = "\n\n    // Consent belongs to the unobstructed Chats list",
            )

        assertTrue("folder editor handoff must exist", handoffStart >= 0)
        listOf(
            "globalSearchState:",
            "onGlobalSearchStateChange:",
            // Keyed by the viewport's owner — account, runtime generation and list variant — so a
            // retained frame cannot carry one account's position into another's list.
            "val chatListState = searchViewport?.listState(showArchived)",
        ).forEach { declaration ->
            assertTrue(
                "$declaration must remain outside the editor swap so closing it preserves list state",
                source.indexOf(declaration) in 0 until handoffStart,
            )
        }
        assertTrue(
            "isolated owners must retain their account/runtime/list-keyed fallback outside the editor swap",
            "?: key(viewportOwner) { rememberLazyListState() }" in source.substring(0, handoffStart),
        )
        assertTrue(
            "global search must be shell-owned so the editor swap does not reset it",
            "globalSearchState = scopedGlobalSearchState" in mainShellSource().readText(),
        )
        assertTrue(
            "folder filter must be parent-owned so the editor swap does not reset it",
            "selectedFolderId:" in source.substring(0, handoffStart),
        )
        assertTrue(
            "the rendered chat list must use the state preserved across the editor swap",
            "state = chatListState" in source,
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
            "ChatFolderEditScreen(" in handoff &&
                Regex("""\n\s*return\s*\n\s*}\s*$""").containsMatchIn(handoff),
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

    private fun mainShellSource(): File =
        listOf(
            File("src/main/java/dev/ipf/whitenoise/android/ui/navigation/MainShell.kt"),
            File("app/src/main/java/dev/ipf/whitenoise/android/ui/navigation/MainShell.kt"),
        ).firstOrNull { it.exists() }
            ?: error("Missing MainShell.kt source file")

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
