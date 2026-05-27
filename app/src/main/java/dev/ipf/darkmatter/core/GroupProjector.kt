package dev.ipf.darkmatter.core

import dev.ipf.marmotkit.AppGroupMemberRecordFfi
import dev.ipf.marmotkit.AppGroupRecordFfi

data class GroupTitleCopy(
    val inviteFromFormat: String,
    val groupOfPeopleFormat: String,
) {
    fun inviteFrom(name: String): String = String.format(inviteFromFormat, name)
    fun groupOfPeople(count: Int): String = String.format(groupOfPeopleFormat, count)

    companion object {
        val Default = GroupTitleCopy(
            inviteFromFormat = "Invite from %1\$s",
            groupOfPeopleFormat = "Group of %1\$d people",
        )
    }
}

object GroupProjector {
    fun displayTitle(
        group: AppGroupRecordFfi,
        otherMemberAccount: String?,
        memberCount: Int,
        memberTitle: (String) -> String,
        copy: GroupTitleCopy = GroupTitleCopy.Default,
    ): String {
        group.name.trim().takeIf { it.isNotBlank() }?.let { return it }
        if (group.pendingConfirmation) {
            inviteAccount(group, otherMemberAccount)?.let { return copy.inviteFrom(memberTitle(it)) }
        }
        if (memberCount > 2) return copy.groupOfPeople(memberCount)
        if (memberCount == 2) {
            otherMemberAccount?.takeIf { it.isNotBlank() }?.let { return memberTitle(it) }
        }
        return IdentityFormatter.short(group.groupIdHex)
    }

    fun inviteAccount(group: AppGroupRecordFfi, otherMemberAccount: String?): String? {
        if (!group.pendingConfirmation) return null
        return group.welcomerAccountIdHex?.takeIf { it.isNotBlank() }
            ?: otherMemberAccount?.takeIf { it.isNotBlank() }
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
