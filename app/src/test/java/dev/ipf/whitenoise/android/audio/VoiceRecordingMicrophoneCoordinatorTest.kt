package dev.ipf.whitenoise.android.audio

import android.content.Context
import android.media.AudioManager
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.nio.file.Files
import kotlin.coroutines.CoroutineContext

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

    /**
     * Tearing down while a replacement start waits for a discarded take, with the discard's cleanup and
     * the cancelled start resuming on Main in that order, still frees the shared microphone (#3707).
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun teardownDuringPendingReplacementStartReleasesTheLease() =
        runTest {
            val coordinator = MicrophoneCaptureCoordinator()
            val main = QueueDispatcher()
            val recorderDispatcher = StandardTestDispatcher(testScheduler)
            val output = Files.createTempDirectory("voice-teardown-coordinator-test").toFile()
            val context = ApplicationProvider.getApplicationContext<Context>()
            val discarded = FakeVoiceRecordingSession(onCancel = {})
            val replacement = FakeVoiceRecordingSession(onCancel = {})
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
                    mainDispatcher = main,
                    recorderDispatcher = recorderDispatcher,
                )

            try {
                assertTrue(controller.start())
                // The first take prepares on the recorder dispatcher, then publishes itself on Main.
                main.drain()
                runCurrent()
                main.drain()
                assertTrue(discarded.started)
                controller.cancel()
                assertTrue(controller.start())
                // The replacement start suspends waiting for the discard.
                main.drain()
                // The discard's native close runs and queues its Main cleanup.
                runCurrent()
                // Teardown cancels the waiting start; its resumption queues behind the discard cleanup.
                controller.release()
                main.drain()
                runCurrent()
                main.drain()

                assertTrue(discarded.cancelled)
                assertFalse("the cancelled start never opened a recorder", replacement.started)
                assertTrue("dictation can take the mic after teardown", coordinator.tryAcquire(Any()))
            } finally {
                output.deleteRecursively()
            }
        }

    /** Leaving the screen while a discarded take still closes keeps the lease until that close has finished. */
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun teardownDuringDiscardKeepsTheLeaseUntilTheRecorderCloses() =
        runTest {
            val coordinator = MicrophoneCaptureCoordinator()
            val dispatcher = StandardTestDispatcher(testScheduler)
            val output = Files.createTempDirectory("voice-discard-teardown-test").toFile()
            val discarded = FakeVoiceRecordingSession(onCancel = {})
            val controller = discardTestController(coordinator, dispatcher, output, discarded)

            try {
                assertTrue(controller.start())
                runCurrent()
                controller.cancel()
                controller.release()

                assertTrue("the closing recorder still holds the mic", coordinator.isOwnedBy(controller))
                runCurrent()
                assertTrue(discarded.cancelled)
                assertFalse(coordinator.isOwnedBy(controller))
            } finally {
                output.deleteRecursively()
            }
        }

    /** A replacement start refused audio focus during a discard leaves the lease to the discard's cleanup. */
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun deniedReplacementDuringDiscardKeepsTheLeaseUntilTheRecorderCloses() =
        runTest {
            val coordinator = MicrophoneCaptureCoordinator()
            val dispatcher = StandardTestDispatcher(testScheduler)
            val output = Files.createTempDirectory("voice-discard-denied-test").toFile()
            val discarded = FakeVoiceRecordingSession(onCancel = {})
            val controller = discardTestController(coordinator, dispatcher, output, discarded, onError = {})
            val context = ApplicationProvider.getApplicationContext<Context>()
            val audio = shadowOf(context.getSystemService(AudioManager::class.java))

            try {
                assertTrue(controller.start())
                runCurrent()
                controller.cancel()
                audio.setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_FAILED)
                assertFalse(controller.start())

                assertTrue("the closing recorder still holds the mic", coordinator.isOwnedBy(controller))
                runCurrent()
                assertFalse(coordinator.isOwnedBy(controller))
            } finally {
                controller.release()
                output.deleteRecursively()
            }
        }

    /** A controller whose first recorder is [discarded], with Main and recorder work on [dispatcher]. */
    private fun TestScope.discardTestController(
        coordinator: MicrophoneCaptureCoordinator,
        dispatcher: CoroutineDispatcher,
        output: File,
        discarded: FakeVoiceRecordingSession,
        onError: (Throwable) -> Unit = { throw it },
    ) = VoiceRecordingController(
        context = ApplicationProvider.getApplicationContext(),
        outputDirectory = output,
        scope = this,
        onPermissionRequest = { true },
        onRecordingComplete = { _, _ -> error("No take is finished here") },
        onError = onError,
        microphoneCaptures = coordinator,
        recorderFactory = { _, _, _ -> discarded },
        mainDispatcher = dispatcher,
        recorderDispatcher = dispatcher,
    )

    /** A Main stand-in that runs queued work only when the test drains it, to pin an interleaving. */
    private class QueueDispatcher : CoroutineDispatcher() {
        private val queue = ArrayDeque<Runnable>()

        override fun dispatch(
            context: CoroutineContext,
            block: Runnable,
        ) {
            queue.addLast(block)
        }

        /** Runs queued work, including work it queues, until nothing is left. */
        fun drain() {
            while (queue.isNotEmpty()) queue.removeFirst().run()
        }
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
