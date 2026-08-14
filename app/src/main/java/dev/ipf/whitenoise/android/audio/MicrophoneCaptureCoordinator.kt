package dev.ipf.whitenoise.android.audio

/**
 * Process-local lease for capture features that share Android's microphone.
 *
 * Audio focus alone is not sufficient: a voice-note recorder can still be
 * finalizing its encoder after its UI disappears, while a speech recognizer
 * can outlive the conversation composable that started it. A stable owner
 * token keeps those paths mutually exclusive until the native capture resource
 * is actually released.
 */
class MicrophoneCaptureCoordinator {
    private val lock = Any()
    private var owner: Any? = null

    fun tryAcquire(candidate: Any): Boolean =
        synchronized(lock) {
            when (owner) {
                null -> {
                    owner = candidate
                    true
                }
                candidate -> true
                else -> false
            }
        }

    fun release(candidate: Any) {
        synchronized(lock) {
            if (owner === candidate) owner = null
        }
    }

    internal fun isOwnedBy(candidate: Any): Boolean = synchronized(lock) { owner === candidate }
}
