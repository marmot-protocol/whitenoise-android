package dev.ipf.whitenoise.android.ui.conversation.media

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import dev.ipf.marmotkit.AttachmentTransferStateFfi
import dev.ipf.whitenoise.android.state.ConversationController
import dev.ipf.whitenoise.android.state.NativeAttachmentProgress
import dev.ipf.whitenoise.android.state.attachmentNativeProgress
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.firstOrNull

/**
 * MDK readmits a body it parked for lack of storage once space frees, and finishes it without the reader asking
 * (#3289). While [parked], a visible surface waits for that body to become ready, then [onReady] lets it reload, so it
 * does not keep saying "Not enough storage" until someone taps Retry. Observing never demands or retries anything.
 */
@Composable
internal fun storageReadmissionEffect(
    controller: ConversationController,
    messageIdHex: String,
    attachmentIndex: Int,
    parked: Boolean,
    onReady: () -> Unit,
) {
    val ready by rememberUpdatedState(onReady)
    LaunchedEffect(controller, messageIdHex, attachmentIndex, parked) {
        if (!parked) return@LaunchedEffect
        if (awaitStorageReadmission(controller.attachmentNativeProgress(messageIdHex, attachmentIndex))) ready()
    }
}

/** True once native progress reports the parked body ready, false when observation ends first. */
internal suspend fun awaitStorageReadmission(progress: Flow<NativeAttachmentProgress?>): Boolean =
    progress.firstOrNull { it?.phase == AttachmentTransferStateFfi.READY } != null
