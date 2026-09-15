package dev.ipf.whitenoise.android.ui.conversation.composer

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.ipf.whitenoise.android.audio.MicrophoneCaptureCoordinator
import dev.ipf.whitenoise.android.audio.VoiceRecordingController
import dev.ipf.whitenoise.android.audio.VoiceRecordingResult
import dev.ipf.whitenoise.android.audio.VoiceRecordingSession
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** Checks native recording completion, explicit sending and revocation without real microphone or network I/O. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
@OptIn(ExperimentalCoroutinesApi::class)
class VoiceRecordingReviewTest {
    @get:Rule val files = TemporaryFolder()

    /** Disposal retains only the presence marker, while explicit discard and native acceptance clear it. */
    @Test
    fun reviewPresenceTracksNativeOwnershipWithoutPreservingPrivateAudio() =
        runTest {
            val marker = VoiceReviewRecoveryMarker("owner")

            /** Opens the review step under test. */
            fun review() =
                VoiceRecordingReview(
                    this,
                    { true },
                    { _, _, _, accepted -> accepted(true) },
                    playback = FakePlayback(),
                    onReviewPresenceChanged = marker::updatePresence,
                )
            val disposed = review()
            val privateTake = take()
            assertTrue(disposed.offer(privateTake, 2_000L))
            assertTrue(marker.hasReview)
            disposed.release()
            assertFalse(privateTake.exists())
            assertTrue(marker.hasReview)

            val discarded = review()
            assertTrue(discarded.offer(take(), 2_000L))
            discarded.discard(requireNotNull(discarded.clip))
            assertFalse(marker.hasReview)
            discarded.release()

            val accepted = review()
            assertTrue(accepted.offer(take(), 2_000L))
            accepted.send(requireNotNull(accepted.clip))
            assertFalse(marker.hasReview)
            accepted.release()
        }

    /** Releasing/Stopping the real controller frees its microphone and opens review without sending. */
    @Test
    fun finalizedNativeCaptureRequiresAnExplicitSend() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            val output = files.newFolder()
            val file = File(output, "voice-review.m4a").apply { writeBytes(byteArrayOf(1, 2, 3)) }
            val coordinator = MicrophoneCaptureCoordinator()
            val sent = mutableListOf<File>()
            val review =
                VoiceRecordingReview(
                    this,
                    { true },
                    { take, _, _, accepted ->
                        sent += take
                        accepted(true)
                    },
                    playback = FakePlayback(),
                )
            val controller =
                VoiceRecordingController(
                    context = ApplicationProvider.getApplicationContext<Context>(),
                    outputDirectory = output,
                    scope = this,
                    onPermissionRequest = { true },
                    onRecordingComplete = { take, duration -> review.offer(take, duration) },
                    onError = { throw it },
                    microphoneCaptures = coordinator,
                    recorderFactory = { _, _, _ ->
                        object : VoiceRecordingSession {
                            /** Fake playback: starts. */
                            override fun start() = Unit

                            /** Fake playback: stops. */
                            override fun stop() = VoiceRecordingResult(file, 1_500L)

                            /** Fake operation: cancels. */
                            override fun cancel() = Unit
                        }
                    },
                    mainDispatcher = dispatcher,
                    recorderDispatcher = dispatcher,
                )
            try {
                assertTrue(controller.start())
                runCurrent()
                controller.lock()
                controller.stop()
                assertNull(review.clip)
                advanceTimeBy(VoiceRecordingController.RECORDING_TAIL_MS)
                runCurrent()
                assertFalse(controller.isRecording)
                assertFalse(coordinator.isOwnedBy(controller))
                assertNotNull(review.clip)
                assertTrue(sent.isEmpty())
                val clip = checkNotNull(review.clip)
                assertTrue(review.send(clip))
                assertFalse(review.send(clip))
                assertEquals(listOf(file), sent)
            } finally {
                review.release()
                controller.release()
                runCurrent()
            }
        }

    /** Old screen callbacks cannot publish or send, including while the native sender is reading. */
    @Test
    fun ownerChangeAndDisposalRevokeCompletionAndPostReadSendAdmission() =
        runTest {
            var current = true
            var admission: (() -> Boolean)? = null
            val review = VoiceRecordingReview(this, { current }, { _, _, guard, _ -> admission = guard })
            val file = take()
            assertTrue(review.offer(file, 2_000L))
            val clip = checkNotNull(review.clip)
            assertTrue(review.send(clip))
            assertTrue(checkNotNull(admission).invoke())
            current = false
            assertFalse(checkNotNull(admission).invoke())
            val stale = take()
            assertFalse(review.offer(stale, 1_000L))
            assertFalse(stale.exists())
            current = true
            review.release()
            assertFalse(checkNotNull(admission).invoke())
            assertFalse(review.offer(take(), 1_000L))
        }

    /** A replaced/discarded take never aliases the new take through captured controls. */
    @Test
    fun replacementRejectsOldActionsAndRerecordDiscardsOnlyItsOwnTake() =
        runTest {
            var sends = 0
            var starts = 0
            val review =
                VoiceRecordingReview(
                    this,
                    { true },
                    { _, _, _, accepted ->
                        sends++
                        accepted(true)
                    },
                    playback = FakePlayback(),
                )
            val first = take()
            review.offer(first, 1_000L)
            val old = checkNotNull(review.clip)
            val second = take()
            review.offer(second, 2_000L)
            val current = checkNotNull(review.clip)
            assertFalse(first.exists())
            assertFalse(review.send(old))
            assertFalse(review.discard(old))
            review.recordAgain(old) { starts++ }
            assertTrue(second.exists())
            review.recordAgain(current) { starts++ }
            assertFalse(second.exists())
            assertNull(review.clip)
            assertEquals(1, starts)
            assertEquals(0, sends)
        }

    /** Disposing while playback prepares cancels its job and releases only this preview key. */
    @Test
    fun playbackPreparationAndUnsentFileAreCancelledOnDisposal() =
        runTest {
            val pending = CompletableDeferred<Boolean>()
            val playback = FakePlayback(pending)
            val review = VoiceRecordingReview(this, { true }, { _, _, _, _ -> error("No Send") }, playback = playback)
            val file = take()
            review.offer(file, 1_000L)
            val clip = checkNotNull(review.clip)
            review.togglePlayback(clip)
            runCurrent()
            assertEquals(listOf(clip.key), playback.played)
            review.release()
            runCurrent()
            pending.complete(true)
            runCurrent()
            assertEquals(listOf(clip.key), playback.stopped)
            assertFalse(file.exists())
            assertNull(review.clip)
        }

    /** Rejection retains the file; acceptance alone clears it, and rapid or stale callbacks cannot send twice. */
    @Test
    fun queueRejectionPreservesTheTakeAndAcceptanceTransfersItExactlyOnce() =
        runTest {
            val completions = mutableListOf<(Boolean) -> Unit>()
            val guards = mutableListOf<() -> Boolean>()
            var failures = 0
            val review =
                VoiceRecordingReview(
                    this,
                    { true },
                    { _, _, guard, completion ->
                        guards += guard
                        completions += completion
                    },
                    onSendFailure = { failures++ },
                    playback = FakePlayback(),
                )
            val file = take()
            review.offer(file, 1_000L)
            val clip = checkNotNull(review.clip)
            assertTrue(review.send(clip))
            assertTrue(review.isSending)
            assertFalse(review.send(clip))
            assertEquals(1, completions.size)
            assertTrue(file.exists())
            assertTrue(review.clip === clip)
            completions[0](false)
            assertFalse(review.isSending)
            assertFalse(guards[0]())
            assertTrue(file.exists())
            assertEquals(1, failures)
            assertTrue(review.send(clip))
            completions[0](true)
            assertTrue(review.isSending)
            assertTrue(review.clip === clip)
            assertTrue(file.exists())
            completions[1](true)
            assertFalse(review.isSending)
            assertNull(review.clip)
            assertFalse(file.exists())
            assertFalse(guards[1]())
            completions[1](false)
            assertEquals(1, failures)
        }

    /** A synchronous dispatch failure is retryable; discard revokes an asynchronous pending queue attempt. */
    @Test
    fun dispatchFailureAndDiscardDuringPreparationNeverLoseOrSendTheWrongTake() =
        runTest {
            var throws = true
            var guard: (() -> Boolean)? = null
            var completion: ((Boolean) -> Unit)? = null
            var failures = 0
            val review =
                VoiceRecordingReview(
                    this,
                    { true },
                    { _, _, canSend, onQueued ->
                        if (throws) throw IllegalStateException("dispatch failed")
                        guard = canSend
                        completion = onQueued
                    },
                    onSendFailure = { failures++ },
                    playback = FakePlayback(),
                )
            val file = take()
            review.offer(file, 1_000L)
            val clip = checkNotNull(review.clip)
            review.send(clip)
            assertTrue(review.clip === clip)
            assertTrue(file.exists())
            assertFalse(review.isSending)
            assertEquals(1, failures)
            throws = false
            assertTrue(review.send(clip))
            assertTrue(checkNotNull(guard)())
            assertTrue(review.discard(clip))
            assertFalse(checkNotNull(guard)())
            val replacement = take()
            review.offer(replacement, 2_000L)
            checkNotNull(completion)(false)
            assertTrue(replacement.exists())
            assertTrue(review.clip?.file === replacement)
            assertFalse(review.isSending)
            assertFalse(file.exists())
        }

    /** Native quick restart cannot expose the preceding finalized tail over an active new recording. */
    @Test
    fun newRecordingDropsOnlyThePreviousUnsentReview() =
        runTest {
            val review = VoiceRecordingReview(this, { true }, { _, _, _, _ -> error("No Send") })
            val oldFile = take()
            review.offer(oldFile, 1_000L)
            val old = checkNotNull(review.clip)
            review.discardForNewRecording()
            assertFalse(oldFile.exists())
            assertFalse(review.send(old))
            val newFile = take()
            review.offer(newFile, 2_000L)
            assertTrue(review.clip?.file === newFile)
            assertTrue(newFile.exists())
            review.release()
            assertFalse(newFile.exists())
        }

    /** Creates a private stand-in for one finalized native take. */
    private fun take(): File = files.newFile().apply { writeBytes(byteArrayOf(1)) }

    private class FakePlayback(
        private val pending: CompletableDeferred<Boolean>? = null,
    ) : VoiceReviewPlayback {
        val played = mutableListOf<String>()
        val stopped = mutableListOf<String>()

        /** Fake playback: plays. */
        override suspend fun play(clip: VoiceReviewClip): Boolean {
            played += clip.key
            return pending?.await() ?: true
        }

        /** Fake playback: pauses. */
        override fun pause(key: String) = Unit

        /** Fake playback: stops. */
        override fun stop(key: String) {
            stopped += key
        }

        /** Fake playback: whether it is playing. */
        override fun isPlaying(key: String) = false
    }
}
