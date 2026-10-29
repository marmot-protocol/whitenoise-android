package dev.ipf.whitenoise.android.audio

import android.content.Context
import android.media.AudioManager
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
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

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class VoiceRecordingInterruptionTest {
    /** Keeps a lock gesture accepted while recorder preparation is still suspended. */
    @Test
    fun lockAcceptedDuringPreparationSurvivesNativeStart() =
        runTest {
            withRecording {
                assertTrue(controller.start())
                controller.lock()
                runCurrent()
                assertTrue(controller.isRecording)
                assertTrue(controller.locked)
            }
        }

    /** Repeated stop signals deliver one interrupted take without advancing the ordinary tail delay. */
    @Test
    fun interruptPreservesExactlyOneTakeWithoutTheNormalTail() =
        runTest {
            withRecording {
                controller.start()
                runCurrent()
                advanceTimeBy(2_000L)
                controller.interrupt()
                controller.stop()
                controller.interrupt()
                runCurrent()
                assertFalse(controller.isRecording)
                assertEquals(1, sessions.single().stops)
                assertEquals(1, completed.size)
                assertEquals(listOf(VoiceRecordingStopReason.Interrupted), reasons)
                assertFalse(coordinator.isOwnedBy(controller))
            }
        }

    /** A silenced session finalizes its take without interrupting a later replacement session. */
    @Test
    fun silencedCaptureStopsTheTimerAndRetiredSessionCannotStopANewTake() =
        runTest {
            withRecording {
                controller.start()
                runCurrent()
                val first = sessions.single()
                first.captureInterrupted = true
                advanceTimeBy(50L)
                runCurrent()
                assertFalse(controller.isRecording)
                assertEquals(1, completed.size)
                controller.start()
                runCurrent()
                advanceTimeBy(100L)
                runCurrent()
                assertTrue(controller.isRecording)
                assertEquals(0, sessions.last().stops)
                assertEquals(1, completed.size)
            }
        }

    /** An interrupted pending start publishes no take and relinquishes its microphone lease. */
    @Test
    fun interruptionBeforePreparationDoesNotPublishOrLeakARecorder() =
        runTest {
            withRecording {
                controller.start()
                controller.lock()
                controller.interrupt()
                runCurrent()
                assertFalse(controller.isRecording)
                assertTrue(completed.isEmpty())
                assertTrue(reasons.isEmpty())
                assertFalse(coordinator.isOwnedBy(controller))
            }
        }

    /** Crossing the cap delivers one limit result and gives the next take a fresh warning budget. */
    @Test
    fun capWarnsThenFinalizesOnceAndTheNextTakeResetsItsBudget() =
        runTest {
            withRecording {
                controller.start()
                runCurrent()
                advanceTimeBy(269_950L)
                runCurrent()
                assertFalse(controller.isNearLimit)
                advanceTimeBy(50L)
                runCurrent()
                assertTrue(controller.isNearLimit)
                assertEquals(30_000L, controller.remainingMs)
                advanceTimeBy(30_000L)
                runCurrent()
                assertFalse(controller.isRecording)
                assertEquals(1, completed.size)
                assertEquals(listOf(VoiceRecordingStopReason.LimitReached), reasons)
                controller.stop()
                assertEquals(1, sessions.single().stops)
                controller.start()
                runCurrent()
                assertFalse(controller.isNearLimit)
                assertEquals(VoiceRecordingController.MAX_RECORDING_MS, controller.remainingMs)
            }
        }

    /** Ordinary release waits for trailing audio, while cancellation adds no review result. */
    @Test
    fun ordinaryReleaseRetainsItsTailAndCancellationNeverOffersAReview() =
        runTest {
            withRecording {
                controller.start()
                runCurrent()
                controller.stop()
                runCurrent()
                assertTrue(completed.isEmpty())
                advanceTimeBy(VoiceRecordingController.RECORDING_TAIL_MS)
                runCurrent()
                assertEquals(1, completed.size)
                assertTrue(reasons.isEmpty())
                controller.start()
                runCurrent()
                controller.cancel()
                runCurrent()
                assertEquals(1, completed.size)
                assertFalse(coordinator.isOwnedBy(controller))
            }
        }

    /** Focus loss finalizes capture, but ducking and stale listeners cannot stop the active replacement. */
    @Test
    fun audioFocusLossFinalizesAndRetiredFocusCannotInterruptReplacement() =
        runTest {
            withRecording {
                val context = ApplicationProvider.getApplicationContext<Context>()
                val audio = shadowOf(context.getSystemService(Context.AUDIO_SERVICE) as AudioManager)
                controller.start()
                runCurrent()
                val oldFocus = audio.lastAudioFocusRequest.listener
                oldFocus.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK)
                runCurrent()
                assertTrue(controller.isRecording)
                assertTrue(completed.isEmpty())
                oldFocus.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
                runCurrent()
                assertFalse(controller.isRecording)
                assertEquals(1, completed.size)
                assertEquals(listOf(VoiceRecordingStopReason.Interrupted), reasons)
                controller.start()
                runCurrent()
                oldFocus.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS)
                runCurrent()
                assertTrue(controller.isRecording)
                assertEquals(1, completed.size)
            }
        }

    /** Disposal during encoder stop removes finalized bytes that never reached the review owner. */
    @Test
    fun disposalDuringInterruptedEncoderStopDeletesTheUnclaimedFile() =
        runTest {
            withRecording {
                controller.start()
                runCurrent()
                val captured = output.listFiles()!!.single()
                sessions.single().onStop = { controller.release() }
                controller.interrupt()
                runCurrent()
                assertEquals(1, sessions.single().stops)
                assertTrue(completed.isEmpty())
                assertFalse(captured.exists())
                assertFalse(coordinator.isOwnedBy(controller))
            }
        }

    /** A failed interrupted save reports its dedicated error without claiming a reviewable take. */
    @Test
    fun failedInterruptedFinalizationReportsSaveFailureWithoutInventingAReview() =
        runTest {
            val errors = mutableListOf<Throwable>()
            withRecording(onError = errors::add) {
                controller.start()
                runCurrent()
                sessions.single().saveable = false
                controller.interrupt()
                runCurrent()
                assertTrue(completed.isEmpty())
                assertTrue(reasons.isEmpty())
                assertTrue(errors.single() is VoiceRecordingSaveException)
                assertFalse(coordinator.isOwnedBy(controller))
            }
        }

    /** Releases the fixture controller and temporary files even when a recording assertion fails. */
    private fun TestScope.withRecording(
        onError: (Throwable) -> Unit = { throw it },
        block: Fixture.() -> Unit,
    ) {
        val fixture = Fixture(this, onError)
        try {
            fixture.block()
        } finally {
            fixture.controller.release()
            runCurrent()
            fixture.output.deleteRecursively()
        }
    }

    private class Fixture(
        scope: TestScope,
        onError: (Throwable) -> Unit,
    ) {
        val output: File = Files.createTempDirectory("voice-interruption").toFile()
        val coordinator = MicrophoneCaptureCoordinator()
        val sessions = mutableListOf<Session>()
        val completed = mutableListOf<File>()
        val reasons = mutableListOf<VoiceRecordingStopReason>()
        private val dispatcher = StandardTestDispatcher(scope.testScheduler)
        val controller =
            VoiceRecordingController(
                context = ApplicationProvider.getApplicationContext<Context>(),
                outputDirectory = output,
                scope = scope,
                onPermissionRequest = { true },
                onRecordingComplete = { file, _ -> completed.add(file) },
                onError = onError,
                microphoneCaptures = coordinator,
                recorderFactory = { _, file, _ -> Session(file).also(sessions::add) },
                mainDispatcher = dispatcher,
                recorderDispatcher = StandardTestDispatcher(scope.testScheduler, name = "voice-recorder-io"),
                elapsedRealtime = { scope.testScheduler.currentTime },
                onAutomaticStop = reasons::add,
            )
    }

    private class Session(
        private val file: File,
    ) : VoiceRecordingSession {
        override var captureInterrupted = false
        var stops = 0
        var saveable = true
        var onStop: (() -> Unit)? = null

        /** Creates deterministic captured bytes without invoking the platform encoder. */
        override fun start() = file.writeText("captured audio")

        /** Counts finalization and optionally simulates an unsaveable capture or disposal race. */
        override fun stop(): VoiceRecordingResult? {
            stops++
            onStop?.invoke()
            if (!saveable) {
                file.delete()
                return null
            }
            return VoiceRecordingResult(file, 2_000L)
        }

        /** Deletes an unfinished fake capture while leaving an already finalized result to its owner. */
        override fun cancel() {
            if (stops == 0) file.delete()
        }
    }
}
