package dev.ipf.whitenoise.android.ui.conversation

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class MessageMultiSelectCoverageTest {
    @Test
    fun longPressMenuOffersSelectEntry() {
        val source = source("messages/MessageActions.kt")

        assertTrue(source.contains("canSelect: Boolean"))
        assertTrue(source.contains("onSelect: () -> Unit"))
        assertTrue(source.contains("R.string.select"))
    }

    @Test
    fun conversationOwnsSelectionActionsAndHidesBottomChrome() {
        val source = source("ConversationScreen.kt")

        assertTrue(source.contains("selectedMessages"))
        assertTrue(source.contains("MessageSelectionBar("))
        assertTrue(source.contains("batchCopyText(selectedActionItems)"))
        assertTrue(source.contains("batchForwardBodies(selectedActionItems)"))
        assertTrue(source.contains("selectionMode ->"))
        assertTrue(source.contains("if (initialTimelineAnchored && !selectionMode)"))
    }

    @Test
    fun batchSelectionStateKeysOnControllerIdentity() {
        val source = source("ConversationScreen.kt").replace(Regex("\\s+"), " ")
        val controllerIdentity = "chat.id, appState.activeAccountRef, appState.runtimeGeneration"

        assertTrue(source.contains("val selectedMessages = remember($controllerIdentity)"))
        assertTrue(source.contains("val selectedSelections by remember($controllerIdentity)"))
        assertTrue(source.contains("var batchForwardSheetOpen by remember($controllerIdentity)"))
        assertTrue(source.contains("var showBatchDeleteConfirm by remember($controllerIdentity)"))
    }

    @Test
    fun bubbleSelectionOverlayInterceptsRowTaps() {
        val source = source("messages/MessageBubble.kt")

        assertTrue(source.contains("selectionMode: Boolean"))
        assertTrue(source.contains("batchSelectable: Boolean"))
        assertTrue(source.contains("selected: Boolean"))
        assertTrue(source.contains("onToggleSelection: () -> Unit"))
        assertTrue(source.contains(".matchParentSize()"))
        assertTrue(source.contains("Icons.Default.CheckCircle"))
        assertTrue(source.contains("canSelect = !readOnly && batchSelectable"))
    }

    @Test
    fun selectionModeBlocksEveryMessageActionMenuEntryPoint() {
        val source = source("messages/MessageBubble.kt").replace(Regex("\\s+"), " ")

        assertTrue(source.contains("expanded = isActionMenuOpen && !deleted && !selectionMode"))
        assertTrue(source.contains("remember(selectionMode, onActionMenuOpenChange)"))
        assertTrue(source.contains("if (!selectionMode) { longPressWindowY = null onActionMenuOpenChange(true)"))
        assertTrue(source.contains("if (!deleted && !selectionMode) { longPressWindowY = null onActionMenuOpenChange(true)"))
    }

    private fun source(relativePath: String): String =
        listOf(
            File("src/main/java/dev/ipf/whitenoise/android/ui/conversation/$relativePath"),
            File("app/src/main/java/dev/ipf/whitenoise/android/ui/conversation/$relativePath"),
        ).firstOrNull { it.exists() }
            ?.readText()
            ?: error("Missing conversation source: $relativePath")
}
