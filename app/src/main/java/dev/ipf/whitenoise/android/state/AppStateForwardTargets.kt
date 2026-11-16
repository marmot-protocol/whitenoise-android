package dev.ipf.whitenoise.android.state

/**
 * Confirmed chats the active account can forward a message into, recent
 * first. Empty when no chats controller is attached yet (the chat-list
 * stream hasn't bound) — the forward picker then shows its empty state.
 */
fun WhiteNoiseAppState.forwardTargets(): List<ChatListItem> = chatsController?.forwardTargets().orEmpty()

internal val WhiteNoiseAppState.forwardTargetsLoading: Boolean
    get() = chatsController?.isLoading == true

internal val WhiteNoiseAppState.forwardTargetsError: ErrorPresentation?
    get() = chatsController?.error

internal val WhiteNoiseAppState.forwardTargetMembersRevision: Long
    get() = chatsController?.memberSnapshotsRevision ?: 0L

internal val WhiteNoiseAppState.forwardTargetsRevision: Long
    get() = chatsController?.forwardTargetsRevision ?: 0L

internal fun WhiteNoiseAppState.requestForwardTargetMembers(groupIds: Iterable<String>) {
    chatsController?.requestMemberSnapshots(groupIds)
}

internal fun WhiteNoiseAppState.retryForwardTargets() {
    chatsController?.retryLoad()
}
