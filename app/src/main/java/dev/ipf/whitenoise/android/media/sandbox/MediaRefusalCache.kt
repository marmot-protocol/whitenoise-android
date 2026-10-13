package dev.ipf.whitenoise.android.media.sandbox

import kotlin.coroutines.cancellation.CancellationException

/** Serialized by the process media slot; only actual source failures acquire a short cooldown. */
internal class MediaRefusalCache(
    private val now: () -> Long = android.os.SystemClock::elapsedRealtime,
) {
    private val refusals = LinkedHashMap<String, Long>()

    fun <T> attempt(
        key: String,
        check: () -> Unit,
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
        if (result == null) {
            check() // A retired or timed-out call cannot mark a healthy source as malformed.
            refusals[key] = now() + MediaDecodePolicy.REFUSAL_TTL_MS
            while (refusals.size > MediaDecodePolicy.MAX_REFUSALS) refusals.remove(refusals.keys.first())
        }
        return result
    }
}
