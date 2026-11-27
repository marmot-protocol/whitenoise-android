package dev.ipf.whitenoise.android.media.sandbox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.time.Duration

/** Real pinned Sonic processing; the device seam records accepted PCM, not an assumed audible hardware head. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30, 36])
class RawPcmOutputSonicTest {
    /** Public Video And Voice Position Getters Retain The Last Good Clock After Device Fault. */
    @Test fun publicVideoAndVoicePositionGettersRetainTheLastGoodClockAfterDeviceFault() {
        val context =
            androidx.test.core.app.ApplicationProvider
                .getApplicationContext<android.content.Context>()
        for (voice in listOf(false, true)) {
            val device = Device()
            val output = RawPcmOutput(8000, 1, device)
            output.play()
            output.write(ByteArray(160) { if (it % 2 == 1) 32 else 0 }, 0)
            val uri = android.net.Uri.parse("file:///fixture.mp4")
            val video = if (!voice) IsolatedAttachmentPlayer(context, uri) else null
            val audio =
                if (voice) {
                    val constructor =
                        dev.ipf.whitenoise.android.audio.RawVoicePlaybackPlayer::class.java
                            .getDeclaredConstructor(android.content.Context::class.java, java.io.File::class.java)
                    constructor.isAccessible = true
                    constructor.newInstance(context, java.io.File("/fixture.wav"))
                } else {
                    null
                }
            val adapter = audio ?: checkNotNull(video)
            val engine = field(adapter, "engine") as RawAvPlaybackEngine
            val renderer = field(engine, "renderer") as RawAvRenderSession
            setField(engine, "playIntent", true)
            setField(engine, "status", RawAvPlaybackEngine.Status.Ready)
            setField(engine, "headerReady", true)
            setField(engine, "activation", 1L)
            setField(engine, "seekRevision", 1L)
            setField(renderer, "activeRun", 1L)
            setField(renderer, "appliedSeek", 1L)
            setField(renderer, "sourceHasAudio", true)
            setField(renderer, "pcm", output)
            device.fail = true
            val calls = device.calls
            try {
                repeat(3) {
                    if (voice) {
                        assertEquals(10, checkNotNull(audio).currentPosition)
                    } else {
                        assertEquals(10L, checkNotNull(video).currentPosition)
                    }
                    assertEquals(10L, engine.snapshot().positionMs)
                }
                assertEquals(calls, device.calls)
                val failure = assertThrows(RawOutputFailure::class.java) { output.prepareForWait(false) }
                engine.failed(1, 1, failure)
                assertTrue(engine.snapshot().error is RawOutputFailure)
                assertFalse(engine.snapshot().status == RawAvPlaybackEngine.Status.Ended)
                if (voice) checkNotNull(audio).currentPosition else checkNotNull(video).currentPosition
            } finally {
                video?.release()
                audio?.release()
            }
            assertTrue(device.closeAttempted)
        }
    }

    /**
     * Reads a private fixture field so assertions can inspect the exact owner rather than a replacement
     * instance.
     */
    private fun field(
        target: Any,
        name: String,
    ): Any? =
        target.javaClass.getDeclaredField(name).let {
            it.isAccessible = true
            it.get(target)
        }

    /**
     * Installs controlled fixture state through reflection; production visibility and synchronization stay
     * unchanged.
     */
    private fun setField(
        target: Any,
        name: String,
        value: Any,
    ) {
        target.javaClass.getDeclaredField(name).let {
            it.isAccessible = true
            it.set(target, value)
        }
    }

    @Test fun presentationClockLagsMixerAndHoldsEndOfStreamUntilTheAudibleTail() {
        val device = Device().apply { presented = 0L }
        RawPcmOutput(8000, 1, device).use { output ->
            output.play()
            assertEquals(160, output.write(ByteArray(160), 0))
            output.prepareForWait(true)
            assertTrue(device.head >= 80)
            assertEquals(0L, output.positionUs())
            assertFalse(output.drained())

            device.presented = 40L
            output.prepareForWait(true)
            assertEquals(5000L, output.positionUs())
            assertFalse(output.drained())

            device.presented = 80L
            output.prepareForWait(true)
            assertEquals(10_000L, output.positionUs())
            assertTrue(output.drained())
        }
    }

    @Test fun underrunRejectsStaleSamplesUntilTheHardwareClockAdvancesAgain() {
        var sampleTimeNs = 1_000_000_000L
        var sampleFrame = 8_000L
        val clock =
            RawPcmPresentationClock(8_000) { timestamp ->
                timestamp.nanoTime = sampleTimeNs
                timestamp.framePosition = sampleFrame
                true
            }
        assertEquals(8_000L, clock.frames(sampleTimeNs, underruns = 0, mixerHead = 8_400))
        sampleTimeNs += 50_000_000L
        sampleFrame += 400
        assertEquals(8_400L, clock.frames(sampleTimeNs, underruns = 0, mixerHead = 8_800))

        val stalledAtNs = sampleTimeNs + 1_000_000_000L
        assertEquals(16_400L, clock.frames(stalledAtNs, underruns = 0, mixerHead = 9_200))
        sampleTimeNs += 100_000_000L
        sampleFrame = 9_000L
        assertNull(clock.frames(stalledAtNs, underruns = 1, mixerHead = 9_200))

        // Android can report the final old frame with a new timestamp while the audio pipeline is idle.
        sampleTimeNs = stalledAtNs + 50_000_000L
        sampleFrame = 9_200L
        assertNull(clock.frames(sampleTimeNs, underruns = 1, mixerHead = 9_440))

        // Android 11 can pause and play the same track again after priming.
        clock.reset()
        sampleTimeNs += 50_000_000L
        assertNull(clock.frames(sampleTimeNs, underruns = 1, mixerHead = 9_440))

        sampleTimeNs += 50_000_000L
        sampleFrame = 9_280L
        assertEquals(9_280L, clock.frames(sampleTimeNs, underruns = 1, mixerHead = 9_680))
    }

    @Test fun endOfStreamWaitsForRouteLatencyBeforeTheFirstTimestamp() {
        val device = Device().apply { fallbackPresentationLatencyMs = 150 }
        RawPcmOutput(8000, 1, device).use { output ->
            output.play()
            assertEquals(160, output.write(ByteArray(160), 0))
            output.prepareForWait(true)
            assertTrue(device.head >= 80)
            assertEquals(0L, output.positionUs())
            assertFalse(output.drained())

            ShadowSystemClock.advanceBy(Duration.ofMillis(150))
            output.prepareForWait(true)
            assertEquals(10_000L, output.positionUs())
            assertTrue(output.drained())
        }
    }

    /** Tiny Nonzero Audio Is Flushed At Every Supported Representative Rate. */
    @Test fun tinyNonzeroAudioIsFlushedAtEverySupportedRepresentativeRate() {
        for (rate in listOf(.25f, .5f, 1f, 2f)) {
            val device = Device()
            val output = RawPcmOutput(8000, 1, device)
            output.speed(rate)
            output.play()
            val pcm = byteArrayOf(0, 32, 0, 32)
            var offset = 0
            repeat(100) {
                if (offset < pcm.size) offset += output.write(pcm, offset)
            }
            assertEquals(pcm.size, offset)
            repeat(100) { output.prepareForWait(true) }
            assertTrue(output.drained())
            assertEquals(250L, output.positionUs())
            val nonzero = device.audio.toByteArray().any { it != 0.toByte() }
            assertTrue("Sonic must expose nonzero transformed PCM at $rate", nonzero)
            assertEquals((2 / rate).toInt() * 2, device.audio.size())
            output.close()
        }
    }

    /** Partial And Zero Writes Retain Actual Processor Output Until Accepted. */
    @Test fun partialAndZeroWritesRetainActualProcessorOutputUntilAccepted() {
        val device = Device().apply { maxWrite = 0 }
        val output = RawPcmOutput(8000, 1, device)
        output.speed(.25f)
        output.play()
        val pcm = byteArrayOf(0, 32, 0, 32)
        var offset = 0
        repeat(20) { if (offset < pcm.size) offset += output.write(pcm, offset) }
        output.prepareForWait(true)
        assertFalse(output.drained())
        assertEquals(0, device.audio.size())
        device.maxWrite = 2
        repeat(100) { output.prepareForWait(true) }
        assertTrue(output.drained())
        assertEquals(16, device.audio.size())
        assertTrue(device.audio.toByteArray().any { it != 0.toByte() })
        output.close()
    }

    /** Snapshots Perform No Hardware Operations And Typed Fault Retires At Render Boundary. */
    @Test fun snapshotsPerformNoHardwareOperationsAndTypedFaultRetiresAtRenderBoundary() {
        val device = Device()
        val output = RawPcmOutput(8000, 1, device)
        output.play()
        output.write(byteArrayOf(0, 32, 0, 32), 0)
        val before = output.positionUs()
        device.fail = true
        val calls = device.calls
        repeat(10) {
            assertEquals(before, output.positionUs())
            output.writtenPositionUs()
            output.bufferedPositionUs()
        }
        assertEquals(calls, device.calls)
        assertThrows(RawOutputFailure::class.java) { output.prepareForWait(false) }
        assertEquals(before, output.positionUs())
        output.close()
        assertTrue(device.pauseAttempted)
        assertTrue(device.closeAttempted)
    }

    /** Restoration Refusal Never Runs Inside Clock Snapshots. */
    @Test fun restorationRefusalNeverRunsInsideClockSnapshots() {
        val device = Device().apply { autoAdvance = false }
        val output = RawPcmOutput(8000, 1, device)
        output.play()
        output.write(ByteArray(160) { if (it % 2 == 1) 32 else 0 }, 0)
        device.head = 40
        device.failRestore = true
        val calls = device.calls
        assertEquals(0L, output.positionUs())
        assertEquals(calls, device.calls)
        val failure = assertThrows(RawOutputFailure::class.java) { output.prepareForWait(false) }
        assertTrue(rawPlaybackFailure(failure) is RawOutputFailure)
        assertEquals(0L, output.positionUs())
        output.close()
        assertTrue(device.pauseAttempted)
        assertTrue(device.closeAttempted)
    }

    /** Impossible Hardware Head Is Typed Only At The Serialized Boundary. */
    @Test fun impossibleHardwareHeadIsTypedOnlyAtTheSerializedBoundary() {
        val device = Device()
        val output = RawPcmOutput(8000, 1, device)
        output.play()
        output.write(byteArrayOf(0, 32, 0, 32), 0)
        val lastGood = output.positionUs()
        device.head = Int.MAX_VALUE
        assertEquals(lastGood, output.positionUs())
        val failure = assertThrows(RawOutputFailure::class.java) { output.prepareForWait(false) }
        assertTrue(rawPlaybackFailure(failure) is RawOutputFailure)
        assertEquals(lastGood, output.positionUs())
        assertThrows(RawOutputFailure::class.java) { output.drained() }
        output.close()
        assertTrue(device.closeAttempted)
    }

    /** Rate Transition Keeps Physical Device And Monotone Source Clock. */
    @Test fun rateTransitionKeepsPhysicalDeviceAndMonotoneSourceClock() {
        val device = Device()
        val output = RawPcmOutput(8000, 1, device)
        output.play()
        val input = ByteArray(160) { if (it % 2 == 1) 32 else 0 }
        assertEquals(input.size, output.write(input, 0))
        output.speed(.5f)
        output.speed(.25f)
        var offset = 0
        var previous = output.positionUs()
        repeat(100) {
            if (offset < input.size) offset += output.write(input, offset)
            output.prepareForWait(false)
            assertTrue(output.positionUs() >= previous)
            previous = output.positionUs()
        }
        assertEquals(input.size, offset)
        repeat(100) { output.prepareForWait(true) }
        assertTrue(output.drained())
        assertEquals(20_000L, output.positionUs())
        assertEquals(800, device.audio.size())
        assertFalse(device.closeAttempted)
        output.close()
    }

    internal class Device : RawPcmDevice {
        override val normalFrames = 2000
        override val capacityFrames = 2000
        override val legacyPriming = true
        val audio = ByteArrayOutputStream()
        var maxWrite = Int.MAX_VALUE
        var autoAdvance = true
        var failRestore = false
        var head = 0
        var presented: Long? = null
        override var fallbackPresentationLatencyMs = 0L
        var calls = 0
        var fail = false
        var pauseAttempted = false
        var closeAttempted = false

        private fun access() {
            calls++
            check(!fail)
        }

        override fun startThresholdOne(): Boolean {
            access()
            return true
        }

        override fun resize(frames: Int): Int {
            access()
            check(!failRestore || frames != normalFrames)
            return frames.coerceAtLeast(16)
        }

        override fun headPosition(): Int {
            access()
            return head
        }

        override fun presentationFrames(mixerHead: Long): Long? = presented

        override fun write(
            buffer: ByteBuffer,
            byteCount: Int,
        ): Int {
            access()
            val count = minOf(byteCount, maxWrite) / 2 * 2
            val bytes = ByteArray(count)
            buffer.get(bytes)
            audio.write(bytes)
            if (autoAdvance) head += count / 2
            return count
        }

        override fun writeSilence(
            bytes: ByteArray,
            byteCount: Int,
        ): Int {
            access()
            val count = minOf(byteCount, maxWrite) / 2 * 2
            if (autoAdvance) head += count / 2
            return count
        }

        override fun play() {
            access()
        }

        override fun pause() {
            pauseAttempted = true
            access()
        }

        override fun volume(value: Float) {
            access()
        }

        override fun close() {
            closeAttempted = true
            access()
        }
    }
}
