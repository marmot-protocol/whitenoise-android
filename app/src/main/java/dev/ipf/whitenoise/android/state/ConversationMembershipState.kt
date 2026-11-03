package dev.ipf.whitenoise.android.state

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.ipf.marmotkit.AppGroupMemberRecordFfi
import dev.ipf.marmotkit.AppGroupRecordFfi
import dev.ipf.marmotkit.SelfMembershipFfi
import dev.ipf.whitenoise.android.core.GroupProjector

/**
 * Whether the engine's authoritative self-membership says this device no longer
 * holds the group: [SelfMembershipFfi.REMOVED] (evicted), [SelfMembershipFfi.LEFT]
 * (voluntary departure) or [SelfMembershipFfi.DEVICE_REMOVED] (this device's leaf
 * was removed while the account stays in through another of its devices). All
 * three are terminal for this device's copy, which takes no sends; only
 * [SelfMembershipFfi.MEMBER] preserves membership. `DEVICE_REMOVED` is not an
 * eviction, so eviction notices still read [SelfMembershipFfi.REMOVED] alone.
 */
internal fun SelfMembershipFfi.isNonMember(): Boolean =
    when (this) {
        SelfMembershipFfi.REMOVED, SelfMembershipFfi.LEFT, SelfMembershipFfi.DEVICE_REMOVED -> true
        SelfMembershipFfi.MEMBER -> false
    }

/** Whether only this device's copy left the group while the account stays in through another of its devices. */
internal fun AppGroupRecordFfi.isDeviceRemovedCopy(): Boolean = selfMembership == SelfMembershipFfi.DEVICE_REMOVED

/**
 * Whether local abandonment (#3310) is offered for this group. A read-only invite declines instead, and once MDK
 * confirms the account no longer holds the group (it left, was removed, or the group is disbanded) there is nothing
 * to abandon. A queued leave or a converging disband is not that confirmation, so abandonment stays the way out while
 * either never finishes. Group Details and the chat list share this one rule.
 */
internal fun AppGroupRecordFfi.offersLocalAbandonment(): Boolean {
    val confirmedNonMember = selfMembership.isNonMember() || disbanded
    return !pendingConfirmation && !confirmedNonMember
}

internal data class ConversationMembershipSeed(
    val members: List<AppGroupMemberRecordFfi>,
    val membersLoaded: Boolean,
    val seededSelfMember: Boolean,
    val seededMembershipKnown: Boolean,
    val membersVerified: Boolean,
    val accountStaysOnRoster: Boolean,
)

internal fun conversationMembershipSeed(
    initialGroup: AppGroupRecordFfi,
    initialMemberSnapshot: GroupMemberSnapshot?,
    activeAccountIdHex: String?,
): ConversationMembershipSeed {
    val initialMembers = initialMemberSnapshot?.members.orEmpty()
    val projectedNonMember = initialGroup.selfMembership.isNonMember()
    val projectedMember = initialGroup.selfMembership == SelfMembershipFfi.MEMBER
    // A removed device ends this device's sends, but the account stays on the roster through its other device.
    val accountStaysOnRoster = initialGroup.isDeviceRemovedCopy()
    val seededMembers =
        if (projectedNonMember && !accountStaysOnRoster) {
            GroupProjector.membersWithoutActiveAccount(initialMembers, activeAccountIdHex)
        } else {
            initialMembers
        }
    val seededSelfMember =
        projectedMember ||
            (
                !projectedNonMember &&
                    initialMembers.any { GroupProjector.isActiveAccountMember(it, activeAccountIdHex) }
            )
    return ConversationMembershipSeed(
        members = seededMembers,
        membersLoaded = initialMemberSnapshot?.members?.isNotEmpty() == true,
        seededSelfMember = seededSelfMember,
        seededMembershipKnown = projectedMember || projectedNonMember || initialMemberSnapshot != null,
        membersVerified = projectedNonMember,
        accountStaysOnRoster = accountStaysOnRoster,
    )
}

internal class ConversationSelfLeftState(
    seededMembershipKnown: Boolean,
    seededSelfMember: Boolean,
    seededAccountStaysOnRoster: Boolean = false,
) {
    var selfLeft by mutableStateOf(seededMembershipKnown && !seededSelfMember)
        private set

    /**
     * Whether this device left while its account stays in the group through another device
     * ([SelfMembershipFfi.DEVICE_REMOVED]). This device takes no sends, but the roster keeps the account.
     */
    private var accountStaysOnRoster by mutableStateOf(seededAccountStaysOnRoster)

    /** Whether the roster drops this account: it left, and no other device of it keeps it in the group. */
    private val hidesAccount: Boolean
        get() = selfLeft && !accountStaysOnRoster

    /** Latches this device's departure; [accountStaysOnRoster] keeps the account listed for a removed device. */
    fun recordSelfLeft(accountStaysOnRoster: Boolean = false) {
        selfLeft = true
        this.accountStaysOnRoster = accountStaysOnRoster
    }

    /** Clears the departure latch, as a fresh reinvite does. */
    fun clearSelfLeft() {
        selfLeft = false
        accountStaysOnRoster = false
    }

    fun isSelfMember(
        members: List<AppGroupMemberRecordFfi>,
        activeAccountIdHex: String?,
    ): Boolean = GroupProjector.isSelfStillMember(members, activeAccountIdHex, selfLeft)

    fun rosterHonoringSelfLeft(
        members: List<AppGroupMemberRecordFfi>,
        activeAccountIdHex: String?,
    ): List<AppGroupMemberRecordFfi> = GroupProjector.rosterHonoringSelfLeft(members, activeAccountIdHex, hidesAccount)
}
