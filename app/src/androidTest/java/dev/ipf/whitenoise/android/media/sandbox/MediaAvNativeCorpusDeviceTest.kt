package dev.ipf.whitenoise.android.media.sandbox

import android.graphics.Color
import android.os.Process
import android.system.OsConstants
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.ipf.whitenoise.android.PullRequestDeviceSmoke
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Tiny synthetic common-format checks; real camera/HDR/performance qualification remains separate. */
@RunWith(AndroidJUnit4::class)
@PullRequestDeviceSmoke
@MediaContainmentAcceptance
class MediaAvNativeCorpusDeviceTest {
    @Test
    fun commonVideoFormatsReturnRedPixelsFromFreshRestrictedWorkers() {
        for (name in listOf("red-h264.mp4", "red-vp9.webm", "red-av1.webm", "red-hevc.mp4")) {
            withFixture(name) { source ->
                val previous = IsolatedMediaDecoder.lastAvIsolation
                val probe = requireNotNull(IsolatedMediaDecoder.probe(source, 64, true)) { name }
                try {
                    restricted(previous)
                    assertTrue("Duration: $name", probe.durationMs in 800L..1500L)
                    val poster = requireNotNull(probe.poster) { name }
                    assertEquals(64, poster.width)
                    assertEquals(48, poster.height)
                    val pixel = poster.getPixel(32, 24)
                    assertTrue("Red channel: $name", Color.red(pixel) > 200)
                    assertTrue("Green channel: $name", Color.green(pixel) < 50)
                    assertTrue("Blue channel: $name", Color.blue(pixel) < 50)
                } finally {
                    probe.poster?.recycle()
                }
            }
        }
    }

    @Test
    fun commonAudioFormatsReturnFiniteWaveformsAndDurations() {
        for (name in listOf("tone-aac.m4a", "tone-opus.ogg", "tone-mp3.mp3", "tone-pcm.wav")) {
            withFixture(name) { source ->
                val beforeWaveform = IsolatedMediaDecoder.lastAvIsolation
                val waveform = requireNotNull(IsolatedMediaDecoder.waveform(source)) { name }
                restricted(beforeWaveform)
                assertEquals(64, waveform.size)
                assertTrue("Waveform range: $name", waveform.all { it.isFinite() && it in 0f..1f })
                assertTrue("Waveform signal: $name", waveform.any { it > 0.05f })
                val beforeProbe = IsolatedMediaDecoder.lastAvIsolation
                val probe = requireNotNull(IsolatedMediaDecoder.probe(source, 32, false)) { name }
                try {
                    restricted(beforeProbe)
                    assertTrue("Duration: $name", probe.durationMs in 800L..1500L)
                } finally {
                    probe.poster?.recycle()
                }
            }
        }
    }

    private fun withFixture(
        name: String,
        checkOutput: (File) -> Unit,
    ) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        IsolatedMediaDecoder.install(context)
        val source = File.createTempFile("native-corpus-", name.substringAfterLast('.'), context.cacheDir)
        val canary = File(context.filesDir, "media-containment-canary")
        var ownsCanary = false
        try {
            check(canary.createNewFile()) { "Another fixture owns the private canary" }
            ownsCanary = true
            canary.writeText("synthetic-native-corpus-canary")
            instrumentation.context.assets.open("native-av-corpus/$name").use { input ->
                source.outputStream().use { output -> input.copyTo(output) }
            }
            checkOutput(source)
        } finally {
            val sourceDeleted = source.delete()
            val canaryDeleted = !ownsCanary || canary.delete()
            check(sourceDeleted && canaryDeleted) { "Synthetic fixture cleanup failed" }
        }
    }

    private fun restricted(previous: WorkerIsolation?) {
        val worker = requireNotNull(IsolatedMediaDecoder.lastAvIsolation)
        assertNotSame(previous, worker)
        assertNotEquals(Process.myUid(), worker.uid)
        assertTrue(worker.isolated)
        assertFalse(worker.internetGranted)
        assertFalse(worker.runtimeInitialized)
        assertTrue(worker.privateCanaryDenied)
        assertEquals(OsConstants.EBADF, worker.inputWriteErrno)
    }
}
