package dev.ipf.whitenoise.android.maestro

import dev.ipf.marmotkit.Marmot
import dev.ipf.marmotkit.TimelineMessageQueryFfi
import dev.ipf.marmotkit.TimelineMessageRecordFfi
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/** Read both native timelines; optimistic UI text cannot certify a reply, edit or deletion scope. */
internal suspend fun verifyMaestroMessageMutation(
    native: Marmot,
    state: WhiteNoiseAppState,
    postcondition: String,
    baseline: MaestroMessageBaseline,
) {
    check(withContext(Dispatchers.Main.immediate) { state.activeAccountRef } == baseline.account)
    checkNotNull(native.presentedChatListRow(baseline.account, baseline.group)) {
        "Message action removed its conversation"
    }
    withTimeout(30_000L) {
        while (true) {
            val own = readMaestroMessages(native, baseline.account, baseline.group)
            val remote = readMaestroMessages(native, baseline.peer, baseline.group)
            val original = own.singleOrNull { it.messageIdHex == baseline.messageId }
            val peerOriginal = remote.singleOrNull { it.messageIdHex == baseline.messageId }
            val matches =
                when (postcondition) {
                    "message-reply" ->
                        originalMessagesRetained(original, peerOriginal) && replyMatches(own, remote, baseline)
                    "message-edit" -> editMatches(original, peerOriginal)
                    "message-delete-local" ->
                        localDeletionMatches(original, peerOriginal)
                    "message-delete-everyone" -> deletionMatches(original, peerOriginal)
                    else -> error("Unknown message postcondition")
                }
            if (matches && state.draftStore.get(baseline.account, baseline.group).isNullOrBlank()) return@withTimeout
            delay(100L)
        }
    }
}

/** Use the same typed MDK projection as the application, without synthesizing message records. */
private suspend fun readMaestroMessages(
    native: Marmot,
    account: String,
    group: String,
): List<TimelineMessageRecordFfi> =
    native.timelineMessages(account, TimelineMessageQueryFfi(group, null, null, null, null, null, 100u)).messages

/** Reply publication preserves the original and references that exact original on both accounts. */
private fun originalMessagesRetained(
    own: TimelineMessageRecordFfi?,
    peer: TimelineMessageRecordFfi?,
): Boolean {
    if (own == null || peer == null) return false
    return own.plaintext == "Generated fixture message" && !own.deleted &&
        peer.plaintext == "Generated fixture message" && !peer.deleted
}

/** Duplicate reply publication or a plain text send without its native reply target is a failure. */
private fun replyMatches(
    own: List<TimelineMessageRecordFfi>,
    peer: List<TimelineMessageRecordFfi>,
    baseline: MaestroMessageBaseline,
): Boolean {
    val local = own.filter { it.plaintext == "Maestro verified reply" }
    val remote = peer.filter { it.plaintext == "Maestro verified reply" }
    check(local.size <= 1 && remote.size <= 1) { "Duplicate reply publication" }
    val localReply = local.singleOrNull()
    val remoteReply = remote.singleOrNull()
    if (localReply == null || remoteReply == null) return false
    return localReply.messageIdHex == remoteReply.messageIdHex &&
        localReply.replyToMessageIdHex == baseline.messageId && remoteReply.replyToMessageIdHex == baseline.messageId
}

/** An accepted edit must change the original identity on the owner and peer, with native edit metadata. */
private fun editMatches(
    own: TimelineMessageRecordFfi?,
    peer: TimelineMessageRecordFfi?,
): Boolean {
    if (own == null || peer == null) return false
    return own.plaintext == "Maestro verified edit" && own.edit != null &&
        peer.plaintext == "Maestro verified edit" && peer.edit != null
}

/** Device-local deletion must leave the exact original visible and undeleted to its peer. */
private fun localDeletionMatches(
    own: TimelineMessageRecordFfi?,
    peer: TimelineMessageRecordFfi?,
): Boolean {
    if (peer == null) return false
    return (own == null || own.deleted) && !peer.deleted && peer.plaintext == "Generated fixture message"
}

/** Both accounts must identify the same published deletion of the baseline message. */
private fun deletionMatches(
    own: TimelineMessageRecordFfi?,
    peer: TimelineMessageRecordFfi?,
): Boolean {
    if (own == null || peer == null) return false
    return own.deleted && peer.deleted &&
        own.deletedByMessageIdHex != null && own.deletedByMessageIdHex == peer.deletedByMessageIdHex
}
