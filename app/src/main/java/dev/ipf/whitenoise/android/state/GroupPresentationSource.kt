package dev.ipf.whitenoise.android.state

/** Reads the live source projection without ever crossing an account owner. */
internal fun WhiteNoiseAppState.currentGroupAvatarItem(
    accountRef: String?,
    groupIdHex: String,
): ChatListItem? {
    forwardTargetsRevision // Compose observes updates folded while the list is hidden.
    return groupPresentationChatsController
        ?.takeIf { accountRef != null && it.boundAccountRef == accountRef }
        ?.currentGroupAvatarItem(groupIdHex)
}

/** Keeps retry admission at the existing controller boundary. */
internal fun WhiteNoiseAppState.requestProfileGroupMembers(
    groupIds: Iterable<String>,
    retry: Boolean = false,
) {
    if (retry) {
        groupPresentationChatsController?.retryMemberSnapshots(groupIds)
    } else {
        groupPresentationChatsController?.requestMemberSnapshots(groupIds)
    }
}
