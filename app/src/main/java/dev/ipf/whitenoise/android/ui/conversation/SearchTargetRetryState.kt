package dev.ipf.whitenoise.android.ui.conversation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * The localized Retry for a focus request whose window could not load — the conversation's load-error Retry, or the
 * search-result Retry chip while [pending] — may resume only its still-current request.
 */
internal class SearchTargetRetryState {
    var generation by mutableLongStateOf(0L)
        private set

    /** A still-current focus request failed to load and waits for Retry. */
    var pending by mutableStateOf(false)
        private set

    private var failedRequest: MessageTargetNavigationOwner.Request? = null
    private var awaitingRecovery = false

    /** Records [request] as the failed focus request, if it still owns navigation. */
    fun failed(request: MessageTargetNavigationOwner.Request) {
        if (request.isCurrent()) {
            failedRequest = request
            pending = true
        }
    }

    /** Forgets the failed request; a newer navigation or a dismissal supersedes its Retry. */
    fun clear() {
        failedRequest = null
        awaitingRecovery = false
        pending = false
    }

    /** Resumes the failed request if it is still current, waiting first for a present load failure to clear. */
    fun retry(loadFailurePresent: Boolean = false) {
        val request = failedRequest
        if (request?.isCurrent() != true) {
            clear()
        } else if (loadFailurePresent) {
            awaitingRecovery = true
        } else {
            clear()
            generation++
        }
    }

    /** Resumes an awaited retry once the conversation's load failure clears. */
    fun onLoadFailureChanged(loadFailurePresent: Boolean) {
        if (awaitingRecovery && !loadFailurePresent) retry()
    }
}

/** Recovery resumes only an explicitly retried request that still owns navigation. */
@Composable
internal fun searchTargetRetryRecoveryEffect(
    retry: SearchTargetRetryState,
    loadFailurePresent: Boolean,
) {
    LaunchedEffect(retry, loadFailurePresent) {
        retry.onLoadFailureChanged(loadFailurePresent)
    }
}
