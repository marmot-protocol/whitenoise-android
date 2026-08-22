package dev.ipf.whitenoise.android.ui.conversation.media

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.ipf.whitenoise.android.state.ConversationController
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
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
        appState.attachmentOpenIntentRevision,
        lifecycleOwner,
    ) {
        if (!controller.hasAttachmentOpenIntent(messageIdHex, attachmentIndex)) {
            return@LaunchedEffect
        }
        currentEnsureMaterialization.value()
        dispatchAttachmentOpenWhenReady(
            lifecycle = lifecycleOwner.lifecycle,
            awaitReady = {
                snapshotFlow { currentIsReady.value() }.first { it }
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
    }
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
    withContext(NonCancellable) {
        val consumed = consume()
        if (!consumed) return@withContext false
        val failure = runCatching { dispatch() }.exceptionOrNull() ?: return@withContext true
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
