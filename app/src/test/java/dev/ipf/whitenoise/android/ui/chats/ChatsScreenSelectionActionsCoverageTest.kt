package dev.ipf.whitenoise.android.ui.chats

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ChatsScreenSelectionActionsCoverageTest {
    /** Behavioral coverage also lives in GlobalSearchAnchorRestorationTest and GlobalSearchStagedViewportTest. */
    @Test
    fun pendingBodySearchProtectsDeepAnchorsWithoutHidingFreshLocalNames() {
        val source = chatsScreenSource().readText()
        assertTrue("the viewport uses the staged loading fence", "blockLocalSearchRows(" in source)
        assertTrue(
            "the fence receives the restoration owner",
            "preservePendingAnchor = preservePendingSearchAnchor" in source,
        )
        assertTrue(
            "the fence receives available local rows",
            "hasLocalRows = visibleItems.isNotEmpty() || messageResultsMode" in source,
        )
        assertTrue("the fence waits until message results adopt the query", "loading = messageResultsPending" in source)
        assertTrue("the dataset owns restoration", "rememberSearchBodyAnchorRestoration(" in source)
        val stage = File(chatsScreenSource().parentFile, "GlobalSearchAnchorRestoration.kt").readText()
        assertTrue("restoration survives request-only retry", "remember(datasetKey)" in stage)
        assertTrue(
            "committed loading retires restoration",
            "SideEffect { restoration.observeLoading(loading) }" in stage,
        )
        val deepAnchor = preserveSearchBodyAnchor(sameDataset = true, firstVisibleIndex = 40, firstVisibleOffset = 9)
        assertTrue(
            "pending deep restoration cannot measure a shortened list",
            blockLocalSearchRows(true, true, deepAnchor),
        )
        assertFalse("fresh local names do not wait for body work", blockLocalSearchRows(true, true, false))
        assertTrue(
            "message-only empty results still wait for matching bodies",
            blockLocalSearchRows(true, false, false),
        )
        assertTrue("the viewport expires off-window return focus", "globalSearchReturnFocusExpiryEffect(" in source)
    }

    /**
     * A failed message batch keeps names and loaded results and retries at the list boundary, and a failed
     * attachment batch offers a retry that pages the account library again.
     */
    @Test
    fun lookupFailureRetainsLocalRowsAndOffersRequestOnlyRetry() {
        val source = chatsScreenSource().readText()
        assertTrue(
            "message results own their failure at the list boundary, never a full-screen error",
            "messageResultsMode -> Unit" in source && "onRetry = { messageResults?.retry() }" in source,
        )
        val list = File(chatsScreenSource().parentFile, "GlobalSearchResultsList.kt").readText()
        assertTrue(
            "the boundary failure preserves its retry control",
            "TextButton(onClick = content.actions.onRetry)" in list,
        )
        val library = File(chatsScreenSource().parentFile, "GlobalAttachmentLibraryHost.kt").readText()
        assertTrue(
            "attachment failures have recoverable presentation",
            "failed = failure != null," in library && "onRetry = loadMore," in library,
        )
        val controller = File(chatsScreenSource().parentFile, "GlobalSearchResultsController.kt").readText()
        assertTrue(
            "late batches from an older query, account or runtime cannot publish",
            "!closed && owner == query && environment.isCurrent()" in controller,
        )
        assertTrue(
            "library pages come from the account pager",
            "rememberGlobalAttachmentPager(" in source && "searchViewport?.libraryExtent," in source,
        )
        val session = File(chatsScreenSource().parentFile, "GlobalAttachmentLibrarySession.kt").readText()
        assertTrue(
            "the library names and opens archived and off-window chats, which the account index includes",
            "rememberLibraryChats(controller, libraryEntries)" in source &&
                "openSearchMessage(group, message, libraryChats)" in source &&
                "controller.items + controller.archivedItems" in session &&
                "controller.resolveChatListActionItem(group, owner)" in session,
        )
        assertTrue(
            "failed lookups cannot expire return focus as settled data",
            "messageResultsState?.failed != true" in source,
        )
    }

    /** Bulk actions must enter one fixed-scope command, never loop the selection-clearing row callbacks. */
    @Test fun selectionCapturesExplicitTargetsAndDirections() {
        val source = chatsScreenSource().readText()
        val controls =
            source
                .requiredSection("ChatListSelectionControls(", "\n        floatingActionButton =")
                .replace(Regex("\\s+"), " ")
        assertTrue(
            "fixed scope captured before launch",
            "PendingChatListBatch( selectedChatIds.toList(), command, owner," in source.replace(Regex("\\s+"), " "),
        )
        assertTrue(
            "screen lifetime guards admission",
            "controller.captureChatListActionOwner { batchScope.get() }" in source,
        )
        assertTrue("mark read is explicit", "onMarkRead = { startStateBatch(ChatListBatchCommand.READ) }" in controls)
        assertTrue(
            "mark unread is explicit",
            "onMarkUnread = { startStateBatch(ChatListBatchCommand.UNREAD) }" in controls,
        )
        assertTrue(
            "mute and unmute remain separate destinations",
            "ChatListBatchCommand.MUTE else ChatListBatchCommand.UNMUTE" in controls,
        )
        assertTrue(
            "pin and unpin remain separate destinations",
            "ChatListBatchCommand.PIN else ChatListBatchCommand.UNPIN" in controls,
        )
        assertTrue(
            "ordering uses complete native universe",
            "controller.movePinnedSelection(ids, delta, owner)" in controls,
        )
        assertFalse(
            "bulk never forwards to a selection-clearing helper",
            "markChatRead(item" in controls || "toggleChatMute(item" in controls,
        )
    }

    /**
     * #2925: Select all claims the complete native view, never only the loaded window. Late reads are fenced, every
     * command revalidates natively first and destructive actions stay hidden while unloaded chats are selected.
     */
    @Test fun selectAllUsesCompleteNativeViewWithFencedRevalidation() {
        val source = chatsScreenSource().readText()
        val flat = source.replace(Regex("\\s+"), " ")
        assertTrue(
            "select all reads the complete view with not-ready retries",
            "resolveCompleteChatListIds { controller.completeChatListIds(target, owner) }" in source,
        )
        assertTrue(
            "a superseded select-all read is discarded",
            "if (completeSelection !== pending) return@launch" in source,
        )
        assertTrue(
            "the complete IDs extend the loaded selection",
            "selectedChatIds.addAll(mergeCompleteChatListIds(ids, loadedIds))" in source,
        )
        assertTrue(
            "commands revalidate the complete selection before running and start nothing without that answer",
            "controller.revalidateCompleteChatListIds( completeScope, request.ids, request.owner, )" in flat &&
                "ids?.let { controller.runChatListBatch(it, request.command, request.owner) }" in source &&
                "completeScope = completeSelection?.takeIf { it.resolved }?.scope," in source,
        )
        assertTrue(
            "window movement prunes only chats native revalidation drops",
            "controller.revalidateCompleteChatListIds(complete.scope, sent.toList(), owner)" in source &&
                "retainRevalidatedChatListIds(sent, kept)" in source,
        )
        assertTrue(
            "changing scope cancels the complete claim",
            "LaunchedEffect(completeSelectAllScope) { completeSelection = null }" in source,
        )
        assertTrue("delete is hidden beyond loaded chats", "showDelete = !selectionBeyondLoaded," in source)
        assertTrue(
            "folder actions are hidden beyond loaded chats",
            "showAddToFolder = !selectionBeyondLoaded," in source &&
                "selectedFolder?.takeIf { !selectionBeyondLoaded }" in source,
        )
        assertTrue(
            "a row toggle supersedes a pending Select all read",
            "if (completeSelection?.resolved == false) completeSelection = null" in source,
        )
        assertTrue(
            "abandonment is hidden beyond loaded chats",
            "!selectionBeyondLoaded && controller.accountRef != null" in flat,
        )
        val controllers = File(chatsScreenSource().parentFile, "../../state/Controllers.kt").readText()
        assertTrue(
            "native complete reads are owner fenced",
            "internal suspend fun completeChatListIds(" in controllers &&
                "internal suspend fun revalidateCompleteChatListIds(" in controllers &&
                "revalidate(owner.accountRef, ChatListViewFfi.LEFT, outside)" in controllers,
        )
    }

    /** Row/menu callbacks retain the existing captured presentation and session ownership boundaries. */
    @Test fun singleContextActionsKeepOwnershipAndAuthoritativeResolution() {
        val source = chatsScreenSource().readText()
        val helper = source.requiredSection("fun markChatRead(", "\n    fun toggleChatMute(")
        assertTrue(
            "single callbacks still resolve native rows",
            "controller.resolveChatListActionItem(item.id, owner)" in helper,
        )
        assertTrue(
            "single callbacks still own their presentation",
            "controller.captureChatListActionOwner { actionScope.get() }" in helper,
        )
        assertTrue("single mark read uses original controller", "controller.markAllRead(current, owner)" in helper)
        assertTrue("single mark unread uses original controller", "controller.markUnread(current, owner)" in helper)
        assertTrue("single callbacks retain their selection dismissal", "clearSelection()" in helper)
    }

    @Test fun batchRunnerRetainsNativeAuthorityAndSingleFlightAcrossFilterRemoval() {
        val source = File(chatsScreenSource().parentFile, "../../state/Controllers.kt").readText()
        val batch =
            source.requiredSection(
                "internal suspend fun runChatListBatch(",
                "private suspend fun applyChatListBatchCommand(",
            )
        assertTrue(
            "admission is single flight",
            "if (chatListBatchInProgress || !chatListActionIsCurrent(owner)) return null" in batch,
        )
        assertTrue("targets are copied before suspension", "val targets = ids.map" in batch)
        assertTrue(
            "eligibility and satisfied state come from keyed native projection",
            "liveSubscriptions.presentedRowByGroup?.invoke(owner.accountRef, id)" in batch,
        )
        assertTrue("results retain every target", "targets.associateWith" in batch)
        assertTrue("retired owners stop remaining targets", "if (!chatListActionIsCurrent(owner)) break" in batch)
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
