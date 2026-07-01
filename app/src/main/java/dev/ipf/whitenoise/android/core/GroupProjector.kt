package dev.ipf.whitenoise.android.core

import dev.ipf.marmotkit.AppGroupMemberRecordFfi
import dev.ipf.marmotkit.AppGroupRecordFfi

data class GroupTitleCopy(
    val inviteFromFormat: String,
    val groupOfPeopleFormat: String,
    val unknownTitle: String,
) {
    fun inviteFrom(name: String): String = String.format(inviteFromFormat, name)

    fun groupOfPeople(count: Int): String = String.format(groupOfPeopleFormat, count)

    companion object {
        val Default =
            GroupTitleCopy(
                inviteFromFormat = "Invite from %1\$s",
                groupOfPeopleFormat = "Group of %1\$d people",
                unknownTitle = "Unknown",
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
     * for larger unnamed groups, the other member for a pair, else an Unknown
     * fallback — never the group id hex, which is opaque to users.
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
        // An unresolved roster (e.g. a DM whose peer snapshot is still empty)
        // must not leak the opaque group id hex as a title.
        return copy.unknownTitle
    }

    fun inviteAccount(
        group: AppGroupRecordFfi,
        otherMemberAccount: String?,
    ): String? {
        if (!group.pendingConfirmation) return null
        return group.welcomerAccountIdHex?.takeIf { it.isNotBlank() }
            ?: otherMemberAccount?.takeIf { it.isNotBlank() }
    }

    /**
     * The peer account whose profile picture stands in for a 1:1 conversation's
     * avatar: the inviter while a welcome is pending, otherwise the lone
     * counterparty of an unnamed two-member chat. Null for multi-member or named
     * groups, which render their own group avatar instead. Shared by the
     * chat-list row and the conversation top bar so both surfaces resolve a DM's
     * avatar from the same rule (#837).
     */
    fun avatarAccount(
        group: AppGroupRecordFfi,
        otherMemberAccount: String?,
        memberCount: Int,
    ): String? =
        inviteAccount(group, otherMemberAccount)
            ?: otherMemberAccount?.takeIf { group.name.isBlank() && memberCount == 2 }

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

    /**
     * True when [members] is an implicit DM between the active account and
     * [targetIdHex] (npub or hex), as the Start-DM resolver requires (#825).
     *
     * All three must hold, read from *current* MLS state, never a historical
     * snapshot:
     * - the group has no custom [name] (it still presents as an implicit DM;
     *   a renamed two-person conversation is a group, not a DM);
     * - the active account is still a member;
     * - the currently-active roster is exactly `{me, target}`.
     *
     * So a conversation the target was removed from (roster now `{me}`), or one
     * that was renamed, never matches — Start-DM must open a fresh DM instead
     * of landing in the stale group. [equivalentTarget] resolves the peer's
     * account id against the target in whatever form it was pasted (the peer is
     * stored as hex; the target may be an npub), mirroring [otherMemberAccount]'s
     * hex/npub comparison at the call site.
     */
    fun isImplicitDmWith(
        members: List<AppGroupMemberRecordFfi>,
        name: String,
        activeAccountIdHex: String?,
        targetIdHex: String,
        equivalentTarget: (other: String) -> Boolean,
    ): Boolean {
        if (targetIdHex.isBlank()) return false
        if (!isDm(memberCount = members.size, name = name)) return false
        if (!members.any { isActiveAccountMember(it, activeAccountIdHex) }) return false
        val other = otherMemberAccount(members, activeAccountIdHex)?.takeIf { it.isNotBlank() } ?: return false
        return other.equals(targetIdHex, ignoreCase = true) || equivalentTarget(other)
    }

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
     * Number of distinct admins, compared case-insensitively. The admin list
     * can carry the same identity twice with hex-casing drift (the same drift
     * [isAdminRef] already guards against); a raw `admins.size` would then
     * misread a sole admin as two and unlock leave/transfer gates that should
     * stay closed.
     */
    private fun uniqueAdminCount(group: AppGroupRecordFfi): Int =
        group.admins
            .asSequence()
            .filter { it.isNotBlank() }
            .map { it.lowercase() }
            .distinct()
            .count()

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

    /**
     * The roster with the active account dropped. Used on a successful leave
     * to rewrite the cached member snapshot synchronously, so re-opening the
     * just-left group seeds a snapshot that no longer places self in the group
     * (issue #545). A blank [activeAccountIdHex] leaves the roster untouched —
     * [isActiveAccountMember] never matches a blank id, so there is nothing to
     * remove.
     */
    fun membersWithoutActiveAccount(
        members: List<AppGroupMemberRecordFfi>,
        activeAccountIdHex: String?,
    ): List<AppGroupMemberRecordFfi> = members.filterNot { isActiveAccountMember(it, activeAccountIdHex) }

    /**
     * Whether the active account should be treated as a member of [members].
     *
     * [selfLeft] is the controller's authoritative local self-leave latch
     * (issue #787): once a self-leave (or engine eviction) is observed, the
     * answer is false even if a transient roster round-trip still lists self,
     * because that roster predates the engine eviction landing locally. While
     * the latch is clear this is just [isActiveAccountMember] over the roster.
     */
    fun isSelfStillMember(
        members: List<AppGroupMemberRecordFfi>,
        activeAccountIdHex: String?,
        selfLeft: Boolean,
    ): Boolean = !selfLeft && members.any { isActiveAccountMember(it, activeAccountIdHex) }

    /**
     * The roster to commit from an authoritative group-details round-trip,
     * honouring the local self-leave latch (issue #787). When [selfLeft] is
     * set, self is stripped so a details read that still predates the engine
     * eviction cannot re-add self (reviving the member count and composer right
     * after a leave). Otherwise the roster is taken as-is.
     */
    fun rosterHonoringSelfLeft(
        members: List<AppGroupMemberRecordFfi>,
        activeAccountIdHex: String?,
        selfLeft: Boolean,
    ): List<AppGroupMemberRecordFfi> = if (selfLeft) membersWithoutActiveAccount(members, activeAccountIdHex) else members

    fun canLeaveGroup(
        group: AppGroupRecordFfi,
        activeAccountIdHex: String?,
        memberCount: Int,
    ): Boolean {
        if (!isAdminRef(group, activeAccountIdHex)) return true
        // A sole admin who is also the only remaining member can always leave:
        // dissolving the group orphans no one. Without this they'd be stuck.
        if (memberCount == 1) return true
        return uniqueAdminCount(group) > 1
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

    /**
     * Which leave-confirmation flow the group-settings "Leave group" action
     * should present for the active account, derived purely from the group
     * record + headcount so it can be unit-tested without Compose. The three
     * cases (issue #416) are mutually exclusive:
     *
     * - [LeaveAction.SoleMemberDeletesGroup]: the active account is the only
     *   member, so leaving dissolves the group entirely. Takes precedence over
     *   the admin gate — a sole member orphans no one even if they're admin.
     * - [LeaveAction.SoleAdminMustTransfer]: the active account is the only
     *   admin of a group that still has other members. Leaving would strand the
     *   group with no admin, so the flow blocks the leave and asks the user to
     *   transfer admin first. Lines up with [canLeaveGroup] returning false.
     * - [LeaveAction.Standard]: an ordinary leave — either a non-admin, or an
     *   admin in a group that retains at least one other admin.
     */
    fun leaveAction(
        group: AppGroupRecordFfi,
        activeAccountIdHex: String?,
        memberCount: Int,
    ): LeaveAction {
        // Sole member wins over the admin gate: dissolving a one-person group
        // strands no one, so it's always a (destructive) leave, never a
        // transfer-admin block. Mirrors canLeaveGroup's memberCount == 1 branch.
        if (memberCount <= 1) return LeaveAction.SoleMemberDeletesGroup
        if (isAdminRef(group, activeAccountIdHex) && uniqueAdminCount(group) <= 1) {
            return LeaveAction.SoleAdminMustTransfer
        }
        return LeaveAction.Standard
    }

    /**
     * True when revoking [member]'s admin rights would leave a group that
     * still has other members with no admin at all. The engine refuses such a
     * demote (`WouldRemoveLastAdmin` / `AdminDepletion`); the UI mirrors the
     * check so it can offer "Transfer admin first" instead of letting the call
     * fail. A single-member group is exempt: dissolving it orphans no one.
     */
    fun revokeWouldDepleteAdmins(
        group: AppGroupRecordFfi,
        member: AppGroupMemberRecordFfi,
        memberCount: Int,
    ): Boolean {
        if (!isAdmin(group, member)) return false
        if (memberCount <= 1) return false
        // Only this member is left holding admin, so demoting them empties it.
        return uniqueAdminCount(group) <= 1
    }

    /**
     * True when [member] is a valid recipient for a "Transfer admin" (grant +
     * step down) initiated by the active account. The target must be another
     * member who is not already an admin, and the active account must itself
     * be an admin (only admins can change the admin list).
     */
    fun canTransferAdminTo(
        group: AppGroupRecordFfi,
        member: AppGroupMemberRecordFfi,
        activeAccountIdHex: String?,
    ): Boolean {
        if (!isAdminRef(group, activeAccountIdHex)) return false
        if (isActiveAccountMember(member, activeAccountIdHex)) return false
        return !isAdmin(group, member)
    }

    /**
     * True when the active account is the *sole* admin of a group that still
     * has other members. Such an admin is trapped — they cannot revoke their
     * own admin rights or leave without first handing admin to someone else
     * (issue #417 / #46). The UI uses this to surface the "Transfer admin"
     * entry point from the otherwise-blocked revoke and leave paths.
     */
    fun isSoleAdminWithOtherMembers(
        group: AppGroupRecordFfi,
        activeAccountIdHex: String?,
        memberCount: Int,
    ): Boolean {
        if (!isAdminRef(group, activeAccountIdHex)) return false
        if (memberCount <= 1) return false
        return uniqueAdminCount(group) <= 1
    }
}

/**
 * The leave-confirmation variant the group-settings screen should show.
 * See [GroupProjector.leaveAction].
 */
enum class LeaveAction {
    Standard,
    SoleAdminMustTransfer,
    SoleMemberDeletesGroup,
}
