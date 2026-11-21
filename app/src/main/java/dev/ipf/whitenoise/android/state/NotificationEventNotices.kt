package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.MarmotEventFfi
import dev.ipf.marmotkit.NotificationTriggerFfi
import dev.ipf.marmotkit.NotificationUpdateFfi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Tells the user about an [event] that needs saying wherever they are in the app. Each notice is presented on the
 * main thread as a child of [scope], so none delays the next event.
 */
internal fun WhiteNoiseAppState.presentEventNotices(
    event: MarmotEventFfi,
    scope: CoroutineScope,
) {
    // MDK drops an oversized queued group change without retrying it, so the user is told.
    queuedSizeRefusal(event)?.let { refusal ->
        scope.launch(Dispatchers.Main) { presentQueuedSizeRefusal(refusal) }
    }
    // A chat MDK could not return to its latest state needs a full restart to retry.
    rewindRestoreFailure(event)?.let { failure ->
        scope.launch(Dispatchers.Main) { presentRewindRestoreFailure(failure) }
    }
    // Another of the account's devices removed this one: MDK has signed the account out here.
    deviceUnlinkedAccount(event)?.let { account ->
        scope.launch(Dispatchers.Main) { presentDeviceUnlinked(account) }
    }
}

/**
 * Whether a new-message [update] may carry attachments this device still has to fetch. Without linked devices a
 * self update is this installation's own send, whose media is already local. With them it can be the account's
 * message from another device, which arrives here like any received message.
 */
internal fun needsIncomingAttachmentDownload(
    update: NotificationUpdateFfi,
    linkedDevices: Boolean,
): Boolean = update.trigger == NotificationTriggerFfi.NEW_MESSAGE && (!update.isFromSelf || linkedDevices)

/**
 * Whether a received message of [kind] may carry attachments this device still has to fetch, by the same rule as
 * [needsIncomingAttachmentDownload]: a chat message from someone else always may, and with linked devices so may the
 * account's own message, because it can have been sent from another of its devices.
 */
internal fun receivedChatMayCarryAttachments(
    kind: ULong,
    fromSelf: Boolean,
    linkedDevices: Boolean,
): Boolean = kind == MARMOT_CHAT_EVENT_KIND && (!fromSelf || linkedDevices)
