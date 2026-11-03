package dev.ipf.whitenoise.android.core

import kotlinx.coroutines.CompletableDeferred

/**
 * Exact-key waiters for decoded pixels a cache publishes (#3557). Only mounted missing consumers register,
 * so the set stays bounded by visible placeholders, and a publication wakes only the waiters of its own key —
 * never every avatar. The owner calls every method under its own cache lock, which makes a consumer's
 * register-then-read atomic with respect to the publication it is waiting for.
 */
internal class AvatarCachePublications {
    private val waitersByKey = HashMap<String, MutableSet<Registration>>()

    /** One consumer's wait across the keys that could satisfy it, completed by the first of them published. */
    class Registration internal constructor(
        val keys: Set<String>,
    ) {
        val published = CompletableDeferred<Unit>()
    }

    /** Number of keys with at least one waiter, so tests can assert cleanup after disposal. */
    val waitingKeyCount: Int get() = waitersByKey.size

    /** Starts waiting for any of [keys]; release the result once the wait ends for any reason. */
    fun register(keys: Set<String>): Registration =
        Registration(keys).also { registration ->
            keys.forEach { key -> waitersByKey.getOrPut(key) { mutableSetOf() }.add(registration) }
        }

    /** Drops [registration] from every key it named, removing keys nobody waits for any more. */
    fun release(registration: Registration) {
        registration.keys.forEach { key ->
            val waiters = waitersByKey[key] ?: return@forEach
            waiters.remove(registration)
            if (waiters.isEmpty()) waitersByKey.remove(key)
        }
    }

    /** Wakes only the consumers waiting for exactly [key]; they re-read the cache themselves. */
    fun published(key: String) {
        waitersByKey[key]?.toList()?.forEach { it.published.complete(Unit) }
    }

    /** Wakes every waiter when the cache lifetime ends, so each can see its lifetime is stale and stop. */
    fun retireAll() {
        waitersByKey.values.flatten().forEach { it.published.complete(Unit) }
    }
}
