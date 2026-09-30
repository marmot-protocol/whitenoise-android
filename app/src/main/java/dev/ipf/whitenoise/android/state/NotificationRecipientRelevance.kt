package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.AppGroupMemberRecordFfi

/** Counts locally signed-in identities positively established as relevant to this conversation. */
internal fun relevantSignedInRecipientCount(
    recipientAccountIdHex: String?,
    signedInAccountIds: Set<String>,
    groupMembers: List<AppGroupMemberRecordFfi>,
): Int {
    val recipient = recipientAccountIdHex?.trim()?.lowercase()?.takeIf(String::isNotEmpty) ?: return 0
    val signedIn = signedInAccountIds.mapTo(mutableSetOf()) { it.trim().lowercase() }
    return if (recipient !in signedIn) {
        0
    } else {
        groupMembers
            .mapTo(mutableSetOf()) { it.memberIdHex.trim().lowercase() }
            .filterTo(mutableSetOf()) { it in signedIn }
            .plus(recipient)
            .size
    }
}
