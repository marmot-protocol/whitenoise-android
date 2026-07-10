package dev.ipf.whitenoise.android.media

import androidx.media3.common.PlaybackException

/** True when a playback failure indicates corrupt local attachment bytes. */
internal fun playbackErrorInvalidatesAttachmentCache(error: PlaybackException): Boolean =
    when (error.errorCode) {
        PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND,
        PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE,
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
        PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED,
        PlaybackException.ERROR_CODE_DECODING_FAILED,
        -> true
        else -> false
    }
