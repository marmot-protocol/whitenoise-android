package dev.ipf.whitenoise.android.ui.conversation

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.LayoutDirection
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.whitenoise.android.audio.ConversationDictationController
import dev.ipf.whitenoise.android.audio.ConversationDictationDraftSnapshot
import dev.ipf.whitenoise.android.audio.ConversationDictationPlatform
import dev.ipf.whitenoise.android.audio.ConversationDictationRecognitionListener
import dev.ipf.whitenoise.android.audio.ConversationDictationRecognitionSession
import dev.ipf.whitenoise.android.audio.ConversationDictationState
import dev.ipf.whitenoise.android.audio.VoiceRecordingController
import dev.ipf.whitenoise.android.core.MessageTextCopy
import dev.ipf.whitenoise.android.state.MessageStatus
import dev.ipf.whitenoise.android.ui.conversation.composer.ComposerBar
import dev.ipf.whitenoise.android.ui.conversation.messages.MESSAGE_FULL_SCREEN_TAG
import dev.ipf.whitenoise.android.ui.conversation.messages.MessageBubbleSurfaceState
import dev.ipf.whitenoise.android.ui.conversation.messages.MessageFullScreenView
import dev.ipf.whitenoise.android.ui.conversation.messages.ReaderSendDismissal
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

private typealias ReaderDictationListener = ConversationDictationRecognitionListener

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h780dp-mdpi")
class ReaderComposerControlsScreenshotTest {
    @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private var recorder: VoiceRecordingController? = null
    private val host = ConversationReaderComposer()
    private val readerState = MessageBubbleSurfaceState()
    private val pendingSendRevision = mutableIntStateOf(0)
    private var readerView: android.view.View? = null
    private var composerIdentity: Any? = null
    private var reveals = 0

    @After
    fun releaseRecorder() {
        recorder?.release()
        scope.cancel()
    }

    @Test fun lightReaderControls() = capture("reader_composer_controls_light")

    @Test fun darkReaderControls() = capture("reader_composer_controls_dark", dark = true)

    @Test fun largeRtlReaderControls() = capture("reader_composer_controls_large_rtl", rtl = true)

    @Test
    fun dictatedSendReturnsWithoutKeyboardGapLight() {
        capture("reader_dictation_return_no_keyboard_gap_light", finishSend = true)
    }

    @Test
    fun dictatedSendReturnsWithoutKeyboardGapDark() {
        capture("reader_dictation_return_no_keyboard_gap_dark", dark = true, finishSend = true)
    }

    private fun capture(
        name: String,
        dark: Boolean = false,
        rtl: Boolean = false,
        finishSend: Boolean = false,
    ) {
        val dictation =
            ConversationDictationController(
                platform = Platform,
                readDraft = { _, _ -> ConversationDictationDraftSnapshot(TextFieldValue(""), 0L) },
                writeDraft = { _, _, _, _ -> 0L },
                disclosureAccepted = { true },
                markDisclosureAccepted = {},
                targetValidationScope = scope,
                sendTranscriptIfOriginUnchanged = { request ->
                    request.beginDispatch().also { claimed ->
                        if (claimed) {
                            request.onPendingShown()
                            pendingSendRevision.intValue += 1
                        }
                    }
                },
            )
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        recorder =
            VoiceRecordingController(
                context = context,
                outputDirectory = context.cacheDir,
                scope = scope,
                onPermissionRequest = { true },
                onRecordingComplete = { _, _ -> },
                onError = {},
            )
        render(dictation, dark, rtl)
        val restingBounds = composeRule.onNodeWithTag("reader-composer-host").getUnclippedBoundsInRoot()
        val restingHeight = (restingBounds.bottom - restingBounds.top).value
        val identity = composeRule.runOnIdle { composerIdentity }
        composeRule.runOnIdle { readerState.openExpandedFullView() }
        composeRule.onNodeWithContentDescription("Dictate text").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Hold to record voice message").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Add attachment").assertIsDisplayed()
        if (!finishSend) {
            composeRule.onNodeWithTag(MESSAGE_FULL_SCREEN_TAG).captureRoboImage("src/test/snapshots/$name.png")
        }
        composeRule.onNodeWithContentDescription("Dictate text").performClick()
        composeRule.runOnIdle { assertTrue(dictation.state is ConversationDictationState.Starting) }
        composeRule.onNodeWithContentDescription("Hold to record voice message").assertDoesNotExist()
        if (finishSend) {
            finishDictationSend(name, identity, restingHeight)
        } else {
            composeRule.onNodeWithContentDescription("Cancel").assertIsDisplayed().performClick()
            composeRule.runOnIdle { assertTrue(dictation.state is ConversationDictationState.Idle) }
        }
    }

    private fun render(
        dictation: ConversationDictationController,
        dark: Boolean,
        rtl: Boolean,
    ) {
        composeRule.setContent {
            CompositionLocalProvider(
                LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
            ) {
                WhiteNoiseTheme(darkTheme = dark, fontScale = if (rtl) 2f else 1f) {
                    Column {
                        LaunchedEffect(pendingSendRevision.intValue) {
                            if (pendingSendRevision.intValue > 0 && !host.onDictationSendAccepted()) {
                                reveals++
                                readerState.expandedFullView = false
                            }
                        }
                        host.Host(Modifier.testTag("reader-composer-host")) {
                            composerIdentity = remember { Any() }
                            Composer(dictation)
                        }
                        if (readerState.expandedFullView) {
                            ReaderSendDismissal(readerState, { reveals++ }) {
                                host.session?.let(host::detach)
                            }
                            Reader()
                        }
                    }
                }
            }
        }
    }

    private fun finishDictationSend(
        name: String,
        identity: Any?,
        restingHeight: Float,
    ) {
        composeRule.runOnIdle {
            val view = requireNotNull(readerView)
            ViewCompat.dispatchApplyWindowInsets(
                view.rootView,
                WindowInsetsCompat
                    .Builder()
                    .setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, 280))
                    .setVisible(WindowInsetsCompat.Type.ime(), true)
                    .build(),
            )
        }
        composeRule.onNodeWithContentDescription("Send").performClick()
        composeRule.runOnIdle { Platform.listener.onResult("Dictated reader reply") }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(MESSAGE_FULL_SCREEN_TAG).assertDoesNotExist()
        composeRule.runOnIdle {
            assertEquals(1, reveals)
            assertSame(identity, composerIdentity)
        }
        val returned = composeRule.onNodeWithTag("reader-composer-host")
        val returnedBounds = returned.getUnclippedBoundsInRoot()
        assertEquals(
            "Closed reader IME must not reserve keyboard space",
            restingHeight,
            (returnedBounds.bottom - returnedBounds.top).value,
            1f,
        )
        returned.captureRoboImage("src/test/snapshots/$name.png")
    }

    @Composable
    private fun Composer(dictation: ConversationDictationController) {
        ComposerBar(
            replyingTo = null,
            messageTextCopy = MessageTextCopy.Default,
            onCancelReply = {},
            onSend = { _, _ -> },
            onPickFromGallery = {},
            onCaptureFromCamera = {},
            onPickDocument = {},
            onShareLocation = {},
            onShareUser = {},
            onShareContact = {},
            onCreatePoll = {},
            voiceRecordingController = recorder,
            dictationController = dictation,
            dictationAccountRef = "reader-account",
            dictationGroupIdHex = "reader-group",
            modifier = Modifier.fillMaxWidth(),
        )
    }

    @Composable
    private fun Reader() {
        val session = readerState.expandedFullViewSession
        MessageFullScreenView(
            senderDisplayName = "Alice",
            senderSeed = "alice",
            senderAvatarUrl = null,
            body = "Read this longer message while composing a reply.\n\n".repeat(12),
            bodyMarkdownDocument = null,
            mentionDisplayName = null,
            onNostrProfileTap = null,
            onCopyMarkdownLink = {},
            timeText = "12:34",
            showStatus = false,
            status = MessageStatus.Sent,
            canReply = true,
            canReact = true,
            canDelete = false,
            onReply = {},
            onReact = {},
            onCopy = {},
            onDelete = {},
            onDismiss = { host.session?.onDismiss() },
            bottomBar = {
                readerView = LocalView.current
                host.Reader(
                    onAfterSend = { readerState.finishExpandedReaderSend(session) },
                    onDismiss = { readerState.expandedFullView = false },
                )
            },
        )
    }

    private object Platform : ConversationDictationPlatform {
        lateinit var listener: ReaderDictationListener

        override fun hasRecordAudioPermission() = true

        override fun recognitionAvailable() = true

        override fun createSession(listener: ReaderDictationListener): ConversationDictationRecognitionSession {
            this.listener = listener
            return object : ConversationDictationRecognitionSession {
                override fun start() = Unit

                override fun stop() = Unit

                override fun cancel() = Unit

                override fun destroy() = Unit
            }
        }
    }
}
