package dev.ipf.whitenoise.android.maestro

import dev.ipf.marmotkit.Marmot
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout

/** Verify native pin state or strictly device-local deletion without interpreting a hidden UI row as success. */
internal suspend fun verifyMaestroChatList(
    native: Marmot,
    owner: String,
    peer: String,
    group: String,
    postcondition: String,
) {
    withTimeout(30_000L) {
        while (true) {
            val local = native.presentedChatListRow(owner, group)?.row
            val remote = native.presentedChatListRow(peer, group)?.row
            check(remote?.pinned != true) { "Alice's local action altered Bob's row" }
            val matched =
                when (postcondition) {
                    "chat-deleted" -> local == null
                    "chat-pinned" -> local?.pinned == true
                    "chat-unpinned" -> local?.pinned == false
                    else -> error("Unknown chat-list postcondition")
                }
            if (remote != null && matched) return@withTimeout
            delay(100L)
        }
    }
}
