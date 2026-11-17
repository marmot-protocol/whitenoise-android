package dev.ipf.whitenoise.android.state

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async

/** Owns one shared native-or-legacy acquisition decision per attachment cache key. */
internal class InFlightAttachmentAcquisitions(
    private val scope: CoroutineScope,
    private val gate: AttachmentDownloadGate,
) {
    private val lock = Any()
    private val entries = mutableMapOf<String, Deferred<AttachmentAcquisitionOutcome>>()

    /** Identifies a joined automatic owner so a deliberate tap can upgrade its native demand. */
    fun isActive(cacheKey: String): Boolean = synchronized(lock) { entries[cacheKey]?.isActive == true }

    /**
     * Joins active work without choosing a second network path. Interactive demand promotes the queued owner, and a
     * [foreground] bubble's automatic demand only brings it to the front of the automatic queue. When an owner
     * finishes, any such demand it never consumed is dropped, so a later owner of the key does not inherit it.
     */
    fun acquire(
        cacheKey: String,
        priority: AttachmentDownloadPriority,
        foreground: Boolean = false,
        block: suspend CoroutineScope.() -> AttachmentAcquisitionOutcome,
    ): Deferred<AttachmentAcquisitionOutcome> =
        synchronized(lock) {
            entries[cacheKey]?.takeIf { it.isActive }?.let { active ->
                when {
                    priority == AttachmentDownloadPriority.Interactive -> gate.promote(cacheKey)
                    foreground -> gate.bringForward(cacheKey)
                }
                return@synchronized active
            }
            scope.async(block = block).also { deferred ->
                entries[cacheKey] = deferred
                deferred.invokeOnCompletion {
                    synchronized(lock) {
                        if (entries[cacheKey] !== deferred) return@synchronized
                        entries.remove(cacheKey)
                        // An owner settled from a cache never registers, so its visible joins' demand ends here.
                        gate.forgetForeground(cacheKey)
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
        val owners =
            synchronized(lock) {
                // Completion handlers skip cleared entries, so drop their unconsumed visible demand here.
                entries.keys.forEach(gate::forgetForeground)
                entries.values.toList().also { entries.clear() }
            }
        owners.forEach { it.cancel() }
    }
}
