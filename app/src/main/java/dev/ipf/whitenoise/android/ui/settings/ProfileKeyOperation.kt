package dev.ipf.whitenoise.android.ui.settings

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Screen-owned secret work whose late native result cannot outlive cancellation or its account/lifecycle. */
internal class ProfileKeyOperation {
    private var generation = 0L
    private var job: Job? = null

    /** Rejects reentry and rechecks authority after suspension, including work that ignores cancellation. */
    fun start(
        scope: CoroutineScope,
        canDeliver: () -> Boolean,
        load: suspend () -> String?,
        onResult: (String?) -> Unit,
        onFinished: () -> Unit = {},
    ): Boolean {
        if (job?.isActive == true || !canDeliver()) return false
        val ticket = ++generation
        val next =
            scope.launch(start = CoroutineStart.LAZY) {
                try {
                    if (!canDeliver()) return@launch
                    val value = load()
                    if (isActive && generation == ticket && canDeliver()) onResult(value)
                } finally {
                    if (generation == ticket) {
                        job = null
                        onFinished()
                    }
                }
            }
        job = next
        next.start()
        return true
    }

    /** Invalidates publication before cancelling, so a non-cooperative native completion is also discarded. */
    fun cancel() {
        generation++
        job?.cancel()
        job = null
    }
}
