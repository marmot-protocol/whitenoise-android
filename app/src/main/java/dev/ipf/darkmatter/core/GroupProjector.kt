package dev.ipf.darkmatter.core

import dev.ipf.marmotkit.AppGroupMemberRecordFfi
import dev.ipf.marmotkit.AppGroupRecordFfi

object GroupProjector {
    fun displayTitle(
        group: AppGroupRecordFfi,
        otherMemberAccount: String?,
        memberCount: Int,
        memberTitle: (String) -> String,
    ): String {
        group.name.trim().takeIf { it.isNotBlank() }?.let { return it }
        if (memberCount > 2) return "Group of $memberCount people"
        if (memberCount == 2) {
            otherMemberAccount?.takeIf { it.isNotBlank() }?.let { return memberTitle(it) }
        }
        return IdentityFormatter.short(group.groupIdHex)
    }

    fun otherMemberAccount(
        members: List<AppGroupMemberRecordFfi>,
        activeAccountIdHex: String?,
    ): String? {
        // memberIdHex is the Nostr pubkey/account id; account is a local label.
        val active = activeAccountIdHex?.takeIf { it.isNotBlank() }
        if (active != null) {
            members.firstOrNull { member ->
                member.memberIdHex.isNotBlank() && !member.memberIdHex.equals(active, ignoreCase = true)
            }?.memberIdHex?.let { return it }
        }
        return members.firstOrNull { !it.local && it.memberIdHex.isNotBlank() }?.memberIdHex
            ?: members.firstOrNull { it.memberIdHex.isNotBlank() }?.memberIdHex
    }

    fun shouldShowTranscriptSenderAvatar(memberCount: Int, mine: Boolean): Boolean {
        return !mine && memberCount > 2
    }

    fun memberRef(member: AppGroupMemberRecordFfi): String {
        return member.account?.takeIf { it.isNotBlank() } ?: member.memberIdHex
    }

    fun isAdmin(group: AppGroupRecordFfi, member: AppGroupMemberRecordFfi): Boolean {
        return group.admins.contains(memberRef(member)) || group.admins.contains(member.memberIdHex)
    }

    fun canLeaveGroup(group: AppGroupRecordFfi, activeAccountIdHex: String?): Boolean {
        if (activeAccountIdHex == null || !group.admins.contains(activeAccountIdHex)) return true
        return group.admins.size > 1
    }

    fun requiresSelfDemoteBeforeLeave(group: AppGroupRecordFfi, activeAccountIdHex: String?): Boolean {
        return activeAccountIdHex != null && group.admins.contains(activeAccountIdHex)
    }
}
