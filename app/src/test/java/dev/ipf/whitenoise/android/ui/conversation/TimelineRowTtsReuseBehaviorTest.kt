package dev.ipf.whitenoise.android.ui.conversation

import android.speech.tts.TextToSpeech
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.input.TextFieldValue
import androidx.test.core.app.ApplicationProvider
import dev.ipf.marmotkit.AccountSummaryFfi
import dev.ipf.marmotkit.AppBlobEndpointFfi
import dev.ipf.marmotkit.AppGroupEncryptedMediaComponentFfi
import dev.ipf.marmotkit.AppGroupRecordFfi
import dev.ipf.marmotkit.AppMessageRecordFfi
import dev.ipf.marmotkit.AppProtocolProfileFfi
import dev.ipf.marmotkit.EncryptedMediaVersionFfi
import dev.ipf.marmotkit.MarkdownBlockFfi
import dev.ipf.marmotkit.MarkdownDocumentFfi
import dev.ipf.marmotkit.MarkdownInlineFfi
import dev.ipf.marmotkit.SelfMembershipFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.audio.tts.TtsSpeechEngine
import dev.ipf.whitenoise.android.audio.tts.projectTtsSpeakableEntry
import dev.ipf.whitenoise.android.core.EditState
import dev.ipf.whitenoise.android.core.EditVersion
import dev.ipf.whitenoise.android.state.ConversationController
import dev.ipf.whitenoise.android.state.DraftPersistence
import dev.ipf.whitenoise.android.state.DraftStore
import dev.ipf.whitenoise.android.state.MessageStatus
import dev.ipf.whitenoise.android.state.TimelineMessage
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.ui.conversation.composer.ComposerGate
import dev.ipf.whitenoise.android.ui.conversation.composer.ComposerTextState
import dev.ipf.whitenoise.android.ui.conversation.messages.TtsReadAloudHighlightRangeKey
import dev.ipf.whitenoise.android.ui.conversation.messages.TtsReadAloudHighlightStyle
import dev.ipf.whitenoise.android.ui.conversation.messages.colorFromArgb
import dev.ipf.whitenoise.android.ui.conversation.messages.messageBubblePresentation
import dev.ipf.whitenoise.android.ui.conversation.messages.rememberTtsReadAloudHighlightStyle
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import dev.ipf.whitenoise.android.ui.theme.isAmoledSurfaceTheme
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class TimelineRowTtsReuseBehaviorTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val app = ApplicationProvider.getApplicationContext<android.app.Application>()
    private val engine = FakeTimelineTtsSpeechEngine()
    private lateinit var appState: WhiteNoiseAppState
    private lateinit var controller: ConversationController
    private val composerTextState = ComposerTextState(TextFieldValue(""))

    @Before
    fun setUp() {
        appState = appState()
        controller = ConversationController(appState = appState, initialGroup = group())
        appState.ttsController.attachEngine(engine)
    }

    @Test
    @Suppress("LongMethod")
    fun keyedRowReuseClearsHighlightAndReadAloudProgress() {
        val activeEdit = "Hello *bright* world."
        val activeRecord = speakableRecord(MESSAGE_A, "Original body.")
        val otherRecord = speakableRecord(MESSAGE_B, "Other message body.")
        controller.editsByTarget = mapOf(MESSAGE_A to editState(activeEdit))
        val entry =
            runBlocking {
                projectTtsSpeakableEntry(
                    message = activeRecord,
                    editedText = activeEdit,
                    senderDisplayName = "Alice",
                    parseMarkdown = { editedDocument() },
                )!!
            }
        check(appState.ttsController.speak(listOf(entry), Locale.US))
        engine.range(index = 0, start = 13, end = 19)

        var showOtherMessage by mutableStateOf(false)
        lateinit var expectedPaint: TtsReadAloudHighlightStyle

        composeRule.setContent {
            val record = if (showOtherMessage) otherRecord else activeRecord
            val item = timelineMessage(record)
            WhiteNoiseTheme {
                val presentation = messageBubblePresentation(deleted = false, mine = false)
                expectedPaint =
                    rememberTtsReadAloudHighlightStyle(
                        background = colorFromArgb(presentation.backgroundArgb),
                        content = colorFromArgb(presentation.contentArgb),
                        sentenceAccent = MaterialTheme.colorScheme.outlineVariant,
                        wordAccent = MaterialTheme.colorScheme.tertiary,
                        amoled = isAmoledSurfaceTheme(),
                    )
                Box(Modifier.fillMaxWidth()) {
                    key(item.record.messageIdHex) {
                        TimelineRowMessageBubble(
                            messageIdHex = item.record.messageIdHex,
                            item = item,
                            controller = controller,
                            appState = appState,
                            composerTextState = composerTextState,
                            highlighted = false,
                            selectionMode = false,
                            textSelectionMode = false,
                            onTextSelectionModeChange = {},
                            onTextSelectionBoundsChange = {},
                            batchSelectable = false,
                            selected = false,
                            onToggleSelection = {},
                            rangeDragActive = false,
                            onDragSelectionStart = {},
                            onDragSelection = { false },
                            onDragSelectionEnd = {},
                            onDragSelectionCancel = {},
                            quickReactionEmojis = emptyList(),
                            recentEmojis = emptyList(),
                            onEmojiUsed = {},
                            isActionMenuOpen = false,
                            onActionMenuOpenChange = {},
                            onQuickReactionsSave = {},
                            onQuickReactionsReset = {},
                            onReplyPreviewClick = {},
                            composerGate = ComposerGate.COMPOSER,
                            onBack = {},
                            mentionCandidates = emptyList(),
                            mentionPickerEnabled = false,
                            showSenderName = false,
                            showSenderAvatar = false,
                            collapseLongMessages = false,
                            readOnly = false,
                            parseMarkdown = { editedDocument() },
                        )
                    }
                }
            }
        }

        composeRule.waitForIdle()
        assertEquals(6 until 12, highlightRange("bright"))
        val rendered =
            composeRule
                .onNodeWithText("bright", substring = true, useUnmergedTree = true)
                .captureToImage()
                .toPixelMap()
        assertTrue(rendered.containsArgb(expectedPaint.sentenceFill.toArgb()))
        assertTrue(rendered.containsArgb(expectedPaint.sentenceMarker.toArgb()))
        assertTrue(rendered.containsArgbOutsideStartRail(expectedPaint.wordMarker.toArgb()))
        composeRule
            .onNodeWithTag("tts-read-aloud-progress")
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.ContentDescription,
                    listOf(
                        app.getString(
                            R.string.tts_bar_progress,
                            1,
                            1,
                            1,
                            1,
                        ),
                    ),
                ),
            )

        composeRule.runOnIdle { showOtherMessage = true }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Hello", substring = true, useUnmergedTree = true).assertDoesNotExist()
        composeRule.onNodeWithText("Other", substring = true, useUnmergedTree = true).assertExists()
        composeRule.onNodeWithText("bright", substring = true, useUnmergedTree = true).assertDoesNotExist()
        assertNull(highlightRange("Other"))
        composeRule.onNodeWithTag("tts-read-aloud-progress").assertDoesNotExist()
    }

    private fun highlightRange(text: String): IntRange? =
        composeRule
            .onNodeWithText(text, substring = true, useUnmergedTree = true)
            .fetchSemanticsNode()
            .config
            .getOrNull(TtsReadAloudHighlightRangeKey)

    private fun androidx.compose.ui.graphics.PixelMap.containsArgb(expected: Int): Boolean {
        for (y in 0 until height) {
            for (x in 0 until width) {
                if (this[x, y].toArgb() == expected) return true
            }
        }
        return false
    }

    private fun androidx.compose.ui.graphics.PixelMap.containsArgbOutsideStartRail(expected: Int): Boolean {
        for (y in (height / 2) until height) {
            for (x in 6 until width) {
                if (this[x, y].toArgb() == expected) return true
            }
        }
        return false
    }

    private fun timelineMessage(record: AppMessageRecordFfi) =
        TimelineMessage(
            id = "msg:${record.messageIdHex}",
            record = record,
            status = MessageStatus.Received,
        )

    private fun speakableRecord(
        messageIdHex: String,
        plaintext: String,
    ) = AppMessageRecordFfi(
        messageIdHex = messageIdHex,
        direction = "received",
        groupIdHex = GROUP_ID,
        sender = SENDER_ID,
        plaintext = plaintext,
        contentTokens = plainTextDocument(plaintext),
        kind = 9uL,
        tags = emptyList(),
        sourceEpoch = null,
        retentionSeconds = null,
        retentionExpiresAt = null,
        recordedAt = 1uL,
        receivedAt = 1uL,
    )

    private fun plainTextDocument(text: String) =
        MarkdownDocumentFfi(
            truncated = false,
            blankLinesBefore = byteArrayOf(),
            blocks =
                listOf(
                    MarkdownBlockFfi.Paragraph(
                        inlines = listOf(MarkdownInlineFfi.Text(text)),
                    ),
                ),
        )

    private fun editState(text: String) =
        EditState(
            latestText = text,
            count = 1,
            versions =
                listOf(
                    EditVersion(
                        messageIdHex = MESSAGE_A,
                        text = text,
                        recordedAt = 2uL,
                    ),
                ),
        )

    private fun editedDocument() =
        MarkdownDocumentFfi(
            truncated = false,
            blankLinesBefore = byteArrayOf(),
            blocks =
                listOf(
                    MarkdownBlockFfi.Paragraph(
                        inlines =
                            listOf(
                                MarkdownInlineFfi.Text("Hello "),
                                MarkdownInlineFfi.Emph(listOf(MarkdownInlineFfi.Text("bright"))),
                                MarkdownInlineFfi.Text(" world."),
                            ),
                    ),
                ),
        )

    private fun appState() =
        WhiteNoiseAppState(
            context = context,
            draftStore = DraftStore(EmptyDraftPersistence()),
            accountIdHexResolver = { null },
            accounts =
                listOf(
                    AccountSummaryFfi(
                        label = ACCOUNT_REF,
                        accountIdHex = ACCOUNT_ID,
                        localSigning = true,
                        externalSigning = false,
                        signedOut = false,
                        running = true,
                    ),
                ),
            activeAccountRef = ACCOUNT_REF,
        )

    private fun group() =
        AppGroupRecordFfi(
            groupIdHex = GROUP_ID,
            protocolProfile = AppProtocolProfileFfi.LEGACY,
            endpoint = "wss://relay.example",
            profilePresent = true,
            name = "Timeline row reuse group",
            description = "",
            admins = listOf(ACCOUNT_ID),
            relays = emptyList(),
            nostrGroupIdHex = "03".repeat(32),
            avatarUrl = null,
            avatarDim = null,
            avatarThumbhash = null,
            imageHashHex = null,
            encryptedMedia =
                AppGroupEncryptedMediaComponentFfi(
                    componentId = 0x8008u,
                    component = "marmot.group.encrypted-media.v1",
                    required = true,
                    version = EncryptedMediaVersionFfi.V1,
                    mediaFormat = "encrypted-media-v1",
                    allowedLocatorKinds = listOf("blossom-v1"),
                    defaultBlobEndpoints =
                        listOf(
                            AppBlobEndpointFfi(
                                locatorKind = "blossom-v1",
                                baseUrl = "https://blossom.example",
                            ),
                        ),
                ),
            disappearingMessageSecs = 0uL,
            archived = false,
            pendingConfirmation = false,
            unrecoverable = false,
            selfMembership = SelfMembershipFfi.MEMBER,
            leaveRequestPending = false,
            leaveRequestedAtMs = null,
            disbanding = false,
            disbandRequest = null,
            disbanded = false,
            welcomerAccountIdHex = null,
            viaWelcomeMessageIdHex = null,
        )

    private class EmptyDraftPersistence : DraftPersistence {
        override fun read(): Map<String, String> = emptyMap()

        override fun write(
            key: String,
            value: String?,
        ) = Unit
    }

    private class FakeTimelineTtsSpeechEngine : TtsSpeechEngine {
        private val spoken = mutableListOf<Spoken>()
        private var rangeCallback: ((String?, Int, Int, Int) -> Unit)? = null

        override fun setLanguage(locale: Locale): Int = TextToSpeech.LANG_AVAILABLE

        override fun setSpeechRate(rate: Float) = Unit

        override fun setCallbacks(
            onStart: (String?) -> Unit,
            onDone: (String?) -> Unit,
            onError: (String?, Int) -> Unit,
            onRangeStart: (String?, Int, Int, Int) -> Unit,
            onStop: (String?, Boolean) -> Unit,
        ) {
            rangeCallback = onRangeStart
        }

        override fun clearCallbacks() {
            rangeCallback = null
        }

        override fun speak(
            text: String,
            utteranceId: String,
        ): Int {
            spoken += Spoken(text, utteranceId)
            return TextToSpeech.SUCCESS
        }

        override fun stop() = Unit

        fun range(
            index: Int,
            start: Int,
            end: Int,
        ) {
            rangeCallback?.invoke(spoken[index].utteranceId, start, end, 0)
        }

        private data class Spoken(
            val text: String,
            val utteranceId: String,
        )
    }

    private companion object {
        const val ACCOUNT_REF = "personal"
        val ACCOUNT_ID = "01" + "00".repeat(31)
        val SENDER_ID = "02" + "00".repeat(31)
        val GROUP_ID = "04" + "00".repeat(31)
        val MESSAGE_A = "05" + "00".repeat(31)
        val MESSAGE_B = "06" + "00".repeat(31)
    }
}
