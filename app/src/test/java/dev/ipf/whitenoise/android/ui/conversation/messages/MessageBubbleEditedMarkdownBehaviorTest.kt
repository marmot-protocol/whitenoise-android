package dev.ipf.whitenoise.android.ui.conversation.messages

import android.speech.tts.TextToSpeech
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
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
import dev.ipf.whitenoise.android.ui.conversation.TimelineRowMessageBubble
import dev.ipf.whitenoise.android.ui.conversation.composer.ComposerGate
import dev.ipf.whitenoise.android.ui.conversation.composer.ComposerTextState
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class MessageBubbleEditedMarkdownBehaviorTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val app = ApplicationProvider.getApplicationContext<android.app.Application>()

    @Test
    @Suppress("LongMethod")
    fun editedMarkdownTransitionsPlaybackSelectionAndStopInOneComposition() {
        val editedDocument = editedDocument("value")
        val editedText = "Edited *value*"
        val record = editedRecord()
        val appState = appState()
        val engine = FakeMessageBubbleTtsSpeechEngine()
        appState.ttsController.attachEngine(engine)
        val controller = ConversationController(appState = appState, initialGroup = group())
        controller.editsByTarget = mapOf(MESSAGE_ID to editState(editedText))
        val item =
            TimelineMessage(
                id = "msg:$MESSAGE_ID",
                record = record,
                status = MessageStatus.Received,
            )
        val composerTextState = ComposerTextState(TextFieldValue(""))
        var textSelectionMode by mutableStateOf(false)

        composeRule.setContent {
            WhiteNoiseTheme {
                TimelineRowMessageBubble(
                    messageIdHex = MESSAGE_ID,
                    item = item,
                    controller = controller,
                    appState = appState,
                    composerTextState = composerTextState,
                    highlighted = false,
                    selectionMode = false,
                    textSelectionMode = textSelectionMode,
                    onTextSelectionModeChange = { textSelectionMode = it },
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
                    parseMarkdown = { editedDocument },
                )
            }
        }

        composeRule.waitForIdle()
        composeRule.onNodeWithText("Edited value").assertExists()
        assertNull(highlightRange("value"))

        val entry =
            runBlocking {
                projectTtsSpeakableEntry(
                    message = record,
                    editedText = editedText,
                    senderDisplayName = "Alice",
                    parseMarkdown = { editedDocument },
                )!!
            }
        check(appState.ttsController.speak(listOf(entry), Locale.US))
        engine.range(index = 0, start = 14, end = 19)
        composeRule.waitForIdle()
        assertEquals(7 until 12, highlightRange("value"))
        composeRule
            .onNodeWithTag("tts-read-aloud-progress")
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.ContentDescription,
                    listOf(app.getString(R.string.tts_bar_progress, 1, 1, 1, 1)),
                ),
            )

        textSelectionMode = true
        composeRule.waitForIdle()
        assertNull(highlightRange("value"))
        composeRule.onNodeWithTag("tts-read-aloud-progress").assertDoesNotExist()
        composeRule.onNodeWithText("Edited value").assertExists()

        textSelectionMode = false
        appState.ttsController.stop()
        composeRule.waitForIdle()
        assertNull(highlightRange("value"))
        composeRule.onNodeWithTag("tts-read-aloud-progress").assertDoesNotExist()
        composeRule.onNodeWithText("Edited value").assertExists()
    }

    @Test
    @Suppress("LongMethod")
    fun supersededEditParseCompletingLateDoesNotRevertDisplay() {
        val parseBStarted = CompletableDeferred<Unit>()
        val parseBGate = CompletableDeferred<Unit>()
        val appState = appState()
        val engine = FakeMessageBubbleTtsSpeechEngine()
        appState.ttsController.attachEngine(engine)
        val controller = ConversationController(appState = appState, initialGroup = group())
        controller.editsByTarget = mapOf(MESSAGE_ID to editState(EDIT_A))
        val record = editedRecord()
        val item =
            TimelineMessage(
                id = "msg:$MESSAGE_ID",
                record = record,
                status = MessageStatus.Received,
            )
        val composerTextState = ComposerTextState(TextFieldValue(""))

        composeRule.setContent {
            WhiteNoiseTheme {
                TimelineRowMessageBubble(
                    messageIdHex = MESSAGE_ID,
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
                    parseMarkdown = { text ->
                        when (text) {
                            EDIT_A -> editedDocument("alpha")
                            EDIT_B -> {
                                parseBStarted.complete(Unit)
                                try {
                                    parseBGate.await()
                                } catch (_: CancellationException) {
                                    // Model an FFI parser that finishes after its caller was cancelled.
                                    withContext(NonCancellable) { parseBGate.await() }
                                }
                                editedDocument("beta")
                            }
                            EDIT_C -> editedDocument("gamma")
                            else -> emptyDocument()
                        }
                    },
                )
            }
        }

        composeRule.waitForIdle()
        composeRule.onNodeWithText("Edited alpha").assertExists()
        val entry =
            runBlocking {
                projectTtsSpeakableEntry(
                    message = record,
                    editedText = EDIT_A,
                    senderDisplayName = "Alice",
                    parseMarkdown = { editedDocument("alpha") },
                )!!
            }
        check(appState.ttsController.speak(listOf(entry), Locale.US))
        engine.range(index = 0, start = 14, end = 19)
        composeRule.waitForIdle()
        assertEquals(7 until 12, highlightRange("alpha"))
        composeRule.onNodeWithTag("tts-read-aloud-progress").assertExists()

        composeRule.runOnIdle {
            controller.editsByTarget = mapOf(MESSAGE_ID to editState(EDIT_B))
        }
        composeRule.onNodeWithText(EDIT_B).assertExists()
        composeRule.onNodeWithText("Edited alpha", substring = true).assertDoesNotExist()
        assertNull(highlightRange(EDIT_B))
        composeRule.onNodeWithTag("tts-read-aloud-progress").assertDoesNotExist()
        composeRule.waitUntil(timeoutMillis = 5_000) { parseBStarted.isCompleted }
        composeRule.runOnIdle {
            controller.editsByTarget = mapOf(MESSAGE_ID to editState(EDIT_C))
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Edited gamma").assertExists()
        composeRule.onNodeWithText("Edited alpha", substring = true).assertDoesNotExist()
        composeRule.onNodeWithText("Edited beta", substring = true).assertDoesNotExist()
        assertNull(highlightRange("gamma"))
        composeRule.onNodeWithTag("tts-read-aloud-progress").assertDoesNotExist()

        parseBGate.complete(Unit)
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Edited gamma").assertExists()
        composeRule.onNodeWithText("Edited alpha", substring = true).assertDoesNotExist()
        composeRule.onNodeWithText("Edited beta", substring = true).assertDoesNotExist()
        assertNull(highlightRange("gamma"))
        composeRule.onNodeWithTag("tts-read-aloud-progress").assertDoesNotExist()
    }

    private fun highlightRange(text: String): IntRange? =
        composeRule
            .onNodeWithText(text, substring = true)
            .fetchSemanticsNode()
            .config
            .getOrNull(TtsReadAloudHighlightRangeKey)

    private fun editState(text: String) =
        EditState(
            latestText = text,
            count = 1,
            versions =
                listOf(
                    EditVersion(
                        messageIdHex = MESSAGE_ID,
                        text = text,
                        recordedAt = 2uL,
                    ),
                ),
        )

    private fun editedDocument(word: String) =
        MarkdownDocumentFfi(
            truncated = false,
            blankLinesBefore = byteArrayOf(),
            blocks =
                listOf(
                    MarkdownBlockFfi.Paragraph(
                        inlines =
                            listOf(
                                MarkdownInlineFfi.Text("Edited "),
                                MarkdownInlineFfi.Emph(listOf(MarkdownInlineFfi.Text(word))),
                            ),
                    ),
                ),
        )

    private fun emptyDocument() =
        MarkdownDocumentFfi(
            truncated = false,
            blocks = emptyList(),
            blankLinesBefore = byteArrayOf(),
        )

    private fun editedRecord() =
        AppMessageRecordFfi(
            messageIdHex = MESSAGE_ID,
            direction = "received",
            groupIdHex = GROUP_ID,
            sender = SENDER_ID,
            plaintext = "Original **value**",
            contentTokens =
                MarkdownDocumentFfi(
                    truncated = false,
                    blankLinesBefore = byteArrayOf(),
                    blocks =
                        listOf(
                            MarkdownBlockFfi.Paragraph(
                                inlines =
                                    listOf(
                                        MarkdownInlineFfi.Text("Original "),
                                        MarkdownInlineFfi.Strong(listOf(MarkdownInlineFfi.Text("value"))),
                                    ),
                            ),
                        ),
                ),
            kind = 9uL,
            tags = emptyList(),
            sourceEpoch = null,
            retentionSeconds = null,
            retentionExpiresAt = null,
            recordedAt = 1uL,
            receivedAt = 1uL,
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
            name = "Edited markdown behavior group",
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

    private class FakeMessageBubbleTtsSpeechEngine : TtsSpeechEngine {
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
        const val EDIT_A = "Edited *alpha*"
        const val EDIT_B = "Edited *beta*"
        const val EDIT_C = "Edited *gamma*"
        val ACCOUNT_ID = "01" + "00".repeat(31)
        val SENDER_ID = "02" + "00".repeat(31)
        val GROUP_ID = "04" + "00".repeat(31)
        val MESSAGE_ID = "05" + "00".repeat(31)
    }
}
