package dev.ipf.whitenoise.android.state

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * Coalesces per-account unread-count refreshes across a notification burst.
 *
 * Every posted notification used to trigger an immediate
 * `refreshAccountUnreadCount`, which reads the whole chat list and a
 * `groupMembers` roster per unread row to preserve removed-group suppression
 * (#625/#662). During catch-up bursts after doze/reconnect that runs once per
 * update, so the cost is O(updates * unread groups) and it stalls the
 * notification subscription loop on FFI work after every update.
 *
 * This scheduler records the accounts that need a refresh and drains them once
 * per [drainWindowMillis] window: a single drained pass refreshes each pending
 * account exactly once regardless of how many updates touched it. Accounts that
 * arrive while a drain is in flight are picked up by a follow-on drain so none
 * is lost.
 *
 * This holds only short-lived lifecycle state (a pending-account set and the
 * active drain [Job]); it is not a second Android-owned cache of White Noise
 * protocol data. The actual count still comes from [refresh], which reads the
 * SQLite/FFI source of truth and applies removed-group suppression. See
 * AGENTS.md.
 */
internal class UnreadRefreshScheduler(
    private val scope: CoroutineScope,
    private val drainWindowMillis: Long = DEFAULT_DRAIN_WINDOW_MILLIS,
    private val sleep: suspend (Long) -> Unit = { delay(it) },
    private val refresh: suspend (String) -> Unit,
) {
    // Acts as a concurrent set: presence of a key means "this account still
    // needs a refresh". Booleans are placeholder values.
    private val pending = ConcurrentHashMap<String, Boolean>()
    private val lock = Any()
    private var drainJob: Job? = null

    /**
     * Records that [accountRef] needs an unread refresh and ensures a drain is
     * scheduled. Cheap and non-blocking: it never crosses the FFI on the
     * caller's thread, so the notification subscription loop is not held up.
     */
    fun schedule(accountRef: String) {
        val ref = accountRef.takeIf { it.isNotBlank() } ?: return
        pending[ref] = true
        ensureDrain()
    }

    private fun ensureDrain() {
        synchronized(lock) {
            if (drainJob?.isActive == true) return
            drainJob = scope.launch { drain() }
        }
    }

    /**
     * Drains the pending set in waves until it is empty. Each wave waits one
     * [drainWindowMillis] window so a burst collapses into a single refresh per
     * account, then snapshots and clears the set and refreshes those accounts.
     * Accounts scheduled during a wave are handled by the next wave; the loop
     * exits only once no work remains so nothing is dropped.
     */
    private suspend fun drain() {
        while (true) {
            sleep(drainWindowMillis)
            val batch = drainPending()
            if (batch.isEmpty()) {
                synchronized(lock) {
                    // Re-check under the lock: a schedule() that raced between
                    // the empty snapshot and here would otherwise leave a
                    // pending account with no running drain.
                    if (pending.isEmpty()) {
                        drainJob = null
                        return
                    }
                }
                continue
            }
            batch.forEach { ref -> refresh(ref) }
        }
    }

    private fun drainPending(): List<String> {
        val keys = pending.keys.toList()
        keys.forEach { pending.remove(it) }
        return keys
    }

    internal companion object {
        // One frame's worth of slack: long enough to collapse a catch-up burst
        // into a single per-account refresh, short enough to stay imperceptible
        // for an isolated notification.
        const val DEFAULT_DRAIN_WINDOW_MILLIS = 16L
    }
}
