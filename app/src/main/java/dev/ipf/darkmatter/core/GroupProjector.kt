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
        group.name.takeIf { it.isNotBlank() }?.let { return it }
        if (memberCount > 2) return "$memberCount person group"
        if (memberCount == 2) {
            otherMemberAccount?.takeIf { it.isNotBlank() }?.let { return memberTitle(it) }
        }
        return IdentityFormatter.short(group.groupIdHex)
    }

    fun otherMemberAccount(
        members: List<AppGroupMemberRecordFfi>,
        activeAccountIdHex: String?,
    ): String? {
        if (members.any { it.local }) {
            members.firstOrNull { !it.local && !it.account.isNullOrBlank() }?.account?.let { return it }
        }
        return members.firstOrNull { member ->
            val account = member.account
            account != null && account.isNotBlank() && account != activeAccountIdHex
        }?.account
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
}
