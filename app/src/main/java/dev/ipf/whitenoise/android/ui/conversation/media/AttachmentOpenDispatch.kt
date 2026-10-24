package dev.ipf.whitenoise.android.ui.conversation.media

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

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
