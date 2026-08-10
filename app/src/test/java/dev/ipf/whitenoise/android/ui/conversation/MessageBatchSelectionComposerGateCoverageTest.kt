package dev.ipf.whitenoise.android.ui.conversation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class MessageBatchSelectionComposerGateCoverageTest {
    @Test
    fun batchReplyAvailabilityUsesSharedComposerGateNotInviteAlone() {
        val screen = conversationScreenSource()

        assertFalse(
            "batch reply must not treat only pendingConfirmation as read-only; every non-COMPOSER gate disables reply",
            screen.contains("val selectionReadOnly = controller.group.pendingConfirmation"),
        )
        assertTrue(
            screen.contains("rememberConversationBatchSelectionUiState(") &&
                screen.contains("composerGate = composerGate"),
        )
    }

    @Test
    fun conversationComputesComposerGateOnceForBatchSelectionAndBottomBar() {
        val screen = conversationScreenSource()
        val gateAssignments =
            Regex("""val composerGate\s*=""")
                .findAll(screen)
                .toList()

        assertTrue(
            "ConversationScreen should compute composerGate once and reuse it for batch reply and the bottom bar",
            gateAssignments.size == 1,
        )
    }

    private fun conversationScreenSource(): String =
        listOf(
            File("src/main/java/dev/ipf/whitenoise/android/ui/conversation/ConversationScreen.kt"),
            File("app/src/main/java/dev/ipf/whitenoise/android/ui/conversation/ConversationScreen.kt"),
        ).first(File::exists).readText()
}
