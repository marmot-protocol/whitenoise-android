package dev.ipf.whitenoise.android.media.sandbox

import android.graphics.Bitmap
import android.graphics.Color
import android.os.Process
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.ipf.whitenoise.android.PullRequestDeviceSmoke
import dev.ipf.whitenoise.android.media.sandbox.MediaWorkerGenerationProbes.assertDeathPrecedesEachNextInput
import dev.ipf.whitenoise.android.media.sandbox.MediaWorkerGenerationProbes.awaitDeathRecord
import dev.ipf.whitenoise.android.media.sandbox.MediaWorkerGenerationProbes.awaitDecodeSent
import dev.ipf.whitenoise.android.media.sandbox.MediaWorkerGenerationProbes.awaitExitRecord
import dev.ipf.whitenoise.android.media.sandbox.MediaWorkerGenerationProbes.awaitExitRecordFor
import dev.ipf.whitenoise.android.media.sandbox.MediaWorkerGenerationProbes.awaitRetireSent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File

/** Real Binder/descriptor/pixel transport. No account fixture, relay or destructive app reset. */
@RunWith(AndroidJUnit4::class)
@PullRequestDeviceSmoke
@MediaContainmentAcceptance
// These scenarios share real Binder lifecycle probes; added function docs must not force a fixture split.
@Suppress("LargeClass")
class IsolatedMediaDecoderDeviceTest {
    /** Each still input gets a fresh generation whose exact Binder death precedes its pixels and the next input. */
    @Test
    fun eachStillInputWaitsForItsExactBinderDeathBeforeReturningPixels() {
        val context = MediaWorkerBaselineBinding(InstrumentationRegistry.getInstrumentation().targetContext)
        val transport = IsolatedMediaTransport(context)
        try {
            for (index in 0 until 3) {
                val decoded = requireNotNull(transport.decode(png(), 1, false, MediaDecodePolicy.DECODE))
                try {
                    val inputs = context.inputEndpoints()
                    assertEquals(index + 1, inputs.size)
                    val producer = inputs.last()
                    assertEquals(1, producer.getInt("decodes"))
                    assertFalse(context.isEndpointAlive(producer.getInt("generation")))
                    assertEquals(Color.RED, decoded.getPixel(0, 0))
                    awaitEndpointDeath(context, producer.getInt("generation"))
                } finally {
                    decoded.recycle()
                }
            }
            assertDeathPrecedesEachNextInput(context.inputEndpoints())
        } finally {
            transport.close()
            context.closeObservers()
        }
    }

    @Test
    fun stillPixelsCrossAnIsolatedUidWithoutNetworkOrRuntimeInitialization() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val canary = java.io.File(context.filesDir, "media-containment-canary")
        check(!canary.exists())
        canary.writeText("synthetic-private-canary")
        IsolatedMediaDecoder.install(context)
        var decoded: Bitmap? = null
        try {
            val result = requireNotNull(IsolatedMediaDecoder.decode(png(), 1))
            decoded = result
            assertEquals(1, result.width)
            assertEquals(Color.RED, result.getPixel(0, 0))
            val worker = requireNotNull(IsolatedMediaDecoder.lastIsolation)
            assertNotEquals(Process.myUid(), worker.uid)
            assertTrue(worker.isolated)
            assertFalse(worker.internetGranted)
            assertFalse(worker.runtimeInitialized)
            assertEquals(android.system.OsConstants.EBADF, worker.inputWriteErrno)
            assertTrue(canary.isFile)
            assertTrue(
                "Private canary must stay inaccessible (errno=${worker.privateCanaryErrno})",
                worker.privateCanaryDenied,
            )
            val jpeg = requireNotNull(IsolatedMediaDecoder.decode(imageBytes(Bitmap.CompressFormat.JPEG, Color.RED), 2))
            try {
                assertTrue(Color.red(jpeg.getPixel(0, 0)) >= 250)
                assertTrue(Color.green(jpeg.getPixel(0, 0)) <= 5)
                assertEquals(255, Color.alpha(jpeg.getPixel(0, 0)))
            } finally {
                jpeg.recycle()
            }
            val transparent =
                requireNotNull(
                    IsolatedMediaDecoder.decode(imageBytes(Bitmap.CompressFormat.PNG, Color.argb(128, 255, 0, 0)), 2),
                )
            try {
                assertEquals(128, Color.alpha(transparent.getPixel(0, 0)))
                assertTrue(Color.red(transparent.getPixel(0, 0)) >= 254)
            } finally {
                transparent.recycle()
            }
        } finally {
            decoded?.recycle()
            canary.delete()
        }
    }

    @Test
    fun malformedInputCannotDisableHealthySubsequentImages() {
        IsolatedMediaDecoder.install(InstrumentationRegistry.getInstrumentation().targetContext)
        assertNull(IsolatedMediaDecoder.decode(byteArrayOf(1, 2, 3), 128))
        val decoded = IsolatedMediaDecoder.decode(png(), 128)
        assertNotNull(decoded)
        decoded?.recycle()
    }

    @Test
    fun actualWorkerMalformedRepliesAreRejectedAndCannotPoisonRecovery() {
        IsolatedMediaDecoder.install(InstrumentationRegistry.getInstrumentation().targetContext)
        val caller = Process.myPid()
        for (mode in 1..4) {
            assertNull(IsolatedMediaDecoder.exerciseMalformedReplyForTest(mode))
            val until = android.os.SystemClock.elapsedRealtime() + 5000L
            var recovered: Bitmap? = null
            while (recovered == null && android.os.SystemClock.elapsedRealtime() < until) {
                recovered = IsolatedMediaDecoder.decode(png(), 2)
                if (recovered == null) Thread.sleep(25L)
            }
            requireNotNull(recovered).recycle()
            assertEquals(caller, Process.myPid())
        }
    }

    /** Account reset retires a hung worker well before its watchdog and the next input uses a new generation. */
    @Test
    fun accountResetRetiresThePriorWorkerAndKeepsRenderingAvailable() =
        withObservedImageOwner { context, owner ->
            val executor =
                java.util.concurrent.Executors
                    .newSingleThreadExecutor()
            try {
                val started = android.os.SystemClock.elapsedRealtime()
                val blocked = executor.submit { IsolatedMediaDecoder.exerciseWatchdogForTest() }
                awaitDispatch(context, owner)
                val hung = context.inputEndpoints().single().getInt("generation")
                assertTrue(context.isEndpointAlive(hung))
                val instrumentation = InstrumentationRegistry.getInstrumentation()
                instrumentation.runOnMainSync {
                    val app =
                        instrumentation.targetContext.applicationContext as
                            dev.ipf.whitenoise.android.WhiteNoiseApplication
                    app.appState.clearCrossAccountCachesForTest()
                }
                blocked.get(5, java.util.concurrent.TimeUnit.SECONDS)
                awaitEndpointDeath(context, hung)
                assertTrue(android.os.SystemClock.elapsedRealtime() - started < MediaDecodePolicy.WATCHDOG_MS)
                requireNotNull(IsolatedMediaDecoder.decode(png(), 128)).recycle()
                val inputs = context.inputEndpoints()
                assertEquals(2, inputs.size)
                awaitEndpointDeath(context, inputs.last().getInt("generation"))
                assertDeathPrecedesEachNextInput(context.inputEndpoints())
            } finally {
                executor.shutdownNow()
            }
        }

    /** The worker watchdog kills only the hung worker, its verified death admits a fresh generation. */
    @Test
    fun watchdogTerminatesOnlyTheWorkerAndPermitsVerifiedDeathRecovery() =
        withObservedImageOwner { context, _ ->
            val caller = Process.myPid()
            IsolatedMediaDecoder.exerciseWatchdogForTest()
            awaitEndpointDeath(context, context.inputEndpoints().single().getInt("generation"))
            requireNotNull(IsolatedMediaDecoder.decode(png(), 128)).recycle()
            assertEquals(caller, Process.myPid())
            val inputs = context.inputEndpoints()
            assertEquals(2, inputs.size)
            awaitEndpointDeath(context, inputs.last().getInt("generation"))
            assertDeathPrecedesEachNextInput(context.inputEndpoints())
        }

    /** Isolated Images And Platform Video Use Selected Descriptors. */
    @Test
    fun isolatedImagesAndPlatformVideoUseSelectedDescriptors() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        IsolatedMediaDecoder.install(context)
        val info = requireNotNull(IsolatedMediaDecoder.inspect(png()))
        assertEquals(2, info.width)
        assertEquals(2, info.height)
        assertEquals("image/png", info.mime)
        assertEquals(1, IsolatedMediaDecoder.exif(png()))
        val svg =
            """<svg xmlns="http://www.w3.org/2000/svg" width="2" height="2">
            <rect width="2" height="2" fill="blue"/></svg>""".toByteArray()
        val raster = requireNotNull(IsolatedMediaDecoder.svg(svg))
        try {
            assertEquals(MediaDecodePolicy.SVG_EDGE, raster.width)
            assertEquals(Color.BLUE, raster.getPixel(raster.width / 2, raster.height / 2))
        } finally {
            raster.recycle()
        }
        val video = File.createTempFile("isolated-video", ".mp4", context.cacheDir)
        try {
            video.writeBytes(verifiedVideoFixture())
            val canary = beginMediaCanary(context)
            try {
                val previousReceipt = IsolatedMediaDecoder.lastAvIsolation
                val probe = requireNotNull(IsolatedMediaDecoder.probe(video, edge = 96, poster = true))
                assertEquals(320, probe.width)
                assertEquals(240, probe.height)
                assertTrue(probe.durationMs >= 9000L)
                assertRestrictedMediaWorker(previousReceipt)
                val poster = requireNotNull(probe.poster)
                try {
                    assertTrue(poster.width in 1..96 && poster.height in 1..96)
                    val pixel = poster.getPixel(poster.width / 2, poster.height / 2)
                    assertTrue(Color.red(pixel) <= 5 && Color.green(pixel) <= 5 && Color.blue(pixel) <= 5)
                    assertEquals(255, Color.alpha(pixel))
                } finally {
                    poster.recycle()
                }
            } finally {
                canary.delete()
            }
        } finally {
            video.delete()
        }
    }

    /** Audio Waveform And Duration Use Only The Selected Descriptor. */
    @Test
    fun audioWaveformAndDurationUseOnlyTheSelectedDescriptor() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        IsolatedMediaDecoder.install(context)
        val audio = File.createTempFile("isolated-waveform", ".wav", context.cacheDir)
        val canary = beginMediaCanary(context)
        try {
            audio.writeBytes(pcmWaveFixture())
            val previousReceipt = IsolatedMediaDecoder.lastAvIsolation
            val waveform = requireNotNull(IsolatedMediaDecoder.waveform(audio))
            assertRestrictedMediaWorker(previousReceipt)
            assertEquals(dev.ipf.whitenoise.android.audio.AudioWaveformExtractor.BARS, waveform.size)
            assertTrue(waveform.all { it.isFinite() && it in 0f..1f })
            assertTrue(waveform.any { it > 0.05f })
            val previousProbeReceipt = IsolatedMediaDecoder.lastAvIsolation
            val probe = requireNotNull(IsolatedMediaDecoder.probe(audio, edge = 32, poster = false))
            assertNull(probe.poster)
            assertTrue(probe.durationMs in 100L..300L)
            assertRestrictedMediaWorker(previousProbeReceipt)
        } finally {
            canary.delete()
            audio.delete()
        }
    }

    /** Malformed Automatic Media Cannot Poison Healthy Source Recovery. */
    @Test
    fun malformedAutomaticMediaCannotPoisonHealthySourceRecovery() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        IsolatedMediaDecoder.install(context)
        val source = File.createTempFile("native-media-recovery", ".bin", context.cacheDir)
        val canary = beginMediaCanary(context)
        try {
            repeat(3) {
                source.writeBytes(verifiedVideoFixture().copyOf(16))
                assertNull(IsolatedMediaDecoder.probe(source, 32, true))
                source.writeBytes(verifiedVideoFixture())
                val previousReceipt = IsolatedMediaDecoder.lastAvIsolation
                val healthy = requireNotNull(IsolatedMediaDecoder.probe(source, 32, true))
                try {
                    assertNotNull(healthy.poster)
                    assertTrue(healthy.durationMs >= 9000L)
                    assertRestrictedMediaWorker(previousReceipt)
                } finally {
                    healthy.poster?.recycle()
                }
            }
        } finally {
            canary.delete()
            source.delete()
        }
    }

    /** Paused Seek Poster Uses The Requested Frame And Can Recover To The Start. */
    @Test
    fun pausedSeekPosterUsesTheRequestedFrameAndCanRecoverToTheStart() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        IsolatedMediaDecoder.install(context)
        val source = File.createTempFile("native-seek-colors", ".mp4", context.cacheDir)
        val canary = beginMediaCanary(context)
        try {
            source.writeBytes(android.util.Base64.decode(SEEK_COLORS, android.util.Base64.DEFAULT))
            val uri = android.net.Uri.fromFile(source)
            listOf(-1L, Long.MAX_VALUE).forEach { invalid ->
                val previousReceipt = IsolatedMediaDecoder.lastAvIsolation
                assertNull(IsolatedMediaDecoder.probeUriAt(context.contentResolver, uri, invalid, 32))
                org.junit.Assert.assertSame(previousReceipt, IsolatedMediaDecoder.lastAvIsolation)
            }
            val targets = listOf(0L to Color.RED, 1500L to Color.BLUE, 1999L to Color.BLUE, 0L to Color.RED)
            targets.forEach { (time, color) ->
                val previousReceipt = IsolatedMediaDecoder.lastAvIsolation
                val probe =
                    requireNotNull(IsolatedMediaDecoder.probeUriAt(context.contentResolver, uri, time, 32))
                val bitmap = requireNotNull(probe.poster)
                try {
                    val pixel = bitmap.getPixel(bitmap.width / 2, bitmap.height / 2)
                    assertTrue(kotlin.math.abs(Color.red(pixel) - Color.red(color)) < 50)
                    assertTrue(kotlin.math.abs(Color.blue(pixel) - Color.blue(color)) < 50)
                    assertRestrictedMediaWorker(previousReceipt)
                } finally {
                    bitmap.recycle()
                }
            }
        } finally {
            canary.delete()
            source.delete()
        }
    }

    /** Starts the fixture canary used to observe whether media parsing reaches the privileged process. */
    private fun beginMediaCanary(context: android.content.Context): File =
        File(context.filesDir, "media-containment-canary").also {
            check(it.createNewFile()) { "Another fixture owns the private canary" }
            it.writeText("synthetic-private-media-canary")
        }

    /** A fresh automatic A/V receipt is required; a still-image reply cannot satisfy this proof. */
    private fun assertRestrictedMediaWorker(previousReceipt: WorkerIsolation?) {
        val worker = requireNotNull(IsolatedMediaDecoder.lastAvIsolation)
        org.junit.Assert.assertNotSame(previousReceipt, worker)
        assertNotEquals(Process.myUid(), worker.uid)
        assertTrue(worker.isolated)
        assertFalse(worker.internetGranted)
        assertFalse(worker.runtimeInitialized)
        assertTrue(worker.privateCanaryDenied)
        assertEquals(android.system.OsConstants.EBADF, worker.inputWriteErrno)
    }

    @Test
    fun blockedParsersDoNotHoldProviderAdmission() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val sources = MediaSourceAccess(context)
        val workers =
            java.util.concurrent.Executors
                .newFixedThreadPool(2)
        val file = File.createTempFile("provider-gate", ".png", context.cacheDir)
        try {
            file.writeBytes(png())
            val uri = android.net.Uri.fromFile(file)
            for (mediaFirst in listOf(true, false)) {
                val entered = java.util.concurrent.CountDownLatch(1)
                val release = java.util.concurrent.CountDownLatch(1)
                try {
                    val blocked =
                        workers.submit<Int?> {
                            val callback: (android.os.ParcelFileDescriptor) -> Int? = {
                                entered.countDown()
                                release.await(10, java.util.concurrent.TimeUnit.SECONDS)
                                7
                            }
                            if (mediaFirst) {
                                sources.withMediaUriInput(context.contentResolver, uri, callback)
                            } else {
                                sources.withUriInput(context.contentResolver, uri, callback)
                            }
                        }
                    assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS))
                    val second =
                        workers.submit<Long?> {
                            if (mediaFirst) {
                                sources.withUriInput(context.contentResolver, uri) { it.statSize }
                            } else {
                                sources.withMediaUriInput(context.contentResolver, uri) { it.statSize }
                            }
                        }
                    assertEquals(file.length(), second.get(5, java.util.concurrent.TimeUnit.SECONDS))
                    release.countDown()
                    assertEquals(7, blocked.get(5, java.util.concurrent.TimeUnit.SECONDS))
                } finally {
                    release.countDown()
                }
            }
        } finally {
            workers.shutdownNow()
            sources.close()
            file.delete()
        }
    }

    /** An interrupted dispatched request retires only its own generation, and the lane recovers after its death. */
    @Test
    fun interruptedImageRequestRetiresOnlyItsWorkerAndCanRecover() {
        val context = MediaWorkerBaselineBinding(InstrumentationRegistry.getInstrumentation().targetContext)
        val transport = IsolatedMediaTransport(context, "interrupted_images")
        val executor =
            java.util.concurrent.Executors
                .newSingleThreadExecutor()
        val ended = java.util.concurrent.CountDownLatch(1)
        try {
            requireNotNull(transport.decode(png(), 2, false, MediaDecodePolicy.DECODE)).recycle()
            awaitEndpointDeath(context, context.inputEndpoints().single().getInt("generation"))
            val blocked =
                executor.submit {
                    try {
                        transport.decode(byteArrayOf(1), 1, false, MediaDecodePolicy.DEBUG_HANG)
                    } finally {
                        ended.countDown()
                    }
                }
            awaitDispatch(context, transport, expectedInputs = 2)
            val hung =
                context
                    .inputEndpoints()
                    .also { assertEquals(2, it.size) }
                    .last()
                    .getInt("generation")
            blocked.cancel(true)
            assertTrue(ended.await(5, java.util.concurrent.TimeUnit.SECONDS))
            awaitEndpointDeath(context, hung)
            val recoveryDeadline = android.os.SystemClock.elapsedRealtime() + 5000L
            var recovered: Bitmap? = null
            while (recovered == null && android.os.SystemClock.elapsedRealtime() < recoveryDeadline) {
                recovered = transport.decode(png(), 2, false, MediaDecodePolicy.DECODE)
                if (recovered == null) Thread.sleep(20L)
            }
            requireNotNull(recovered).recycle()
            val inputs = context.inputEndpoints()
            assertEquals(3, inputs.size)
            awaitEndpointDeath(context, inputs.last().getInt("generation"))
            assertDeathPrecedesEachNextInput(context.inputEndpoints())
        } finally {
            executor.shutdownNow()
            transport.close()
            context.closeObservers()
        }
    }

    /** Source A's retained worker state and descriptor are absent from source B's fresh worker. */
    @Test
    fun retainedStateAndDescriptorOfOneSourceNeverReachTheNextSourcesWorker() {
        val context = MediaWorkerBaselineBinding(InstrumentationRegistry.getInstrumentation().targetContext)
        val transport = IsolatedMediaTransport(context, "canary_images")
        try {
            val sourceA = png(Color.RED)
            val sourceB = png(Color.BLUE)
            val first = WorkerCanary.parse(transport.debugProbeForTest(sourceA, MediaDecodePolicy.DEBUG_CANARY))
            val second = WorkerCanary.parse(transport.debugProbeForTest(sourceB, MediaDecodePolicy.DEBUG_CANARY))
            assertEquals(WorkerCanary(first.pid, "-", "-"), first)
            // Under a reused worker, B would report A's digest and could read A's retained descriptor.
            assertEquals(WorkerCanary(second.pid, "-", "-"), second)
            assertNotEquals(first.pid, second.pid)
            val inputs = context.inputEndpoints()
            assertEquals(2, inputs.size)
            assertDeathPrecedesEachNextInput(inputs)
            // OS process-lifetime evidence, beyond the Binder callback: A's process is gone. A cooperative
            // self-exit has no reliable ApplicationExitInfo record on every API, so none is required here.
            assertFalse(WorkerCanary.processAlive(first.pid))
        } finally {
            transport.close()
            context.closeObservers()
        }
    }

    /** A worker that ignores RETIRE keeps the next input out until Android stops it, only its death admits input. */
    @Test
    fun noncooperativeRetiringWorkerBlocksTheNextInputUntilAndroidStopsIt() {
        val context = MediaWorkerBaselineBinding(InstrumentationRegistry.getInstrumentation().targetContext)
        val transport = IsolatedMediaTransport(context, "noncooperative_images")
        val executor =
            java.util.concurrent.Executors
                .newSingleThreadExecutor()
        try {
            val stubborn =
                executor.submit<String?> {
                    transport.debugProbeForTest(png(), MediaDecodePolicy.DEBUG_IGNORE_RETIRE)
                }
            val held = awaitRetireSent(context)
            val queued = android.os.SystemClock.elapsedRealtimeNanos()
            requireNotNull(transport.decode(png(Color.BLUE), 2, false, MediaDecodePolicy.DECODE)).recycle()
            val canary = WorkerCanary.parse(stubborn.get(10, java.util.concurrent.TimeUnit.SECONDS))
            val inputs = context.inputEndpoints()
            assertEquals(2, inputs.size)
            val stubbornEndpoint = inputs.first()
            assertEquals(held, stubbornEndpoint.getInt("generation"))
            assertEquals(1, stubbornEndpoint.getInt("decodes"))
            assertFalse(
                "The next input completed while the stubborn worker was still alive",
                stubbornEndpoint.isNull("death_observed_elapsed_ns"),
            )
            val retired = stubbornEndpoint.getLong("retire_elapsed_ns")
            val died = stubbornEndpoint.getLong("death_observed_elapsed_ns")
            val grace =
                java.util.concurrent.TimeUnit.MILLISECONDS
                    .toNanos(MediaWorkerPool.RETIRE_ESCALATION_MS)
            assertTrue("The worker ignored RETIRE and outlived the escalation grace", died - retired >= grace)
            assertTrue("The next input was queued while the stubborn worker lived", queued < died)
            assertDeathPrecedesEachNextInput(inputs)
            assertFalse(WorkerCanary.processAlive(canary.pid))
            // Android, not the worker, ended it: a cooperative RETIRE exit is recorded as a self-signal.
            val exit = awaitExitRecord(canary.pid)
            assertTrue(exit.processName, exit.processName.endsWith(":" + stubbornEndpoint.getString("instance")))
            assertEquals(exit.description, android.app.ApplicationExitInfo.REASON_OTHER, exit.reason)
        } finally {
            executor.shutdownNow()
            transport.close()
            context.closeObservers()
        }
    }

    /** An abandoned request whose worker ignores RETIRE still holds the next input until Android stops it. */
    @Test
    fun abandonedNoncooperativeWorkerStillHoldsTheNextInputUntilItsDeath() {
        val since = System.currentTimeMillis()
        val context = MediaWorkerBaselineBinding(InstrumentationRegistry.getInstrumentation().targetContext)
        val transport = IsolatedMediaTransport(context, "abandoned_images")
        val executor =
            java.util.concurrent.Executors
                .newSingleThreadExecutor()
        try {
            val stubborn =
                executor.submit {
                    transport.decode(byteArrayOf(1), 1, false, MediaDecodePolicy.DEBUG_STUBBORN_HANG)
                }
            awaitDecodeSent(context)
            // Abandoning the request sends RETIRE without waiting, the worker ignores it and keeps running.
            stubborn.cancel(true)
            val held = awaitRetireSent(context)
            val queued = android.os.SystemClock.elapsedRealtimeNanos()
            requireNotNull(transport.decode(png(Color.BLUE), 2, false, MediaDecodePolicy.DECODE)).recycle()
            val inputs = context.inputEndpoints()
            assertEquals(2, inputs.size)
            val abandoned = inputs.first()
            assertEquals(held, abandoned.getInt("generation"))
            assertFalse(
                "The next input completed while the abandoned worker was still alive",
                abandoned.isNull("death_observed_elapsed_ns"),
            )
            val retired = abandoned.getLong("retire_elapsed_ns")
            val died = abandoned.getLong("death_observed_elapsed_ns")
            val grace =
                java.util.concurrent.TimeUnit.MILLISECONDS
                    .toNanos(MediaWorkerPool.RETIRE_ESCALATION_MS)
            assertTrue("The worker ignored RETIRE and outlived the escalation grace", died - retired >= grace)
            // Its own watchdog would end it after six seconds; the escalation must stop it far sooner.
            val watchdog =
                java.util.concurrent.TimeUnit.MILLISECONDS
                    .toNanos(MediaDecodePolicy.WATCHDOG_MS)
            assertTrue("Android stopped it before the watchdog", died - retired < watchdog / 2)
            assertTrue("The next input was requested while the abandoned worker lived", queued < died)
            // Only the admission fence separates these: the abandoned exchange never waited for the death.
            assertDeathPrecedesEachNextInput(inputs)
            val exit = awaitExitRecordFor(abandoned.getString("instance"), since)
            assertEquals(exit.description, android.app.ApplicationExitInfo.REASON_OTHER, exit.reason)
        } finally {
            executor.shutdownNow()
            transport.close()
            context.closeObservers()
        }
    }

    /** An abandoned worker that ignores RETIRE is stopped by Android on the parent's clock, with no further request. */
    @Test
    fun abandonedNoncooperativeWorkerIsStoppedWithoutAnotherRequest() {
        val since = System.currentTimeMillis()
        val context = MediaWorkerBaselineBinding(InstrumentationRegistry.getInstrumentation().targetContext)
        val transport = IsolatedMediaTransport(context, "abandoned_idle_images")
        val executor =
            java.util.concurrent.Executors
                .newSingleThreadExecutor()
        try {
            val stubborn =
                executor.submit {
                    transport.decode(byteArrayOf(2), 1, false, MediaDecodePolicy.DEBUG_STUBBORN_HANG)
                }
            awaitDecodeSent(context)
            stubborn.cancel(true)
            val held = awaitRetireSent(context)
            val endpoint = awaitDeathRecord(context, held)
            assertFalse("No request is waiting, yet the worker must die", endpoint.isNull("death_observed_elapsed_ns"))
            val retired = endpoint.getLong("retire_elapsed_ns")
            val died = endpoint.getLong("death_observed_elapsed_ns")
            val grace =
                java.util.concurrent.TimeUnit.MILLISECONDS
                    .toNanos(MediaWorkerPool.RETIRE_ESCALATION_MS)
            val watchdog =
                java.util.concurrent.TimeUnit.MILLISECONDS
                    .toNanos(MediaDecodePolicy.WATCHDOG_MS)
            assertTrue("The worker ignored RETIRE", died - retired >= grace)
            assertTrue("Android stopped it before the watchdog", died - retired < watchdog / 2)
            assertEquals(1, context.inputEndpoints().size)
            val exit = awaitExitRecordFor(endpoint.getString("instance"), since)
            assertEquals(exit.description, android.app.ApplicationExitInfo.REASON_OTHER, exit.reason)
        } finally {
            executor.shutdownNow()
            transport.close()
            context.closeObservers()
        }
    }

    /** Observe the real facade owner without changing the production context/factory contract. */
    private fun withObservedImageOwner(block: (MediaWorkerBaselineBinding, IsolatedMediaTransport) -> Unit) {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val context = MediaWorkerBaselineBinding(target)
        val field = IsolatedMediaDecoder::class.java.getDeclaredField("imageOwner").apply { isAccessible = true }
        val previous = field.get(IsolatedMediaDecoder)
        val owner = IsolatedMediaTransport(context, "observed_images")
        IsolatedMediaDecoder.clear()
        try {
            field.set(IsolatedMediaDecoder, owner)
            IsolatedMediaDecoder.install(target)
            block(context, owner)
        } finally {
            IsolatedMediaDecoder.clear()
            owner.close()
            field.set(IsolatedMediaDecoder, previous)
            IsolatedMediaDecoder.install(target)
            context.closeObservers()
        }
    }

    /** Waits for a witnessed parent-side DECODE, not merely admission before the Messenger send. */
    private fun awaitDispatch(
        context: MediaWorkerBaselineBinding,
        owner: IsolatedMediaTransport,
        expectedInputs: Int = 1,
    ) {
        val deadline = android.os.SystemClock.elapsedRealtime() + 5_000L
        while (
            (!owner.isRequestDispatchedForTest() || context.inputEndpoints().size != expectedInputs) &&
            android.os.SystemClock.elapsedRealtime() < deadline
        ) {
            android.os.SystemClock.sleep(20)
        }
        assertTrue(owner.isRequestDispatchedForTest())
        assertEquals(expectedInputs, context.inputEndpoints().size)
    }

    /** Waits for the kernel Binder death of the [generation]th binding attempt. */
    private fun awaitEndpointDeath(
        context: MediaWorkerBaselineBinding,
        generation: Int,
    ) {
        val endpoint = awaitDeathRecord(context, generation)
        assertEquals(generation, endpoint.getInt("generation"))
        assertFalse(endpoint.getBoolean("death_registration_failed"))
        assertFalse(endpoint.isNull("death_observed_elapsed_ns"))
        assertFalse(context.isEndpointAlive(generation))
    }

    private fun png(color: Int = Color.RED): ByteArray = imageBytes(Bitmap.CompressFormat.PNG, color)

    /** Produces deterministic bounded encoded-image fixture bytes for the selected decode operation. */
    private fun imageBytes(
        format: Bitmap.CompressFormat,
        color: Int,
    ): ByteArray {
        val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        return try {
            bitmap.eraseColor(color)
            ByteArrayOutputStream().also { bitmap.compress(format, 100, it) }.toByteArray()
        } finally {
            bitmap.recycle()
        }
    }

    private companion object {
        // Synthetic 2-second H.264 fixture: one red second followed by one blue second, four frames/second.
        const val SEEK_COLORS =
            "AAAAIGZ0eXBpc29tAAACAGlzb21pc28yYXZjMW1wNDEAAAODbW9vdgAAAGxtdmhkAAAAAAAAAAAAAAAAAAAD6AAAB9AAAQAAAQAA" +
                "AAAAAAAAAAAAAAEAAAAAAAAAAAAAAAAAAAABAAAAAAAAAAAAAAAAAABAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAgAA" +
                "Aq50cmFrAAAAXHRraGQAAAADAAAAAAAAAAAAAAABAAAAAAAAB9AAAAAAAAAAAAAAAAAAAAAAAAEAAAAAAAAAAAAAAAAAAAABAAAA" +
                "AAAAAAAAAAAAAABAAAAAAEAAAAAwAAAAAAAkZWR0cwAAABxlbHN0AAAAAAAAAAEAAAfQAAAgAAABAAAAAAImbWRpYQAAACBtZGhk" +
                "AAAAAAAAAAAAAAAAAABAAAAAgABVxAAAAAAALWhkbHIAAAAAAAAAAHZpZGUAAAAAAAAAAAAAAABWaWRlb0hhbmRsZXIAAAAB0W1p" +
                "bmYAAAAUdm1oZAAAAAEAAAAAAAAAAAAAACRkaW5mAAAAHGRyZWYAAAAAAAAAAQAAAAx1cmwgAAAAAQAAAZFzdGJsAAAAwXN0c2QA" +
                "AAAAAAAAAQAAALFhdmMxAAAAAAAAAAEAAAAAAAAAAAAAAAAAAAAAAEAAMABIAAAASAAAAAAAAAABFUxhdmM2MS4xOS4xMDEgbGli" +
                "eDI2NAAAAAAAAAAAAAAAGP//AAAAN2F2Y0MBZAAK/+EAGWdkAAqscgREewEQAAADABAAAAMAgPEiWEYBAAdo6EOCMsiw/fj4AAAA" +
                "ABBwYXNwAAAAAQAAAAEAAAAUYnRydAAAAAAAAA2AAAAAAAAAABhzdHRzAAAAAAAAAAEAAAAIAAAQAAAAABRzdHNzAAAAAAAAAAEA" +
                "AAABAAAAOGN0dHMAAAAAAAAABQAAAAEAACAAAAAAAQAAgAAAAAABAAAwAAAAAAIAAAAAAAAAAwAAEAAAAAAcc3RzYwAAAAAAAAAB" +
                "AAAAAQAAAAgAAAABAAAANHN0c3oAAAAAAAAAAAAAAAgAAALdAAAAGgAAABwAAAANAAAADQAAABEAAAARAAAAEQAAABRzdGNvAAAA" +
                "AAAAAAEAAAOzAAAAYXVkdGEAAABZbWV0YQAAAAAAAAAhaGRscgAAAAAAAAAAbWRpcmFwcGwAAAAAAAAAAAAAAAAsaWxzdAAAACSp" +
                "dG9vAAAAHGRhdGEAAAABAAAAAExhdmY2MS43LjEwMwAAAAhmcmVlAAADaG1kYXQAAAKvBgX//6vcRem95tlIt5Ys2CDZI+7veDI2" +
                "NCAtIGNvcmUgMTY0IHIzMTA4IDMxZTE5ZjkgLSBILjI2NC9NUEVHLTQgQVZDIGNvZGVjIC0gQ29weWxlZnQgMjAwMy0yMDIzIC0g" +
                "aHR0cDovL3d3dy52aWRlb2xhbi5vcmcveDI2NC5odG1sIC0gb3B0aW9uczogY2FiYWM9MSByZWY9MTYgZGVibG9jaz0xOjA6MCBh" +
                "bmFseXNlPTB4MzoweDEzMyBtZT11bWggc3VibWU9MTAgcHN5PTEgcHN5X3JkPTEuMDA6MC4wMCBtaXhlZF9yZWY9MSBtZV9yYW5n" +
                "ZT0yNCBjaHJvbWFfbWU9MSB0cmVsbGlzPTIgOHg4ZGN0PTEgY3FtPTAgZGVhZHpvbmU9MjEsMTEgZmFzdF9wc2tpcD0xIGNocm9t" +
                "YV9xcF9vZmZzZXQ9LTIgdGhyZWFkcz0xIGxvb2thaGVhZF90aHJlYWRzPTEgc2xpY2VkX3RocmVhZHM9MCBucj0wIGRlY2ltYXRl" +
                "PTEgaW50ZXJsYWNlZD0wIGJsdXJheV9jb21wYXQ9MCBjb25zdHJhaW5lZF9pbnRyYT0wIGJmcmFtZXM9OCBiX3B5cmFtaWQ9MiBi" +
                "X2FkYXB0PTIgYl9iaWFzPTAgZGlyZWN0PTMgd2VpZ2h0Yj0xIG9wZW5fZ29wPTAgd2VpZ2h0cD0yIGtleWludD0yNTAga2V5aW50" +
                "X21pbj00IHNjZW5lY3V0PTQwIGludHJhX3JlZnJlc2g9MCByY19sb29rYWhlYWQ9NjAgcmM9Y3JmIG1idHJlZT0xIGNyZj0xOC4w" +
                "IHFjb21wPTAuNjAgcXBtaW49MCBxcG1heD02OSBxcHN0ZXA9NCBpcF9yYXRpbz0xLjQwIGFxPTE6MS4wMACAAAAAJmWIgQACX/7u" +
                "gr4FNs0QPwcsKsvJ+Zz46oL00L6dDupUQrYoBcbBAAAAFkGaCOxjQfAS4FAB/A+Ae6CH//7WqQ4AAAAYQZ4QZxBH/84kP+6Kw9VH" +
                "IeKGnLsL+5gvAAAACQGeGC6IIf8BWwAAAAkBnhhOiCH/AVsAAAANAZ4YjUgh/9gK/+55uQAAAA0BnhitSCH/2Ar/7nm5AAAADQGe" +
                "GM1IIf/YCv/uebk="
    }
}
