package dev.ipf.whitenoise.android.media.sandbox

import android.graphics.Bitmap
import android.graphics.Color
import android.os.Process
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.ipf.whitenoise.android.PullRequestDeviceSmoke
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
class IsolatedMediaDecoderDeviceTest {
    @Test
    fun eachStillInputWaitsForItsExactBinderDeathBeforeReturningPixels() {
        val context = MediaWorkerBaselineBinding(InstrumentationRegistry.getInstrumentation().targetContext)
        val transport = IsolatedMediaTransport(context)
        try {
            for (index in 0 until 2) {
                val decoded = requireNotNull(transport.decode(png(), 1, false, MediaDecodePolicy.DECODE))
                try {
                    assertFalse(context.isEndpointAlive(index + 1))
                    assertEquals(Color.RED, decoded.getPixel(0, 0))
                    val deadline = android.os.SystemClock.elapsedRealtime() + 1_000
                    var endpoint = context.snapshot().getJSONArray("endpoints").getJSONObject(index)
                    while (endpoint.isNull("death_observed_elapsed_ns") &&
                        android.os.SystemClock.elapsedRealtime() < deadline
                    ) {
                        android.os.SystemClock.sleep(5)
                        endpoint = context.snapshot().getJSONArray("endpoints").getJSONObject(index)
                    }
                    assertFalse(endpoint.getBoolean("death_registration_failed"))
                    assertFalse(endpoint.isNull("death_observed_elapsed_ns"))
                    assertEquals(index + 1, context.snapshot().getInt("binding_attempts"))
                } finally {
                    decoded.recycle()
                }
            }
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

    @Test
    fun accountResetRetiresThePriorWorkerAndKeepsRenderingAvailable() =
        withObservedImageOwner { context, owner ->
            val executor =
                java.util.concurrent.Executors
                    .newSingleThreadExecutor()
            try {
                val started = android.os.SystemClock.elapsedRealtime()
                val blocked = executor.submit { IsolatedMediaDecoder.exerciseWatchdogForTest() }
                awaitDispatch(owner)
                assertTrue(context.isEndpointAlive(1))
                assertEquals(1, context.snapshot().getInt("binding_attempts"))
                val instrumentation = InstrumentationRegistry.getInstrumentation()
                instrumentation.runOnMainSync {
                    val app =
                        instrumentation.targetContext.applicationContext as
                            dev.ipf.whitenoise.android.WhiteNoiseApplication
                    app.appState.clearCrossAccountCachesForTest()
                }
                blocked.get(5, java.util.concurrent.TimeUnit.SECONDS)
                awaitEndpointDeath(context, 1)
                assertTrue(android.os.SystemClock.elapsedRealtime() - started < MediaDecodePolicy.WATCHDOG_MS)
                requireNotNull(IsolatedMediaDecoder.decode(png(), 128)).recycle()
                assertEquals(2, context.snapshot().getInt("binding_attempts"))
                awaitEndpointDeath(context, 2)
            } finally {
                executor.shutdownNow()
            }
        }

    @Test
    fun watchdogTerminatesOnlyTheWorkerAndPermitsVerifiedDeathRecovery() =
        withObservedImageOwner { context, _ ->
            val caller = Process.myPid()
            IsolatedMediaDecoder.exerciseWatchdogForTest()
            awaitEndpointDeath(context, 1)
            requireNotNull(IsolatedMediaDecoder.decode(png(), 128)).recycle()
            assertEquals(caller, Process.myPid())
            assertEquals(2, context.snapshot().getInt("binding_attempts"))
            awaitEndpointDeath(context, 2)
        }

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
            awaitEndpointDeath(context, 1)
            val blocked =
                executor.submit {
                    try {
                        transport.decode(byteArrayOf(1), 1, false, MediaDecodePolicy.DEBUG_HANG)
                    } finally {
                        ended.countDown()
                    }
                }
            val dispatchDeadline = android.os.SystemClock.elapsedRealtime() + 5000L
            while (
                !transport.isRequestDispatchedForTest() &&
                android.os.SystemClock.elapsedRealtime() < dispatchDeadline
            ) {
                Thread.sleep(20L)
            }
            assertTrue(transport.isRequestDispatchedForTest())
            assertEquals(2, context.snapshot().getInt("binding_attempts"))
            blocked.cancel(true)
            assertTrue(ended.await(5, java.util.concurrent.TimeUnit.SECONDS))
            awaitEndpointDeath(context, 2)
            val recoveryDeadline = android.os.SystemClock.elapsedRealtime() + 5000L
            var recovered: Bitmap? = null
            while (recovered == null && android.os.SystemClock.elapsedRealtime() < recoveryDeadline) {
                recovered = transport.decode(png(), 2, false, MediaDecodePolicy.DECODE)
                if (recovered == null) Thread.sleep(20L)
            }
            requireNotNull(recovered).recycle()
            assertEquals(3, context.snapshot().getInt("binding_attempts"))
            awaitEndpointDeath(context, 3)
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
            IsolatedMediaDecoder.installForLifetimeQualification(target)
            block(context, owner)
        } finally {
            IsolatedMediaDecoder.clear()
            owner.close()
            field.set(IsolatedMediaDecoder, previous)
            IsolatedMediaDecoder.install(target)
            context.closeObservers()
        }
    }

    private fun awaitDispatch(owner: IsolatedMediaTransport) {
        val deadline = android.os.SystemClock.elapsedRealtime() + 5_000L
        while (!owner.isRequestDispatchedForTest() && android.os.SystemClock.elapsedRealtime() < deadline) {
            android.os.SystemClock.sleep(20)
        }
        assertTrue(owner.isRequestDispatchedForTest())
    }

    private fun awaitEndpointDeath(
        context: MediaWorkerBaselineBinding,
        generation: Int,
    ) {
        val deadline = android.os.SystemClock.elapsedRealtime() + 5_000L
        var endpoint = context.snapshot().getJSONArray("endpoints").getJSONObject(generation - 1)
        while (endpoint.isNull("death_observed_elapsed_ns") && android.os.SystemClock.elapsedRealtime() < deadline) {
            android.os.SystemClock.sleep(20)
            endpoint = context.snapshot().getJSONArray("endpoints").getJSONObject(generation - 1)
        }
        assertEquals(generation, endpoint.getInt("generation"))
        assertFalse(endpoint.getBoolean("death_registration_failed"))
        assertFalse(endpoint.isNull("death_observed_elapsed_ns"))
        assertFalse(context.isEndpointAlive(generation))
    }

    private fun png(): ByteArray = imageBytes(Bitmap.CompressFormat.PNG, Color.RED)

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
