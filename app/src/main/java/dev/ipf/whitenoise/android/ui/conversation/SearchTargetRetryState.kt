package dev.ipf.whitenoise.android.ui.conversation

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.setValue

/** The existing localized load-error Retry may resume only its still-current focus request. */
internal class SearchTargetRetryState {
    var generation by mutableLongStateOf(0L)
        private set

    private var failedRequest: MessageTargetNavigationOwner.Request? = null

    fun failed(request: MessageTargetNavigationOwner.Request) {
        if (request.isCurrent()) failedRequest = request
    }

    fun clear() {
        failedRequest = null
    }

    fun retry() {
        val request = failedRequest
        failedRequest = null
        if (request?.isCurrent() == true) generation++
    }
}
