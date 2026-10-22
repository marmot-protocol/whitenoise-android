package dev.ipf.whitenoise.android.state

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Coalesces read-state card cleanup within its receipt-loop scope. A conversation has one worker even while a
 * synchronous native read is blocked. New requests mark that worker dirty rather than cancelling its binding and
 * starting another. It reads the latest state again, then follows up once after the latest pass has stayed quiet.
 * Idle and cancelled workers release their registrations; no notification or native state is cached here.
 */
internal class NotificationReadReconciliationCoordinator(
    private val scope: CoroutineScope,
    private val followUpMillis: Long,
    private val reconcile: suspend (accountRef: String, groupIdHex: String) -> Unit,
) {
    private val lock = Any()
    private val pending = mutableMapOf<Pair<String, String>, PendingReconciliation>()

    /** Conflates requests by account and normalized conversation without interrupting an in-flight read. */
    fun request(
        accountRef: String,
        groupIdHex: String,
    ) {
        val key = accountRef to groupIdHex.lowercase()
        val job =
            synchronized(lock) {
                pending[key]?.let {
                    it.version.value += 1L
                    return
                }
                val entry = PendingReconciliation()
                val worker = scope.launch(start = CoroutineStart.LAZY) { reconcileUntilQuiet(key, entry) }
                pending[key] = entry
                worker.invokeOnCompletion {
                    synchronized(lock) {
                        if (pending[key] === entry) pending.remove(key)
                    }
                }
                worker
            }
        job.start()
    }

    /** Serializes current-state reads and restarts the follow-up delay whenever a newer request arrives. */
    private suspend fun reconcileUntilQuiet(
        key: Pair<String, String>,
        entry: PendingReconciliation,
    ) {
        while (currentCoroutineContext().isActive) {
            val version = entry.version.value
            reconcile(key.first, key.second)
            val quiet =
                entry.version.value == version &&
                    withTimeoutOrNull(followUpMillis) { entry.version.first { it != version } } == null
            if (quiet) {
                reconcile(key.first, key.second)
                if (retireIfCurrent(key, entry, version)) return
            }
        }
    }

    /** Atomically retires only a fully reconciled latest request, so an arriving event cannot be lost. */
    private fun retireIfCurrent(
        key: Pair<String, String>,
        entry: PendingReconciliation,
        version: Long,
    ): Boolean =
        synchronized(lock) {
            if (pending[key] === entry && entry.version.value == version) {
                pending.remove(key)
                true
            } else {
                false
            }
        }

    private class PendingReconciliation {
        val version = MutableStateFlow(0L)
    }
}
