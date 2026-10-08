package dev.ipf.whitenoise.android.maestro

import dev.ipf.marmotkit.Marmot
import dev.ipf.marmotkit.TimelineMessageQueryFfi
import dev.ipf.marmotkit.TimelineMessageRecordFfi
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout

/** Create two genuine native reactions after both generated accounts have received the original message. */
internal suspend fun seedMaestroReactions(
    native: Marmot,
    owner: String,
    peer: String,
    group: String,
) {
    val original =
        withTimeout(15_000L) {
            while (maestroReactionMessage(native, peer, group) == null) delay(100L)
            checkNotNull(maestroReactionMessage(native, owner, group))
        }
    native.reactToMessage(owner, group, original.messageIdHex, "👍")
    native.reactToMessage(peer, group, original.messageIdHex, "❤️")
    verifyMaestroReactions(native, owner, peer, group)
}

/** Filtering and dismissal must leave the same owner/peer reactions intact in both native projections. */
internal suspend fun verifyMaestroReactions(
    native: Marmot,
    owner: String,
    peer: String,
    group: String,
) {
    withTimeout(30_000L) {
        while (true) {
            val local = maestroReactionMessage(native, owner, group)
            val remote = maestroReactionMessage(native, peer, group)
            val projections = listOfNotNull(local, remote)
            val matchingTarget = local?.messageIdHex == remote?.messageIdHex
            if (projections.size == 2 && matchingTarget && projections.all(::maestroReactionsMatch)) {
                return@withTimeout
            }
            delay(100L)
        }
    }
}

/** Read only the generated original from the packaged MDK timeline, retaining its native reaction projection. */
private suspend fun maestroReactionMessage(
    native: Marmot,
    account: String,
    group: String,
): TimelineMessageRecordFfi? =
    native
        .timelineMessages(account, TimelineMessageQueryFfi(group, null, null, null, null, null, 100u))
        .messages
        .singleOrNull { it.plaintext == "Generated fixture message" }

/** Both distinct senders and exact emoji matter; a count alone could hide a duplicated or altered reaction. */
private fun maestroReactionsMatch(message: TimelineMessageRecordFfi): Boolean {
    val reactions = message.reactions.userReactions
    val expectedEmojis = reactions.map { it.emoji }.toSet() == setOf("👍", "❤️")
    val distinctSenders = reactions.map { it.sender }.toSet().size == 2
    val ownerThumb = reactions.any { it.sender == message.sender && it.emoji == "👍" }
    val expectedTypes = reactions.size == 2 && expectedEmojis && distinctSenders
    return expectedTypes && ownerThumb
}
