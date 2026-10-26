package dev.ipf.whitenoise.android.media

import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first

/** A finite decoder wait expired; wait for a real ownership change without holding an IO thread. */
internal class AnimationAdmissionBusyException(
    private val availability: StateFlow<Long>,
    private val observedGeneration: Long,
    private val capacityAvailable: Boolean = false,
) : Exception("animation worker busy") {
    /** StateFlow retains a release between the failed attempt and this lifecycle-owned observation. */
    suspend fun awaitAvailability() {
        if (!capacityAvailable) availability.first { it != observedGeneration }
    }
}
