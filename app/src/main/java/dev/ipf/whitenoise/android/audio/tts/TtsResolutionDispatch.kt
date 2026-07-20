package dev.ipf.whitenoise.android.audio.tts

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * Runs TTS resolution off the caller thread and closes a resolved handle if the
 * caller is cancelled during the dispatcher handoff back to its own context.
 */
internal suspend fun resolveTtsOnDispatcher(
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
    resolve: suspend () -> TtsResolutionResult,
): TtsResolutionResult {
    var completedResolution: TtsResolutionResult? = null
    return try {
        withContext(dispatcher) {
            resolve().also { completedResolution = it }
        }
    } catch (cancelled: CancellationException) {
        withContext(NonCancellable + dispatcher) {
            completedResolution?.handle?.let { handle -> runCatching { handle.release() } }
        }
        throw cancelled
    }
}
