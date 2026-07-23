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
            "mute overflow must route to appState.setConversationMuted",
            "appState.setConversationMuted" in muteHandler,
        )
        assertTrue(
            "mute overflow must exit selection mode",
            "clearSelection()" in muteHandler,
        )
    }

    private fun chatsScreenSource(): File =
        listOf(
            File("src/main/java/dev/ipf/whitenoise/android/ui/chats/ChatsScreen.kt"),
            File("app/src/main/java/dev/ipf/whitenoise/android/ui/chats/ChatsScreen.kt"),
        ).firstOrNull { it.exists() }
            ?: error("Missing ChatsScreen.kt source file")

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
