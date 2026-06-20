package dev.ipf.darkmatter.state

/** Initial backoff before reconnecting a live Marmot subscription (matches iOS). */
internal const val LIVE_SUBSCRIPTION_INITIAL_RETRY_DELAY_MS: Long = 500L

/** Maximum backoff between live subscription reconnect attempts (matches iOS). */
internal const val LIVE_SUBSCRIPTION_MAX_RETRY_DELAY_MS: Long = 8_000L

/**
 * Next delay for a live subscription retry loop: double [current], clamped to
 * [LIVE_SUBSCRIPTION_MAX_RETRY_DELAY_MS].
 */
internal fun nextLiveSubscriptionRetryDelayMillis(current: Long): Long =
    nextRetryBackoffMillis(current, LIVE_SUBSCRIPTION_MAX_RETRY_DELAY_MS)
