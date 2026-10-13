package dev.ipf.whitenoise.android.audio

import dev.ipf.whitenoise.android.media.sandbox.IsolatedMediaDecoder
import dev.ipf.whitenoise.android.media.sandbox.MediaDecodeTransport
import dev.ipf.whitenoise.android.media.sandbox.TestMediaDecodeTransport
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class AudioWaveformRetryTest {
    @Test
    fun unavailableWorkerCanRetryAndOnlySuccessfulWaveformIsCached() =
        runTest {
            val file = File.createTempFile("waveform-retry", ".m4a")
            var calls = 0
            val expected = FloatArray(AudioWaveformExtractor.BARS) { 0.5f }
            IsolatedMediaDecoder.installTestTransport(
                object : MediaDecodeTransport by TestMediaDecodeTransport {
                    override fun waveform(file: File): FloatArray? {
                        calls += 1
                        return if (calls == 1) null else expected
                    }
                },
            )
            try {
                assertNull(AudioWaveformExtractor.decode(file))
                assertArrayEquals(expected, AudioWaveformExtractor.decode(file), 0f)
                assertArrayEquals(expected, AudioWaveformExtractor.decode(file), 0f)
                assertEquals(2, calls)
            } finally {
                IsolatedMediaDecoder.installTestTransport(TestMediaDecodeTransport)
                file.delete()
            }
        }
}
