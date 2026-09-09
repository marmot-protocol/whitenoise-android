package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.GroupRecoveryStatusFfi
import dev.ipf.marmotkit.GroupRejoinInvitationFfi

/** Finds an invitation only when every user-reviewed field still matches the current engine status. */
@Suppress("MaxLineLength") // Extension receiver plus generated FFI types exceed the project threshold.
internal fun GroupRecoveryStatusFfi.matchingRejoinInvitation(displayed: GroupRejoinInvitationFfi): GroupRejoinInvitationFfi? =
    rejoinInvitations.firstOrNull {
        it.welcomeIdHex == displayed.welcomeIdHex &&
            it.localStateToken == displayed.localStateToken &&
            it.welcomerAccountIdHex == displayed.welcomerAccountIdHex &&
            it.epoch == displayed.epoch
    }
