package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.ChatListViewFfi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Keeps retired wrappers alive until admitted native calls have returned or been cancelled. */
internal class ChatListWindowLifetime(
    private val handles: Map<ChatListViewFfi, ChatListWindowHandle>,
) {
    /** Also guards the owning set's cursors and installed frames, so retirement rejects publication atomically. */
    val lock = Any()
    private var retired = false
    private var activeCalls = 0
    private var releaseClaimed = false
    private val receivers = mutableSetOf<Job>()
    private val released = CompletableDeferred<Unit>()

    val closed: Boolean get() = synchronized(lock) { retired }

    /** Registers a lazy receive worker before it can dispatch a native wait. */
    fun registerReceiver(job: Job): Boolean =
        synchronized(lock) {
            if (retired || job.isCompleted) return@synchronized false
            receivers.add(job)
        }

    /** Removes even a worker cancelled before its coroutine body started. */
    fun unregisterReceiver(job: Job) {
        synchronized(lock) { receivers.remove(job) }
    }

    /** Must run on IO: admission immediately precedes invocation, with no dispatcher hop between them. */
    suspend fun <T> withHandle(
        view: ChatListViewFfi,
        block: suspend (ChatListWindowHandle) -> T,
    ): T? {
        val handle =
            synchronized(lock) {
                if (retired) return@synchronized null
                handles[view]?.also { activeCalls++ }
            } ?: return null
        return try {
            block(handle)
        } finally {
            val release =
                synchronized(lock) {
                    activeCalls--
                    claimReleaseIfIdle()
                }
            if (release) releaseHandles()
        }
    }

    /**
     * Rejects new calls immediately and cancels only receive workers, without waiting for a command mutex.
     * Native destruction finishes asynchronously after admitted calls; use [awaitReleased] to observe it.
     */
    fun close() {
        val (jobs, release) =
            synchronized(lock) {
                if (retired) return
                retired = true
                receivers.toList() to claimReleaseIfIdle()
            }
        jobs.forEach { it.cancel() }
        if (release) releaseHandles()
    }

    /** Completes after every native close has been attempted; useful when teardown must be observed. */
    suspend fun awaitReleased() = released.await()

    /** Called only under [lock]; claiming once also fences concurrent close/finally paths. */
    private fun claimReleaseIfIdle(): Boolean {
        if (!retired || activeCalls != 0 || releaseClaimed) return false
        releaseClaimed = true
        return true
    }

    private fun releaseHandles() {
        // A finite cleanup job outlives cancelled receivers, never blocks the UI, and cannot admit new work.
        CoroutineScope(Dispatchers.IO).launch {
            try {
                handles.values.forEach { handle -> runCatching { handle.close() } }
            } finally {
                released.complete(Unit)
            }
        }
    }
}
