package dev.ipf.whitenoise.android.media.sandbox

import dev.ipf.whitenoise.android.media.MediaTrustDomain
import kotlinx.coroutines.asContextElement

/**
 * Carries a request's [MediaTrustDomain] from a domain-aware entry point, across the unchanged
 * [IsolatedMediaDecoder] facade, to the transport on the same thread. The transport reads it once, at entry,
 * before any thread hop. A call made outside [within] or [element] is [MediaTrustDomain.Unique].
 */
internal object MediaTrustScope {
    private val active = ThreadLocal<MediaTrustDomain?>()

    /** The domain of the still-image request now being made on this thread. */
    fun current(): MediaTrustDomain = active.get() ?: MediaTrustDomain.Unique

    /** The domain an enclosing [within] or [element] declared on this thread, or null when none did. */
    fun declared(): MediaTrustDomain? = active.get()

    /**
     * A coroutine context element declaring [domain] on whatever thread runs the coroutine, for suspending
     * paths whose decode happens after a thread hop. Work launched into another scope does not inherit it.
     */
    fun element(domain: MediaTrustDomain): kotlin.coroutines.CoroutineContext.Element = active.asContextElement(domain)

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
