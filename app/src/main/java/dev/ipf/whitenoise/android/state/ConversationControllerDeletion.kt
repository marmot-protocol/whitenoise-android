package dev.ipf.whitenoise.android.state

/** True for an optimistic tombstone or a retained timeline row whose projection is deleted. */
internal fun ConversationController.isMessageDeleted(messageId: String): Boolean =
    messageId in deletedMessageIds || retainedTimelineItem(messageId)?.projected?.deleted == true
