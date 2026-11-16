package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.AppGroupHydrationQuarantineReasonFfi
import dev.ipf.marmotkit.AppQuarantinedGroupFfi
import dev.ipf.marmotkit.GroupEventKindFfi
import dev.ipf.marmotkit.MarmotEventFfi
import dev.ipf.marmotkit.MarmotInterface
import dev.ipf.whitenoise.android.R

/** A chat MDK quarantined because returning it to its latest state failed, which only a full restart can retry. */
internal data class RewindRestoreFailure(
    val accountRef: String,
    val groupIdHex: String,
)

/** The rewind-restore failure [event] reports, or null for any other event or quarantine reason. */
internal fun rewindRestoreFailure(event: MarmotEventFfi): RewindRestoreFailure? {
    val groupEvent = event as? MarmotEventFfi.GroupEvent
    val quarantined = groupEvent?.event as? GroupEventKindFfi.GroupHydrationQuarantined
    val restoreFailed = quarantined?.reason == AppGroupHydrationQuarantineReasonFfi.REWIND_RESTORE_FAILED
    return if (groupEvent != null && restoreFailed) {
        RewindRestoreFailure(groupEvent.accountLabel, groupEvent.groupIdHex)
    } else {
        null
    }
}

/** The rewind-restore failures in [quarantined], MDK's quarantine list for [accountRef]. */
internal fun rewindRestoreFailures(
    accountRef: String,
    quarantined: List<AppQuarantinedGroupFfi>,
): List<RewindRestoreFailure> =
    quarantined
        .filter { it.reason == AppGroupHydrationQuarantineReasonFfi.REWIND_RESTORE_FAILED }
        .map { RewindRestoreFailure(accountRef, it.groupIdHex) }

/**
 * Reads every signed-in account's quarantined chats when the event stream opens, so a failure from account open or
 * from before a reconnect still reaches the user. A failed read for one account skips only that account.
 */
internal suspend fun WhiteNoiseAppState.rewindRestoreFailuresOnOpen(marmot: MarmotInterface) =
    accounts.filter { it.isSignedInSigningAccount() }.flatMap { account ->
        runCatchingCancellable { marmot.quarantinedGroups(account.label) }
            .getOrNull()
            ?.let { rewindRestoreFailures(account.label, it) }
            .orEmpty()
    }

/**
 * Tells the user that a chat could not return to its latest state and that restarting White Noise retries it, once
 * per account and chat in this process. The detail says how to restart on Android: a swipe away can leave the
 * background connection running, so it asks for a force stop or a phone restart. Any signed-in account's failure
 * shows, because one restart retries every account's chats. The record lives only in memory, so a failure that
 * returns after the restart shows again. Must run on the main thread.
 */
internal fun WhiteNoiseAppState.presentRewindRestoreFailure(failure: RewindRestoreFailure) {
    if (accounts.none { it.label == failure.accountRef && it.isSignedInSigningAccount() }) return
    // Keyed by account and chat; the bounded record is shared in shape with the queued-refusal notices.
    if (!shownRewindRestoreFailures.firstShowing(failure.accountRef, failure.groupIdHex)) return
    presentText(
        AppText.Resource(R.string.notice_rewind_restore_title),
        AppText.Resource(R.string.quarantine_rewind_restore),
    )
}
