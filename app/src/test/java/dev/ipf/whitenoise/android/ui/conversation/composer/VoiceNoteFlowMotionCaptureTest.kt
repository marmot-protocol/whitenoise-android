package dev.ipf.whitenoise.android.ui.conversation.composer

import android.content.Context
import android.provider.Settings
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.TouchInjectionScope
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureScreenRoboImage
import dev.ipf.whitenoise.android.audio.VoiceDraftStore
import dev.ipf.whitenoise.android.audio.VoiceNoteKey
import dev.ipf.whitenoise.android.audio.VoiceNoteSessionOwner
import dev.ipf.whitenoise.android.audio.VoiceRecordingController
import dev.ipf.whitenoise.android.audio.VoiceRecordingResult
import dev.ipf.whitenoise.android.audio.VoiceRecordingSession
import dev.ipf.whitenoise.android.core.MessageTextCopy
import dev.ipf.whitenoise.android.ui.chats.ChatRowVoiceStatus
import dev.ipf.whitenoise.android.ui.common.LocalSystemAnimationsEnabled
import dev.ipf.whitenoise.android.ui.conversation.media.VoiceAttachmentContent
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.nio.file.Files

/**
 * Opt-in frame export of the voice-note flow through the real ComposerBar, recorder, review and voice bubble card.
 * Run with ANIMATION_CAPTURE_FRAMES=1. Frames land in build/animation-frames/voice-flow/<flow>/frame-NNNN.png at
 * 16 ms per frame, numbered continuously per flow; each segment's last frame is held for 30 frames, and
 * segments.txt lists the first frame of every segment.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-w360dp-h780dp-mdpi")
class VoiceNoteFlowMotionCaptureTest {
    @get:Rule val composeRule = createComposeRule()
    private var previousAnimatorScale: String? = null

    @Before fun enableMotion() {
        assumeTrue(System.getenv("ANIMATION_CAPTURE_FRAMES") == "1")
        val resolver = ApplicationProvider.getApplicationContext<Context>().contentResolver
        previousAnimatorScale = Settings.Global.getString(resolver, Settings.Global.ANIMATOR_DURATION_SCALE)
        Settings.Global.putFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
    }

    @After fun restoreMotion() {
        if (System.getenv("ANIMATION_CAPTURE_FRAMES") != "1") return
        val resolver = ApplicationProvider.getApplicationContext<Context>().contentResolver
        Settings.Global.putString(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, previousAnimatorScale)
    }

    /** Idle, hold, lock, pause, resume, stop and close, review, send, the pending bubble's ring, and sent. */
    @Test
    fun captureMainFlow() =
        runTest {
            val flow = Flow(this, "main")
            flow.render()
            flow.segment("01-idle", frames = 6) {}
            flow.segment("02-hold", frames = 40) { flow.launcher { down(center) } }
            flow.segment("03-lock", frames = 30) {
                flow.launcher {
                    moveBy(Offset(0f, -LOCK_TRAVEL_DP * density))
                    up()
                }
            }
            flow.segment("04-pause", frames = 24) { flow.tap("composer.voice.pause") }
            flow.segment("05-resume", frames = 24) { flow.tap("composer.voice.resume") }
            flow.segment("06-stop-closing", frames = 30) { flow.stop() }
            flow.segment("07-review", frames = 24) {}
            flow.segment("08-send-sending", frames = 30) { flow.tap("conversation.voice.review.send") }
            flow.segment("09-bubble-ring", frames = 40) { flow.seed() }
            flow.segment("10-sent", frames = 24) { flow.bubbleSending = false }
            flow.release()
        }

    /** A hold slides past the cancel threshold and releases: the strip leaves toward the cancel side. */
    @Test
    fun captureCancelFlow() =
        runTest {
            val flow = Flow(this, "cancel")
            flow.render()
            flow.segment("01-hold", frames = 24) { flow.launcher { down(center) } }
            repeat(CANCEL_STEPS) { step ->
                flow.segment("02-slide-$step", frames = 4, hold = false) {
                    flow.launcher { moveBy(Offset(-CANCEL_STEP_DP * density, 0f)) }
                }
            }
            flow.segment("03-armed", frames = 12) {}
            flow.segment("04-release", frames = 30) { flow.launcher { up() } }
            flow.release()
        }

    /** A long take stopped into review and discarded through its confirmation: the review leaves the same way. */
    @Test
    fun captureDiscardFlow() =
        runTest {
            val flow = Flow(this, "discard", takeMs = 12_000L)
            flow.render()
            flow.launcher { down(center) }
            flow.launcher {
                moveBy(Offset(0f, -LOCK_TRAVEL_DP * density))
                up()
            }
            flow.advance(1_000L)
            flow.stop()
            flow.advance(VoiceRecordingController.RECORDING_TAIL_MS)
            flow.segment("01-review", frames = 24) {}
            flow.segment("02-confirm", frames = 24) { flow.tap("conversation.voice.review.discard") }
            flow.segment("03-discard", frames = 30) { flow.tap("confirmation.confirm") }
            flow.release()
        }

    /**
     * A locked take keeps recording through the process-level owner while its chat is closed: the chat-list row
     * shows the red waveform glyph, and reopening the chat presents the same running strip.
     */
    @Test
    fun captureLeaveAndReturnFlow() =
        runTest {
            val flow = Flow(this, "leave-return")
            flow.renderOwned()
            flow.launcher { down(center) }
            flow.launcher {
                moveBy(Offset(0f, -LOCK_TRAVEL_DP * density))
                up()
            }
            flow.segment("01-locked", frames = 24) {}
            flow.segment("02-chat-list", frames = 40) { flow.inChat = false }
            flow.segment("03-return", frames = 40) { flow.inChat = true }
            flow.release()
        }

    /** One flow's composer, recorder, review and bubble, and its continuous frame counter. */
    private inner class Flow(
        private val scope: TestScope,
        name: String,
        private val takeMs: Long = 4_000L,
    ) {
        private val folder = File("build/animation-frames/voice-flow/$name").apply { deleteRecursively() }
        private val manifest = StringBuilder()
        private var frame = 0
        private val output: File = Files.createTempDirectory("voice-flow").toFile()
        private val context = ApplicationProvider.getApplicationContext<Context>()
        private val dispatcher = StandardTestDispatcher(scope.testScheduler)
        private var queued: ((Boolean) -> Unit)? = null
        private var owner: VoiceNoteSessionOwner? = null
        var bubbleVisible by mutableStateOf(false)
        var bubbleSending by mutableStateOf(false)
        var inChat by mutableStateOf(true)
        val review =
            VoiceRecordingReview(
                scope = scope,
                ownerIsCurrent = { true },
                send = { _, _, _, onQueued -> queued = onQueued },
                playback = StillPlayback,
                deleteFile = {},
            )
        val controller = newController(output) { file, durationMs -> review.offer(file, durationMs) }

        init {
            folder.mkdirs()
        }

        fun newController(
            directory: File,
            onComplete: (File, Long) -> Unit,
        ) = VoiceRecordingController(
            context = context,
            outputDirectory = directory,
            scope = scope,
            onPermissionRequest = { true },
            onRecordingComplete = onComplete,
            onError = {},
            recorderFactory = { _, file, _ -> Session(file, takeMs) },
            mainDispatcher = dispatcher,
            recorderDispatcher = StandardTestDispatcher(scope.testScheduler, name = "voice-flow-io"),
            elapsedRealtime = { scope.testScheduler.currentTime },
        )

        fun render() =
            show {
                Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                    Spacer(Modifier.weight(1f))
                    if (bubbleVisible) Bubble(bubbleSending)
                    Composer(controller)
                }
            }

        /** Binds the chat's recorder from a process-level owner, as the conversation screen does. */
        fun renderOwned() {
            val sessionOwner =
                VoiceNoteSessionOwner(
                    store = VoiceDraftStore(output),
                    newController = { _, hooks -> newController(hooks.outputDirectory, hooks.onRecordingComplete) },
                    presentError = {},
                    presentElsewhere = {},
                )
            owner = sessionOwner
            show {
                Crossfade(targetState = inChat, animationSpec = voiceTween(open = true), label = "voice-flow-screen") {
                    if (it) {
                        Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                            Spacer(Modifier.weight(1f))
                            val bound = remember { sessionOwner.bind(FlowKey) { true } }
                            DisposableEffect(bound) { onDispose { sessionOwner.unbind(FlowKey, bound) } }
                            Composer(bound)
                        }
                    } else {
                        ChatListRow(sessionOwner)
                    }
                }
            }
        }

        private fun show(content: @Composable () -> Unit) {
            composeRule.mainClock.autoAdvance = false
            composeRule.setContent {
                CompositionLocalProvider(LocalSystemAnimationsEnabled provides true) {
                    WhiteNoiseTheme(darkTheme = true, amoled = true) { content() }
                }
            }
            composeRule.mainClock.advanceTimeBy(FRAME_MS)
            composeRule.waitForIdle()
        }

        @Composable
        private fun Composer(recorder: VoiceRecordingController) {
            ComposerBar(
                replyingTo = null,
                messageTextCopy = MessageTextCopy.Default,
                onCancelReply = {},
                onSend = { _, _ -> },
                voiceRecordingController = recorder,
                voiceReview = review,
            )
        }

        fun launcher(gesture: TouchInjectionScope.() -> Unit) {
            composeRule.onNodeWithTag("composer.voice.launcher", useUnmergedTree = true).performTouchInput(gesture)
            advance(0L)
        }

        fun tap(tag: String) {
            composeRule.onNodeWithTag(tag, useUnmergedTree = true).performSemanticsAction(SemanticsActions.OnClick)
            advance(0L)
        }

        fun stop() {
            controller.stop()
            advance(0L)
        }

        /** The review hands the take to its row: the bubble appears pending in the turn the review clears. */
        fun seed() {
            bubbleSending = true
            bubbleVisible = true
            checkNotNull(queued).invoke(true)
        }

        fun advance(ms: Long) {
            if (ms > 0L) scope.advanceTimeBy(ms)
            scope.runCurrent()
            composeRule.runOnIdle { Snapshot.sendApplyNotifications() }
        }

        /** Runs [action], renders [frames] 16 ms frames, then holds the last one for 30 frames unless [hold] is off. */
        fun segment(
            name: String,
            frames: Int,
            hold: Boolean = true,
            action: () -> Unit,
        ) {
            manifest
                .append(name)
                .append(' ')
                .append("%04d".format(frame))
                .append('\n')
            action()
            var last: File? = null
            repeat(frames) {
                composeRule.mainClock.advanceTimeBy(FRAME_MS)
                advance(FRAME_MS)
                composeRule.waitForIdle()
                last = next().also { captureScreenRoboImage(it.path) }
            }
            val held = checkNotNull(last)
            if (hold) repeat(HOLD_FRAMES) { held.copyTo(next()) }
            File(folder, "segments.txt").writeText(manifest.toString())
        }

        fun release() {
            controller.release()
            owner?.onAccountUnavailable(FlowKey.accountRef)
            review.release()
            scope.runCurrent()
            output.deleteRecursively()
        }

        private fun next(): File = File(folder, "frame-%04d.png".format(frame++))
    }

    /** The real voice bubble card, outgoing, with its sending ring while the row is pending. */
    @Composable
    private fun Bubble(sending: Boolean) {
        Box(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            contentAlignment = Alignment.CenterEnd,
        ) {
            Box(
                Modifier
                    .background(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.shapes.large)
                    .padding(4.dp),
            ) {
                VoiceAttachmentContent(
                    loading = false,
                    failed = false,
                    startDownload = true,
                    localFileAvailable = true,
                    isPlaying = false,
                    isPaused = false,
                    activePositionMs = 0,
                    activeDurationMs = 0,
                    totalDurationMs = 4_000,
                    progressFraction = 0f,
                    outgoing = true,
                    onLongPress = {},
                    onActionClick = {},
                    sending = sending,
                )
            }
        }
    }

    /** A chat-list row reduced to its title line, carrying the real voice marker for this chat. */
    @Composable
    private fun ChatListRow(owner: VoiceNoteSessionOwner) {
        Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(16.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text("Review group", style = MaterialTheme.typography.titleMedium)
                ChatRowVoiceStatus(owner.activity(FlowKey))
            }
            Text("See you at six", style = MaterialTheme.typography.bodyMedium)
        }
    }

    private object StillPlayback : VoiceReviewPlayback {
        override suspend fun play(clip: VoiceReviewClip) = false

        override fun pause(key: String) = Unit

        override fun stop(key: String) = Unit

        override fun isPlaying(key: String) = false
    }

    private class Session(
        private val file: File,
        private val durationMs: Long,
    ) : VoiceRecordingSession {
        override fun start() = file.writeText("captured audio")

        override fun stop() = VoiceRecordingResult(file, durationMs)

        override fun cancel() = Unit
    }

    private companion object {
        const val FRAME_MS = 16L
        const val HOLD_FRAMES = 30
        const val LOCK_TRAVEL_DP = 96f
        const val CANCEL_STEPS = 6
        const val CANCEL_STEP_DP = 26f
        val FlowKey = VoiceNoteKey("motion-account", "ab".repeat(32))
    }
}
