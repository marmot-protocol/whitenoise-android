package dev.ipf.darkmatter.state

internal class ProfileRefreshGate(
    private val retryCooldownMillis: Long,
) {
    private val inFlight = mutableSetOf<String>()
    private val retryAfterMillis = mutableMapOf<String, Long>()

    @Synchronized
    fun tryStart(
        accountIdHex: String,
        nowMillis: Long,
    ): Boolean {
        pruneExpired(nowMillis)
        if (accountIdHex in inFlight) return false
        if (nowMillis < retryAfterMillis.getOrDefault(accountIdHex, 0L)) return false
        inFlight.add(accountIdHex)
        return true
    }

    @Synchronized
    fun finish(
        accountIdHex: String,
        nowMillis: Long,
    ) {
        inFlight.remove(accountIdHex)
        // Sweep elapsed cooldowns here too: tryStart is the only other place
        // that prunes, so on a gate that goes quiescent after a burst of
        // finish() calls (e.g. many distinct senders seen between tryStart
        // calls in a large group) the map would otherwise grow with one
        // never-evicted entry per distinct pubkey for the process lifetime
        // (see #230). Pruning on every finish keeps the retained set bounded
        // by the number of pubkeys with a *live* (unexpired) cooldown.
        pruneExpired(nowMillis)
        retryAfterMillis[accountIdHex] = nowMillis + retryCooldownMillis
    }

    private fun pruneExpired(nowMillis: Long) {
        retryAfterMillis.entries.removeAll { (_, retryAfter) -> retryAfter <= nowMillis }
    }

    @Synchronized
    internal fun retainedCooldownCount(): Int = retryAfterMillis.size
}
