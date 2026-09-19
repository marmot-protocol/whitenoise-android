package dev.ipf.whitenoise.android.audio

import java.util.concurrent.atomic.AtomicBoolean

/**
 * Holds a terminal recognizer callback until all caller-owned audio relevant to it is durable.
 *
 * A provider pipe can close before the microphone thread has sealed the final partial chunk. When
 * Paste or Send requested capture closure, waiting for both boundaries prevents the controller
 * from observing an empty queue and completing before the tail becomes available.
 */
internal class ConversationDictationCallerAudioCompletionBarrier(
    private val dispatch: (() -> Unit) -> Unit,
) {
    private val captureClosureRequired = AtomicBoolean(false)
    private val delivered = AtomicBoolean(false)

    /** Makes every subsequent terminal callback wait for the shared capture to close. */
    fun requireCaptureClosure() {
        captureClosureRequired.set(true)
    }

    /** Delivers a provider-microphone result through the same exactly-once generation fence. */
    fun deliverImmediately(delivery: () -> Unit) {
        dispatch { deliverOnce(delivery) }
    }

    /**
     * Waits for the active provider feed, then rechecks the stop boundary on the dispatch thread.
     * Rechecking after feed closure covers a Paste or Send click racing an already-returned result.
     */
    fun deliver(
        onFeedClosed: ((() -> Unit) -> Unit),
        onCaptureClosed: ((() -> Unit) -> Unit),
        delivery: () -> Unit,
    ) {
        onFeedClosed {
            dispatch {
                if (captureClosureRequired.get()) {
                    onCaptureClosed { dispatch { deliverOnce(delivery) } }
                } else {
                    deliverOnce(delivery)
                }
            }
        }
    }

    /** Suppresses platform error/result duplicates for the same recognizer generation. */
    private fun deliverOnce(delivery: () -> Unit) {
        if (delivered.compareAndSet(false, true)) delivery()
    }
}
