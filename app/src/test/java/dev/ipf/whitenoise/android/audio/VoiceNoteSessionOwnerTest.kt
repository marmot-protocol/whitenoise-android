package dev.ipf.whitenoise.android.audio

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.ipf.whitenoise.android.ui.conversation.composer.VoiceRecordingReview
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** The process-level owner keeps a chat's take beyond its screen and ends it only for account or chat removal. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class VoiceNoteSessionOwnerTest {
    @get:Rule val folder = TemporaryFolder()
    private val chat = VoiceNoteKey("account", "aa".repeat(32))
    private val otherChat = VoiceNoteKey("account", "bb".repeat(32))

    @Test
    fun closingTheChatKeepsALockedTakeRecordingAndTurnsAHoldIntoADraft() =
        runTest {
            val fixture = Fixture(this)
            val locked = fixture.owner.bind(chat) { true }
            locked.start()
            runCurrent()
            locked.lock()
            fixture.owner.unbind(chat, locked)
            advanceTimeBy(1_000L)
            runCurrent()
            assertTrue(locked.isRecording)
            assertEquals(VoiceNoteActivity.Recording, fixture.owner.activity(chat))
            assertSame("returning finds the running take", locked, fixture.owner.bind(chat) { true })
            locked.stop()
            advanceTimeBy(VoiceRecordingController.RECORDING_TAIL_MS)
            runCurrent()
            assertEquals(VoiceNoteActivity.Draft, fixture.owner.activity(chat))

            val held = fixture.owner.bind(otherChat) { true }
            held.start()
            runCurrent()
            fixture.owner.unbind(otherChat, held)
            runCurrent()
            assertFalse(held.isRecording)
            assertTrue(checkNotNull(fixture.owner.draft(otherChat)).file.isFile)
            assertEquals(VoiceRecordingStopReason.Interrupted, fixture.owner.draft(otherChat)?.stopReason)
            fixture.release()
        }

    @Test
    fun aSecondChatCannotTakeTheMicrophoneAndSaysWhy() =
        runTest {
            val fixture = Fixture(this)
            val first = fixture.owner.bind(chat) { true }
            first.start()
            runCurrent()
            val second = fixture.owner.bind(otherChat) { true }
            assertFalse(second.start())
            assertEquals(1, fixture.elsewhere)
            assertTrue(fixture.errors.isEmpty())
            assertTrue(fixture.owner.recordingElsewhere(otherChat))
            assertFalse("dictation cannot take it either", fixture.coordinator.tryAcquire(Any()))
            fixture.release()
        }

    @Test
    fun aDraftSurvivesTheProcessAndIsDeletedOnlyBySendOrDiscard() =
        runTest {
            val fixture = Fixture(this)
            val recorder = fixture.owner.bind(chat) { true }
            recorder.start()
            runCurrent()
            recorder.stop()
            advanceTimeBy(VoiceRecordingController.RECORDING_TAIL_MS)
            runCurrent()
            fixture.owner.unbind(chat, recorder)
            val draft = checkNotNull(fixture.owner.draft(chat))

            val restarted = Fixture(this, folder.root)
            restarted.owner.bind(chat) { true }
            assertEquals(draft, restarted.owner.draft(chat))
            restarted.owner.consume(chat, draft.file)
            assertNull(restarted.owner.draft(chat))
            assertFalse(draft.file.exists())
            fixture.release()
            restarted.release()
        }

    /** A failed consumption marker write keeps the same review available until Discard can commit. */
    @Test
    fun failedDiscardKeepsTheReviewAndAllowsRetry() =
        runTest {
            val fixture = Fixture(this)
            val store = VoiceDraftStore(folder.root)
            val recorder = fixture.owner.bind(chat) { true }
            recorder.start()
            runCurrent()
            recorder.stop()
            advanceTimeBy(VoiceRecordingController.RECORDING_TAIL_MS)
            runCurrent()
            val draft = checkNotNull(fixture.owner.draft(chat))
            val marker = store.directory(chat).resolve("take.consumed")
            assertTrue(marker.mkdir())
            var failures = 0

            val review =
                VoiceRecordingReview(
                    scope = this,
                    ownerIsCurrent = { true },
                    send = { _, _, _, _ -> error("unexpected send") },
                    deleteFile = { fixture.owner.consume(chat, it) },
                    onDiscardFailure = { failures++ },
                )
            assertTrue(review.adopt(draft.file, draft.durationMs))
            val clip = checkNotNull(review.clip)
            assertFalse(review.discardByUser(clip))
            var started = false
            review.recordAgain(clip) { started = true }

            assertEquals(2, failures)
            assertFalse(started)
            assertSame(clip, review.clip)
            assertSame(draft, fixture.owner.draft(chat))
            assertTrue(draft.file.isFile)

            assertEquals(VoiceDraftRecovery.Ready(draft), store.recover(chat))
            assertTrue(marker.delete())
            review.recordAgain(clip) { started = true }

            assertTrue(started)
            assertNull(review.clip)
            assertNull(fixture.owner.draft(chat))
            assertEquals(VoiceDraftRecovery.None, VoiceDraftStore(folder.root).recover(chat))
            fixture.release()
        }

    /** Removing a chat revokes the old recorder, and account teardown purges its remaining drafts. */
    @Test
    fun signOutAndLeavingTheChatEndTheTakeAndPurgeItsDrafts() =
        runTest {
            val fixture = Fixture(this)
            val recorder = fixture.owner.bind(chat) { true }
            recorder.start()
            runCurrent()
            recorder.lock()
            fixture.owner.onTargetRemoved(chat.accountRef, chat.groupIdHex)
            runCurrent()
            assertFalse(recorder.isRecording)
            assertFalse(recorder.start())
            assertFalse(VoiceDraftStore(folder.root).directory(chat).exists())
            assertEquals(VoiceNoteActivity.None, fixture.owner.activity(chat))

            val other = fixture.owner.bind(otherChat) { true }
            other.start()
            runCurrent()
            other.stop()
            advanceTimeBy(VoiceRecordingController.RECORDING_TAIL_MS)
            runCurrent()
            val draft = checkNotNull(fixture.owner.draft(otherChat))
            fixture.owner.stopAccountCapture(otherChat.accountRef)
            fixture.owner.purgeAccountDrafts(otherChat.accountRef)
            assertNull(fixture.owner.draft(otherChat))
            assertFalse(draft.file.exists())
            fixture.release()
        }

    /** A take still finalizing during a quick restart belongs to the removed chat as well. */
    @Test
    fun removingTheChatCancelsTheOldTailDuringRestart() =
        runTest {
            val fixture = Fixture(this)
            val recorder = fixture.owner.bind(chat) { true }
            assertTrue(recorder.start())
            runCurrent()
            recorder.stop()
            assertTrue(recorder.start())

            assertTrue(fixture.owner.onTargetRemoved(chat.accountRef, chat.groupIdHex))
            advanceTimeBy(VoiceRecordingController.RECORDING_TAIL_MS)
            runCurrent()

            assertFalse(recorder.isRecording)
            assertNull(fixture.owner.draft(chat))
            assertFalse(VoiceDraftStore(folder.root).directory(chat).exists())
            fixture.release()
        }

    private inner class Fixture(
        scope: TestScope,
        root: File = folder.root,
    ) {
        val coordinator = MicrophoneCaptureCoordinator()
        val errors = mutableListOf<Throwable>()
        var elsewhere = 0
        private val dispatcher = StandardTestDispatcher(scope.testScheduler)
        private val controllers = mutableListOf<VoiceRecordingController>()
        val owner =
            VoiceNoteSessionOwner(
                store = VoiceDraftStore(root),
                newController = { _, hooks ->
                    VoiceRecordingController(
                        context = ApplicationProvider.getApplicationContext<Context>(),
                        outputDirectory = hooks.outputDirectory,
                        scope = scope,
                        onPermissionRequest = { true },
                        onRecordingComplete = hooks.onRecordingComplete,
                        onError = hooks.onError,
                        microphoneCaptures = coordinator,
                        recorderFactory = { _, file, _ -> FakeSession(file) },
                        mainDispatcher = dispatcher,
                        recorderDispatcher = StandardTestDispatcher(scope.testScheduler, name = "voice-io"),
                        elapsedRealtime = { scope.testScheduler.currentTime },
                        onAutomaticStop = hooks.onAutomaticStop,
                    ).also(controllers::add)
                },
                presentError = errors::add,
                presentElsewhere = { elsewhere++ },
            )

        fun release() = controllers.forEach { it.release() }
    }

    private class FakeSession(
        private val file: File,
    ) : VoiceRecordingSession {
        private var stopped = false

        override fun start() = file.writeText("captured audio")

        override fun stop(): VoiceRecordingResult {
            stopped = true
            return VoiceRecordingResult(file, 2_000L)
        }

        override fun cancel() {
            if (!stopped) file.delete()
        }
    }
}
