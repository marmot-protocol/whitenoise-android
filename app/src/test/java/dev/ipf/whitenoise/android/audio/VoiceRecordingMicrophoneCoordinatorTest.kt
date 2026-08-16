package dev.ipf.whitenoise.android.audio

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.nio.file.Files

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class VoiceRecordingMicrophoneCoordinatorTest {
    @Test
    fun activeDictationLeaseBlocksVoiceRecorderWithoutStealingTheLease() =
        runTest {
            val coordinator = MicrophoneCaptureCoordinator()
            val dictationOwner = Any()
            val nextOwner = Any()
            val errors = mutableListOf<Throwable>()
            val output = Files.createTempDirectory("voice-coordinator-test").toFile()
            val context = ApplicationProvider.getApplicationContext<Context>()
            val controller =
                VoiceRecordingController(
                    context = context,
                    outputDirectory = output,
                    scope = this,
                    onPermissionRequest = { true },
                    onRecordingComplete = { _, _ -> error("Blocked recording must not complete") },
                    onError = errors::add,
                    microphoneCaptures = coordinator,
                )

            try {
                assertTrue(coordinator.tryAcquire(dictationOwner))

                assertFalse(controller.start())

                assertFalse(controller.isRecording)
                assertTrue(errors.single() is IllegalStateException)
                assertTrue(coordinator.isOwnedBy(dictationOwner))
                assertFalse(coordinator.tryAcquire(nextOwner))

                coordinator.release(dictationOwner)
                assertTrue(coordinator.tryAcquire(nextOwner))
            } finally {
                coordinator.release(dictationOwner)
                coordinator.release(nextOwner)
                controller.release()
                output.deleteRecursively()
            }
        }
}
