package dev.ipf.whitenoise.android.ui.conversation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ConversationExitCoordinatorTest {
    @Test
    fun imeVisibleExitWaitsForInsetReleaseBeforeClearingFocusAndNavigating() {
        val events = mutableListOf<String>()
        val coordinator = ConversationExitCoordinator()

        coordinator.requestExit(
            imeIsOpen = true,
            hideIme = { events += "hide-ime" },
            clearComposerFocus = { events += "clear-focus" },
            navigate = { events += "navigate" },
        )
        coordinator.requestExit(
            imeIsOpen = true,
            hideIme = { events += "hide-ime-again" },
            clearComposerFocus = { events += "clear-focus-again" },
            navigate = { events += "navigate-again" },
        )

        assertEquals(listOf("hide-ime"), events)
        assertTrue(coordinator.awaitingImeDismiss)

        coordinator.onImeVisibilityChanged(
            imeIsOpen = false,
            clearComposerFocus = { events += "clear-focus" },
            navigate = { events += "navigate" },
        )

        assertEquals(listOf("hide-ime", "clear-focus", "navigate"), events)
        assertFalse(coordinator.awaitingImeDismiss)
    }

    @Test
    fun closedImeExitStillRequestsHideBeforeClearingFocusAndNavigating() {
        val events = mutableListOf<String>()
        val coordinator = ConversationExitCoordinator()

        coordinator.requestExit(
            imeIsOpen = false,
            hideIme = { events += "hide-ime" },
            clearComposerFocus = { events += "clear-focus" },
            navigate = { events += "navigate" },
        )

        assertEquals(listOf("hide-ime", "clear-focus", "navigate"), events)
    }

    @Test
    fun completedExitStartsFreshCycleForSameRetainedCoordinator() {
        val events = mutableListOf<String>()
        val coordinator = ConversationExitCoordinator()

        repeat(2) { cycle ->
            coordinator.requestExit(
                imeIsOpen = false,
                hideIme = { events += "hide-ime-$cycle" },
                clearComposerFocus = { events += "clear-focus-$cycle" },
                navigate = { events += "navigate-$cycle" },
            )
        }

        assertEquals(
            listOf(
                "hide-ime-0",
                "clear-focus-0",
                "navigate-0",
                "hide-ime-1",
                "clear-focus-1",
                "navigate-1",
            ),
            events,
        )
    }

    @Test
    fun conversationScreenRoutesEveryListExitThroughTheCoordinator() {
        val screen = sourceFile("ConversationScreen.kt").readText()

        assertTrue(screen.contains("fun exitConversation()"))
        assertTrue(screen.contains("ConversationTopBar("))
        assertTrue(screen.contains("onBack = ::exitConversation"))
        assertTrue(screen.contains("ConversationBackAction.NAVIGATE_UP -> exitConversation()"))
        assertTrue(screen.contains("onLeft = ::exitConversation"))
        listOf(
            "ConversationBackAction.NAVIGATE_UP -> onBack()",
            "onLeft = onBack",
            "IconButton(onClick = onBack)",
            "if (controller.declineInvite()) onBack()",
            "if (controller.leaveGroup()) onBack()",
        ).forEach { bypass ->
            assertFalse("Conversation list exit bypasses the coordinator: $bypass", screen.contains(bypass))
        }
    }

    private fun sourceFile(name: String): File =
        listOf(
            File("src/main/java/dev/ipf/whitenoise/android/ui/conversation/$name"),
            File("app/src/main/java/dev/ipf/whitenoise/android/ui/conversation/$name"),
        ).firstOrNull { it.exists() }
            ?: error("Missing conversation source file: $name")
}
