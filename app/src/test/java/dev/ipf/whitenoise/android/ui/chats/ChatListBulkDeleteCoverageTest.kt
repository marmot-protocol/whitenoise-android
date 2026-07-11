package dev.ipf.whitenoise.android.ui.chats

import dev.ipf.whitenoise.android.audio.kotlinFunctionBody
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Regression for issue #1169: bulk Delete local must wipe device data only and
 * never call MLS leave for still-member groups.
 */
class ChatListBulkDeleteCoverageTest {
    @Test
    fun bulkDeleteConfirmUsesPureLocalWipePath() {
        val source = chatsScreenSource().readText()
        val confirmBlock =
            source.requiredSection(
                start = "pendingBulkDelete?.let { items ->",
                end = "\n}\n\n/**\n * Resolution state for a chat-list search query",
            )

        assertTrue(
            "bulk delete confirm must call the pure local wipe helper",
            "deleteGroupLocalFromChatList" in confirmBlock,
        )
        assertFalse(
            "bulk delete confirm must not route through leave-first chat-list delete",
            "deleteGroupFromChatList" in confirmBlock,
        )
        assertFalse(
            "bulk local delete must not gate sole admins or route them through transfer-and-leave",
            "soleAdminTransferCandidates" in confirmBlock ||
                "transferAdminThenDeleteFromChatList" in confirmBlock,
        )
    }

    @Test
    fun deleteGroupLocalFromChatList_neverLeavesGroup() {
        val body = controllersSource().readText().kotlinFunctionBody("deleteGroupLocalFromChatList")

        assertTrue(
            "local chat-list wipe must reuse shared client cleanup",
            "deleteGroupLocalWithClientCleanup" in body,
        )
        assertFalse(
            "local chat-list wipe must not consult membership or leave the group",
            "leaveGroup" in body || "groupMembers" in body,
        )
        assertTrue(
            "local chat-list wipe must optimistically hide and restore the row",
            "removeChatRow" in body && "foldChatRow" in body,
        )
    }

    private fun chatsScreenSource(): File =
        listOf(
            File("src/main/java/dev/ipf/whitenoise/android/ui/chats/ChatsScreen.kt"),
            File("app/src/main/java/dev/ipf/whitenoise/android/ui/chats/ChatsScreen.kt"),
        ).firstOrNull { it.exists() }
            ?: error("Missing ChatsScreen.kt source file")

    private fun controllersSource(): File =
        listOf(
            File("src/main/java/dev/ipf/whitenoise/android/state/Controllers.kt"),
            File("app/src/main/java/dev/ipf/whitenoise/android/state/Controllers.kt"),
        ).firstOrNull { it.exists() }
            ?: error("Missing Controllers.kt source file")

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
