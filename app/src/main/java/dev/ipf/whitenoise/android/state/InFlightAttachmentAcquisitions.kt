package dev.ipf.whitenoise.android.state

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async

/** Owns one shared native-or-legacy acquisition decision per attachment cache key. */
internal class InFlightAttachmentAcquisitions(
    private val scope: CoroutineScope,
    private val promote: (String) -> Unit,
) {
    private val lock = Any()
    private val entries = mutableMapOf<String, Deferred<AttachmentAcquisitionOutcome>>()

    /** Identifies a joined automatic owner so a deliberate tap can upgrade its native demand. */
    fun isActive(cacheKey: String): Boolean = synchronized(lock) { entries[cacheKey]?.isActive == true }

    /** Joins active work, promoting interactive demand without choosing a second network path. */
    fun acquire(
        cacheKey: String,
        priority: AttachmentDownloadPriority,
        block: suspend CoroutineScope.() -> AttachmentAcquisitionOutcome,
    ): Deferred<AttachmentAcquisitionOutcome> =
        synchronized(lock) {
            entries[cacheKey]?.takeIf { it.isActive }?.let { active ->
                if (priority == AttachmentDownloadPriority.Interactive) promote(cacheKey)
                return@synchronized active
            }
            scope.async(block = block).also { deferred ->
                entries[cacheKey] = deferred
                deferred.invokeOnCompletion {
                    synchronized(lock) {
                        if (entries[cacheKey] === deferred) entries.remove(cacheKey)
                    }
                }
            }
        }

    /** Cancels only the active owner for an explicit user stop, preserving a newer retry. */
    fun cancel(
        cacheKey: String,
        cause: CancellationException,
    ): Boolean {
        val active = synchronized(lock) { entries[cacheKey]?.takeIf { it.isActive } }
        active?.cancel(cause)
        return active != null
    }

    /** Cancels and forgets every owner when the enclosing app-state session closes. */
    fun cancelAll() {
        val owners = synchronized(lock) { entries.values.toList().also { entries.clear() } }
        owners.forEach { it.cancel() }
    }
}
