package dev.ipf.whitenoise.android.media

import androidx.media3.common.PlaybackException

// True when a playback failure indicates corrupt or missing local attachment
// bytes. DECODING_FAILED is deliberately excluded: Media3 raises it for broad
// decode failures including device/codec bugs on otherwise valid files, so
// invalidating on it would delete a good attachment and force a needless
// re-download that hits the same decoder.
internal fun playbackErrorInvalidatesAttachmentCache(error: PlaybackException): Boolean =
    when (error.errorCode) {
        PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND,
        PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE,
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
        PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED,
        -> true
        else -> false
    }
