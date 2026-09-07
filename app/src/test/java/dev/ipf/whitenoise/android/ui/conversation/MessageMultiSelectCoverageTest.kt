package dev.ipf.whitenoise.android.ui.conversation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class MessageMultiSelectCoverageTest {
    @Test
    fun longPressMenuOffersSelectEntry() {
        val menuSource = source("messages/MessageActions.kt")
        val actionModelSource = source("messages/MessageActionKind.kt")

        assertTrue(menuSource.contains("canSelect: Boolean"))
        assertTrue(menuSource.contains("onSelect: () -> Unit"))
        assertTrue(actionModelSource.contains("if (canSelect) add(MessageActionKind.Select)"))
        assertTrue(actionModelSource.contains("MessageActionKind.Select -> stringResource(R.string.select)"))
    }

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
        assertTrue(screenSource.contains("if (initialTimelineAnchored && !selectionMode)"))
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
        assertTrue(source.contains("surfaceState ?: remember($controllerIdentity) { ConversationSurfaceState() }"))
        val surfaceOwner =
            "remember(selectedOrPendingConversationController, appState.runtimeGeneration) { " +
                "ConversationSurfaceState() }"
        assertTrue(mainShell.contains(surfaceOwner))
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
        assertTrue(source.contains("canSelect = !deleted && !readOnly && batchSelectable"))
    }

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
                    "longPressWindowPosition = null selectionSeedVisibleOffset = null longPressWindowY = null " +
                    "actionMenuAnchorBounds = messageBoundsInWindow[0] " +
                    "onActionMenuOpenChange(true)",
            ),
        )
        assertTrue(
            source.contains(
                "if (!deleted && !selectionMode && !textSelectionMode) { " +
                    "longPressWindowPosition = null selectionSeedVisibleOffset = null longPressWindowY = null " +
                    "actionMenuAnchorBounds = messageBoundsInWindow[0] " +
                    "onActionMenuOpenChange(true)",
            ),
        )
    }

    @Test
    fun messageBubbleUsesOneGuardForBothDeleteMutations() {
        val source =
            source("messages/MessageBubble.kt")
                .substringAfter("var deleteDialogOpen")
                .substringBefore("fun reactWithEmoji")

        assertFalse(source.contains("deleteForMeInFlight"))
        assertFalse(source.contains("deleteForEveryoneInFlight"))
        assertTrue(source.contains("var deleteInFlight by remember"))
        assertTrue(source.split("if (deleteInFlight) return").size - 1 == 2)
        assertTrue(source.split("deleteInFlight = true").size - 1 == 2)
        assertTrue(source.split("deleteInFlight = false").size - 1 == 2)
    }

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
