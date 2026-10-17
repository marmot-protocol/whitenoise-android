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

/** True once native progress shows the body off its storage park, false when observation ends while still parked. */
internal suspend fun awaitStorageReadmission(progress: Flow<NativeAttachmentProgress?>): Boolean =
    progress.firstOrNull { it != null && it.phase != AttachmentTransferStateFfi.STORAGE_BLOCKED } != null

/**
 * True when this failure is MDK parking the body for lack of storage. A surface sets its storage wording from this on
 * every failure, so an earlier park never relabels a later, unrelated failure.
 */
internal fun Throwable?.parksForStorage(): Boolean = this?.failedTransferState() == StorageBlocked
