package dev.ipf.whitenoise.android.ui.conversation.media

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import dev.ipf.whitenoise.android.state.AttachmentTransferState
import dev.ipf.whitenoise.android.state.ConversationController
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.state.refreshAttachmentTransferState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Keeps one persisted in-place download armed until its media is ready, then retires the intent without opening
 * anything. A download never presents what it fetched; the user opens it with a later tap. The store's atomic consume
 * fences sibling compositions, rotation and process recreation, so the intent is retired exactly once.
 */
@Composable
internal fun persistedAttachmentDownloadEffect(
    messageIdHex: String,
    attachmentIndex: Int,
    sourceEpoch: ULong,
    controller: ConversationController,
    appState: WhiteNoiseAppState,
    isReady: () -> Boolean,
    ensureMaterialization: () -> Unit,
) {
    val currentIsReady = rememberUpdatedState(isReady)
    val currentEnsureMaterialization = rememberUpdatedState(ensureMaterialization)

    LaunchedEffect(
        controller,
        messageIdHex,
        attachmentIndex,
        sourceEpoch,
        appState.attachmentOpens.revision,
    ) {
        if (!controller.hasAttachmentOpenIntent(messageIdHex, attachmentIndex)) {
            return@LaunchedEffect
        }
        val transferState =
            controller.attachmentTransferState(
                messageIdHex = messageIdHex,
                attachmentIndex = attachmentIndex,
                initiallyAvailable = currentIsReady.value(),
            )
        val cacheProbe =
            launch(start = CoroutineStart.UNDISPATCHED) {
                appState.mediaCacheRevision.collect {
                    controller.refreshAttachmentTransferState(messageIdHex, attachmentIndex)
                }
            }
        try {
            currentEnsureMaterialization.value()
            settleAttachmentDownloadIntent(
                awaitReady = {
                    awaitAttachmentReadyAfterDurableCompletion(
                        readiness = snapshotFlow { currentIsReady.value() },
                        transferState = transferState,
                        ensureMaterialization = { currentEnsureMaterialization.value() },
                    )
                },
                consume = { controller.consumeAttachmentOpenIntent(messageIdHex, attachmentIndex) },
            )
        } finally {
            cacheProbe.cancel()
            controller.releaseAttachmentTransferState(messageIdHex, attachmentIndex)
        }
    }
}

/**
 * Waits until a downloaded attachment is ready in place, then retires its download intent. Nothing is opened, and
 * the retirement is not cancellable once the bytes are ready, so a leaving owner cannot strand the intent.
 */
internal suspend fun settleAttachmentDownloadIntent(
    awaitReady: suspend () -> Unit,
    consume: suspend () -> Boolean,
): Boolean {
    awaitReady()
    return withContext(NonCancellable) { consume() }
}

/** Re-enters visual materialization when durable work publishes this attachment as available. */
internal suspend fun awaitAttachmentReadyAfterDurableCompletion(
    readiness: Flow<Boolean>,
    transferState: Flow<AttachmentTransferState>,
    ensureMaterialization: () -> Unit,
) {
    readiness
        .combine(transferState) { ready, state -> ready to state }
        .onEach { (ready, state) ->
            if (!ready && state == AttachmentTransferState.Available) {
                ensureMaterialization()
            }
        }.first { (ready, _) -> ready }
}

/** Claims a typed persisted phase and restores it when final platform dispatch fails. */
internal suspend fun <Claim : Any> claimAndDispatchAttachmentOpen(
    claim: suspend () -> Claim?,
    restore: suspend () -> Unit,
    dispatch: suspend (Claim) -> Unit,
): Boolean =
    withContext(NonCancellable) {
        val claimed = claim() ?: return@withContext false
        val failure = runCatching { dispatch(claimed) }.exceptionOrNull() ?: return@withContext true
        runCatching { restore() }
            .exceptionOrNull()
            ?.let(failure::addSuppressed)
        throw failure
    }

/** Typed variant used when an installer-permission recovery is claimed. */
@Suppress("TooGenericExceptionCaught")
internal suspend fun <Claim : Any> claimAndDispatchAttachmentOpenReportingFailure(
    claim: suspend () -> Claim?,
    restore: suspend () -> Unit,
    dispatch: suspend (Claim) -> Unit,
    onFailure: (Throwable) -> Unit,
): Boolean =
    try {
        claimAndDispatchAttachmentOpen(claim, restore, dispatch)
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (failure: Throwable) {
        onFailure(failure)
        false
    }
