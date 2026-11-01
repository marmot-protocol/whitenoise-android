package dev.ipf.whitenoise.android.audio

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.ipf.whitenoise.android.PullRequestDeviceSmoke
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

@RunWith(AndroidJUnit4::class)
@PullRequestDeviceSmoke
class AttachmentPcmDecoderDeviceTest {
    @Test
    fun monoFramesCrossChunkBoundary() =
        runBlocking {
            withPcmFile(16_000, 1, 15 * 16_000 + 7) { file, expected ->
                val received = ByteArrayOutputStream()
                val sizes = mutableListOf<Int>()
                decodeAttachmentPcm(file) { chunk ->
                    assertEquals(16_000, chunk.sampleRateHz)
                    assertEquals(1, chunk.channelCount)
                    sizes.add(chunk.bytes.size)
                    received.write(chunk.bytes)
                }
                assertEquals(listOf(480_000, 14), sizes)
                assertArrayEquals(expected, received.toByteArray())
            }
        }

    @Test
    fun stereoNormalizesAcrossChunks() =
        runBlocking {
            val frames = 30 * 48_000 + 7
            val sample = { frame: Int, channel: Int ->
                val bias =
                    when (channel) {
                        0 -> -300
                        else -> 300
                    }
                (frame / 3 % 20_001 - 10_000 + bias).toShort()
            }
            val expected = ByteBuffer.allocate(((frames + 2) / 3) * 2).order(ByteOrder.LITTLE_ENDIAN)
            repeat((frames + 2) / 3) { frame -> expected.putShort((frame % 20_001 - 10_000).toShort()) }
            withPcmFile(48_000, 2, frames, sample) { file, _ ->
                val received = ByteArrayOutputStream()
                val sizes = mutableListOf<Int>()
                decodeAttachmentPcm(file) { chunk ->
                    assertEquals(16_000, chunk.sampleRateHz)
                    assertEquals(1, chunk.channelCount)
                    sizes.add(chunk.bytes.size)
                    received.write(chunk.bytes)
                }
                assertEquals(listOf(480_000, 480_000, 6), sizes)
                assertArrayEquals(expected.array(), received.toByteArray())
            }
        }

    @Test
    fun fractionalRateKeepsPhaseTail() =
        runBlocking {
            val frames = 15 * 44_100 + 3
            val sample = { frame: Int, _: Int -> (frame % 441 * 50 - 11_000).toShort() }
            val expected = ByteBuffer.allocate((15 * 16_000 + 2) * 2).order(ByteOrder.LITTLE_ENDIAN)
            repeat(15 * 16_000 + 1) { frame ->
                expected.putShort(((50 * (frame % 160) * 441 - 11_000 * 160) / 160).toShort())
            }
            // The last requested position lies after the final source frame, but before its duration ends.
            expected.putShort(-10_900)
            withPcmFile(44_100, 1, frames, sample) { file, _ ->
                val received = ByteArrayOutputStream()
                val sizes = mutableListOf<Int>()
                decodeAttachmentPcm(file) { chunk ->
                    assertEquals(16_000, chunk.sampleRateHz)
                    assertEquals(1, chunk.channelCount)
                    sizes.add(chunk.bytes.size)
                    received.write(chunk.bytes)
                }
                assertEquals(listOf(480_000, 4), sizes)
                assertArrayEquals(expected.array(), received.toByteArray())
            }
        }

    @Test
    fun upsamplingKeepsFinalDuration() =
        runBlocking {
            val sample = { frame: Int, _: Int -> (frame * 200 - 100).toShort() }
            withPcmFile(8_000, 1, 2, sample) { file, _ ->
                val received = ByteArrayOutputStream()
                decodeAttachmentPcm(file) { received.write(it.bytes) }
                val expected = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
                expected
                    .putShort(-100)
                    .putShort(0)
                    .putShort(100)
                    .putShort(100)
                assertArrayEquals(expected.array(), received.toByteArray())
            }
        }

    @Test
    fun rawPcm24IsConvertedToPcm16() =
        runBlocking {
            val source = byteArrayOf(0, 0, 0, 0, 0, 64, 0, 0, -64)
            withPcmBytes(16_000, 1, 24, source) { file ->
                val received = ByteArrayOutputStream()
                decodeAttachmentPcm(file) { received.write(it.bytes) }
                val expected = ByteBuffer.allocate(6).order(ByteOrder.LITTLE_ENDIAN)
                expected.putShort(0).putShort(16_384).putShort(-16_384)
                assertArrayEquals(expected.array(), received.toByteArray())
            }
        }

    @Test
    fun consumerFailureReleasesCodec() =
        runBlocking {
            withPcmFile(16_000, 1, 15 * 16_000 + 7) { file, expected ->
                val refusal = IOException("Consumer refused PCM")
                val failure = runCatching { decodeAttachmentPcm(file) { throw refusal } }.exceptionOrNull()
                assertSame(refusal, failure)
                val received = ByteArrayOutputStream()
                decodeAttachmentPcm(file) { received.write(it.bytes) }
                assertArrayEquals(expected, received.toByteArray())
            }
        }

    @Test
    fun cancellationReleasesAdmission() =
        runBlocking {
            withPcmFile(16_000, 1, 15 * 16_000 + 7) { file, expected ->
                val entered = CompletableDeferred<Unit>()
                val decoding =
                    launch {
                        decodeAttachmentPcm(file) {
                            entered.complete(Unit)
                            awaitCancellation()
                        }
                    }
                entered.await()
                decoding.cancelAndJoin()
                val received = ByteArrayOutputStream()
                decodeAttachmentPcm(file) { received.write(it.bytes) }
                assertArrayEquals(expected, received.toByteArray())
            }
        }

    private suspend fun withPcmFile(
        rate: Int,
        channels: Int,
        frames: Int,
        sample: (Int, Int) -> Short = { frame, channel -> (frame * 37 + channel * 101).toShort() },
        block: suspend (File, ByteArray) -> Unit,
    ) {
        val pcm = ByteBuffer.allocate(frames * channels * 2).order(ByteOrder.LITTLE_ENDIAN)
        repeat(frames) { frame ->
            repeat(channels) { channel -> pcm.putShort(sample(frame, channel)) }
        }
        withPcmBytes(rate, channels, 16, pcm.array()) { file -> block(file, pcm.array()) }
    }

    private suspend fun withPcmBytes(
        rate: Int,
        channels: Int,
        bits: Int,
        pcm: ByteArray,
        block: suspend (File) -> Unit,
    ) {
        val frameBytes = channels * (bits / 8)
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray(Charsets.US_ASCII)).putInt(36 + pcm.size)
        header.put("WAVEfmt ".toByteArray(Charsets.US_ASCII)).putInt(16)
        header
            .putShort(1)
            .putShort(channels.toShort())
            .putInt(rate)
            .putInt(rate * frameBytes)
        header.putShort(frameBytes.toShort()).putShort(bits.toShort())
        header.put("data".toByteArray(Charsets.US_ASCII)).putInt(pcm.size)
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        dev.ipf.whitenoise.android.media.sandbox.IsolatedMediaDecoder
            .install(context)
        val file = File.createTempFile("attachment-pcm-", ".wav", context.cacheDir)
        try {
            file.outputStream().use { output ->
                output.write(header.array())
                output.write(pcm)
            }
            block(file)
        } finally {
            file.delete()
        }
    }
}
