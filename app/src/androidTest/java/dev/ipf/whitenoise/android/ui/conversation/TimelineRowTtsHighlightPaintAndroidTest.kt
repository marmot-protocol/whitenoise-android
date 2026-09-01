package dev.ipf.whitenoise.android.ui.conversation

import android.speech.tts.TextToSpeech
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.text.input.TextFieldValue
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
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
import dev.ipf.marmotkit.MarkdownLinkDestinationKindFfi
import dev.ipf.marmotkit.MarkdownListItemFfi
import dev.ipf.marmotkit.MarkdownListKindFfi
import dev.ipf.marmotkit.SelfMembershipFfi
import dev.ipf.whitenoise.android.audio.tts.TtsSpeechEngine
import dev.ipf.whitenoise.android.audio.tts.TtsState
import dev.ipf.whitenoise.android.audio.tts.projectTtsSpeakableEntry
import dev.ipf.whitenoise.android.state.ConversationController
import dev.ipf.whitenoise.android.state.DraftPersistence
import dev.ipf.whitenoise.android.state.DraftStore
import dev.ipf.whitenoise.android.state.MessageStatus
import dev.ipf.whitenoise.android.state.TimelineMessage
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.ui.conversation.composer.ComposerGate
import dev.ipf.whitenoise.android.ui.conversation.composer.ComposerTextState
import dev.ipf.whitenoise.android.ui.conversation.messages.TtsReadAloudHighlightRangeKey
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Locale

/**
 * The device counterpart of `TimelineRowTtsHighlightPaintTest`.
 *
 * Robolectric renders text through a simulated layout engine, so a highlight
 * whose geometry degenerates only under real font metrics stays invisible to
 * the JVM suite. This runs the same three shapes on a device, where
 * [androidx.compose.ui.text.TextLayoutResult] reports the measurements the
 * painter actually consumes.
 *
 * Every gate between
 * [dev.ipf.whitenoise.android.audio.tts.TtsController] and the leaf painter can
 * drop that paint while semantics, progress, and every range assertion stay
 * green. This drives a real controller passage through the production timeline
 * row and compares rendered pixels, so a highlight that never reaches the
 * screen fails here.
 */
@RunWith(AndroidJUnit4::class)
class TimelineRowTtsHighlightPaintAndroidTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val engine = FakePaintTtsSpeechEngine()
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
    fun speakingPassagePaintsSentenceAndWordHighlightThroughTheProductionRow() {
        val record = speakableRecord(MESSAGE_A, BODY)
        val entry =
            runBlocking {
                projectTtsSpeakableEntry(
                    message = record,
                    editedText = null,
                    senderDisplayName = SENDER_NAME,
                    parseMarkdown = { plainTextDocument(BODY) },
                )!!
            }

        composeRule.setContent {
            val item = timelineMessage(record)
            WhiteNoiseTheme {
                Box(Modifier.fillMaxWidth()) {
                    key(item.record.messageIdHex) {
                        row(item)
                    }
                }
            }
        }

        composeRule.waitForIdle()
        val idlePixels = renderedPixels()

        check(appState.ttsController.speak(listOf(entry), Locale.US))
        composeRule.waitForIdle()
        val sentencePixels = renderedPixels()
        val sentenceDiagnostics = seamDiagnostics()

        engine.range(index = 0, start = WORD_START, end = WORD_END)
        composeRule.waitForIdle()
        val wordPixels = renderedPixels()
        val wordDiagnostics = seamDiagnostics()

        assertTrue(
            "Starting read-aloud painted nothing: the rendered row is pixel-identical to the idle row. " +
                "Seam state: $sentenceDiagnostics",
            changedPixelCount(idlePixels, sentencePixels) > 0,
        )
        assertTrue(
            "An engine word range painted nothing: the rendered row is unchanged by the active word. " +
                "Seam state: $wordDiagnostics",
            changedPixelCount(sentencePixels, wordPixels) > 0,
        )
    }

    @Test
    fun speakingPassagePaintsHighlightThroughRichMarkdownLeaves() {
        val record = richRecord()
        val entry =
            runBlocking {
                projectTtsSpeakableEntry(
                    message = record,
                    editedText = null,
                    senderDisplayName = SENDER_NAME,
                    parseMarkdown = { richDocument() },
                )!!
            }

        composeRule.setContent {
            val item = timelineMessage(record)
            WhiteNoiseTheme {
                Box(Modifier.fillMaxWidth()) {
                    key(item.record.messageIdHex) {
                        row(item)
                    }
                }
            }
        }

        composeRule.waitForIdle()
        val idlePixels = renderedPixels()

        check(appState.ttsController.speak(listOf(entry), Locale.US))
        composeRule.waitForIdle()
        val speakingPixels = renderedPixels()
        val diagnostics = seamDiagnostics()

        assertTrue(
            "Read-aloud on a rich Markdown message painted nothing. Seam state: $diagnostics",
            changedPixelCount(idlePixels, speakingPixels) > 0,
        )
    }

    @Test
    fun speakingPassagePaintsHighlightWhileCollapseLongMessagesIsEnabled() {
        val body = (1..LONG_BODY_LINES).joinToString(" ") { "Sentence $it about bright things." }
        val record = speakableRecord(MESSAGE_C, body)
        val entry =
            runBlocking {
                projectTtsSpeakableEntry(
                    message = record,
                    editedText = null,
                    senderDisplayName = SENDER_NAME,
                    parseMarkdown = { plainTextDocument(body) },
                )!!
            }

        composeRule.setContent {
            val item = timelineMessage(record)
            WhiteNoiseTheme {
                Box(Modifier.fillMaxWidth()) {
                    key(item.record.messageIdHex) {
                        row(item, collapseLongMessages = true)
                    }
                }
            }
        }

        composeRule.waitForIdle()
        val idlePixels = renderedPixels()

        check(appState.ttsController.speak(listOf(entry), Locale.US))
        composeRule.waitForIdle()
        val speakingPixels = renderedPixels()
        val diagnostics = seamDiagnostics()

        assertTrue(
            "Read-aloud painted nothing while Collapse long messages was enabled. Seam state: $diagnostics",
            changedPixelCount(idlePixels, speakingPixels) > 0,
        )
    }

    /**
     * Names the first seam that dropped the highlight. The progress tag proves
     * the passage cleared the bubble's message and projection identity gate;
     * the semantics range proves the projection resolver produced a rendered
     * highlight for a leaf.
     */
    private fun seamDiagnostics(): String {
        val passageState = appState.ttsController.state.value
        val progressPresent =
            runCatching {
                composeRule
                    .onNodeWithTag("tts-read-aloud-progress", useUnmergedTree = true)
                    .fetchSemanticsNode()
            }.isSuccess
        val highlightRange =
            runCatching {
                composeRule
                    .onNodeWithText("bright", substring = true, useUnmergedTree = true)
                    .fetchSemanticsNode()
                    .config
                    .getOrNull(TtsReadAloudHighlightRangeKey)
            }.getOrNull()
        return "controllerState=${passageState::class.simpleName} " +
            "controllerPassage=${(passageState as? TtsState.Speaking)?.passage} " +
            "projectionGatePassed=$progressPresent " +
            "renderedHighlightRange=$highlightRange"
    }

    @Composable
    @Suppress("FunctionNaming", "LongMethod")
    private fun row(
        item: TimelineMessage,
        collapseLongMessages: Boolean = false,
    ) {
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
            collapseLongMessages = collapseLongMessages,
            readOnly = false,
            parseMarkdown = { item.record.contentTokens },
        )
    }

    private fun richRecord() =
        AppMessageRecordFfi(
            messageIdHex = MESSAGE_B,
            direction = "received",
            groupIdHex = GROUP_ID,
            sender = SENDER_ID,
            plaintext = RICH_BODY,
            contentTokens = richDocument(),
            kind = 9uL,
            tags = emptyList(),
            sourceEpoch = null,
            retentionSeconds = null,
            retentionExpiresAt = null,
            recordedAt = 1uL,
            receivedAt = 1uL,
        )

    /**
     * Mirrors the shapes a tester actually reads aloud: a heading, emphasis,
     * inline code, a link, a list, and a quote. Each rendered leaf must still
     * align with the projected speech text for the highlight to survive.
     */
    private fun richDocument() =
        MarkdownDocumentFfi(
            truncated = false,
            blankLinesBefore = byteArrayOf(0, 0, 0, 0),
            blocks =
                listOf(
                    MarkdownBlockFfi.Heading(
                        level = 1u,
                        inlines = listOf(MarkdownInlineFfi.Text("Release notes")),
                    ),
                    MarkdownBlockFfi.Paragraph(
                        inlines =
                            listOf(
                                MarkdownInlineFfi.Text("Important "),
                                MarkdownInlineFfi.Strong(listOf(MarkdownInlineFfi.Text("bright"))),
                                MarkdownInlineFfi.Text(" details with "),
                                MarkdownInlineFfi.Code("code"),
                                MarkdownInlineFfi.Text(" and "),
                                MarkdownInlineFfi.Link(
                                    dest = "https://example.com/docs",
                                    title = null,
                                    children = listOf(MarkdownInlineFfi.Text("a link")),
                                    classification = MarkdownLinkDestinationKindFfi.WEB,
                                ),
                                MarkdownInlineFfi.Text("."),
                            ),
                    ),
                    MarkdownBlockFfi.ListBlock(
                        kind = MarkdownListKindFfi.Bullet("-"),
                        tight = true,
                        items =
                            listOf(
                                MarkdownListItemFfi(
                                    blocks =
                                        listOf(
                                            MarkdownBlockFfi.Paragraph(
                                                inlines = listOf(MarkdownInlineFfi.Text("First item.")),
                                            ),
                                        ),
                                    checked = null,
                                    blankLinesBefore = byteArrayOf(0),
                                ),
                            ),
                    ),
                    MarkdownBlockFfi.BlockQuote(
                        blocks =
                            listOf(
                                MarkdownBlockFfi.Paragraph(
                                    inlines = listOf(MarkdownInlineFfi.Text("A quoted line.")),
                                ),
                            ),
                        blankLinesBefore = byteArrayOf(0),
                    ),
                ),
        )

    /**
     * A frame with one colour in it is a broken capture, not a rendered row:
     * the harness draws text on a bubble, so a real frame always holds several.
     * Native capture can fail transiently under load, and reporting that as
     * "painted nothing" would accuse the production code of this suite's own
     * flakiness. Retry once, then say plainly which one happened.
     */
    private fun renderedPixels(): IntArray {
        repeat(2) { attempt ->
            val pixels = capturedPixels()
            if (pixels.any { it != pixels[0] }) return pixels
            if (attempt == 0) composeRule.waitForIdle()
        }
        throw AssertionError(
            "Capture produced a uniform frame twice, so no rendering was observed at all. " +
                "This is a capture failure, not a missing highlight.",
        )
    }

    private fun capturedPixels(): IntArray {
        val pixelMap = composeRule.onRoot().captureToImage().toPixelMap()
        return IntArray(pixelMap.width * pixelMap.height) { index ->
            pixelMap[index % pixelMap.width, index / pixelMap.width].toArgb()
        }
    }

    private fun changedPixelCount(
        before: IntArray,
        after: IntArray,
    ): Int {
        if (before.size != after.size) return maxOf(before.size, after.size)
        var changed = 0
        before.indices.forEach { index -> if (before[index] != after[index]) changed += 1 }
        return changed
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
            name = "Read-aloud paint group",
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

    private class FakePaintTtsSpeechEngine : TtsSpeechEngine {
        private val spoken = mutableListOf<String>()
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
            spoken += utteranceId
            return TextToSpeech.SUCCESS
        }

        override fun stop() = Unit

        fun range(
            index: Int,
            start: Int,
            end: Int,
        ) {
            rangeCallback?.invoke(spoken[index], start, end, 0)
        }
    }

    private companion object {
        const val ACCOUNT_REF = "personal"
        const val SENDER_NAME = "Alice"
        const val BODY = "Hello bright world."
        val ACCOUNT_ID = "01" + "00".repeat(31)
        val SENDER_ID = "02" + "00".repeat(31)
        val GROUP_ID = "04" + "00".repeat(31)
        val MESSAGE_A = "05" + "00".repeat(31)
        val MESSAGE_B = "06" + "00".repeat(31)
        val MESSAGE_C = "07" + "00".repeat(31)
        const val LONG_BODY_LINES = 90
        const val RICH_BODY =
            "# Release notes\n\nImportant **bright** details with `code` and [a link](https://example.com/docs).\n\n- First item.\n\n> A quoted line."

        // "bright" inside the engine payload, which carries the "Alice: "
        // sender announcement in front of the message body.
        val WORD_START = "$SENDER_NAME: ".length + BODY.indexOf("bright")
        val WORD_END = WORD_START + "bright".length
    }
}
