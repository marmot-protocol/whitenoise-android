package dev.ipf.whitenoise.android.ui.conversation

import android.speech.tts.TextToSpeech
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.text.AnnotatedString
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
import java.util.Locale

/**
 * Placement, not merely presence.
 *
 * `TimelineRowTtsHighlightPaintAndroidTest` proves the painter runs by counting
 * changed pixels, which stays green for a highlight drawn over the wrong word.
 * This drives known engine ranges through the production row on a device and
 * asserts where the highlight landed: that the word marker covers exactly the
 * spoken characters, that the sentence band stops at the sentence being spoken,
 * and that each word's marker occupies its own place under real font metrics.
 */
class TtsHighlightPlacementAndroidTest {
    @get:Rule
    val composeRule = createComposeRule()

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
    fun eachEngineWordHighlightsExactlyThatWord() {
        startSpeaking(BODY)

        for (word in WORDS) {
            val start = PREFIX.length + BODY.indexOf(word)
            engine.range(start, start + word.length)
            composeRule.waitForIdle()

            val leaf = leafCarryingHighlight()
            assertNotNull("no rendered highlight for \"$word\"", leaf)
            val rendered = leaf!!.text()
            val range = leaf.config.getOrNull(TtsReadAloudHighlightRangeKey)
            assertNotNull("word \"$word\" produced no word range", range)
            assertEquals(
                "word marker landed on the wrong characters for \"$word\"",
                word,
                rendered.substring(range!!.first, range.last + 1),
            )
        }
    }

    @Test
    fun theSentenceBandCoversOnlyTheSentenceBeingSpoken() {
        startSpeaking(TWO_SENTENCES)

        // Still on the opening utterance: the band must stop at the first
        // sentence rather than covering the whole message.
        val first = PREFIX.length + TWO_SENTENCES.indexOf("first")
        engine.range(first, first + "first".length)
        composeRule.waitForIdle()
        assertEquals(
            "the band covers more than the sentence being spoken",
            FIRST_SENTENCE,
            renderedSentenceBand(),
        )
    }

    /**
     * The assertion real font metrics can fail and the JVM suite cannot make.
     *
     * The semantics range above is computed from the projection mapping and is
     * layout-independent, so Robolectric already covers it. What only a device
     * can answer is whether the paint lands under the word those coordinates
     * name: the word marker is a 2dp underline positioned from
     * `TextLayoutResult`, and a marker drawn at the wrong offset — or pinned to
     * one position for every word — still changes pixels and still passes a
     * `changedPixelCount > 0` check.
     */
    @Test
    fun eachWordMarkerIsPaintedInItsOwnPlace() {
        startSpeaking(BODY)
        val baseline = renderedPixels()

        // Bounding box, not just the horizontal span: a wrapped line legitimately
        // restarts at the same left margin, so x alone cannot tell "moved to the
        // next line" from "never moved".
        val boxes = WORDS.associateWith { word -> markerBoxAfterSpeaking(word, baseline) }
        boxes.forEach { (word, box) ->
            assertNotNull("highlighting \"$word\" painted nothing", box)
        }
        assertEquals(
            "distinct words share a marker box, so the paint is not following the word: $boxes",
            WORDS.size,
            boxes.values.toSet().size,
        )
    }

    /** Bounding box of the pixels this word's marker changed, or null. */
    private fun markerBoxAfterSpeaking(
        word: String,
        baseline: IntArray,
    ): MarkerBox? {
        val start = PREFIX.length + BODY.indexOf(word)
        engine.range(start, start + word.length)
        composeRule.waitForIdle()
        val pixelMap = composeRule.onRoot(useUnmergedTree = true).captureToImage().toPixelMap()
        var left = Int.MAX_VALUE
        var right = Int.MIN_VALUE
        var top = Int.MAX_VALUE
        var bottom = Int.MIN_VALUE
        for (index in baseline.indices) {
            val x = index % pixelMap.width
            val y = index / pixelMap.width
            if (baseline[index] != pixelMap[x, y].toArgb()) {
                if (x < left) left = x
                if (x > right) right = x
                if (y < top) top = y
                if (y > bottom) bottom = y
            }
        }
        return if (left <= right) MarkerBox(left, right, top, bottom) else null
    }

    private data class MarkerBox(
        val left: Int,
        val right: Int,
        val top: Int,
        val bottom: Int,
    )

    private fun renderedPixels(): IntArray {
        val pixelMap = composeRule.onRoot(useUnmergedTree = true).captureToImage().toPixelMap()
        return IntArray(pixelMap.width * pixelMap.height) { index ->
            pixelMap[index % pixelMap.width, index / pixelMap.width].toArgb()
        }
    }

    private fun renderedSentenceBand(): String {
        val leaf = leafCarryingHighlight()
        assertNotNull("no rendered highlight", leaf)
        val rendered = leaf!!.text()
        val sentence = leaf.config.getOrNull(TtsReadAloudSentenceHighlightRangeKey)
        assertNotNull("no sentence range was rendered", sentence)
        return rendered.substring(sentence!!.first, sentence.last + 1)
    }

    private fun startSpeaking(body: String) {
        val record = speakableRecord(body)
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
                    key(item.record.messageIdHex) { row(item) }
                }
            }
        }
        composeRule.waitForIdle()
        check(appState.ttsController.speak(listOf(entry), Locale.US))
        composeRule.waitForIdle()
    }

    private fun leafCarryingHighlight(): SemanticsNode? =
        composeRule
            .onRoot(useUnmergedTree = true)
            .fetchSemanticsNode()
            .descendants()
            .firstOrNull { node ->
                node.config.getOrNull(TtsReadAloudHighlightRangeKey) != null ||
                    node.config.getOrNull(TtsReadAloudSentenceHighlightRangeKey) != null
            }

    private fun SemanticsNode.descendants(): List<SemanticsNode> = children + children.flatMap { it.descendants() }

    private fun SemanticsNode.text(): String =
        config
            .getOrNull(SemanticsProperties.Text)
            .orEmpty()
            .joinToString("") { annotated: AnnotatedString -> annotated.text }

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

    private fun speakableRecord(plaintext: String) =
        AppMessageRecordFfi(
            messageIdHex = MESSAGE_ID,
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
            blocks = listOf(MarkdownBlockFfi.Paragraph(inlines = listOf(MarkdownInlineFfi.Text(text)))),
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
            name = "Read-aloud placement group",
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
        private val spoken = mutableListOf<String>()
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

        override fun speak(
            text: String,
            utteranceId: String,
        ): Int {
            spoken += utteranceId
            return TextToSpeech.SUCCESS
        }

        override fun stop() = Unit

        fun range(
            start: Int,
            end: Int,
        ) {
            rangeCallback?.invoke(spoken.last(), start, end, 0)
        }
    }

    private companion object {
        const val SENDER_NAME = "Alice"
        const val PREFIX = "$SENDER_NAME: "
        const val ACCOUNT_REF = "personal"
        val ACCOUNT_ID = "01" + "00".repeat(31)
        val SENDER_ID = "02" + "00".repeat(31)
        val GROUP_ID = "04" + "00".repeat(31)
        val MESSAGE_ID = "09" + "00".repeat(31)

        const val BODY = "Hello bright world of steady careful reading."
        val WORDS = listOf("Hello", "bright", "world", "steady", "careful", "reading")

        const val FIRST_SENTENCE = "The first sentence sits here."
        const val SECOND_SENTENCE = "The second one follows it."
        const val TWO_SENTENCES = "$FIRST_SENTENCE $SECOND_SENTENCE"
    }
}
