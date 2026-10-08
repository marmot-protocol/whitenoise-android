package dev.ipf.whitenoise.android.ui.conversation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.setValue

/** The existing localized load-error Retry may resume only its still-current focus request. */
internal class SearchTargetRetryState {
    var generation by mutableLongStateOf(0L)
        private set

    private var failedRequest: MessageTargetNavigationOwner.Request? = null
    private var awaitingRecovery = false

    fun failed(request: MessageTargetNavigationOwner.Request) {
        if (request.isCurrent()) failedRequest = request
    }

    fun clear() {
        failedRequest = null
        awaitingRecovery = false
    }

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

    fun onLoadFailureChanged(loadFailurePresent: Boolean) {
        if (awaitingRecovery && !loadFailurePresent) retry()
    }
}

/** Recovery resumes only an explicitly retried request that still owns navigation. */
@Composable
internal fun SearchTargetRetryRecoveryEffect(
    retry: SearchTargetRetryState,
    loadFailurePresent: Boolean,
) {
    LaunchedEffect(retry, loadFailurePresent) {
        retry.onLoadFailureChanged(loadFailurePresent)
    }
}
