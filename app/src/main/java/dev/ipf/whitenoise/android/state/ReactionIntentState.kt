package dev.ipf.whitenoise.android.state

/** Stable map key for the latest optimistic state of one message and emoji. */
internal fun reactionIntentOverlayId(
    target: String,
    emoji: String,
): String = "reaction-intent:${target.length}:$target:$emoji"

/** Removes one optimistic intent and returns the target whose tallies must be recomputed. */
internal fun MutableMap<String, OptimisticReactionChange>.clearReactionIntent(
    optimisticId: String,
    target: String,
): Set<String> {
    remove(optimisticId)
    return setOf(target)
}

/** Includes locally committed additions that have not reached the projected reaction summary yet. */
internal fun MutableSet<String>.includeUnprojectedReactionEmojis(
    target: String,
    unprojectedKeys: Set<Pair<String, String>>,
): Set<String> =
    apply {
        unprojectedKeys
            .asSequence()
            .filter { (messageId, _) -> messageId.equals(target, ignoreCase = true) }
            .mapTo(this) { (_, emoji) -> emoji }
    }

/** Merges reaction event ids without letting a blank presence marker hide a projected id. */
internal fun knownReactionEventIds(
    projectedEventIds: Map<String, String>,
    emoji: String,
    unprojectedEventId: String?,
    authoritativeEventIds: Map<String, String>?,
): Map<String, String> =
    projectedEventIds
        .toMutableMap()
        .apply {
            unprojectedEventId
                ?.takeIf(String::isNotBlank)
                ?.let { put(emoji, it) }
            authoritativeEventIds?.let(::putAll)
        }
