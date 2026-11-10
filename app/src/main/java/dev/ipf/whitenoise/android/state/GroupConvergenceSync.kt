package dev.ipf.whitenoise.android.state

import androidx.annotation.StringRes
import dev.ipf.marmotkit.MarmotEventFfi
import dev.ipf.marmotkit.MarmotKitException
import dev.ipf.whitenoise.android.R

/** How long a conversation waits before reopening a runtime event stream that ended or failed to open. */
internal const val GROUP_CONVERGENCE_EVENT_RETRY_MILLIS = 5_000L

/**
 * Whether [this] is MDK's raw group-state event for [groupIdHex] under [accountRef]. MDK fires it when a chat's
 * convergence parks or resumes, even though the group record itself stays the same, so the conversation's own
 * group-state subscription (which drops unchanged records) never sees that edge.
 */
internal fun MarmotEventFfi.isGroupStateUpdateFor(
    accountRef: String,
    groupIdHex: String,
): Boolean =
    this is MarmotEventFfi.GroupStateUpdated &&
        accountLabel == accountRef &&
        this.groupIdHex.equals(groupIdHex, ignoreCase = true)

/**
 * The localized reason a convergence retry did not get a parked chat syncing again, or null when there is nothing
 * to explain. [stillParked] is the parked flag read after the retry, null when that read failed. A chat that is no
 * longer parked needs no explanation, whatever the retry itself reported, and an unreadable flag after a retry MDK
 * accepted is not evidence of failure. Raw engine text never reaches the user.
 */
@StringRes
internal fun groupConvergenceRetryFailureMessage(
    failure: Throwable?,
    stillParked: Boolean?,
): Int? =
    when {
        stillParked == false -> null
        failure == null -> if (stillParked == true) R.string.group_convergence_retry_still_parked else null
        failure is MarmotKitException.GroupHydrationPending -> R.string.toast_chat_still_loading
        isTransientRelaySendError(failure) || isAmbiguousRelayDeliveryError(failure) ->
            R.string.toast_send_connection_failed
        else -> R.string.group_convergence_retry_failed
    }
