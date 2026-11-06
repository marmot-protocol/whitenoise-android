package dev.ipf.whitenoise.android.media.sandbox

import java.io.IOException

/** A device output refusal says nothing about the validity of the selected compressed source. */
internal class RawOutputFailure(
    message: String,
    cause: Throwable? = null,
) : IOException(message, cause)

/** Coarse demuxer invalid-container refusal from OPEN or READ; MDK owns cache invalidation. */
internal class RawContainerFailure : IOException("Selected media container is malformed")

/** Converts only the native demuxer INVALIDDATA marker; all other layouts retain normal validation. */
internal fun checkedNativeRead(values: LongArray): LongArray {
    if (values.size == 1 && values[0] == -1L) throw RawContainerFailure()
    return values
}

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
