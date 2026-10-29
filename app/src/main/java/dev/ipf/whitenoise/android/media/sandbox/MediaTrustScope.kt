package dev.ipf.whitenoise.android.media.sandbox

import dev.ipf.whitenoise.android.media.MediaTrustDomain

/**
 * Carries a request's [MediaTrustDomain] from a domain-aware entry point, across the unchanged
 * [IsolatedMediaDecoder] facade, to the transport on the same thread. The transport reads it once, at entry,
 * before any thread hop. A call made outside [within] is [MediaTrustDomain.Unique].
 */
internal object MediaTrustScope {
    private val active = ThreadLocal<MediaTrustDomain?>()

    /** The domain of the still-image request now being made on this thread. */
    fun current(): MediaTrustDomain = active.get() ?: MediaTrustDomain.Unique

    /** Runs [block] with [domain] as this thread's request domain, then restores the previous one. */
    fun <T> within(
        domain: MediaTrustDomain,
        block: () -> T,
    ): T {
        val previous = active.get()
        active.set(domain)
        try {
            return block()
        } finally {
            if (previous == null) active.remove() else active.set(previous)
        }
    }
}
