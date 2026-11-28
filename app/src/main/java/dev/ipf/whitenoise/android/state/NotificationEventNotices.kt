package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.MarmotEventFfi
import dev.ipf.marmotkit.MarmotInterface
import dev.ipf.marmotkit.NotificationTriggerFfi.NEW_MESSAGE
import dev.ipf.marmotkit.NotificationUpdateFfi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Tells the user about an [event] that needs saying wherever they are in the app. Each notice is presented on the
 * main thread as a child of [scope], so none delays the next event; [marmot] clears MDK's record of one once shown.
 * A group-change notice runs in [streamScope] instead, so the end of the stream that reported it withdraws it unseen.
 */
internal fun WhiteNoiseAppState.presentEventNotices(
    event: MarmotEventFfi,
    marmot: MarmotInterface,
    scope: CoroutineScope,
    streamScope: CoroutineScope,
) {
    // MDK retries no group change it could not make, so the user is told, and its record is cleared once seen.
    groupChangeNotice(event)?.let { notice ->
        streamScope.launch(Dispatchers.Main) {
            if (showGroupChangeNotice(notice)) {
                withContext(Dispatchers.IO) { runCatchingCancellable { marmot.acknowledgeGroupChange(notice) } }
            }
        }
    }
    // A chat MDK could not return to its latest state needs a full restart to retry.
    rewindRestoreFailure(event)?.let { failure ->
        scope.launch(Dispatchers.Main) { presentRewindRestoreFailure(failure) }
    }
    // Another of the account's devices removed this one: MDK has signed the account out here.
    deviceUnlinkedAccount(event)?.let { account ->
        // Captured before the handler is queued, so a removal that reaches the main thread first still outdates it.
        val copy = accountSession.copyLifetimes.capture(account)
        scope.launch(Dispatchers.Main) { presentDeviceUnlinked(account, copy) }
    }
    // MDK found a sign-in or changed the security warning: the banner, badge and notifications follow it.
    signInAlertAccount(event)?.let { account -> scope.launch { refreshSignInAlerts(account) } }
    if (event is MarmotEventFfi.DeviceLinkChanged) {
        scope.launch(Dispatchers.Main) { refreshMissedDeviceLinkAttempts(event.accountLabel) }
    }
}

/** A new-message receipt can carry media from a peer or another device signed in to this account. */
internal fun needsIncomingAttachmentDownload(update: NotificationUpdateFfi) = update.trigger == NEW_MESSAGE
