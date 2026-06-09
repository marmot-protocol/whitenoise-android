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
        retryAfterMillis.entries.removeAll { (_, retryAfter) -> retryAfter <= nowMillis }
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
        retryAfterMillis[accountIdHex] = nowMillis + retryCooldownMillis
    }

    @Synchronized
    internal fun retainedCooldownCount(): Int = retryAfterMillis.size
}
