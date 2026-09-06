package dev.ipf.whitenoise.android.state

import kotlinx.coroutines.CancellationException

/**
 * Runs independent post-commit refreshes without letting one optional failure
 * suppress later refreshes, while preserving structured cancellation.
 */
internal suspend fun runBestEffortPostCommitSteps(
    steps: List<Pair<String, suspend () -> Unit>>,
    onFailure: (String, Throwable) -> Unit,
) {
    steps.forEach { (name, step) ->
        val failure = runCatching { step() }.exceptionOrNull() ?: return@forEach
        if (failure is CancellationException) throw failure
        onFailure(name, failure)
    }
}
