package dev.ipf.whitenoise.android.media.sandbox

/** Measurement-only view of still-image lane admission, production installation leaves it unset. */
internal fun interface MediaAdmissionObserver {
    /** Reports how long one request waited for the lane and whether it was admitted before the deadline. */
    fun admission(
        waitNanos: Long,
        admitted: Boolean,
    )
}
