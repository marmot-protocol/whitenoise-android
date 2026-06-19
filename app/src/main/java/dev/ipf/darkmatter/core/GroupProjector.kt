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
        val Default =
            GroupTitleCopy(
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
    ): String =
        displayTitle(
            name = group.name,
            pendingInviteAccount = if (group.pendingConfirmation) inviteAccount(group, otherMemberAccount) else null,
            groupIdHex = group.groupIdHex,
            otherMemberAccount = otherMemberAccount,
            memberCount = memberCount,
            memberTitle = memberTitle,
            copy = copy,
        )

    /**
     * Title resolution from primitive parts, so callers without a full
     * [AppGroupRecordFfi] (e.g. the notification pipeline, which only has the
     * group id + members) resolve exactly the same title the chat list shows:
     * the group name when set, an invite line when pending, "Group of N people"
     * for larger unnamed groups, the other member for a pair, else a short id.
     */
    fun displayTitle(
        name: String,
        pendingInviteAccount: String?,
        groupIdHex: String,
        otherMemberAccount: String?,
        memberCount: Int,
        memberTitle: (String) -> String,
        copy: GroupTitleCopy = GroupTitleCopy.Default,
    ): String {
        name
            .trim()
            .takeIf { it.isNotBlank() }
            ?.let { return it }
        pendingInviteAccount?.takeIf { it.isNotBlank() }?.let { return copy.inviteFrom(memberTitle(it)) }
        if (memberCount > 2) return copy.groupOfPeople(memberCount)
        if (memberCount == 2) {
            otherMemberAccount?.takeIf { it.isNotBlank() }?.let { return memberTitle(it) }
        }
        return IdentityFormatter.short(groupIdHex)
    }

    fun inviteAccount(
        group: AppGroupRecordFfi,
        otherMemberAccount: String?,
    ): String? {
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
            members
                .firstOrNull { member ->
                    member.memberIdHex.isNotBlank() && !member.memberIdHex.equals(active, ignoreCase = true)
                }?.memberIdHex
                ?.let { return it }
        }
        return members.firstOrNull { !it.local && it.memberIdHex.isNotBlank() }?.memberIdHex
            ?: members.firstOrNull { it.memberIdHex.isNotBlank() }?.memberIdHex
    }

    fun shouldShowTranscriptSenderAvatar(
        memberCount: Int,
        mine: Boolean,
    ): Boolean = !mine && memberCount > 2

    /**
     * A conversation is a DM when it has exactly two members **and** no group
     * name. A named two-member group is still a group, and anything larger is
     * always a group regardless of name. (The core's canonical `isDm` isn't
     * exposed on the group/chat-list records, so classify from the fields we
     * have — the same name/headcount signals [displayTitle] already uses.)
     */
    fun isDm(
        memberCount: Int,
        name: String,
    ): Boolean = memberCount == 2 && name.isBlank()

    fun memberRef(member: AppGroupMemberRecordFfi): String = member.account?.takeIf { it.isNotBlank() } ?: member.memberIdHex

    fun isAdmin(
        group: AppGroupRecordFfi,
        member: AppGroupMemberRecordFfi,
    ): Boolean {
        val ref = memberRef(member)
        // Case-insensitive: hex casing can drift between admins and member ids.
        return isAdminRef(group, ref) || isAdminRef(group, member.memberIdHex)
    }

    fun isAdminRef(
        group: AppGroupRecordFfi,
        accountIdHex: String?,
    ): Boolean {
        val id = accountIdHex?.takeIf { it.isNotBlank() } ?: return false
        return group.admins.any { it.equals(id, ignoreCase = true) }
    }

    /**
     * True iff [member] is the currently active account on this device.
     *
     * Distinct from [AppGroupMemberRecordFfi.local], which Marmot sets to true
     * when ANY account on this device matches the member identity. With
     * multi-account installs that broader flag mis-identifies other local
     * accounts as "self" and hides admin actions on rows that should be
     * manageable.
     */
    fun isActiveAccountMember(
        member: AppGroupMemberRecordFfi,
        activeAccountIdHex: String?,
    ): Boolean {
        val active = activeAccountIdHex?.takeIf { it.isNotBlank() } ?: return false
        return member.memberIdHex.equals(active, ignoreCase = true)
    }

    fun canLeaveGroup(
        group: AppGroupRecordFfi,
        activeAccountIdHex: String?,
        memberCount: Int,
    ): Boolean {
        if (!isAdminRef(group, activeAccountIdHex)) return true
        // A sole admin who is also the only remaining member can always leave:
        // dissolving the group orphans no one. Without this they'd be stuck.
        if (memberCount == 1) return true
        return group.admins.size > 1
    }

    fun requiresSelfDemoteBeforeLeave(
        group: AppGroupRecordFfi,
        activeAccountIdHex: String?,
        memberCount: Int,
    ): Boolean {
        // No one to hand admin to when you're the only member — just leave.
        if (memberCount == 1) return false
        return isAdminRef(group, activeAccountIdHex)
    }
}
