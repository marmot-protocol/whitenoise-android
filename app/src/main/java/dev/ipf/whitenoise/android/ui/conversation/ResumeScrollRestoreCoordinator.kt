package dev.ipf.whitenoise.android.ui.conversation

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Owns the lifecycle-resume scroll restore while the IME viewport settles.
 *
 * A later pause, replacement resume, or controller disposal must cancel the
 * pending restore so stale lifecycle work cannot move the reader afterward.
 */
internal class ResumeScrollRestoreCoordinator {
    private var resumeWorkJob: Job? = null

    fun launchResumeWork(
        scope: CoroutineScope,
        block: suspend CoroutineScope.() -> Unit,
    ) {
        cancel()
        resumeWorkJob = scope.launch(block = block)
    }

    fun cancel() {
        resumeWorkJob?.cancel()
        resumeWorkJob = null
    }
}
