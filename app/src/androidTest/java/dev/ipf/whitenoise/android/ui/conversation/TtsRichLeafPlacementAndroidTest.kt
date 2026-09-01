package dev.ipf.whitenoise.android.ui.conversation

import android.speech.tts.TextToSpeech
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.text.AnnotatedString
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
import dev.ipf.whitenoise.android.audio.tts.TtsSpeakableEntry
import dev.ipf.whitenoise.android.audio.tts.TtsSpeechEngine
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
import dev.ipf.whitenoise.android.ui.conversation.messages.TtsReadAloudSentenceHighlightRangeKey
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Locale

/**
 * Placement where spoken and visible text diverge.
 *
 * `TtsHighlightPlacementAndroidTest` uses one plain paragraph, where the spoken
 * offset and the rendered offset happen to agree. The mapping that actually
 * carries risk is the one that survives content the projection speaks
 * differently from how the bubble draws it: several Markdown leaves in one
 * message, and a bare URL that speech omits entirely while the bubble still
 * shows it. In that case an engine range near the start of the spoken text
 * addresses a word far along the rendered line, and an off-by-the-URL mapping
 * is invisible to any presence-only assertion.
 *
 * Offsets are taken from the projection the app itself built, which is exactly
 * the coordinate space engine callbacks arrive in.
 */
@RunWith(AndroidJUnit4::class)
class TtsRichLeafPlacementAndroidTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val engine = ReplayEngine()
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
    fun aWordAfterAnOmittedUrlIsHighlightedInItsRenderedPlace() {
        val entry = startSpeaking(OMITTED_URL_BODY, omittedUrlDocument())

        // The URL is not spoken, so "details" sits far earlier in the spoken
        // text than in the rendered line. That offset difference is the point.
        val payload = engine.submitted.first()
        val offset = payload.indexOf(WORD_AFTER_URL)
        check(offset >= 0) { "fixture word missing from engine payload: '$payload'" }
        engine.range(0, offset, offset + WORD_AFTER_URL.length)
        composeRule.waitForIdle()

        assertEquals(
            "the marker did not land on \"$WORD_AFTER_URL\"; engine payload was \"$payload\"",
            WORD_AFTER_URL,
            highlightedWord("payload=\"$payload\""),
        )
    }

    @Test
    fun everyRichLeafHighlightsItsOwnWord() {
        val entry = startSpeaking(RICH_BODY, richDocument())
        Log.i(TAG, "richSpokenText=\"${entry.text}\"")

        for (word in RICH_WORDS) {
            // Each of these words lives in a different sentence, so it belongs to
            // a different utterance with its own offset space. The queue submits
            // every chunk up front, so the one being spoken must be addressed by
            // index and reached by completing the utterances before it.
            val chunkIndex = engine.submitted.indexOfFirst { it.contains(word) }
            check(chunkIndex >= 0) { "fixture word '$word' missing from ${engine.submitted}" }
            val payload = engine.submitted[chunkIndex]
            val offset = payload.indexOf(word)
            engine.advanceTo(chunkIndex)
            engine.range(chunkIndex, offset, offset + word.length)
            composeRule.waitForIdle()
            assertEquals(
                "the marker did not land on \"$word\" in its rendered leaf",
                word,
                highlightedWord("word=\"$word\" chunk=$chunkIndex payload=\"$payload\" offset=$offset"),
            )
        }
    }

    /**
     * The characters the rendered word marker currently covers.
     *
     * The primary range a leaf publishes is `word ?: sentence`, so in a
     * multi-leaf message every leaf carrying the sentence band also carries a
     * primary range. Only the leaf whose primary range differs from its own
     * sentence range is the one holding the word.
     */
    private fun highlightedWord(diagnostic: String = ""): String {
        val candidates =
            composeRule
                .onRoot(useUnmergedTree = true)
                .fetchSemanticsNode()
                .descendants()
                .filter { it.config.getOrNull(TtsReadAloudHighlightRangeKey) != null }
        val leaf =
            candidates.firstOrNull { node ->
                node.config.getOrNull(TtsReadAloudHighlightRangeKey) !=
                    node.config.getOrNull(TtsReadAloudSentenceHighlightRangeKey)
            }
        assertNotNull(
            "no leaf carried a word range distinct from its sentence band; $diagnostic candidates=" +
                candidates.joinToString { node ->
                    val primary = node.config.getOrNull(TtsReadAloudHighlightRangeKey)
                    val sentence = node.config.getOrNull(TtsReadAloudSentenceHighlightRangeKey)
                    "\"${node.text()}\" primary=$primary sentence=$sentence"
                },
            leaf,
        )
        val range = checkNotNull(leaf!!.config.getOrNull(TtsReadAloudHighlightRangeKey))
        return leaf.text().substring(range.first, range.last + 1)
    }

    private fun SemanticsNode.descendants(): List<SemanticsNode> = children + children.flatMap { it.descendants() }

    private fun SemanticsNode.text(): String =
        config
            .getOrNull(SemanticsProperties.Text)
            .orEmpty()
            .joinToString("") { annotated: AnnotatedString -> annotated.text }

    private fun startSpeaking(
        body: String,
        document: MarkdownDocumentFfi,
    ): TtsSpeakableEntry {
        val record = speakableRecord(body, document)
        val entry =
            runBlocking {
                projectTtsSpeakableEntry(
                    message = record,
                    editedText = null,
                    senderDisplayName = SENDER_NAME,
                    parseMarkdown = { document },
                )!!
            }
        composeRule.setContent {
            val item = timelineMessage(record)
            WhiteNoiseTheme {
                Box(Modifier.fillMaxWidth()) {
                    key(item.record.messageIdHex) { row(item) }
                }
            }
        }
        composeRule.waitForIdle()
        check(appState.ttsController.speak(listOf(entry), Locale.US))
        composeRule.waitForIdle()
        return entry
    }

    /** "See <url> for details." with the URL as its own autolink label. */
    private fun omittedUrlDocument() =
        MarkdownDocumentFfi(
            truncated = false,
            blankLinesBefore = byteArrayOf(),
            blocks =
                listOf(
                    MarkdownBlockFfi.Paragraph(
                        inlines =
                            listOf(
                                MarkdownInlineFfi.Text("See "),
                                MarkdownInlineFfi.Link(
                                    dest = OMITTED_URL,
                                    title = null,
                                    children = listOf(MarkdownInlineFfi.Text(OMITTED_URL)),
                                    classification = MarkdownLinkDestinationKindFfi.WEB,
                                ),
                                MarkdownInlineFfi.Text(" for $WORD_AFTER_URL."),
                            ),
                    ),
                ),
        )

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

    @Composable
    @Suppress("FunctionNaming", "LongMethod")
    private fun row(item: TimelineMessage) {
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
            parseMarkdown = { item.record.contentTokens },
        )
    }

    private fun timelineMessage(record: AppMessageRecordFfi) =
        TimelineMessage(id = "msg:${record.messageIdHex}", record = record, status = MessageStatus.Received)

    private fun speakableRecord(
        plaintext: String,
        document: MarkdownDocumentFfi,
    ) = AppMessageRecordFfi(
        messageIdHex = MESSAGE_ID,
        direction = "received",
        groupIdHex = GROUP_ID,
        sender = SENDER_ID,
        plaintext = plaintext,
        contentTokens = document,
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
            name = "Rich leaf placement group",
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

    private class ReplayEngine : TtsSpeechEngine {
        val submitted = mutableListOf<String>()
        private val spoken = mutableListOf<String>()
        private var current = 0
        private var rangeCallback: ((String?, Int, Int, Int) -> Unit)? = null
        private var startCallback: ((String?) -> Unit)? = null
        private var doneCallback: ((String?) -> Unit)? = null

        override fun setLanguage(locale: Locale): Int = TextToSpeech.LANG_AVAILABLE

        override fun setSpeechRate(rate: Float) = Unit

        override fun setCallbacks(
            onStart: (String?) -> Unit,
            onDone: (String?) -> Unit,
            onError: (String?, Int) -> Unit,
            onRangeStart: (String?, Int, Int, Int) -> Unit,
            onStop: (String?, Boolean) -> Unit,
        ) {
            startCallback = onStart
            doneCallback = onDone
            rangeCallback = onRangeStart
        }

        override fun clearCallbacks() {
            rangeCallback = null
            startCallback = null
            doneCallback = null
        }

        /** Completes utterances until [chunkIndex] is the one being spoken. */
        fun advanceTo(chunkIndex: Int) {
            while (current < chunkIndex) {
                doneCallback?.invoke(spoken[current])
                current++
                startCallback?.invoke(spoken[current])
            }
        }

        override fun speak(
            text: String,
            utteranceId: String,
        ): Int {
            spoken += utteranceId
            submitted += text
            return TextToSpeech.SUCCESS
        }

        override fun stop() = Unit

        /**
         * The queue pre-buffers every chunk up front, so the utterance being
         * spoken is not the last one submitted. Address it by index.
         */
        fun range(
            chunkIndex: Int,
            start: Int,
            end: Int,
        ) {
            rangeCallback?.invoke(spoken[chunkIndex], start, end, 0)
        }
    }

    private companion object {
        const val TAG = "WnTtsRich"
        const val SENDER_NAME = "Alice"
        const val PREFIX = "$SENDER_NAME: "
        const val ACCOUNT_REF = "personal"
        val ACCOUNT_ID = "01" + "00".repeat(31)
        val SENDER_ID = "02" + "00".repeat(31)
        val GROUP_ID = "04" + "00".repeat(31)
        val MESSAGE_ID = "0a" + "00".repeat(31)

        const val OMITTED_URL = "https://example.com/a/rather/long/path/that/is/never/spoken"
        const val WORD_AFTER_URL = "details"
        const val OMITTED_URL_BODY = "See $OMITTED_URL for $WORD_AFTER_URL."

        const val RICH_BODY =
            "# Release notes\n\nImportant **bright** details with `code` and " +
                "[a link](https://example.com/docs).\n\n- First item.\n\n> A quoted line."
        val RICH_WORDS = listOf("Release", "bright", "code", "link", "First", "quoted")
    }
}
