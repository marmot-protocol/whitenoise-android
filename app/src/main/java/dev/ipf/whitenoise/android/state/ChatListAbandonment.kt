package dev.ipf.whitenoise.android.state

/**
 * Chat-list local abandonment (#3310) of one reviewed row: MDK's forget through the same optimistic row removal,
 * durable cleanup journal, failure notice and scoped retry as Delete from this device, in abandonment wording. It
 * never leaves, demotes or transfers admin, so it completes whatever the peer's acceptance, eligibility or
 * availability. Deferred cleanup finishes quietly from the journal. With [reportedByBatch] the bulk batch dialog
 * reports this chat's outcome and owns its Retry, so neither a success nor a failure notice is posted here.
 */
internal suspend fun ChatsController.abandonChatFromList(
    groupIdHex: String,
    isDm: Boolean,
    reportedByBatch: Boolean = false,
): Boolean {
    val wording = LocalRemovalWording.abandonment(isDm)
    return deleteGroupLocalFromChatList(
        groupIdHex,
        notify = !reportedByBatch,
        wording = wording,
        observer = LocalChatDeleteObserver(presentsFailure = !reportedByBatch),
        forgetProtocol = true,
    )
}
