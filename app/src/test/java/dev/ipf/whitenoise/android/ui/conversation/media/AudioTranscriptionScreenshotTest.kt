@file:Suppress("FunctionNaming")

package dev.ipf.whitenoise.android.ui.conversation.media

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.isOff
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.captureScreenRoboImage
import dev.ipf.whitenoise.android.audio.AttachmentSpeechFailure
import dev.ipf.whitenoise.android.state.MessageStatus
import dev.ipf.whitenoise.android.translation.MessageSideDiscSpacing
import dev.ipf.whitenoise.android.translation.MessageTranslationToggleSlotWidth
import dev.ipf.whitenoise.android.ui.conversation.messages.MediaCaptionFrame
import dev.ipf.whitenoise.android.ui.conversation.messages.MessageBubbleBottomAlignmentLine
import dev.ipf.whitenoise.android.ui.conversation.messages.MessageBubbleFrame
import dev.ipf.whitenoise.android.ui.conversation.messages.MessageInlineFooter
import dev.ipf.whitenoise.android.ui.conversation.messages.MessageSenderAvatarSlot
import dev.ipf.whitenoise.android.ui.conversation.messages.messageBubblePresentation
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * A received voice message keeps its transcribe disc in the side gutter, like translation, and shows the
 * transcript as labelled body text directly beneath the voice row inside the same bubble; no dialog is ever shown.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-rUS-w360dp-h640dp-mdpi")
@OptIn(ExperimentalCoroutinesApi::class, ExperimentalRoborazziApi::class)
class AudioTranscriptionScreenshotTest {
    @get:Rule
    val composeRule = createComposeRule(effectContext = UnconfinedTestDispatcher())

    @Test
    fun idleLight() = capture("idle-light", null)

    /** An edge tap in the wide gutter toggles the action, while its press state stays inside the disc. */
    @Test
    fun pressedDiscKeepsCircularFeedback() {
        val requests = mutableListOf<Boolean>()
        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = true) {
                LazyColumn {
                    item {
                        Surface(Modifier.width(360.dp).testTag(FRAME)) {
                            IncomingVoiceMessage(null, onToggle = { requests += it })
                        }
                    }
                }
            }
        }
        val disc = composeRule.onNodeWithTag(AUDIO_TRANSCRIBE_TEST_TAG)
        val idle = disc.captureToImage().toPixelMap()
        val middleX = idle.width / 2
        val middleY = idle.height / 2
        composeRule.mainClock.autoAdvance = false
        disc.performTouchInput { click(Offset(2f, center.y)) }
        composeRule.mainClock.advanceTimeByFrame()
        val released = disc.captureToImage().toPixelMap()
        assertTrue(
            "a quick edge tap still gives feedback",
            idle[middleX + 9, middleY] != released[middleX + 9, middleY],
        )
        composeRule.mainClock.advanceTimeBy(300)
        assertEquals(listOf(true), requests)
        val faded = disc.captureToImage().toPixelMap()
        assertEquals("feedback fades to idle", idle[middleX + 9, middleY], faded[middleX + 9, middleY])

        disc.performTouchInput { down(Offset(2f, center.y)) }
        composeRule.mainClock.advanceTimeBy(300)
        val pressed = disc.captureToImage().toPixelMap()
        assertTrue("the disc responds to the edge press", idle[middleX + 9, middleY] != pressed[middleX + 9, middleY])
        assertEquals("the gutter has no state layer", idle[2, middleY], pressed[2, middleY])
        assertEquals("clipped disc corner", idle[middleX + 10, middleY + 10], pressed[middleX + 10, middleY + 10])
        assertEquals(listOf(true), requests)
        composeRule.onNodeWithTag(FRAME).captureRoboImage("src/test/snapshots/audio-transcription-pressed-dark.png")
        disc.performTouchInput { up() }
        composeRule.mainClock.advanceTimeBy(300)
        assertEquals(listOf(true, true), requests)
    }

    @Test
    fun processingDark() = capture("working-dark", AudioTranscriptUi(AudioTranscriptPhase.WORKING), Appearance.DARK)

    @Test
    fun completedLight() = capture("complete-light", completed())

    @Test
    fun completedDark() = capture("complete-dark", completed(), Appearance.DARK)

    @Test
    fun completedLargeRtlAmoled() = capture("complete-amoled-rtl-large", completed(), Appearance.AMOLED_RTL)

    /** The production frame: the player on top, the transcript, then the time, all in one bubble. */
    @Test
    fun completedInBubbleLight() = capture("bubble-light", completed(), inlineMedia = true)

    @Test
    fun completedInBubbleAmoledRtlLarge() {
        capture("bubble-amoled-rtl-large", completed(), Appearance.AMOLED_RTL, inlineMedia = true)
    }

    /** Automatic mode shows a transcript that is already known at once, with the disc on and no tap. */
    @Test
    fun automaticShowsKnownTranscriptWithoutTap() {
        val scope = VoiceTranscriptionScope(ReceivedVoiceTranscripts(), "alice", automatic = true)
        scope.storeResult(ATTACHMENT, completed())
        setReceivedTranscription(scope, File("never-read.ogg"))
        composeRule.onNodeWithTag(AUDIO_TRANSCRIPT_TEST_TAG).assertIsDisplayed()
        composeRule.onNodeWithText(completed().text).assertIsDisplayed()
        composeRule.onNodeWithTag(AUDIO_TRANSCRIBE_TEST_TAG).assertIsOn()
    }

    /** Turning the disc off hides the transcript but keeps it, so turning it on again shows it immediately. */
    @Test
    fun hidingKeepsTranscriptForTheNextShow() {
        val scope = VoiceTranscriptionScope(ReceivedVoiceTranscripts(), "alice", automatic = true)
        scope.storeResult(ATTACHMENT, completed())
        setReceivedTranscription(scope, File("never-read.ogg"))
        val disc = composeRule.onNodeWithTag(AUDIO_TRANSCRIBE_TEST_TAG)
        disc.performClick().assertIsOff()
        composeRule.onNodeWithTag(AUDIO_TRANSCRIPT_TEST_TAG).assertDoesNotExist()
        disc.performClick().assertIsOn()
        composeRule.onNodeWithText(completed().text).assertIsDisplayed()
    }

    /**
     * An automatic attempt that fails turns the disc off without putting guidance under the recording; asking
     * for that transcript explicitly runs it again and shows the guidance.
     */
    @Test
    fun automaticFailureStaysQuietUntilRequested() {
        val scope = VoiceTranscriptionScope(ReceivedVoiceTranscripts(), "alice", automatic = true)
        val file = File.createTempFile("voice", ".ogg").apply { deleteOnExit() }
        setReceivedTranscription(scope, file)
        composeRule.waitUntil(TIMEOUT_MS) {
            val discs = composeRule.onAllNodes(hasTestTag(AUDIO_TRANSCRIBE_TEST_TAG) and isOff())
            discs.fetchSemanticsNodes().isNotEmpty()
        }
        assertTrue(scope.shown(MESSAGE))
        composeRule.onNodeWithTag(AUDIO_TRANSCRIPT_TEST_TAG).assertDoesNotExist()

        composeRule.onNodeWithTag(AUDIO_TRANSCRIBE_TEST_TAG).assertIsOff().performClick()
        composeRule.waitUntil(TIMEOUT_MS) {
            composeRule.onAllNodesWithTag(AUDIO_TRANSCRIPT_TEST_TAG).fetchSemanticsNodes().isNotEmpty()
        }
        assertTrue(scope.requestedExplicitly(MESSAGE))
        composeRule.onNodeWithTag(AUDIO_TRANSCRIBE_TEST_TAG).assertIsOn()
    }

    private fun setReceivedTranscription(
        scope: VoiceTranscriptionScope,
        file: File,
    ) {
        val toggle = AudioTranscriptionToggleState(MESSAGE)
        composeRule.setContent {
            WhiteNoiseTheme {
                CompositionLocalProvider(LocalVoiceTranscription provides scope) {
                    Column {
                        AudioTranscriptionToggle(toggle)
                        ReceivedAudioTranscription(file, ATTACHMENT, toggle)
                    }
                }
            }
        }
    }

    @Test
    fun missingProviderLargeRtl() =
        capture(
            "missing-amoled-rtl-large",
            AudioTranscriptUi(AudioTranscriptPhase.FAILED, failure = AttachmentSpeechFailure.PROVIDER_MISSING),
            Appearance.AMOLED_RTL,
        )

    @Test
    fun outdatedProviderLargeRtl() =
        capture(
            "outdated-amoled-rtl-large",
            AudioTranscriptUi(AudioTranscriptPhase.FAILED, failure = AttachmentSpeechFailure.PROVIDER_OUTDATED),
            Appearance.AMOLED_RTL,
        )

    /** One tap on the side disc requests the transcript and a second tap hides it, without any dialog. */
    @Test
    fun singleTapTogglesWithoutDialog() {
        val requests = mutableListOf<Boolean>()
        composeRule.setContent {
            WhiteNoiseTheme {
                var on by remember { mutableStateOf(false) }
                AudioTranscribeDisc(
                    on = on,
                    working = false,
                    enabled = true,
                    onToggle = {
                        requests += it
                        on = it
                    },
                )
            }
        }
        val disc = composeRule.onNodeWithTag(AUDIO_TRANSCRIBE_TEST_TAG)
        disc.assertIsOff().performClick()
        composeRule.onNode(isDialog()).assertDoesNotExist()
        disc.assertIsOn().performClick()
        disc.assertIsOff()
        assertEquals(listOf(true, false), requests)
    }

    /** The disc stays hidden until a received recording registers, so other bubbles keep an empty gutter. */
    @Test
    fun toggleEmitsNothingWithoutRecording() {
        val toggle = AudioTranscriptionToggleState()
        composeRule.setContent { WhiteNoiseTheme { AudioTranscriptionToggle(toggle) } }
        composeRule.onNodeWithTag(AUDIO_TRANSCRIBE_TEST_TAG).assertDoesNotExist()
        composeRule.runOnIdle { toggle.register(Any(), ready = true, working = false, failedAutomatically = false) }
        composeRule.onNodeWithTag(AUDIO_TRANSCRIBE_TEST_TAG).assertIsDisplayed().assertIsOff()
    }

    /** Records three actual inline transcript toggles without adding fixture-only motion. */
    @Test
    @Config(sdk = [36], qualifiers = "en-rUS-w360dp-h780dp-mdpi")
    fun captureTranscriptFrames() {
        org.junit.Assume.assumeTrue(System.getenv("ANIMATION_CAPTURE_FRAMES") == "1")
        val output = File("build/animation-frames/transcript")
        check(!output.exists()) { "Archive the previous transcript output first" }
        check(output.mkdirs())
        val shown = mutableStateOf(false)
        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = true, amoled = true, fontScale = 1f) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    IncomingVoiceMessage(
                        state = if (shown.value) completed() else null,
                        inlineMedia = true,
                        onToggle = { shown.value = it },
                    )
                }
            }
        }
        composeRule.waitForIdle()
        composeRule.mainClock.autoAdvance = false
        repeat(3) { cycle ->
            captureTranscriptTransition(File(output, "cycle_$cycle/forward"), shown = true)
            captureTranscriptTransition(File(output, "cycle_$cycle/reverse"), shown = false)
        }
    }

    private fun captureTranscriptTransition(
        folder: File,
        shown: Boolean,
    ) {
        check(folder.mkdirs())
        val disc = composeRule.onNodeWithTag(AUDIO_TRANSCRIBE_TEST_TAG)
        if (shown) disc.assertIsOff() else disc.assertIsOn()
        captureScreenRoboImage(File(folder, "source.png").path)
        disc.performClick()
        composeRule.runOnIdle {
            androidx.compose.runtime.snapshots.Snapshot
                .sendApplyNotifications()
        }
        repeat(33) { frame ->
            composeRule.mainClock.advanceTimeBy(16)
            composeRule.waitForIdle()
            captureScreenRoboImage(File(folder, "frame-%03d.png".format(frame)).path)
        }
        if (shown) {
            disc.assertIsOn()
            composeRule.onNodeWithTag(AUDIO_TRANSCRIPT_TEST_TAG).assertIsDisplayed()
        } else {
            disc.assertIsOff()
            composeRule.onNodeWithTag(AUDIO_TRANSCRIPT_TEST_TAG).assertDoesNotExist()
        }
        composeRule.onNode(isDialog()).assertDoesNotExist()
    }

    private fun capture(
        name: String,
        state: AudioTranscriptUi?,
        appearance: Appearance = Appearance.LIGHT,
        inlineMedia: Boolean = false,
    ) {
        val amoledRtl = appearance == Appearance.AMOLED_RTL
        val direction = if (amoledRtl) LayoutDirection.Rtl else LayoutDirection.Ltr
        composeRule.setContent {
            WhiteNoiseTheme(
                darkTheme = appearance != Appearance.LIGHT,
                amoled = amoledRtl,
                fontScale = if (amoledRtl) 2f else 1f,
            ) {
                CompositionLocalProvider(LocalLayoutDirection provides direction) {
                    Surface(modifier = Modifier.width(360.dp).testTag(FRAME)) {
                        IncomingVoiceMessage(state, inlineMedia = inlineMedia)
                    }
                }
            }
        }
        val disc = composeRule.onNodeWithTag(AUDIO_TRANSCRIBE_TEST_TAG).assertIsDisplayed()
        if (state == null) disc.assertIsOff() else disc.assertIsOn()
        composeRule.onNode(isDialog()).assertDoesNotExist()
        composeRule.onNodeWithTag(FRAME).captureRoboImage("src/test/snapshots/audio-transcription-$name.png")
    }

    /** The production order: avatar, bubble with the voice row and transcript, then the side disc column. */
    @Composable
    private fun IncomingVoiceMessage(
        state: AudioTranscriptUi?,
        inlineMedia: Boolean = false,
        onToggle: (Boolean) -> Unit = {},
    ) {
        Row(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            MessageSenderAvatarSlot(
                showSenderAvatar = true,
                title = "Alex",
                seed = "alex",
                pictureUrl = null,
                enabled = true,
                alignToBubbleBottom = true,
                onClick = {},
            )
            Column(modifier = Modifier.weight(1f, fill = false).alignBy(MessageBubbleBottomAlignmentLine)) {
                if (inlineMedia) {
                    MediaCaptionFrame(
                        presentation = messageBubblePresentation(deleted = false, mine = false),
                        highlighted = false,
                        mine = false,
                        mentionedSelf = false,
                        mentionedYouLabel = "Mentioned you",
                        alignEnd = false,
                        media = { VoiceAndTranscript(state) },
                        caption = {
                            MessageInlineFooter(
                                timeText = "10:24",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                showStatus = false,
                                status = MessageStatus.Received,
                                editedLabel = null,
                                onEditedClick = null,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        },
                    )
                } else {
                    MessageBubbleFrame(
                        presentation = messageBubblePresentation(deleted = false, mine = false),
                        highlighted = false,
                        mine = false,
                        mentionedSelf = false,
                        mentionedYouLabel = "Mentioned you",
                    ) {
                        VoiceAndTranscript(state)
                    }
                }
            }
            Column(
                modifier = Modifier.alignBy { it.measuredHeight },
                verticalArrangement = Arrangement.spacedBy(MessageSideDiscSpacing),
            ) {
                AudioTranscribeDisc(
                    on = state != null,
                    working = state?.phase == AudioTranscriptPhase.WORKING,
                    enabled = true,
                    onToggle = onToggle,
                    modifier = Modifier.width(MessageTranslationToggleSlotWidth),
                )
            }
        }
    }

    @Composable
    private fun VoiceAndTranscript(state: AudioTranscriptUi?) {
        VoiceAttachmentContent(
            loading = false,
            failed = false,
            startDownload = false,
            localFileAvailable = true,
            isPlaying = false,
            isPaused = false,
            activePositionMs = 0,
            activeDurationMs = 0,
            totalDurationMs = 14_000,
            progressFraction = 0f,
            outgoing = false,
            onLongPress = {},
            onActionClick = {},
        )
        AudioTranscriptBody(state = state, onRetry = {}, onGetProvider = {})
    }

    private fun completed(): AudioTranscriptUi =
        AudioTranscriptUi(
            AudioTranscriptPhase.COMPLETE,
            "The train leaves at ten. I will meet you by the station entrance.",
        )

    private enum class Appearance { LIGHT, DARK, AMOLED_RTL }

    private companion object {
        const val FRAME = "audio.transcription.frame"
        const val MESSAGE = "m1"
        const val ATTACHMENT = "m1:0:1"
        const val TIMEOUT_MS = 10_000L
    }
}
