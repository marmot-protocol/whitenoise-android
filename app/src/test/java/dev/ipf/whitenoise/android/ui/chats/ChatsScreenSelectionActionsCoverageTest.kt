package dev.ipf.whitenoise.android.ui.chats

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ChatsScreenSelectionActionsCoverageTest {
    @Test
    fun singleSelectionOverflowWiresMarkReadAndMute() {
        val source = chatsScreenSource().readText()

        assertTrue(
            "selection bar must expose mark-read for a single unread selection",
            "showMarkRead = singleSelectedItem?.hasUnread == true" in source,
        )
        assertTrue(
            "selection bar must expose mute toggle for a single selection",
            "showMuteToggle = singleSelectedItem != null" in source,
        )
        assertTrue(
            "mark-read overflow must route to controller.markAllRead",
            "controller.markAllRead(item)" in source,
        )
        assertTrue(
            "mute overflow must route to appState.setConversationMuted",
            "appState.setConversationMuted" in source,
        )
    }

    private fun chatsScreenSource(): File =
        listOf(
            File("src/main/java/dev/ipf/whitenoise/android/ui/chats/ChatsScreen.kt"),
            File("app/src/main/java/dev/ipf/whitenoise/android/ui/chats/ChatsScreen.kt"),
        ).firstOrNull { it.exists() }
            ?: error("Missing ChatsScreen.kt source file")
}
