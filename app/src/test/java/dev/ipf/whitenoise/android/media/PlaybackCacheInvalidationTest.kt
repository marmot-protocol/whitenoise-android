package dev.ipf.whitenoise.android.media

import androidx.media3.common.PlaybackException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PlaybackCacheInvalidationTest {
    @Test
    fun corruptContainerMalformedErrorInvalidatesAttachmentCache() {
        assertTrue(
            playbackErrorInvalidatesAttachmentCache(
                PlaybackException(
                    "malformed",
                    null,
                    PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
                ),
            ),
        )
    }

    @Test
    fun decodingFailedErrorDoesNotInvalidateAttachmentCache() {
        // A decode failure is often a device/codec bug on a valid file, so the
        // cache must be preserved rather than deleted and re-downloaded.
        assertFalse(
            playbackErrorInvalidatesAttachmentCache(
                PlaybackException(
                    "decode",
                    null,
                    PlaybackException.ERROR_CODE_DECODING_FAILED,
                ),
            ),
        )
    }

    @Test
    fun localFileIoErrorInvalidatesAttachmentCache() {
        assertTrue(
            playbackErrorInvalidatesAttachmentCache(
                PlaybackException(
                    "missing",
                    null,
                    PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND,
                ),
            ),
        )
    }

    @Test
    fun ioNoPermissionDoesNotInvalidateAttachmentCache() {
        assertFalse(
            playbackErrorInvalidatesAttachmentCache(
                PlaybackException(
                    "permission",
                    null,
                    PlaybackException.ERROR_CODE_IO_NO_PERMISSION,
                ),
            ),
        )
    }

    @Test
    fun decoderInitFailureDoesNotInvalidateAttachmentCache() {
        assertFalse(
            playbackErrorInvalidatesAttachmentCache(
                PlaybackException(
                    "decoder",
                    null,
                    PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
                ),
            ),
        )
    }

    @Test
    fun formatExceedsCapabilitiesDoesNotInvalidateAttachmentCache() {
        assertFalse(
            playbackErrorInvalidatesAttachmentCache(
                PlaybackException(
                    "capability",
                    null,
                    PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
                ),
            ),
        )
    }

    @Test
    fun audioTrackInitFailureDoesNotInvalidateAttachmentCache() {
        assertFalse(
            playbackErrorInvalidatesAttachmentCache(
                PlaybackException(
                    "audio",
                    null,
                    PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED,
                ),
            ),
        )
    }

    @Test
    fun remoteErrorDoesNotInvalidateAttachmentCache() {
        assertFalse(
            playbackErrorInvalidatesAttachmentCache(
                PlaybackException(
                    "remote",
                    null,
                    PlaybackException.ERROR_CODE_REMOTE_ERROR,
                ),
            ),
        )
    }
}
