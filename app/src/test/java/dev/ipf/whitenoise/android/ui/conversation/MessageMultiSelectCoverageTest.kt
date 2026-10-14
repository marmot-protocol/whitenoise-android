package dev.ipf.whitenoise.android.ui.conversation

import dev.ipf.whitenoise.android.ui.conversation.messages.MessageActionKind
import dev.ipf.whitenoise.android.ui.conversation.messages.messageActionKinds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class MessageMultiSelectCoverageTest {
    @Test
    fun longPressMenuOffersSelectEntry() {
        fun actions(canSelect: Boolean) =
            messageActionKinds(
                canReply = false,
                canEdit = false,
                canSelect = canSelect,
                canSelectText = false,
                canCopyText = false,
                canSpeak = false,
                canForward = false,
                canSave = false,
                canInfo = false,
            )

        assertEquals(listOf(MessageActionKind.Select), actions(canSelect = true))
        assertTrue(actions(canSelect = false).isEmpty())
    }

    /** Selection affordances belong to the revealed transcript and cannot expose a hidden notification route. */
    @Test
    fun conversationOwnsSelectionActionsAndBottomSelectionBar() {
        val screenSource = source("ConversationScreen.kt")
        val topBarSource = source("ConversationTopBar.kt")
        val bottomBarSource = source("ConversationBottomBar.kt")

        assertTrue(screenSource.contains("selectedMessages"))
        assertTrue(topBarSource.contains("MessageSelectionBar("))
        assertTrue(bottomBarSource.contains("MessageSelectionBottomBar("))
        assertTrue(screenSource.contains("batchCopyText(actionItems)"))
        assertTrue(screenSource.contains("batchForwardPayloads(actionItems)"))
        assertTrue(screenSource.contains("batchSelectionActionAvailability("))
        // Selection hides the jump controls one by one; pagination never adds an overlay.
        assertTrue(screenSource.contains("if (transcriptReadyToReveal)"))
        assertTrue(screenSource.contains("if (!selectionMode && ttsFollowHandle.showResumeAction)"))
        assertTrue(screenSource.contains("if (!selectionMode && mentionCount > 0)"))
        assertTrue(screenSource.contains("if (!selectionMode && !nearBottom)"))
    }

    @Test
    fun batchSelectionStateKeysOnControllerIdentity() {
        val source = source("ConversationScreen.kt").replace(Regex("\\s+"), " ")
        val mainShell = mainShellSource().replace(Regex("\\s+"), " ")
        // The owner account is the conversation's own ref (pinned during a
        // notification-routed early open), never the in-flight active ref.
        val chatRuntimeIdentity = "chat.id, conversationAccountRef, appState.runtimeGeneration"
        val controllerIdentity = "controller, $chatRuntimeIdentity"

        assertTrue(source.contains("val selectedMessages = presentationState.selectedMessages"))
        assertTrue(source.contains("surfaceState ?: rememberConversationSurfaceState("))
        assertTrue(source.contains("controllerIdentity = controller"))
        assertTrue(mainShell.contains("rememberConversationSurfaceState("))
        assertTrue(mainShell.contains("controllerIdentity = selectedOrPendingConversationController"))
        assertTrue(mainShell.contains("chatId = controllerChatId"))
        assertTrue(mainShell.contains("accountRef = conversationAccountRef"))
        assertTrue(mainShell.contains("chatId = openChat.id"))
        assertTrue(mainShell.contains("accountRef = accountRef"))
        assertTrue(mainShell.contains("runtimeGeneration = appState.runtimeGeneration"))
        assertTrue(mainShell.contains("surfaceState = selectedConversationSurfaceState"))
        assertTrue(source.contains("rememberConversationBatchSelectionUiState("))
        assertTrue(source.contains("chatId = chat.id"))
        assertTrue(source.contains("activeAccountRef = conversationAccountRef"))
        assertTrue(source.contains("runtimeGeneration = appState.runtimeGeneration"))
        assertTrue(source.contains("var batchForwardSheetOpen by remember($chatRuntimeIdentity)"))
        assertTrue(source.contains("var showBatchDeleteConfirm by remember($controllerIdentity)"))
    }

    @Test
    fun batchSelectionDerivationsStayRemembered() {
        val source = source("ConversationScreen.kt").replace(Regex("\\s+"), " ")

        assertTrue(
            source.contains(
                "return remember(selections, appState.profileRevisionForCompose, composerGate) { " +
                    "val actionItems = selections.map { selection -> " +
                    "selection.action.copy(senderDisplayName = appState.displayName(selection.action.senderId)) }",
            ),
        )
        assertTrue(
            source.contains(
                "copyText = batchCopyText(actionItems)",
            ),
        )
        assertTrue(
            source.contains(
                "forwardPayloads = forwardPayloads",
            ),
        )
        assertTrue(
            source.contains(
                "actionAvailability = batchSelectionActionAvailability(actionItems, composerGate)",
            ),
        )
    }

    @Test
    fun selectedMessageInfoUsesRetainedSnapshotOutsideTimelineWindow() {
        val source = source("ConversationScreen.kt")

        assertTrue(source.contains("batchInfoSelection"))
        assertTrue(source.contains("batchInfoSelection = batchSelectionUi.selections.singleOrNull()"))
        assertFalse(source.contains("renderedTimeline.firstOrNull { it.record.messageIdHex == infoMessageId }"))
    }

    @Test
    fun forwardingClearsSelectionThroughThePickerDismissalBoundary() {
        val source = source("ConversationScreen.kt").replace(Regex("\\s+"), " ")
        val forwardSheet = source.substringAfter("if (batchForwardSheetOpen").substringBefore("batchInfoSelection?.let")

        assertTrue("onDismiss = { batchForwardSheetOpen = false selectedMessages.clear() }" in forwardSheet)
        assertTrue("payloads = batchSelectionUi.forwardPayloads" in forwardSheet)
        assertTrue("sourceAccountRef = controller.boundAccountRef" in forwardSheet)
        assertFalse(
            Regex("onForward.*selectedMessages\\.clear\\(\\)").containsMatchIn(forwardSheet),
        )
    }

    /** Batch selection draws a leading gutter and tints behind the bubble content instead of over it. */
    @Test
    fun bubbleSelectionUsesLeadingGutterWithTintBehindContent() {
        val source = source("messages/MessageBubble.kt")

        assertTrue(source.contains("selectionMode: Boolean"))
        assertTrue(source.contains("batchSelectable: Boolean"))
        assertTrue(source.contains("selected: Boolean"))
        assertTrue(source.contains("onToggleSelection: () -> Unit"))
        assertTrue(source.contains("MessageBubbleSelectionGutter("))
        assertTrue(source.contains("messageBubbleSelectionRow("))
        assertTrue(source.contains("messageBubbleSelectionGutterWidth"))
        assertTrue(source.contains("if (mine) Spacer(Modifier.weight(1f))"))
        assertTrue(!source.contains(".matchParentSize()"))
        assertTrue(source.contains("canSelect = !deleted && !actionsReadOnly && batchSelectable"))
    }

    /** Every action-menu entry point is gated on selection mode and resets the long-press anchor before opening. */
    @Test
    fun selectionModeBlocksEveryMessageActionMenuEntryPoint() {
        val source = source("messages/MessageBubble.kt").replace(Regex("\\s+"), " ")

        assertTrue(source.contains("expanded = isActionMenuOpen && !selectionMode && !textSelectionMode"))
        assertTrue(
            source.contains(
                "remember( textSelectionMode, selectionMode, displayedBody, " +
                    "record.contentTokens.truncated, onActionMenuOpenChange, )",
            ),
        )
        assertTrue(
            source.contains(
                "if (!selectionMode && !textSelectionMode) { " +
                    "pressState.longPressWindowPosition = null pressState.selectionSeedVisibleOffset = null " +
                    "longPressWindowY = null pressState.actionMenuAnchorBounds = pressState.messageBoundsInWindow[0] " +
                    "onActionMenuOpenChange(true)",
            ),
        )
        assertTrue(
            source.contains(
                "if (!deleted && !selectionMode && !textSelectionMode) { " +
                    "pressState.longPressWindowPosition = null pressState.selectionSeedVisibleOffset = null " +
                    "longPressWindowY = null pressState.actionMenuAnchorBounds = pressState.messageBoundsInWindow[0] " +
                    "onActionMenuOpenChange(true)",
            ),
        )
    }

    /** Delete for me and delete for everyone share the bubble's single in-flight guard. */
    @Test
    fun messageBubbleUsesOneGuardForBothDeleteMutations() {
        val state = source("messages/MessageBubbleState.kt")
        val source =
            source("messages/MessageBubble.kt")
                .substringAfter("fun requestDelete()")
                .substringBefore("fun reactWithEmoji")

        assertFalse(state.contains("deleteForMeInFlight") || source.contains("deleteForMeInFlight"))
        assertFalse(state.contains("deleteForEveryoneInFlight") || source.contains("deleteForEveryoneInFlight"))
        assertTrue(state.contains("var deleteInFlight by mutableStateOf(false)"))
        assertTrue(source.split("if (surfaces.deleteInFlight) return").size - 1 == 2)
        assertTrue(source.split("surfaces.deleteInFlight = true").size - 1 == 2)
        assertTrue(source.split("surfaces.deleteInFlight = false").size - 1 == 2)
    }

    /** Reads a conversation source file from either the module or the repository working directory. */
    private fun source(relativePath: String): String =
        listOf(
            File("src/main/java/dev/ipf/whitenoise/android/ui/conversation/$relativePath"),
            File("app/src/main/java/dev/ipf/whitenoise/android/ui/conversation/$relativePath"),
        ).firstOrNull { it.exists() }
            ?.readText()
            ?: error("Missing conversation source: $relativePath")

    private fun mainShellSource(): String =
        listOf(
            File("src/main/java/dev/ipf/whitenoise/android/ui/navigation/MainShell.kt"),
            File("app/src/main/java/dev/ipf/whitenoise/android/ui/navigation/MainShell.kt"),
        ).firstOrNull { it.exists() }
            ?.readText()
            ?: error("Missing MainShell.kt source file")
}
