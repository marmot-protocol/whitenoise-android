package dev.ipf.whitenoise.android.audio

import dev.ipf.whitenoise.android.media.sandbox.MediaAdmissionFailure
import dev.ipf.whitenoise.android.media.sandbox.RawOutputFailure
import dev.ipf.whitenoise.android.media.sandbox.rawPlaybackFailure
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class VoicePlaybackFailurePolicyTest {
    @Test
    fun onlyExplicitDeviceOutputFailureKeepsTheCompressedVoiceCache() {
        val output = voicePlaybackErrorCode(RawOutputFailure("device speed refused", IllegalArgumentException()))
        assertFalse(voicePlaybackErrorInvalidatesCache(output))
        val decode = voicePlaybackErrorCode(IOException("selected source decoder failed"))
        assertTrue(voicePlaybackErrorInvalidatesCache(decode))
        assertTrue(voicePlaybackErrorInvalidatesCache(VoicePlaybackPlayer.SOURCE_ERROR))
        assertTrue(voicePlaybackErrorInvalidatesCache(100))
        // A generic source error is never ignored merely because a nested cause resembles output failure.
        assertTrue(voicePlaybackErrorInvalidatesCache(voicePlaybackErrorCode(IOException(RawOutputFailure("nested")))))
    }

    @Test
    fun unresolvedPriorAdmissionRetainsAnUnopenedHealthySourceAndGenericIoStillInvalidates() {
        val admission = MediaAdmissionFailure("Previous media worker lifetime is unresolved")
        val reported = rawPlaybackFailure(admission)
        assertSame(admission, reported)
        assertFalse(voicePlaybackErrorInvalidatesCache(voicePlaybackErrorCode(reported)))
        val source = rawPlaybackFailure(IOException("source read failed", admission))
        assertTrue(voicePlaybackErrorInvalidatesCache(voicePlaybackErrorCode(source)))
        assertTrue(voicePlaybackErrorInvalidatesCache(voicePlaybackErrorCode(IOException("unknown IO"))))
    }
}
