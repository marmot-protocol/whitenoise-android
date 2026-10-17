package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.AppGroupMlsStateFfi
import dev.ipf.marmotkit.GroupDetailsFfi
import dev.ipf.marmotkit.GroupLifecycleStateFfi
import dev.ipf.marmotkit.GroupManagementStateFfi
import dev.ipf.marmotkit.GroupMemberDetailsFfi
import dev.ipf.marmotkit.GroupMutationResultFfi
import dev.ipf.marmotkit.SendAcceptDispositionFfi
import dev.ipf.marmotkit.SendMaintenanceDispositionFfi
import dev.ipf.marmotkit.SendSummaryFfi
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED

/**
 * A group change as MDK returns it for the shared test group: [ids] are members, [admins] are admins, and [self] is
 * the local account [selfRef].
 */
internal fun testGroupMutationResult(
    ids: List<String>,
    admins: List<String>,
    self: String = ConversationTimelineTestIds.ACCOUNT_ID,
    selfRef: String = ConversationTimelineTestIds.ACCOUNT_REF,
): GroupMutationResultFfi {
    val group = conversationTimelineTestGroup().copy(admins = admins)
    val details =
        GroupDetailsFfi(
            group,
            ids.map { id ->
                val local = id == self
                GroupMemberDetailsFfi(id, selfRef.takeIf { local }, local, id in admins, local, "npub-$id", null)
            },
            AppGroupMlsStateFfi(
                group.groupIdHex,
                group.protocolProfile,
                GroupLifecycleStateFfi.STABLE,
                0uL,
                ids.size.toUInt(),
                false,
                emptyList(),
                false,
                false,
                emptyList(),
                null,
            ),
        )
    val management =
        GroupManagementStateFfi(
            myAccountIdHex = self,
            isSelfAdmin = self in admins,
            isLastAdmin = admins == listOf(self),
            canInvite = self in admins,
            canLeave = self !in admins,
            requiresSelfDemoteBeforeLeave = self in admins,
            leaveRequestPending = false,
            leaveRequestedAtMs = null,
            lifecycleState = GroupLifecycleStateFfi.STABLE,
            disbandingEnabled = false,
            disbanding = false,
            canEnableDisbanding = false,
            canDisband = false,
            disbandingBlockers = emptyList(),
            disbandRequest = null,
            memberActions = emptyList(),
        )
    val sent = SendSummaryFfi(1u, emptyList(), SendAcceptDispositionFfi.PUBLISHED, SendMaintenanceDispositionFfi.READY)
    return GroupMutationResultFfi(sent, details, management)
}

/** Delivers a checked UniFFI error through a proxied suspend call's continuation, which a proxy cannot throw. */
internal fun failSuspendedNativeCall(
    arguments: Array<out Any?>?,
    failure: Throwable,
): Any {
    @Suppress("UNCHECKED_CAST")
    val continuation = arguments?.last() as Continuation<Any?>
    continuation.resumeWith(Result.failure(failure))
    return COROUTINE_SUSPENDED
}
