package dev.ipf.whitenoise.android.state

import dev.ipf.whitenoise.android.R

/**
 * Chat-list local abandonment (#3310) of one reviewed row: MDK's forget through the same optimistic row removal,
 * durable cleanup journal, failure notice and scoped retry as Delete from this device. It never leaves, demotes or
 * transfers admin, so it completes whatever the peer's acceptance, eligibility or availability.
 */
internal suspend fun ChatsController.abandonChatFromList(
    groupIdHex: String,
    isDm: Boolean,
): Boolean {
    var cleanupDeferred = false
    val removed =
        deleteGroupLocalFromChatList(
            groupIdHex,
            notify = false,
            failureMessage = if (isDm) R.string.toast_couldnt_abandon_chat else R.string.toast_couldnt_reset_group,
            observer = LocalChatDeleteObserver(onCleanupDeferred = { cleanupDeferred = true }),
            forgetProtocol = true,
        )
    // Deferred cleanup finishes quietly from the journal, as it does for a local delete.
    if (removed && !cleanupDeferred) {
        appState.presentTransient(if (isDm) R.string.toast_chat_abandoned else R.string.toast_group_reset_done)
    }
    return removed
}
