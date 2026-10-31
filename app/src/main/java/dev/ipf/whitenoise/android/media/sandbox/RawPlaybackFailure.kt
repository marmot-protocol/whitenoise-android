package dev.ipf.whitenoise.android.media.sandbox

import java.io.IOException

/** A device output refusal says nothing about the validity of the selected compressed source. */
internal class RawOutputFailure(
    message: String,
    cause: Throwable? = null,
) : IOException(message, cause)

/** Reservation failed before this request acquired or handed off any source descriptor. */
internal class MediaAdmissionFailure(
    message: String,
) : IOException(message)

/** Only explicitly attributable parent failures retain their non-source classification. */
internal fun rawPlaybackFailure(failure: Exception): IOException =
    when (failure) {
        is RawOutputFailure -> failure
        is MediaAdmissionFailure -> failure
        else -> IOException("Isolated playback failed", failure)
    }
