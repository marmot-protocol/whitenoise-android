package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.MessageTagFfi

/** One message's own event tags, which conversation-window rows leave off; null when unreadable. */
internal suspend fun ConversationController.messageTags(messageIdHex: String): List<MessageTagFfi>? {
    val account = boundAccountRef ?: return null
    return runCatchingCancellable {
        appState.marmotIo(MarmotTraceSection.TIMELINE_READ) {
            timelineRowTags(account, group.groupIdHex, messageIdHex)
        }
    }.getOrNull()
}
