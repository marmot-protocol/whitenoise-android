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
                // Still capturing the tail: the screen reader must not speak into it yet.
                assertTrue(controller.isFinalizing)
                advanceTimeBy(VoiceRecordingController.RECORDING_TAIL_MS)
                runCurrent()
                assertEquals(1, completed.size)
                assertFalse(controller.isFinalizing)
                assertTrue(reasons.isEmpty())
                controller.start()
                runCurrent()
                controller.cancel()
                // The discarded take keeps the microphone until its recorder is released.
                assertTrue(controller.isFinalizing)
                runCurrent()
                assertFalse(controller.isFinalizing)
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

    /** Another in-app sound pauses the take in place; only the user's resume continues it. */
    @Test
    fun inAppHandoffPausesTheTakeAndUserResumeContinuesIt() =
        runTest {
            withRecording {
                controller.start()
                runCurrent()
                controller.lock()
                advanceTimeBy(1_000L)
                runCurrent()
                val capturedBeforePause = controller.elapsedMs
                var ttsSurrenders = 0
                assertTrue(
                    AudioFocusOwner.acquire(
                        owner = AudioFocusOwner.Owner.Tts,
                        audioAttributes = AudioFocusOwner.ttsSpeechAttributes,
                        focusGain = AudioManager.AUDIOFOCUS_GAIN,
                        onFocusLoss = {},
                        onOwnerSurrender = { ttsSurrenders++ },
                    ),
                )
                runCurrent()
                val session = sessions.single()
                assertTrue(controller.isRecording)
                assertTrue(controller.isPaused)
                assertTrue(controller.locked)
                assertEquals(1, session.pauses)

                advanceTimeBy(5_000L)
                runCurrent()
                assertEquals(capturedBeforePause, controller.elapsedMs)
                assertEquals(0, session.resumes)
                assertTrue(completed.isEmpty())

                assertTrue(controller.resume())
                assertEquals(1, ttsSurrenders)
                assertFalse(controller.isPaused)
                assertEquals(1, session.resumes)
                advanceTimeBy(500L)
                runCurrent()
                assertEquals(capturedBeforePause + 500L, controller.elapsedMs)

                controller.stop()
                advanceTimeBy(VoiceRecordingController.RECORDING_TAIL_MS)
                runCurrent()
                assertEquals(1, session.stops)
                assertEquals(1, completed.size)
                assertFalse(controller.isPaused)
            }
        }

    /** A take paused by another in-app sound can still be sent for review or discarded. */
    @Test
    fun pausedTakeCanBeInterruptedIntoReview() =
        runTest {
            withRecording {
                controller.start()
                runCurrent()
                AudioFocusOwner.acquire(
                    owner = AudioFocusOwner.Owner.Tts,
                    audioAttributes = AudioFocusOwner.ttsSpeechAttributes,
                    focusGain = AudioManager.AUDIOFOCUS_GAIN,
                    onFocusLoss = {},
                    onOwnerSurrender = {},
                )
                runCurrent()
                assertTrue(controller.isPaused)
                controller.interrupt()
                runCurrent()
                assertFalse(controller.isRecording)
                assertFalse(controller.isPaused)
                assertEquals(1, completed.size)
                assertFalse(coordinator.isOwnedBy(controller))
                AudioFocusOwner.release(AudioFocusOwner.Owner.Tts)
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
            AudioFocusOwner.release(AudioFocusOwner.Owner.Tts)
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
        var pauses = 0
        var resumes = 0

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

        override fun pause() {
            pauses++
        }

        override fun resume() {
            resumes++
        }

        /** Deletes an unfinished fake capture while leaving an already finalized result to its owner. */
        override fun cancel() {
            if (stops == 0) file.delete()
        }
    }
}
