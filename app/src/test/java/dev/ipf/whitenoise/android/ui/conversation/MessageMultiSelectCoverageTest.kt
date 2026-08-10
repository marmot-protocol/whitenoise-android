package dev.ipf.whitenoise.android.ui.conversation

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
    fun conversationOwnsSelectionActionsAndHidesBottomChrome() {
        val screenSource = source("ConversationScreen.kt")
        val topBarSource = source("ConversationTopBar.kt")
        val bottomBarSource = source("ConversationBottomBar.kt")

        assertTrue(screenSource.contains("selectedMessages"))
        assertTrue(topBarSource.contains("MessageSelectionBar("))
        assertTrue(screenSource.contains("batchCopyText(actionItems)"))
        assertTrue(screenSource.contains("batchForwardBodies(actionItems)"))
        assertTrue(bottomBarSource.contains("selectionMode ->"))
        assertTrue(screenSource.contains("if (initialTimelineAnchored && !selectionMode)"))
    }

    @Test
    fun batchSelectionStateKeysOnControllerIdentity() {
        val source = source("ConversationScreen.kt").replace(Regex("\\s+"), " ")
        val controllerIdentity = "chat.id, appState.activeAccountRef, appState.runtimeGeneration"

        assertTrue(source.contains("val selectedMessages = remember($controllerIdentity)"))
        assertTrue(source.contains("rememberConversationBatchSelectionUiState("))
        assertTrue(source.contains("chatId = chat.id"))
        assertTrue(source.contains("activeAccountRef = appState.activeAccountRef"))
        assertTrue(source.contains("runtimeGeneration = appState.runtimeGeneration"))
        assertTrue(source.contains("var batchForwardSheetOpen by remember($controllerIdentity)"))
        assertTrue(source.contains("var showBatchDeleteConfirm by remember($controllerIdentity)"))
    }

    @Test
    fun batchSelectionDerivationsStayRemembered() {
        val source = source("ConversationScreen.kt").replace(Regex("\\s+"), " ")

        assertTrue(
            source.contains(
                "return remember(selections, appState.profileRevisionForCompose) { " +
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
                "forwardBodies = batchForwardBodies(actionItems)",
            ),
        )
        assertTrue(
            source.contains(
                "deleteBreakdown = batchDeleteBreakdown(actionItems)",
            ),
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
        assertTrue(source.contains("canSelect = !readOnly && batchSelectable"))
    }

    @Test
    fun selectionModeBlocksEveryMessageActionMenuEntryPoint() {
        val source = source("messages/MessageBubble.kt").replace(Regex("\\s+"), " ")

        assertTrue(source.contains("expanded = isActionMenuOpen && !deleted && !selectionMode && !textSelectionMode"))
        assertTrue(source.contains("remember(textSelectionMode, selectionMode, onActionMenuOpenChange)"))
        assertTrue(
            source.contains(
                "if (!selectionMode && !textSelectionMode) { " +
                    "longPressWindowPosition = null longPressWindowY = null " +
                    "actionMenuAnchorBounds = messageBoundsInWindow[0] onActionMenuOpenChange(true)",
            ),
        )
        assertTrue(
            source.contains(
                "if (!deleted && !selectionMode && !textSelectionMode) { " +
                    "longPressWindowPosition = null longPressWindowY = null " +
                    "actionMenuAnchorBounds = messageBoundsInWindow[0] onActionMenuOpenChange(true)",
            ),
        )
    }

    private fun source(relativePath: String): String =
        listOf(
            File("src/main/java/dev/ipf/whitenoise/android/ui/conversation/$relativePath"),
            File("app/src/main/java/dev/ipf/whitenoise/android/ui/conversation/$relativePath"),
        ).firstOrNull { it.exists() }
            ?.readText()
            ?: error("Missing conversation source: $relativePath")
}
