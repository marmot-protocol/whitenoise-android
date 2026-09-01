package dev.ipf.whitenoise.android.ui.screenshot

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.marmotkit.AppMessageRecordFfi
import dev.ipf.marmotkit.MarkdownDocumentFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.audio.ConversationDictationController
import dev.ipf.whitenoise.android.audio.ConversationDictationDraftSnapshot
import dev.ipf.whitenoise.android.audio.ConversationDictationPlatform
import dev.ipf.whitenoise.android.audio.ConversationDictationRecognitionListener
import dev.ipf.whitenoise.android.audio.ConversationDictationRecognitionSession
import dev.ipf.whitenoise.android.audio.ConversationDictationTimeoutHandle
import dev.ipf.whitenoise.android.audio.VoiceRecordingController
import dev.ipf.whitenoise.android.core.MessageTextCopy
import dev.ipf.whitenoise.android.core.TimelineReplyDisplay
import dev.ipf.whitenoise.android.ui.conversation.composer.ComposerBar
import dev.ipf.whitenoise.android.ui.conversation.composer.RecordingStripLeading
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Pixel baseline for the composer bar leaf in its idle (empty) and drafted
 * states. App state and the voice controller stay null, so only the pure
 * input surface is pinned.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-rUS-w360dp-h780dp-mdpi")
class ComposerBarScreenshotTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val app = ApplicationProvider.getApplicationContext<android.app.Application>()

    @Test
    fun composerBarIdleLight() {
        render(darkTheme = false, amoled = false, draft = "")
        composeRule.onNodeWithTag(TAG).captureRoboImage("src/test/snapshots/composer_bar_idle_light.png")
    }

    @Test
    fun composerBarIdleDark() {
        render(darkTheme = true, amoled = false, draft = "")
        composeRule.onNodeWithTag(TAG).captureRoboImage("src/test/snapshots/composer_bar_idle_dark.png")
    }

    @Test
    fun composerBarIdleAmoled() {
        render(darkTheme = true, amoled = true, draft = "")
        composeRule.onNodeWithTag(TAG).captureRoboImage("src/test/snapshots/composer_bar_idle_amoled.png")
    }

    @Test
    fun composerBarDraftLight() {
        render(darkTheme = false, amoled = false, draft = "Draft message text")
        composeRule.onNodeWithTag(TAG).captureRoboImage("src/test/snapshots/composer_bar_draft_light.png")
    }

    @Test
    fun composerBarDraftRtl() {
        render(darkTheme = false, amoled = false, draft = "Draft message text", rtl = true)
        composeRule.onNodeWithTag(TAG).captureRoboImage("src/test/snapshots/composer_bar_draft_rtl.png")
    }

    @Test
    fun composerBarLongDraftLight() {
        renderLongComposer(darkTheme = false)
        composeRule.onNodeWithTag(LONG_TAG).captureRoboImage("src/test/snapshots/composer_bar_long_draft_light.png")
    }

    @Test
    fun composerBarLongDraftRtl() {
        renderLongComposer(darkTheme = false, rtl = true)
        composeRule.onNodeWithTag(LONG_TAG).captureRoboImage("src/test/snapshots/composer_bar_long_draft_rtl.png")
    }

    @Test
    fun composerResizeHandlePressedLight() {
        renderLongComposer(darkTheme = false)
        val resizeHandle = composeRule.onNodeWithContentDescription(app.getString(R.string.composer_resize))
        composeRule.mainClock.autoAdvance = false
        try {
            resizeHandle.performTouchInput { down(center) }
            composeRule.mainClock.advanceTimeBy(150L)
            composeRule.runOnIdle { }
            composeRule
                .onNodeWithTag(LONG_TAG)
                .captureRoboImage("src/test/snapshots/composer_resize_handle_pressed_light.png")
        } finally {
            resizeHandle.performTouchInput { up() }
            composeRule.mainClock.autoAdvance = true
        }
    }

    @Test
    fun composerBarLongDraftEndSelectionLight() {
        renderLongComposer(darkTheme = false, selectionAtEnd = true)
        composeRule
            .onNodeWithTag(LONG_TAG)
            .captureRoboImage("src/test/snapshots/composer_bar_long_draft_end_selection_light.png")
    }

    @Test
    fun composerBarLongDraftDark() {
        renderLongComposer(darkTheme = true)
        composeRule.onNodeWithTag(LONG_TAG).captureRoboImage("src/test/snapshots/composer_bar_long_draft_dark.png")
    }

    @Test
    fun composerBarLongDraftAmoled() {
        renderLongComposer(darkTheme = true, amoled = true)
        composeRule.onNodeWithTag(LONG_TAG).captureRoboImage("src/test/snapshots/composer_bar_long_draft_amoled.png")
    }

    /** Captures the first, next, and settled frames after one long bulk replacement. */
    @Test
    fun composerBulkPasteFirstNextAndSettledFrames() {
        renderBulkPasteComposer()
        val field = composeRule.onNode(hasSetTextAction())
        val root = composeRule.onNodeWithTag(BULK_PASTE_TAG)
        val replacement = (1..18).joinToString("\n") { line -> "Bulk paste caret frame $line stays visible." }

        field.performClick()
        composeRule.mainClock.autoAdvance = false
        try {
            field.performTextReplacement(replacement)
            composeRule.mainClock.advanceTimeByFrame()
            composeRule.runOnIdle { }
            root.captureRoboImage("src/test/snapshots/composer_bulk_paste_first_frame.png")

            composeRule.mainClock.advanceTimeByFrame()
            composeRule.runOnIdle { }
            root.captureRoboImage("src/test/snapshots/composer_bulk_paste_next_frame.png")

            repeat(30) { composeRule.mainClock.advanceTimeByFrame() }
            composeRule.runOnIdle { }
            root.captureRoboImage("src/test/snapshots/composer_bulk_paste_settled.png")
        } finally {
            composeRule.mainClock.autoAdvance = true
        }
    }

    @Test
    fun composerBarFullScreenLargeRtl() {
        renderLongComposer(darkTheme = true, largeRtl = true)
        composeRule.onNodeWithContentDescription(app.getString(R.string.composer_resize)).performClick()
        composeRule.waitForIdle()
        composeRule
            .onNodeWithTag(LONG_TAG)
            .captureRoboImage("src/test/snapshots/composer_bar_full_screen_large_rtl.png")
    }

    @Test
    fun composerDictationAvailabilityKeepsEmojiStableCompactLight() {
        render(
            darkTheme = false,
            draft = "Draft message text",
            width = 320,
            dictationPreview = DictationPreview.Idle,
        )
        composeRule.onNodeWithTag(TAG).captureRoboImage("src/test/snapshots/composer_dictation_idle_compact_light.png")
    }

    @Test
    fun composerLineThresholdIsStableAtLargeFontInRtl() {
        render(
            darkTheme = false,
            draft = "#938 close as done, win obtained",
            width = 300,
            fontScale = 1.45f,
            rtl = true,
            dictationPreview = DictationPreview.Idle,
            attachmentsEnabled = true,
        )
        composeRule
            .onNodeWithTag(TAG)
            .captureRoboImage("src/test/snapshots/composer_line_threshold_large_font_rtl.png")
    }

    @Test
    fun composerDictationIsVisuallyDistinctFromVoiceNoteOnBlankDraft() {
        val voiceRecording = previewVoiceRecordingController()
        try {
            render(
                darkTheme = false,
                draft = "",
                width = 320,
                dictationPreview = DictationPreview.Idle,
                voiceRecordingController = voiceRecording,
            )
            val context = ApplicationProvider.getApplicationContext<android.content.Context>()
            composeRule.onNodeWithContentDescription(context.getString(R.string.dictate_text)).assertIsDisplayed()
            composeRule
                .onNodeWithContentDescription(context.getString(R.string.voice_message_record))
                .assertIsDisplayed()
            composeRule
                .onNodeWithTag(TAG)
                .captureRoboImage("src/test/snapshots/composer_dictation_and_voice_note_idle_compact.png")
        } finally {
            voiceRecording.release()
        }
    }

    @Test
    fun voiceRecordingStripUnlockedLight() {
        val voiceRecording = previewVoiceRecordingController()
        try {
            composeRule.mainClock.autoAdvance = false
            composeRule.setContent {
                WhiteNoiseTheme(darkTheme = false) {
                    Surface(modifier = Modifier.width(320.dp).testTag(RECORDING_STRIP_TAG)) {
                        RecordingStripLeading(controller = voiceRecording)
                    }
                }
            }
            composeRule.mainClock.advanceTimeBy(350L)

            composeRule
                .onNodeWithTag(RECORDING_STRIP_TAG)
                .captureRoboImage("src/test/snapshots/composer_voice_recording_strip_unlocked_light.png")
        } finally {
            voiceRecording.release()
        }
    }

    /** Captures the app-owned listening controls in the compact large-font composer. */
    @Test
    fun composerDictationListeningCompactLargeFont() {
        render(
            darkTheme = false,
            draft = "Draft message text",
            width = 320,
            fontScale = 1.6f,
            dictationPreview = DictationPreview.Listening,
        )
        composeRule
            .onNodeWithTag(TAG)
            .captureRoboImage("src/test/snapshots/composer_dictation_listening_compact_large_font.png")
    }

    /** Captures processing controls in the compact large-font RTL composer. */
    @Test
    fun composerDictationProcessingCompactLargeFontRtl() {
        render(
            darkTheme = true,
            draft = "Draft message text",
            width = 320,
            fontScale = 1.6f,
            rtl = true,
            dictationPreview = DictationPreview.Processing,
        )
        composeRule
            .onNodeWithTag(TAG)
            .captureRoboImage("src/test/snapshots/composer_dictation_processing_compact_large_font_rtl.png")
    }

    /** Captures idle dictation availability alongside an active reply preview. */
    @Test
    fun composerDictationIdleWithReplyCompactLargeFont() {
        render(
            darkTheme = false,
            draft = "Reply draft",
            width = 320,
            fontScale = 1.6f,
            dictationPreview = DictationPreview.Idle,
            showReply = true,
        )
        composeRule
            .onNodeWithTag(TAG)
            .captureRoboImage("src/test/snapshots/composer_dictation_idle_reply_compact_large_font.png")
    }

    /** Captures that dictation remains unavailable while editing an existing message. */
    @Test
    fun composerDictationEditConstraintCompactRtl() {
        render(
            darkTheme = true,
            draft = "Preserved draft",
            width = 320,
            rtl = true,
            dictationPreview = DictationPreview.Idle,
            showEdit = true,
        )
        composeRule
            .onNodeWithTag(TAG)
            .captureRoboImage("src/test/snapshots/composer_dictation_edit_compact_rtl.png")
    }

    /** Verifies another chat's app-owned controls are not duplicated in this composer. */
    @Test
    fun composerDefersOtherChatDictationControlToTheAppRoot() {
        render(
            darkTheme = false,
            draft = "Draft message text",
            width = 320,
            dictationPreview = DictationPreview.ElsewhereListening,
        )

        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        composeRule.onNodeWithContentDescription(context.getString(R.string.dictation_cancel)).assertDoesNotExist()
        composeRule.onNodeWithText("Draft message text").assertIsDisplayed()
    }

    /** Verifies cross-chat dictation suppresses a voice-note control that cannot acquire the microphone. */
    @Test
    fun crossChatDictationReplacesVoiceNoteWithoutADeadMicControl() {
        val voiceRecording = previewVoiceRecordingController()
        try {
            render(
                darkTheme = false,
                draft = "",
                width = 320,
                dictationPreview = DictationPreview.ElsewhereListening,
                voiceRecordingController = voiceRecording,
            )

            val context = ApplicationProvider.getApplicationContext<android.content.Context>()
            composeRule.onNodeWithContentDescription(context.getString(R.string.dictation_cancel)).assertDoesNotExist()
            composeRule
                .onNodeWithContentDescription(context.getString(R.string.voice_message_record))
                .assertDoesNotExist()
        } finally {
            voiceRecording.release()
        }
    }

    @Test
    fun composerReplyShowsConvergenceWarning() {
        val warning = "May not be visible to everyone"

        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = false) {
                ComposerBar(
                    replyingTo = replyRecord(),
                    replyingToDisplay =
                        TimelineReplyDisplay(
                            sender = "alice",
                            body = "Parent message",
                            warning = warning,
                        ),
                    messageTextCopy = MessageTextCopy.Default,
                    onCancelReply = {},
                    onSend = { _, _ -> },
                )
            }
        }

        composeRule.onNodeWithText(warning).assertIsDisplayed()
    }

    private fun render(
        darkTheme: Boolean,
        amoled: Boolean = false,
        draft: String,
        width: Int = 360,
        fontScale: Float = 1f,
        rtl: Boolean = false,
        dictationPreview: DictationPreview? = null,
        showReply: Boolean = false,
        showEdit: Boolean = false,
        voiceRecordingController: VoiceRecordingController? = null,
        attachmentsEnabled: Boolean = false,
    ) {
        val dictation = dictationPreview?.let { createDictationPreview(it, TextFieldValue(draft)) }
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, fontScale),
                LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
            ) {
                WhiteNoiseTheme(darkTheme = darkTheme, amoled = amoled) {
                    Surface(modifier = Modifier.width(width.dp).testTag(TAG)) {
                        ComposerBar(
                            replyingTo = replyRecord().takeIf { showReply },
                            replyingToDisplay =
                                TimelineReplyDisplay(
                                    sender = "alice",
                                    body = "Parent message",
                                ).takeIf { showReply },
                            messageTextCopy = MessageTextCopy.Default,
                            onCancelReply = {},
                            onSend = { _, _ -> },
                            onPickFromGallery = {}.takeIf { attachmentsEnabled },
                            onPickDocument = {}.takeIf { attachmentsEnabled },
                            initialDraft = TextFieldValue(draft),
                            editingMessageId = "edited-message".takeIf { showEdit },
                            editingInitialText = "Message being edited".takeIf { showEdit },
                            dictationController = dictation,
                            dictationAccountRef = dictation?.let { ACCOUNT },
                            dictationGroupIdHex = dictation?.let { GROUP },
                            voiceRecordingController = voiceRecordingController,
                        )
                    }
                }
            }
        }
    }

    private fun renderLongComposer(
        darkTheme: Boolean,
        amoled: Boolean = false,
        largeRtl: Boolean = false,
        rtl: Boolean = false,
        selectionAtEnd: Boolean = false,
    ) {
        val draft =
            if (selectionAtEnd) {
                (1..16).joinToString("\n") { line -> "Composer caret regression line $line." }
            } else {
                "A thoughtful long message starts here.\n" +
                    "It keeps growing naturally line by line.\n" +
                    "The controls remain easy to reach.\n" +
                    "Nothing in the draft is replaced.\n" +
                    "The final paragraph stays visible while editing."
            }
        composeRule.setContent {
            val baseDensity = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(baseDensity.density, if (largeRtl) 1.45f else 1f),
                LocalLayoutDirection provides if (largeRtl || rtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
            ) {
                WhiteNoiseTheme(darkTheme = darkTheme, amoled = amoled) {
                    Surface(modifier = Modifier.width(360.dp).height(720.dp)) {
                        Box {
                            ComposerBar(
                                replyingTo = null,
                                messageTextCopy = MessageTextCopy.Default,
                                onCancelReply = {},
                                onSend = { _, _ -> },
                                onPickFromGallery = {},
                                onPickDocument = {},
                                initialDraft =
                                    TextFieldValue(
                                        text = draft,
                                        selection = if (selectionAtEnd) TextRange(draft.length) else TextRange.Zero,
                                    ),
                                modifier = Modifier.testTag(LONG_TAG),
                            )
                        }
                    }
                }
            }
        }
        composeRule.waitForIdle()
    }

    /** Renders a short focused composer at the bottom of a fixed phone viewport. */
    private fun renderBulkPasteComposer() {
        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = false) {
                Surface(Modifier.width(360.dp).height(720.dp).testTag(BULK_PASTE_TAG)) {
                    Box(contentAlignment = Alignment.BottomCenter) {
                        ComposerBar(
                            replyingTo = null,
                            messageTextCopy = MessageTextCopy.Default,
                            onCancelReply = {},
                            onSend = { _, _ -> },
                            initialDraft = TextFieldValue("Short"),
                        )
                    }
                }
            }
        }
        composeRule.waitForIdle()
    }

    private fun previewVoiceRecordingController(): VoiceRecordingController {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        return VoiceRecordingController(
            context = context,
            outputDirectory = context.cacheDir,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            onPermissionRequest = { true },
            onRecordingComplete = { _, _ -> },
            onError = {},
        )
    }

    private fun createDictationPreview(
        preview: DictationPreview,
        draft: TextFieldValue,
    ): ConversationDictationController {
        val platform = FakeDictationPlatform()
        val controller =
            ConversationDictationController(
                platform = platform,
                readDraft = { _, _ -> ConversationDictationDraftSnapshot(draft, 0L) },
                writeDraft = { _, _, _, _ -> true },
                disclosureAccepted = { true },
                markDisclosureAccepted = {},
                scheduleTimeout = { _, _ -> ConversationDictationTimeoutHandle {} },
                elapsedRealtime = { 0L },
            )
        when (preview) {
            DictationPreview.Idle -> Unit
            DictationPreview.Listening -> {
                controller.requestStart(ACCOUNT, GROUP, draft)
                platform.listener.onReady()
            }
            DictationPreview.Processing -> {
                controller.requestStart(ACCOUNT, GROUP, draft)
                platform.listener.onReady()
                platform.listener.onEndOfSpeech()
            }
            DictationPreview.ElsewhereListening -> {
                controller.requestStart(OTHER_ACCOUNT, OTHER_GROUP, draft)
                platform.listener.onReady()
            }
        }
        return controller
    }

    private fun replyRecord() =
        AppMessageRecordFfi(
            messageIdHex = "parent",
            direction = "received",
            groupIdHex = "group",
            sender = "alice",
            plaintext = "Parent message",
            contentTokens =
                MarkdownDocumentFfi(
                    truncated = false,
                    blocks = emptyList(),
                    blankLinesBefore = ByteArray(0),
                ),
            kind = 9uL,
            tags = emptyList(),
            sourceEpoch = null,
            retentionSeconds = null,
            retentionExpiresAt = null,
            recordedAt = 1uL,
            receivedAt = 1uL,
        )

    private companion object {
        const val TAG = "composer-bar"
        const val LONG_TAG = "long-composer-bar"
        const val BULK_PASTE_TAG = "bulk-paste-composer"
        const val RECORDING_STRIP_TAG = "composer-voice-recording-strip"
        const val ACCOUNT = "account"
        const val GROUP = "group"
        const val OTHER_ACCOUNT = "other-account"
        const val OTHER_GROUP = "other-group"
    }

    private enum class DictationPreview {
        Idle,
        Listening,
        Processing,
        ElsewhereListening,
    }

    @Suppress("MaxLineLength")
    private class FakeDictationPlatform : ConversationDictationPlatform {
        lateinit var listener: ConversationDictationRecognitionListener

        override fun hasRecordAudioPermission() = true

        override fun recognitionAvailable() = true

        override fun createSession(listener: ConversationDictationRecognitionListener): ConversationDictationRecognitionSession {
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
