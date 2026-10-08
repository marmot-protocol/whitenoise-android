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
                        localDeletionMatches(state, original, peerOriginal, baseline)
                    "message-delete-everyone" -> deletionMatches(original, peerOriginal)
                    else -> error("Unknown message postcondition")
                }
            if (matches && state.draftStore.get(baseline.account, baseline.group).isNullOrBlank()) return@withTimeout
            delay(100L)
        }
    }
}

/** Use the same typed MDK projection as the application, without synthesizing message records. */
internal fun readMaestroMessages(
    native: Marmot,
    account: String,
    group: String,
): List<TimelineMessageRecordFfi> {
    val query = TimelineMessageQueryFfi(group, null, null, null, null, null, 100u)
    return native.timelineMessages(account, query).messages
}

/** Reply publication preserves the original and references that exact original on both accounts. */
private fun originalMessagesRetained(
    own: TimelineMessageRecordFfi?,
    peer: TimelineMessageRecordFfi?,
): Boolean {
    if (own == null || peer == null) return false
    return own.plaintext == "Generated fixture message" &&
        !own.deleted &&
        peer.plaintext == "Generated fixture message" &&
        !peer.deleted
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
        localReply.replyToMessageIdHex == baseline.messageId &&
        remoteReply.replyToMessageIdHex == baseline.messageId
}

/** An accepted edit must change the original identity on the owner and peer, with native edit metadata. */
private fun editMatches(
    own: TimelineMessageRecordFfi?,
    peer: TimelineMessageRecordFfi?,
): Boolean {
    if (own == null || peer == null) return false
    return own.plaintext == "Maestro verified edit" &&
        own.edit != null &&
        peer.plaintext == "Maestro verified edit" &&
        peer.edit != null
}

/** Delete for me hides only the owner's presentation; both native originals remain undeleted. */
private suspend fun localDeletionMatches(
    state: WhiteNoiseAppState,
    own: TimelineMessageRecordFfi?,
    peer: TimelineMessageRecordFfi?,
    baseline: MaestroMessageBaseline,
): Boolean {
    val hidden =
        withContext(Dispatchers.Main.immediate) {
            state.hiddenMessageIdsInGroup(baseline.account, baseline.group) to
                state.hiddenMessageIdsInGroup(baseline.peer, baseline.group)
        }
    return originalMessagesRetained(own, peer) &&
        hidden.first == setOf(baseline.messageId.lowercase()) &&
        hidden.second.isEmpty()
}

/** Both accounts must identify the same published deletion of the baseline message. */
private fun deletionMatches(
    own: TimelineMessageRecordFfi?,
    peer: TimelineMessageRecordFfi?,
): Boolean {
    if (own == null || peer == null) return false
    return own.deleted &&
        peer.deleted &&
        own.deletedByMessageIdHex != null &&
        own.deletedByMessageIdHex == peer.deletedByMessageIdHex
}
