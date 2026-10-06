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
                start = "pendingBulkDelete?.let { request ->",
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
        assertDeleteRecoveryWiring(confirmBlock)
    }

    private fun assertDeleteRecoveryWiring(confirmBlock: String) {
        assertTrue(
            "bulk deletion must stop when the original account/runtime changes",
            "deleteLocalChatsBatch" in confirmBlock,
        )
        assertTrue(
            "bulk confirmation must reject a stale origin",
            "if (!isCurrent()) return@ChatDeleteConfirmationDialog" in confirmBlock,
        )
        assertTrue(
            "bulk results must not notify a replacement account",
            "if (isCurrent() && result.deleted" in confirmBlock,
        )
        assertTrue("stopped batches must report the full total", "result.deleted < result.total" in confirmBlock)
        assertTrue(
            "the dialog must use its original captured owner",
            "request.isCurrent(appState, controller)" in confirmBlock,
        )
        assertTrue(
            "stopped batches must retain the exact failure diagnostic",
            "onFailure = { failure = it }" in confirmBlock,
        )
        assertTrue("the whole batch must share one readiness budget", "observer = observer" in confirmBlock)
        assertTrue(
            "stopped batches must not use a success banner",
            "presentStoppedLocalChatDeleteBatch( result, failure, notice = " +
                "appState.localDeleteBatchRetryNotice(controller, remaining.groupIds, isCurrent)," in
                confirmBlock.replace(Regex("\\s+"), " "),
        )
        assertTrue(
            "deferred cleanup must not also show a usual success banner",
            "result.deleted > 0 && !cleanupDeferred" in confirmBlock,
        )
    }

    @Test
    fun deleteGroupLocalFromChatList_neverLeavesGroup() {
        val body = controllersSource().readText().kotlinFunctionBody("deleteGroupLocalFromChatList")

        assertTrue(
            "local chat-list wipe must use the bounded, reconciled local cleanup",
            "deleteChatGroupLocalWithRecovery" in body,
        )
        assertFalse(
            "local chat-list wipe must not consult membership or leave the group",
            "leaveGroup" in body || "groupMembers" in body,
        )
        assertTrue(
            "local chat-list wipe must optimistically hide and restore the row",
            "removeChatRow" in body && "restoreRemovedChatRow" in body,
        )
        assertTrue(
            "local wipe must fence account, bind, runtime, sign-out and wipe changes",
            "chatListDepartureIsCurrent(account, epoch, runtime)" in body,
        )
        assertTrue(
            "local wipe must report only a current terminal failure",
            "if (isCurrent()) {" in body && "appState.presentLocalDeleteFailure(" in body,
        )
    }

    /** The screen keeps native eligibility, captured origin and concurrent deletion separate. */
    @Test
    fun departureWiringRetainsItsEligibilityAndOriginGuards() {
        val source = chatsScreenSource().readText()
        val menu = source.requiredSection("onLeaveAndDelete =", "onDismiss =")
        assertTrue("!item.isDm()" in menu)
        assertTrue("!item.group.leaveRequestPending" in menu)
        assertTrue("item.projection?.leaveRequestPending != true" in menu)
        val departure = source.requiredSection("pendingLeaveAndDelete?.let", "pendingBulkDelete?.let")
        assertTrue("controller.leaveAndDeleteFromChatList(groupId)" in departure)
        assertTrue("leavingAndDeleting.add(groupId)" in departure)
        assertTrue("leavingAndDeleting.remove(groupId)" in departure)
        assertTrue("appState.activeAccountRef != originAccount" in departure)
        assertTrue("appState.runtimeGeneration != originRuntime" in departure)
        assertTrue("appState.signOutInProgress || appState.wipeInProgress" in departure.replace(Regex("\\s+"), " "))
        val localDelete = source.requiredSection("pendingBulkDelete?.let", "onDismiss = { pendingBulkDelete = null }")
        assertTrue("request.groupIds.any { it in leavingAndDeleting }" in localDelete)
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
