package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.DiscardFailedSendOutcomeFfi
import dev.ipf.marmotkit.DiscardFailedSendResultFfi
import dev.ipf.marmotkit.TimelineMessageRecordFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.core.MessageAttachments
import dev.ipf.whitenoise.android.core.TimelineProjector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/** MDK's invalidation status for an own send whose local publish attempt failed. */
internal const val LOCAL_PUBLISH_FAILED = "local_publish_failed"

/** Discards in flight, keyed by account, group and message, so a repeated tap makes one native call. */
private val durableDiscardsInFlight: MutableSet<String> = ConcurrentHashMap.newKeySet()

/** Whether [record] is an own send MDK recorded but never confirmed publishing, which MDK can discard. */
internal fun ConversationController.isDurableFailedRecord(record: TimelineMessageRecordFfi?): Boolean =
    record != null &&
        !record.deleted &&
        record.invalidationStatus == LOCAL_PUBLISH_FAILED &&
        isMessageMine(TimelineProjector.toAppMessageRecord(record))

/** The row form of [isDurableFailedRecord], used by the bubble's Discard actions. */
internal fun ConversationController.isDurableFailedSend(item: TimelineMessage) = isDurableFailedRecord(item.projected)

/** MDK's discard call for one failed send, made off the main thread. */
private typealias FailedSendDiscard = suspend (account: String, groupIdHex: String, messageIdHex: String) ->
DiscardFailedSendResultFfi

/**
 * Removes a failed own send from this device through MDK, which publishes nothing, so the group never hears about a
 * message nobody received. The timeline then drops the row through its own removal. Returns true once the row is
 * gone. A send MDK may still publish is left alone with a notice. A row MDK no longer counts as a failed send, because
 * it was delivered or resent meanwhile, is hidden on this device instead, as the user asked to remove it, which
 * matches White Noise on iOS. No group commit lock is taken: nothing is committed, MDK arbitrates the sends it still
 * owns, and the lock is held for whole uploads.
 */
internal suspend fun ConversationController.discardDurableFailedSend(
    messageIdHex: String,
    discard: FailedSendDiscard = { account, groupIdHex, messageId ->
        appState.marmotIo { discardFailedSend(account, groupIdHex, messageId) }
    },
): Boolean {
    val account = conversationAccountRef
    val flightKey = "$account|${group.groupIdHex}|${messageIdHex.lowercase()}"
    if (account == null || !durableDiscardsInFlight.add(flightKey)) return false
    val generation = appState.runtimeGeneration
    val owns = { appState.runtimeGeneration == generation }
    // The removal can land before MDK replies, so capture the row's cache keys first.
    val cacheKeys =
        timelineRecords[messageIdHex]
            ?.let { record -> MessageAttachments.accepted(record.media).map { (index, _) -> index } }
            .orEmpty()
            .map { index -> mediaCacheKey(account, messageIdHex, index) }
    try {
        return runCatchingCancellable { discard(account, group.groupIdHex, messageIdHex) }.fold(
            onSuccess = { result -> applyDiscardOutcome(account, messageIdHex, cacheKeys, result, owns) },
            onFailure = { failure ->
                if (owns()) appState.presentFailure(R.string.toast_couldnt_delete_message, "MESSAGE_DISCARD", failure)
                false
            },
        )
    } finally {
        durableDiscardsInFlight.remove(flightKey)
    }
}

/** Applies MDK's discard answer for [messageIdHex], returning true once the row is gone from this device. */
private suspend fun ConversationController.applyDiscardOutcome(
    account: String,
    messageIdHex: String,
    cacheKeys: List<String>,
    result: DiscardFailedSendResultFfi,
    owns: () -> Boolean,
): Boolean =
    when (result.outcome) {
        DiscardFailedSendOutcomeFfi.DISCARDED -> {
            withContext(NonCancellable) { purgeDiscardedSendMedia(account, cacheKeys, result.mediaCiphertextSha256) }
            if (replyingTo?.messageIdHex == messageIdHex) replyingTo = null
            true
        }
        DiscardFailedSendOutcomeFfi.STILL_SENDING -> {
            if (owns()) presentConversationTransient(R.string.message_discard_still_sending)
            false
        }
        DiscardFailedSendOutcomeFfi.NOT_FAILED_SEND -> owns() && hideMessageForMe(messageIdHex)
    }

/** Drops a discarded send's decrypted media by cache key and by MDK's ciphertext hashes. */
private suspend fun ConversationController.purgeDiscardedSendMedia(
    account: String,
    cacheKeys: List<String>,
    ciphertextSha256: List<String>,
) {
    removeMediaMemoryCacheKeys(cacheKeys, Dispatchers.Main.immediate, appState::removeMediaMemoryCacheEntry)
    withContext(Dispatchers.IO) { cacheKeys.forEach { appState.diskMediaCache.remove(it) } }
    evictExpiredMediaCaches(account, ciphertextSha256.mapTo(mutableSetOf()) { it.lowercase() })
}
