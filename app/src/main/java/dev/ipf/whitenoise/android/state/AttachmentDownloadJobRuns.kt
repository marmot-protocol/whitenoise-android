package dev.ipf.whitenoise.android.state

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.launch

/** Tracks Android job runs without letting an interrupted run retire its replacement. */
internal class AttachmentDownloadJobRuns(
    private val scope: CoroutineScope,
) {
    private val lock = Any()
    private val running = mutableMapOf<Int, Job>()

    /** Registers before starting, and gives only the current live run the final handoff. */
    fun start(
        jobId: Int,
        download: suspend () -> Unit,
        onFinished: () -> Unit,
    ): Job {
        val run =
            scope.launch(start = CoroutineStart.LAZY) {
                download()
                currentCoroutineContext().ensureActive()
                val owner = currentCoroutineContext().job
                synchronized(lock) {
                    if (running[jobId] === owner) {
                        running.remove(jobId)
                        onFinished()
                    }
                }
            }
        val previous = synchronized(lock) { running.put(jobId, run) }
        run.invokeOnCompletion { retire(jobId, run) }
        previous?.cancel()
        run.start()
        return run
    }

    /** Revokes ownership before cancellation, so delayed cleanup cannot finish a stopped job. */
    fun stop(jobId: Int): Boolean {
        val run = synchronized(lock) { running.remove(jobId) }
        run?.cancel()
        return run != null
    }

    /** A lazy cancellation or delayed old completion may remove only its own registration. */
    private fun retire(
        jobId: Int,
        run: Job,
    ) {
        synchronized(lock) {
            if (running[jobId] === run) running.remove(jobId)
        }
    }
}
