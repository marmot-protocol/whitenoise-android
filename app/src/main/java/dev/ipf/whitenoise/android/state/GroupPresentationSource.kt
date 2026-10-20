package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.AppGroupRecordFfi

/**
 * Restores this account's source avatar identity before MDK's selected display URL/hash rewrite.
 * Header hydration may lag the opening transition; preserving source identity lets its existing strict
 * avatar checks reuse the row's selected pixels. Other tapped fields, including optimistic archive state,
 * remain intact. Routes outside the current projection keep their seed.
 */
internal fun WhiteNoiseAppState.conversationSourceGroup(
    item: ChatListItem,
    accountRef: String?,
): AppGroupRecordFfi {
    val source = currentGroupAvatarItem(accountRef, item.group.groupIdHex)?.group ?: return item.group
    return item.group.copy(
        avatarUrl = source.avatarUrl,
        avatarDim = source.avatarDim,
        avatarThumbhash = source.avatarThumbhash,
        imageHashHex = source.imageHashHex,
    )
}

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
