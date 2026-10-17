package dev.ipf.whitenoise.android.state

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** A self-pruning set of mutexes keyed by an application-owned identifier. */
internal class KeyedMutexPool {
    private class Entry {
        val mutex = Mutex()
        var users = 0
    }

    /** The entries the current coroutine holds, so [releasedWhile] can tell its own hold from someone else's. */
    private class Held(
        val entries: Set<Entry>,
    ) : AbstractCoroutineContextElement(Held) {
        companion object Key : CoroutineContext.Key<Held>
    }

    private val entries = mutableMapOf<String, Entry>()
    private val entriesLock = Any()

    /** Runs [block] while holding the mutex for [key]. */
    suspend fun <T> withLock(
        key: String,
        block: suspend () -> T,
    ): T {
        val entry = synchronized(entriesLock) { entries.getOrPut(key) { Entry() }.also { it.users += 1 } }
        try {
            val held = currentCoroutineContext()[Held]?.entries.orEmpty()
            return entry.mutex.withLock { withContext(Held(held + entry)) { block() } }
        } finally {
            synchronized(entriesLock) {
                entry.users -= 1
                if (entry.users == 0 && !entry.mutex.isLocked && entries[key] === entry) entries.remove(key)
            }
        }
    }

    /**
     * Runs [block] with [key] released when the calling coroutine holds it, and holds it again before returning, even
     * when [block] is cancelled. Others may take the lock meanwhile, so state read under it before [block] can be
     * stale afterwards. A key the caller does not hold is left alone.
     */
    suspend fun <T> releasedWhile(
        key: String,
        block: suspend () -> T,
    ): T {
        val entry = synchronized(entriesLock) { entries[key] }
        val held = currentCoroutineContext()[Held]?.entries.orEmpty()
        if (entry == null || entry !in held) return block()
        entry.mutex.unlock()
        try {
            return block()
        } finally {
            withContext(NonCancellable) { entry.mutex.lock() }
        }
    }

    /** Forgets every entry nobody holds or waits for. */
    fun pruneIdle() {
        synchronized(entriesLock) {
            entries.entries.removeAll { (_, entry) -> entry.users == 0 && !entry.mutex.isLocked }
        }
    }
}
