package dev.ipf.whitenoise.android.media.sandbox

/**
 * The quarantined source keys of one image session, each until its expiry, holding at most [limit] keys with
 * the oldest dropped first. Every domain lane checks and records refusals at once, so each access holds the
 * ledger's own lock — no admission semaphore serializes them any longer.
 */
internal class MediaRefusalLedger(
    private val limit: Int = MediaDecodePolicy.MAX_REFUSALS,
) {
    private val lock = Any()
    private val expiries = LinkedHashMap<String, Long>()

    /** Whether [key] is still quarantined at [now]. */
    fun refused(
        key: String,
        now: Long,
    ): Boolean = synchronized(lock) { (expiries[key] ?: 0L) > now }

    /** Quarantines [key] until [until] as the newest entry, then drops the oldest keys beyond the bound. */
    fun refuse(
        key: String,
        until: Long,
    ) {
        synchronized(lock) {
            expiries.remove(key)
            expiries[key] = until
            val oldest = expiries.keys.iterator()
            while (expiries.size > limit) {
                oldest.next()
                oldest.remove()
            }
        }
    }

    /** How many keys are held, quarantined or expired. */
    fun size(): Int = synchronized(lock) { expiries.size }
}
