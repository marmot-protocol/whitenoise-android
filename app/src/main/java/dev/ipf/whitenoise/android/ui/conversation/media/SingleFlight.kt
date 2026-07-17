package dev.ipf.whitenoise.android.ui.conversation.media

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** Coalesces concurrent work for the same key into one non-cancellable operation. */
internal class SingleFlight<K, V> {
    private val lock = Any()
    private val inFlight = mutableMapOf<K, CompletableDeferred<V>>()

    suspend fun run(
        key: K,
        block: suspend () -> V,
    ): V {
        var owner = false
        val shared =
            synchronized(lock) {
                inFlight[key]
                    ?.takeIf { it.isActive }
                    ?: CompletableDeferred<V>()
                        .also {
                            inFlight[key] = it
                            owner = true
                        }
            }
        if (!owner) return shared.await()

        return try {
            withContext(NonCancellable) { block() }
                .also(shared::complete)
        } catch (throwable: Throwable) {
            shared.completeExceptionally(throwable)
            throw throwable
        } finally {
            synchronized(lock) {
                if (inFlight[key] === shared) {
                    inFlight.remove(key)
                }
            }
        }
    }
}
