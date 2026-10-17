package dev.ipf.whitenoise.android.ui.conversation.media

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import dev.ipf.marmotkit.AttachmentTransferStateFfi
import dev.ipf.whitenoise.android.state.AttachmentTransferState.StorageBlocked
import dev.ipf.whitenoise.android.state.ConversationController
import dev.ipf.whitenoise.android.state.NativeAttachmentProgress
import dev.ipf.whitenoise.android.state.attachmentNativeProgress
import dev.ipf.whitenoise.android.state.failedTransferState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.firstOrNull

/**
 * MDK readmits a body it parked for lack of storage once space frees, and resumes it without the reader asking
 * (#3289). While [parked], a visible surface watches that body, and as soon as MDK moves it off the park, whether it
 * resumes, finishes or fails another way, [onMovedOn] lets the surface load again. The load joins the live download,
 * so the surface shows its progress and Cancel instead of "Not enough storage", and a different failure is classified
 * afresh. Observing never demands or retries anything.
 */
@Composable
internal fun storageReadmissionEffect(
    controller: ConversationController,
    messageIdHex: String,
    attachmentIndex: Int,
    parked: Boolean,
    onMovedOn: () -> Unit,
) {
    val movedOn by rememberUpdatedState(onMovedOn)
    LaunchedEffect(controller, messageIdHex, attachmentIndex, parked) {
        if (!parked) return@LaunchedEffect
        if (awaitStorageReadmission(controller.attachmentNativeProgress(messageIdHex, attachmentIndex))) movedOn()
    }
}

/**
 * A storage park outlives the screen, but a surface's download intent does not. While a surface is [parkedByReader],
 * parked on a download the reader started, [recordParkedByReader] remembers that. When a surface enters and
 * [readerParkHeld] finds the reader's download still parked, [onParked] restores it, so the surface says "Not enough
 * storage" and reloads on its own once MDK readmits the body, instead of offering Download again. An automatic
 * download is never recorded, so reopening never promotes it to an explicit request against the reader's settings.
 * Only an [enabled] surface, one that is not downloading already, asks, once per entry.
 */
@Composable
internal fun restoreParkedDownloadOnEntry(
    identity: Any,
    enabled: Boolean,
    parkedByReader: Boolean,
    recordParkedByReader: () -> Unit,
    readerParkHeld: suspend () -> Boolean,
    onParked: () -> Unit,
) {
    val record by rememberUpdatedState(recordParkedByReader)
    LaunchedEffect(identity, parkedByReader) {
        if (parkedByReader) record()
    }
    val stillEnabled by rememberUpdatedState(enabled)
    val restore by rememberUpdatedState(onParked)
    LaunchedEffect(identity) {
        if (stillEnabled && readerParkHeld() && stillEnabled) restore()
    }
}

/** True once native progress shows the body off its storage park, false when observation ends while still parked. */
internal suspend fun awaitStorageReadmission(progress: Flow<NativeAttachmentProgress?>): Boolean =
    progress.firstOrNull { it != null && it.phase != AttachmentTransferStateFfi.STORAGE_BLOCKED } != null

/**
 * True when this failure is MDK parking the body for lack of storage. A surface sets its storage wording from this on
 * every failure, so an earlier park never relabels a later, unrelated failure.
 */
internal fun Throwable?.parksForStorage(): Boolean = this?.failedTransferState() == StorageBlocked
