package dev.ipf.darkmatter.core

import org.marmotprotocol.marmotkit.AppGroupMemberRecordFfi
import org.marmotprotocol.marmotkit.AppGroupRecordFfi

object GroupProjector {
    fun displayTitle(
        group: AppGroupRecordFfi,
        otherMemberAccount: String?,
        memberCount: Int,
        displayName: (String) -> String,
    ): String {
        group.name.takeIf { it.isNotBlank() }?.let { return it }
        otherMemberAccount?.takeIf { it.isNotBlank() }?.let { return displayName(it) }
        return if (memberCount > 1) "${memberCount} members" else IdentityFormatter.short(group.groupIdHex)
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
