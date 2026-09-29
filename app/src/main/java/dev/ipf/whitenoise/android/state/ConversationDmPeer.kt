package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.AppGroupMemberRecordFfi

/** The counterparty of a direct chat, including one with a custom group title. */
internal val ConversationController.dmPeerAccount: String?
    get() =
        resolvedDirectPeerAccount(
            directChat = isDm,
            pendingInvite = group.pendingConfirmation,
            members = members,
            activeAccountIdHex = boundAccountIdHex,
            fallbackPeerAccount = avatarAccount,
        )

/** Prefer the live DM roster; an unnamed accepted invite can use its avatar peer until the roster arrives. */
internal fun resolvedDirectPeerAccount(
    directChat: Boolean,
    pendingInvite: Boolean,
    members: List<AppGroupMemberRecordFfi>,
    activeAccountIdHex: String?,
    fallbackPeerAccount: String?,
): String? =
    if (directChat && !pendingInvite) {
        conversationIdentityProjection(members, activeAccountIdHex, fallbackPeerAccount).otherMemberAccount
    } else {
        null
    }
