package dev.ipf.whitenoise.android.audio

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
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

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun releaseDuringStopTailKeepsLeaseUntilNativeRecorderIsCancelled() =
        runTest {
            val coordinator = MicrophoneCaptureCoordinator()
            val dispatcher = StandardTestDispatcher(testScheduler)
            val output = Files.createTempDirectory("voice-finalize-coordinator-test").toFile()
            val context = ApplicationProvider.getApplicationContext<Context>()
            lateinit var controller: VoiceRecordingController
            var leaseHeldWhenRecorderCancelled = false
            val recorder =
                FakeVoiceRecordingSession(
                    onCancel = {
                        leaseHeldWhenRecorderCancelled = coordinator.isOwnedBy(controller)
                    },
                )
            controller =
                VoiceRecordingController(
                    context = context,
                    outputDirectory = output,
                    scope = this,
                    onPermissionRequest = { true },
                    onRecordingComplete = { _, _ -> error("Disposed recording must not complete") },
                    onError = { throw it },
                    microphoneCaptures = coordinator,
                    recorderFactory = { _, _, _ -> recorder },
                    mainDispatcher = dispatcher,
                    recorderDispatcher = dispatcher,
                )

            try {
                assertTrue(controller.start())
                runCurrent()
                assertTrue(recorder.started)

                controller.stop()
                runCurrent()
                assertFalse(recorder.stopped)

                controller.release()

                assertTrue(coordinator.isOwnedBy(controller))
                runCurrent()
                assertTrue(recorder.cancelled)
                assertTrue(leaseHeldWhenRecorderCancelled)
                assertFalse(coordinator.isOwnedBy(controller))
            } finally {
                controller.release()
                output.deleteRecursively()
            }
        }

    /**
     * Starting a new take while a discarded one is still closing waits for that close before opening a
     * recorder, and the old cleanup cannot release the lease the new take now relies on (#3707).
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun replacementTakeWaitsForDiscardAndKeepsTheLease() =
        runTest {
            val coordinator = MicrophoneCaptureCoordinator()
            val dispatcher = StandardTestDispatcher(testScheduler)
            val output = Files.createTempDirectory("voice-discard-coordinator-test").toFile()
            val context = ApplicationProvider.getApplicationContext<Context>()
            val events = mutableListOf<String>()
            val discarded = FakeVoiceRecordingSession(onCancel = { events += "A cancelled" })
            val replacement = FakeVoiceRecordingSession(onCancel = {}, onStart = { events += "B started" })
            val recorders = ArrayDeque(listOf(discarded, replacement))
            val controller =
                VoiceRecordingController(
                    context = context,
                    outputDirectory = output,
                    scope = this,
                    onPermissionRequest = { true },
                    onRecordingComplete = { _, _ -> error("No take is finished here") },
                    onError = { throw it },
                    microphoneCaptures = coordinator,
                    recorderFactory = { _, _, _ -> recorders.removeFirst() },
                    mainDispatcher = dispatcher,
                    recorderDispatcher = dispatcher,
                )

            try {
                assertTrue(controller.start())
                runCurrent()
                assertTrue(discarded.started)

                controller.cancel()
                assertTrue(controller.start())
                runCurrent()

                assertEquals(listOf("A cancelled", "B started"), events)
                assertTrue(controller.isRecording)
                assertTrue("the new take keeps the shared lease", coordinator.isOwnedBy(controller))
                assertFalse(controller.isFinalizing)
            } finally {
                controller.release()
                runCurrent()
                output.deleteRecursively()
            }
            assertFalse(coordinator.isOwnedBy(controller))
        }

    private class FakeVoiceRecordingSession(
        private val onCancel: () -> Unit,
        private val onStart: () -> Unit = {},
    ) : VoiceRecordingSession {
        var started = false
        var stopped = false
        var cancelled = false

        override fun start() {
            started = true
            onStart()
        }

        override fun stop(): VoiceRecordingResult? {
            stopped = true
            return null
        }

        override fun cancel() {
            cancelled = true
            onCancel()
        }
    }
}
