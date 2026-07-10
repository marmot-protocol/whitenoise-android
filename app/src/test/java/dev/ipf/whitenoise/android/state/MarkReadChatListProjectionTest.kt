package dev.ipf.whitenoise.android.state

import dev.ipf.whitenoise.android.audio.kotlinFunctionBody
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Regression for issue #1251: scroll-driven mark-read must fold
 * [markTimelineMessageRead]'s returned [ChatListRowFfi] into the active
 * [ChatsController] so chat-list unread badges and reopen dividers clear
 * without relying on a posted OS notification.
 */
class MarkReadChatListProjectionTest {
    @Test
    fun markReadUpTo_foldsReturnedChatListRowIntoChatsController() {
        val body = controllersSource().readText().kotlinFunctionBody("markReadUpTo")

        assertTrue(
            "markReadUpTo must apply markTimelineMessageRead's returned ChatListRowFfi to the chat list",
            "applyChatListRowFromMarkRead" in body,
        )
        assertTrue(
            "markReadUpTo must ignore superseded mark-read completions",
            "trimmed != lastReadMessageId" in body,
        )
        assertFalse(
            "mark-read projection refresh must not depend on mute state",
            "isMuted" in body || "chatMutePreferences" in body,
        )
    }

    @Test
    fun markAllRead_foldsReturnedChatListRowIntoChatsController() {
        val body = controllersSource().readText().kotlinFunctionBody("markAllRead")

        assertTrue(
            "markAllRead must apply markTimelineMessageRead's returned ChatListRowFfi to the chat list",
            "applyChatListRow" in body || "foldChatRow" in body,
        )
    }

    @Test
    fun markNotificationMessageRead_foldsReturnedChatListRowIntoChatsController() {
        val body = appStateSource().readText().kotlinFunctionBody("markNotificationMessageRead")

        assertTrue(
            "notification mark-read must apply markTimelineMessageRead's returned ChatListRowFfi to the chat list",
            "applyChatListRowFromMarkRead" in body,
        )
    }

    private fun controllersSource(): File =
        listOf(
            File("src/main/java/dev/ipf/whitenoise/android/state/Controllers.kt"),
            File("app/src/main/java/dev/ipf/whitenoise/android/state/Controllers.kt"),
        ).firstOrNull { it.exists() }
            ?: error("Missing Controllers.kt source file")

    private fun appStateSource(): File =
        listOf(
            File("src/main/java/dev/ipf/whitenoise/android/state/AppState.kt"),
            File("app/src/main/java/dev/ipf/whitenoise/android/state/AppState.kt"),
        ).firstOrNull { it.exists() }
            ?: error("Missing AppState.kt source file")
}
