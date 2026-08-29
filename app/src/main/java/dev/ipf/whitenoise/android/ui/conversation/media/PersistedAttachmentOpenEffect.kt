package dev.ipf.whitenoise.android.ui.conversation.media

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
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
 * Replays one persisted attachment-open intent after its media is ready and
 * the owning UI is resumed. The store's atomic consume fences sibling
 * compositions, rotation, and process recreation from dispatching twice.
 */
@Composable
internal fun persistedAttachmentOpenEffect(
    messageIdHex: String,
    attachmentIndex: Int,
    sourceEpoch: ULong,
    controller: ConversationController,
    appState: WhiteNoiseAppState,
    isReady: () -> Boolean,
    ensureMaterialization: () -> Unit,
    dispatchOpen: suspend () -> Unit,
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentIsReady = rememberUpdatedState(isReady)
    val currentEnsureMaterialization = rememberUpdatedState(ensureMaterialization)
    val currentDispatchOpen = rememberUpdatedState(dispatchOpen)

    LaunchedEffect(
        controller,
        messageIdHex,
        attachmentIndex,
        sourceEpoch,
        appState.attachmentOpens.revision,
        lifecycleOwner,
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
            dispatchAttachmentOpenWhenReady(
                lifecycle = lifecycleOwner.lifecycle,
                awaitReady = {
                    awaitAttachmentReadyAfterDurableCompletion(
                        readiness = snapshotFlow { currentIsReady.value() },
                        transferState = transferState,
                        ensureMaterialization = { currentEnsureMaterialization.value() },
                    )
                },
                isReady = { currentIsReady.value() },
                consume = {
                    controller.consumeAttachmentOpenIntent(messageIdHex, attachmentIndex)
                },
                restore = {
                    controller.restoreAttachmentOpenIntent(messageIdHex, attachmentIndex)
                },
                dispatch = { currentDispatchOpen.value() },
            )
        } finally {
            cacheProbe.cancel()
            controller.releaseAttachmentTransferState(messageIdHex, attachmentIndex)
        }
    }
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

/** Returns true only when this caller atomically consumed and dispatched the intent. */
internal suspend fun dispatchAttachmentOpenWhenReady(
    lifecycle: Lifecycle,
    awaitReady: suspend () -> Unit,
    isReady: () -> Boolean,
    consume: suspend () -> Boolean,
    restore: suspend () -> Unit = {},
    dispatch: suspend () -> Unit,
): Boolean {
    awaitReady()
    val readyAndResumed = isReady() && lifecycle.awaitResumedOrDestroyed()
    if (!readyAndResumed || !isReady()) return false
    return consumeAndDispatchAttachmentOpen(consume, restore, dispatch)
}

/** Cancellation or dispatch failure cannot strand an intent after its atomic consume fence. */
internal suspend fun consumeAndDispatchAttachmentOpen(
    consume: suspend () -> Boolean,
    restore: suspend () -> Unit,
    dispatch: suspend () -> Unit,
): Boolean =
    claimAndDispatchAttachmentOpen(
        claim = { Unit.takeIf { consume() } },
        restore = restore,
        dispatch = { dispatch() },
    )

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

/** Reports a restored dispatch failure without turning it into an uncaught Compose effect failure. */
@Suppress("TooGenericExceptionCaught") // The injected platform launcher can surface any non-cancellation failure.
internal suspend fun consumeAndDispatchAttachmentOpenReportingFailure(
    consume: suspend () -> Boolean,
    restore: suspend () -> Unit,
    dispatch: suspend () -> Unit,
    onFailure: (Throwable) -> Unit,
): Boolean =
    try {
        consumeAndDispatchAttachmentOpen(consume, restore, dispatch)
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (failure: Throwable) {
        onFailure(failure)
        false
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
