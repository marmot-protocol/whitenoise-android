package dev.ipf.whitenoise.android.state

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job

/** Tracks process-local media upload jobs until they complete or the active account changes. */
internal class InFlightMediaUploads {
    private val lock = Any()
    private val jobs = mutableMapOf<String, Job>()

    /** Tracks [job] for one conversation upload without letting stale completion remove its replacement. */
    fun track(
        conversationKey: String,
        uploadKey: String,
        job: Job,
    ) {
        val key = registryKey(conversationKey, uploadKey)
        synchronized(lock) {
            jobs[key] = job
        }
        job.invokeOnCompletion {
            synchronized(lock) {
                if (jobs[key] === job) {
                    jobs.remove(key)
                }
            }
        }
    }

    /** Stops tracking [job] only while it still owns the matching upload slot. */
    fun untrack(
        conversationKey: String,
        uploadKey: String,
        job: Job,
    ) {
        val key = registryKey(conversationKey, uploadKey)
        synchronized(lock) {
            if (jobs[key] === job) {
                jobs.remove(key)
            }
        }
    }

    /** Cancels every distinct tracked job and returns the number of jobs cancelled. */
    fun cancelAll(): Int {
        val active =
            synchronized(lock) {
                jobs
                    .values
                    .toSet()
                    .also { jobs.clear() }
            }
        active.forEach { it.cancel(CancellationException("media upload cancelled by account switch")) }
        return active.size
    }

    /** Namespaces upload identifiers by conversation without ambiguous concatenation. */
    private fun registryKey(
        conversationKey: String,
        uploadKey: String,
    ): String = "$conversationKey\u0000$uploadKey"
}
