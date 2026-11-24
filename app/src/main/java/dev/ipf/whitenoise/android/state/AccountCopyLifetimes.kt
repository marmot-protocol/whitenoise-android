package dev.ipf.whitenoise.android.state

import java.util.concurrent.ConcurrentHashMap

/**
 * One latest-wins boundary for each account's copy on this device. Removing the copy advances its boundary, so work
 * that began for the removed copy cannot publish afterwards, whether no copy is left or a fresh one took its place.
 * A boundary is captured when the work is reported, before it is queued, so a removal that runs first still counts.
 */
internal class AccountCopyLifetimes {
    private val lifetimes = ConcurrentHashMap<String, StalenessGuard>()

    private fun of(accountRef: String) = lifetimes.computeIfAbsent(accountRef) { StalenessGuard() }

    /** The boundary work for [accountRef]'s present copy must hold until it publishes. */
    fun capture(accountRef: String): Long = of(accountRef).capture()

    /** Whether [captured] still stands for the copy of [accountRef] on this device. */
    fun isCurrent(
        accountRef: String,
        captured: Long,
    ): Boolean = of(accountRef).isCurrent(captured)

    /** Runs [publication] only for the copy [captured] stands for, and says whether it ran. */
    fun runIfCurrent(
        accountRef: String,
        captured: Long,
        publication: () -> Unit,
    ): Boolean = of(accountRef).runIfCurrent(captured, publication)

    /** Outdates every earlier capture for [accountRef], running [retire] with publications for that copy held off. */
    fun copyRemoved(
        accountRef: String,
        retire: () -> Unit,
    ) {
        of(accountRef).advance(retire)
    }
}
