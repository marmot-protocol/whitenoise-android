package dev.ipf.whitenoise.android.state

import android.util.Log
import androidx.annotation.StringRes
import dev.ipf.marmotkit.MarmotKitException
import dev.ipf.whitenoise.android.BuildConfig
import dev.ipf.whitenoise.android.R

/**
 * The one send attempt a failure notice is about (#2666).
 *
 * A failed send keeps its optimistic key across a retry, so the retry that finally succeeds is
 * recognisably the same attempt as the failure still on screen. The account and group are part of
 * the identity so a notice is never retired by an unrelated conversation or another profile.
 */
data class SendFailureAttempt(
    val accountRef: String?,
    val groupIdHex: String,
    val optimisticKey: String,
)

/**
 * Reports a send failure to the user without leaking engine internals. The
 * engine's message can name internal state machines and transitions (for
 * example an `illegal queue_app_message transition from PendingPublish`),
 * which is meaningless to a user and is not ours to put on screen; the raw
 * text stays in the log until the privacy-safe report path exists.
 *
 * [attempt] ties an actionable failure notice to the send that produced it, so
 * [dismissSendFailureNotice] can retire it once that same send recovers.
 */
internal fun presentSendFailure(
    appState: WhiteNoiseAppState,
    throwable: Throwable,
    attempt: SendFailureAttempt? = null,
) {
    if (BuildConfig.DEBUG) {
        Log.w("DMSend", "send failed", throwable)
    } else {
        Log.w("DMSend", "send_failed")
    }
    val message = sendFailureMessageRes(throwable)
    when (throwable) {
        is MarmotKitException.GroupSendQueueFull,
        is MarmotKitException.GroupHydrationPending,
        -> appState.present(message)
        else -> appState.presentFailure(message, "MESSAGE_SEND", throwable, sendAttempt = attempt)
    }
}

/**
 * Retires the failure notice [attempt] raised, once that same send has recovered.
 *
 * A send-failure snackbar is an actionable error, so it stays on screen until something dismisses
 * it; before this it survived the successful retry of the very send it was reporting and cleared
 * only on a restart. Only the notice this exact attempt raised is dismissed: a newer error, an
 * error from another conversation or account, and a fresh failure from a failed retry all remain,
 * as does the manual dismiss and diagnostic-copy affordance while a failure is still current.
 */
internal fun WhiteNoiseAppState.dismissSendFailureNotice(attempt: SendFailureAttempt) {
    if (toast?.sendAttempt == attempt) clearToast()
}

/**
 * The engine refuses a send outright when a group's outbound queue is full: the
 * message was never accepted, and the backlog clears on the group's own schedule
 * rather than on any timer this app could pick. That earns its own wording, since
 * the generic failure invites a retry the engine has already ruled out.
 *
 * A hydration-pending group is the opposite case — transient by design, the
 * runtime promotes it shortly after account readiness — so that one gets
 * wording that invites the retry instead of announcing a failure.
 */
@StringRes
internal fun sendFailureMessageRes(throwable: Throwable): Int =
    when (throwable) {
        is MarmotKitException.GroupSendQueueFull -> R.string.toast_send_queue_full
        is MarmotKitException.GroupHydrationPending -> R.string.toast_chat_still_loading
        else ->
            if (isTransientRelaySendError(throwable)) {
                R.string.toast_send_connection_failed
            } else {
                R.string.toast_send_failed
            }
    }
