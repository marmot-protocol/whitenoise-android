package dev.ipf.whitenoise.android.state

/** Reads the live source projection without ever crossing an account owner. */
internal fun ChatsController?.currentGroupAvatarItem(accountRef: String?, groupIdHex: String): ChatListItem? = this
    ?.takeIf { accountRef != null && it.boundAccountRef == accountRef }
    ?.currentGroupAvatarItem(groupIdHex)

/** Keeps retry admission at the existing controller boundary. */
internal fun ChatsController.requestProfileGroupMembers(groupIds: Iterable<String>, retry: Boolean) {
    if (retry) {
        retryMemberSnapshots(groupIds)
    } else {
        requestMemberSnapshots(groupIds)
    }
}
