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
