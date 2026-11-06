package dev.ipf.whitenoise.android.audio

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Owns the foreground-service lease of one dictation session.
 *
 * Capture may start only after both the enqueue was accepted and the service confirmed its
 * promotion, in either order. The lease stops the service at most once per [begin], and a
 * terminal release can wait for the matching native capture closure.
 */
internal class ConversationDictationForegroundLease(
    private val stopService: () -> Unit,
    private val diagnostic: (String) -> Unit,
) {
    /** Whether the service is requested or running for the current session. */
    var active = false
        private set

    /** Increments per lease so a token from an earlier lease cannot match a later one. */
    var generation = 0L
        private set

    /** Whether capture may run under this lease. */
    var ready by mutableStateOf(false)
        private set

    private var startAccepted = false
    private var promotionReceived = false
    private var promotionTimeout: ConversationDictationTimeoutHandle? = null

    /** Capture generation whose native closure owes the deferred terminal release. */
    var deferredReleaseCaptureGeneration: Long? = null
        private set

    /** Token kept for the service while a deferred release waits for native capture closure. */
    var deferredReleaseToken: String? = null
        private set

    /** Whether the lease waits for a capture closure before it stops the service. */
    val releaseDeferred: Boolean get() = deferredReleaseCaptureGeneration != null

    /** Publishes pending ownership for a new lease; returns false if one is already active. */
    fun begin(): Boolean {
        if (active) return false
        generation += 1L
        active = true
        ready = false
        startAccepted = false
        promotionReceived = false
        return true
    }

    fun armPromotionTimeout(handle: ConversationDictationTimeoutHandle) {
        promotionTimeout?.cancel()
        promotionTimeout = handle
    }

    fun cancelPromotionTimeout() {
        promotionTimeout?.cancel()
        promotionTimeout = null
    }

    /** Records enqueue acceptance; true when the promotion was already confirmed. */
    fun acceptStart(): Boolean {
        startAccepted = true
        return promotionReceived
    }

    /** Records the service's promotion; true when the enqueue was already accepted. */
    fun notePromotion(): Boolean {
        promotionReceived = true
        return startAccepted
    }

    fun markReady() {
        cancelPromotionTimeout()
        ready = true
    }

    /** Android already destroyed the service, so there is nothing left to stop. */
    fun onServiceDestroyed() {
        active = false
        ready = false
    }

    /** Keeps the service until the capture of [captureGeneration] reports native closure. */
    fun deferReleaseUntilCaptureClosed(
        captureGeneration: Long,
        token: String?,
    ) {
        deferredReleaseCaptureGeneration = captureGeneration
        deferredReleaseToken = token
    }

    /** Releases a lease deferred for [captureGeneration]; ignores any other closure. */
    fun releaseAfterCaptureClosed(captureGeneration: Long) {
        if (deferredReleaseCaptureGeneration != captureGeneration) return
        deferredReleaseCaptureGeneration = null
        deferredReleaseToken = null
        release()
    }

    /** Stops the service at most once and resets the promotion handshake. */
    fun release() {
        cancelPromotionTimeout()
        if (active) {
            active = false
            diagnostic("event=foreground_service_stop_requested")
            runCatching(stopService)
        }
        ready = false
        startAccepted = false
        promotionReceived = false
    }
}
