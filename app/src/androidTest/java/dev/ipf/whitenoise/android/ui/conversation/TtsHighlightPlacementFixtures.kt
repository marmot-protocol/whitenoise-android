package dev.ipf.whitenoise.android.ui.conversation

import android.speech.tts.TextToSpeech
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.AnnotatedString
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
import dev.ipf.whitenoise.android.state.DraftPersistence
import dev.ipf.whitenoise.android.state.DraftStore
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import org.junit.Assert.assertEquals
import java.util.Locale

/** Reusable data, replay-engine and pixel fixtures for read-aloud placement scenarios. */
internal object TtsHighlightPlacementFixtures {
    fun SemanticsNode.descendants(): List<SemanticsNode> = children + children.flatMap { it.descendants() }

    fun SemanticsNode.text(): String =
        config
            .getOrNull(SemanticsProperties.Text)
            .orEmpty()
            .joinToString("") { annotated: AnnotatedString -> annotated.text }

    data class PixelPoint(
        val x: Float,
        val y: Float,
    )

    data class RenderedFrame(
        val width: Int,
        val height: Int,
        val pixels: IntArray,
    ) {
        /** Lists centers of pixels changed between equal-sized frames. */
        fun changedPixels(other: RenderedFrame): List<PixelPoint> {
            assertEquals(width, other.width)
            assertEquals(height, other.height)
            return pixels.indices
                .filter { pixels[it] != other.pixels[it] }
                .map { index -> PixelPoint(index % width + 0.5f, index / width + 0.5f) }
        }
    }

    enum class SentenceStartPlacement {
        WrappedLineStart,
        MidLine,
    }

    /** Places a local character cell into root coordinates. */
    fun androidx.compose.ui.geometry.Rect.translate(
        x: Float,
        y: Float,
    ): androidx.compose.ui.geometry.Rect =
        androidx.compose.ui.geometry
            .Rect(left + x, top + y, right + x, bottom + y)

    /** Pixel-center containment with one-pixel rasterization tolerance by default. */
    fun androidx.compose.ui.geometry.Rect.contains(
        x: Float,
        y: Float,
        tolerance: Float = 1f,
    ): Boolean = x >= left - tolerance && x <= right + tolerance && y >= top - tolerance && y <= bottom + tolerance

    fun speakableRecord(
        plaintext: String,
        document: MarkdownDocumentFfi = plainTextDocument(plaintext),
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

    fun plainTextDocument(text: String) =
        MarkdownDocumentFfi(
            truncated = false,
            blankLinesBefore = byteArrayOf(),
            blocks = listOf(MarkdownBlockFfi.Paragraph(inlines = listOf(MarkdownInlineFfi.Text(text)))),
        )

    /** Keeps the selected second sentence in a styled Markdown leaf between plain siblings. */
    fun markdownSentenceDocument() =
        MarkdownDocumentFfi(
            truncated = false,
            blankLinesBefore = byteArrayOf(),
            blocks =
                listOf(
                    MarkdownBlockFfi.Paragraph(
                        inlines =
                            listOf(
                                MarkdownInlineFfi.Text("$FIRST_SENTENCE "),
                                MarkdownInlineFfi.Strong(listOf(MarkdownInlineFfi.Text(SECOND_SENTENCE))),
                                MarkdownInlineFfi.Text(" $THIRD_SENTENCE"),
                            ),
                    ),
                ),
        )

    fun appState(context: android.content.Context) =
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

    fun group() =
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

    class ReplayEngine : TtsSpeechEngine {
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

        fun start(chunkIndex: Int) {
            startCallback?.invoke(spoken[chunkIndex])
        }

        fun done(chunkIndex: Int) {
            doneCallback?.invoke(spoken[chunkIndex])
        }

        /**
         * The queue submits every chunk up front, so the utterance being spoken
         * is not the last one submitted. Addressing the wrong one is silently
         * rejected as stale, which looks exactly like a passing test.
         */
        fun range(
            chunkIndex: Int,
            start: Int,
            end: Int,
        ) {
            rangeCallback?.invoke(spoken[chunkIndex], start, end, 0)
        }
    }

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
    const val THIRD_SENTENCE = "The third sentence stays clear."
    const val THREE_SENTENCES = "$FIRST_SENTENCE $SECOND_SENTENCE $THIRD_SENTENCE"
    val SECOND_SENTENCE_START = THREE_SENTENCES.indexOf(SECOND_SENTENCE)
    const val MIN_WRAP_WIDTH = 120
    const val MAX_WRAP_WIDTH = 480
    const val WRAP_WIDTH_STEP_DP = 4
    const val TEST_LOG_TAG = "WnTtsPlacement"
    const val RTL_FIRST_SENTENCE = "המשפט הראשון כאן."
    const val RTL_SECOND_SENTENCE = "המשפט השני מודגש."
    const val RTL_THIRD_SENTENCE = "המשפט השלישי נשאר נקי."
    const val RTL_THREE_SENTENCES = "$RTL_FIRST_SENTENCE $RTL_SECOND_SENTENCE $RTL_THIRD_SENTENCE"
}
