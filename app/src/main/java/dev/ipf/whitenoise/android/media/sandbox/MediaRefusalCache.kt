package dev.ipf.whitenoise.android.media.sandbox

import kotlin.coroutines.cancellation.CancellationException

/** Serialized by the process media slot; only actual source failures acquire a short cooldown. */
internal class MediaRefusalCache(
    private val now: () -> Long = android.os.SystemClock::elapsedRealtime,
) {
    private val refusals = LinkedHashMap<String, Long>()

    /**
     * Checks caller validity before consulting a millisecond cooldown keyed by source identity.
     * Cancellation and interruption propagate without caching. A null result or caught decode failure
     * acquires a bounded cooldown only if still attributable and a second validity check succeeds.
     * The process media-slot owner must serialize access; this cache supplies no lock.
     */
    fun <T> attempt(
        key: String,
        check: () -> Unit,
        attributable: () -> Boolean = { true },
        block: () -> T?,
    ): T? {
        check()
        if ((refusals[key] ?: 0L) > now()) return null
        val result =
            try {
                block()
            } catch (error: CancellationException) {
                throw error
            } catch (error: InterruptedException) {
                throw error
            } catch (_: Exception) {
                null
            } catch (_: OutOfMemoryError) {
                null
            }
        if (result == null && attributable()) {
            check() // A retired or timed-out call cannot mark a healthy source as malformed.
            refusals[key] = now() + MediaDecodePolicy.REFUSAL_TTL_MS
            while (refusals.size > MediaDecodePolicy.MAX_REFUSALS) refusals.remove(refusals.keys.first())
        }
        return result
    }
}
