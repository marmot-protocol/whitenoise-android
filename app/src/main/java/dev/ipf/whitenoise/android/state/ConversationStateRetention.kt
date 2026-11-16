package dev.ipf.whitenoise.android.state

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

/**
 * Access-order conversation-state retention with an optional protected key for
 * the chat currently on screen. The protected key is promoted before overflow
 * pruning so an active controller's optimistic/retry state cannot be aged out
 * by other conversations touching their own state maps.
 * Queued and running uploads also protect their conversation until settlement;
 * pruning then restores the ordinary window and releases inactive private sources.
 */
internal class ConversationStateRetention(
    private val maxEntries: Int,
    private val isProtected: (String) -> Boolean = { false },
) {
    private val recentKeys = LinkedHashMap<String, Unit>(16, 0.75f, true)

    init {
        require(maxEntries > 0) { "maxEntries must be positive" }
    }

    fun retain(
        key: String,
        protectedKey: String? = null,
    ): List<String> {
        recentKeys[key] = Unit
        promoteProtected(protectedKey)
        return evictOverflow(protectedKey)
    }

    fun promote(
        key: String,
        protectedKey: String? = null,
    ): List<String> {
        if (recentKeys.containsKey(key)) {
            recentKeys[key] = Unit
        }
        promoteProtected(protectedKey)
        return evictOverflow(protectedKey)
    }

    fun keysSnapshot(): List<String> = recentKeys.keys.toList()

    /** Settled uploads return to the ordinary retention window without promoting stale state. */
    fun prune(protectedKey: String? = null): List<String> = evictOverflow(protectedKey)

    private fun promoteProtected(protectedKey: String?) {
        if (protectedKey != null && recentKeys.containsKey(protectedKey)) {
            recentKeys[protectedKey] = Unit
        }
    }

    /** Evicts idle conversations only; active uploads may temporarily exceed the ordinary retention window. */
    private fun evictOverflow(protectedKey: String?): List<String> {
        val evicted = mutableListOf<String>()
        while (recentKeys.size > maxEntries) {
            val staleKey = recentKeys.keys.firstOrNull { it != protectedKey && !isProtected(it) } ?: break
            recentKeys.remove(staleKey)
            evicted += staleKey
        }
        return evicted
    }
}
