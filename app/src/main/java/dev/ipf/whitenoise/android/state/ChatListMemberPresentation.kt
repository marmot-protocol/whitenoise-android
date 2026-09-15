package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.AppGroupMemberRecordFfi
import dev.ipf.marmotkit.AppGroupRecordFfi
import dev.ipf.marmotkit.ChatListRowFfi
import dev.ipf.marmotkit.ConversationPresentationFfi
import dev.ipf.marmotkit.PresentationSourceFfi
import dev.ipf.marmotkit.SelectedAvatarFfi
import dev.ipf.whitenoise.android.core.GroupProjector

/** Display-only roster summary; never substitutes for the authoritative member snapshot. */
internal data class ChatListMemberPresentation(
    val otherMemberAccount: String?,
    val memberCount: Int,
    val activeAccountIsSoleMember: Boolean,
)

/** Projects the current roster into account-aware chat-list presentation fields. */
internal fun chatListMemberPresentation(
    members: List<AppGroupMemberRecordFfi>,
    activeAccountIdHex: String?,
): ChatListMemberPresentation =
    ChatListMemberPresentation(
        otherMemberAccount = GroupProjector.otherMemberAccount(members, activeAccountIdHex),
        memberCount = GroupProjector.uniqueMemberCount(members),
        activeAccountIsSoleMember = GroupProjector.isSelfSoleMember(members, activeAccountIdHex),
    )

/**
 * Group record as the chat list should display it. A row carrying any avatar
 * signal is authoritative for the whole avatar identity — a URL↔encrypted
 * switch must clear the stale half. A row with no avatar payload at all is a
 * transient projection state: keep the record's last-known identity so a
 * resolved avatar never degrades to generated initials; a genuine
 * removal still propagates through the group record itself.
 */
internal fun chatListDisplayGroup(
    row: ChatListRowFfi,
    baseGroup: AppGroupRecordFfi,
    selectedPresentation: ConversationPresentationFfi? = null,
    presentationMemberCount: Int = 0,
): AppGroupRecordFfi {
    val rowHasAvatarSignal = row.avatarUrl != null || row.avatar != null
    val avatarUrl = if (rowHasAvatarSignal) row.avatarUrl else baseGroup.avatarUrl
    val projected =
        reconcileTerminalSelfMembership(
            update =
                baseGroup.copy(
                    name = row.groupName.ifBlank { baseGroup.name },
                    avatarUrl = avatarUrl,
                    avatarDim = baseGroup.avatarDim.takeIf { avatarUrl == baseGroup.avatarUrl },
                    avatarThumbhash = baseGroup.avatarThumbhash.takeIf { avatarUrl == baseGroup.avatarUrl },
                    imageHashHex = if (rowHasAvatarSignal) row.avatar?.imageHashHex else baseGroup.imageHashHex,
                    archived = row.archived,
                    pendingConfirmation = row.pendingConfirmation,
                    selfMembership = row.selfMembership,
                ),
            previous = baseGroup,
        )
    return when (val avatar = adoptableSelectedAvatar(selectedPresentation, projected, presentationMemberCount)) {
        is SelectedAvatarFfi.RemoteImage -> projected.copy(avatarUrl = avatar.url, imageHashHex = null)
        is SelectedAvatarFfi.EncryptedGroupImage ->
            projected.copy(
                avatarUrl = null,
                avatarDim = null,
                avatarThumbhash = null,
                imageHashHex = avatar.image.imageHashHex,
            )
        is SelectedAvatarFfi.Placeholder ->
            projected.copy(
                avatarUrl = null,
                avatarDim = null,
                avatarThumbhash = null,
                imageHashHex = null,
            )
        null -> projected
    }
}

/** Applies a local mutation only to its pinned account; null preserves legacy attached-controller routing. */
internal fun ChatsController?.applyLocalGroupUpdateForAccount(
    record: AppGroupRecordFfi,
    accountRef: String?,
) {
    this
        ?.takeIf { accountRef == null || it.boundAccountRef == accountRef }
        ?.applyLocalGroupUpdate(record)
}

/**
 * The selected avatar this row may actually wear. MDK resolves a member's
 * profile picture for two-person conversations; adopting it unconditionally
 * puts a contact's face on a named group in the chat list while the
 * conversation header, which asks the projector, still draws the group
 * monogram. Peer-sourced avatars are therefore adopted only where the
 * projector would lend that member's picture anyway.
 */
private fun adoptableSelectedAvatar(
    selectedPresentation: ConversationPresentationFfi?,
    projected: AppGroupRecordFfi,
    presentationMemberCount: Int,
): SelectedAvatarFfi? {
    val presentation = selectedPresentation ?: return null
    val adoptable =
        !presentation.avatarSource.isPeerSourced() ||
            GroupProjector.lendsPeerAvatar(projected, presentationMemberCount)
    return presentation.avatar.takeIf { adoptable }
}

/** True for the two presentation sources that resolve to a member rather than the group itself. */
private fun PresentationSourceFfi.isPeerSourced(): Boolean =
    this == PresentationSourceFfi.PEER_PROFILE ||
        this == PresentationSourceFfi.PEER_FALLBACK
