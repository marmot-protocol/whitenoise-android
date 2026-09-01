package dev.ipf.whitenoise.android.state

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** A self-pruning set of mutexes keyed by an application-owned identifier. */
internal class KeyedMutexPool {
    private class Entry {
        val mutex = Mutex()
        var users = 0
    }

    private val entries = mutableMapOf<String, Entry>()
    private val entriesLock = Any()

    suspend fun <T> withLock(
        key: String,
        block: suspend () -> T,
    ): T {
        val entry = synchronized(entriesLock) { entries.getOrPut(key) { Entry() }.also { it.users += 1 } }
        try {
            return entry.mutex.withLock { block() }
        } finally {
            synchronized(entriesLock) {
                entry.users -= 1
                if (entry.users == 0 && !entry.mutex.isLocked && entries[key] === entry) entries.remove(key)
            }
        }
    }

    fun pruneIdle() {
        synchronized(entriesLock) {
            entries.entries.removeAll { (_, entry) -> entry.users == 0 && !entry.mutex.isLocked }
        }
    }
}
