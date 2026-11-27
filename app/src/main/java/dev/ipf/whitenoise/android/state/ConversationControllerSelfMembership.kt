package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.AppGroupRecordFfi
import dev.ipf.marmotkit.ChatListRowFfi
import dev.ipf.marmotkit.GroupLifecycleStateFfi
import dev.ipf.marmotkit.SelfMembershipFfi
import dev.ipf.whitenoise.android.ui.conversation.media.ReceivedVoiceTranscripts

/**
 * Whether [update] ends this device's membership. A [SelfMembershipFfi.DEVICE_REMOVED] copy counts: this device takes
 * no more sends, while its account stays on the roster through another device. A durable
 * pending leave is provisional even when the record already says LEFT.
 */
internal fun groupStateUpdateRemovesSelf(
    previous: AppGroupRecordFfi,
    update: AppGroupRecordFfi,
): Boolean = !previous.hasSettledDeparture() && update.hasSettledDeparture()

/** Pending LEFT can still resolve to a joined account after cancellation. */
internal fun AppGroupRecordFfi.hasSettledDeparture(): Boolean = selfMembership.hasSettledDeparture(leaveRequestPending)

internal fun AppGroupRecordFfi.endsVoiceTranscription(): Boolean = disbanded || hasSettledDeparture()

internal fun ChatListRowFfi.endsVoiceTranscription(): Boolean =
    lifecycleState == GroupLifecycleStateFfi.DISBANDED || selfMembership.hasSettledDeparture(leaveRequestPending)

/** Evicts cached transcripts and revokes active work when a mounted conversation loses membership. */
internal fun ReceivedVoiceTranscripts.forgetDepartedGroup(
    accountRef: String?,
    row: ChatListRowFfi,
    controllers: List<ConversationController>,
) {
    if (accountRef == null || !row.endsVoiceTranscription()) return
    val mountedTransition =
        controllers.any {
            it.matchesConversation(accountRef, row.groupIdHex) && it.latestChatListRow?.endsVoiceTranscription() != true
        }
    forgetGroup(accountRef, row.groupIdHex, onlyIfCached = !mountedTransition)
}

private fun SelfMembershipFfi.hasSettledDeparture(leaveRequestPending: Boolean): Boolean =
    isNonMember() && !(this == SelfMembershipFfi.LEFT && leaveRequestPending)

/** Distinguishes an actual eviction from a provisional voluntary departure. */
internal fun SelfMembershipFfi.isGroupEviction(): Boolean =
    when (this) {
        SelfMembershipFfi.REMOVED, SelfMembershipFfi.DEVICE_REMOVED -> true
        SelfMembershipFfi.MEMBER, SelfMembershipFfi.LEFT -> false
    }

/** A readback can rejoin only an active, confirmed group with no outstanding leave request. */
internal fun AppGroupRecordFfi.isJoinedAfterLeave(): Boolean =
    selfMembership == SelfMembershipFfi.MEMBER &&
        !leaveRequestPending &&
        !leaveBlocked &&
        !pendingConfirmation &&
        !disbanded &&
        !disbanding
