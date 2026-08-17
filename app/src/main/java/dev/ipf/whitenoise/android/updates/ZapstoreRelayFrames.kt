package dev.ipf.whitenoise.android.updates

import org.json.JSONArray

/**
 * Pure interpretation of Nostr relay WebSocket frames used by [ZapstoreReleaseClient].
 * Ignores wrong subscription IDs and malformed/non-event frames.
 */
internal object ZapstoreRelayFrames {
    fun parseMessage(text: String): JSONArray? = runCatching { JSONArray(text) }.getOrNull()

    fun frameType(message: JSONArray): String = message.optString(0)

    fun subscriptionId(message: JSONArray): String = message.optString(1)

    /**
     * Returns a parsed [NostrEvent] when [message] is an `EVENT` frame for
     * [expectedSubscriptionId]; otherwise null.
     */
    fun parseEventForSubscription(
        message: JSONArray,
        expectedSubscriptionId: String,
    ): NostrEvent? {
        if (frameType(message) != "EVENT" || subscriptionId(message) != expectedSubscriptionId) return null
        return message
            .optJSONObject(2)
            ?.let { json -> runCatching { NostrEvent.fromJson(json) }.getOrNull() }
    }

    /** True when [message] is `EOSE` or `CLOSED` for [expectedSubscriptionId]. */
    fun isTerminalForSubscription(
        message: JSONArray,
        expectedSubscriptionId: String,
    ): Boolean {
        val type = frameType(message)
        if (type != "EOSE" && type != "CLOSED") return false
        return subscriptionId(message) == expectedSubscriptionId
    }
}
