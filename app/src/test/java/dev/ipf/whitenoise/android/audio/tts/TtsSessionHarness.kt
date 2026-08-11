package dev.ipf.whitenoise.android.audio.tts

import android.speech.tts.TextToSpeech
import dev.ipf.marmotkit.AppMessageRecordFfi
import dev.ipf.marmotkit.MarkdownDocumentFfi
import dev.ipf.whitenoise.android.state.canonicalTimelineRecords
import dev.ipf.whitenoise.android.state.localTimelineMessage
import dev.ipf.whitenoise.android.state.projectedTimelineMessage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import java.util.Locale

/**
 * Session-level fixture shared by the read-aloud paging tests: the real
 * controller, queue, and chunker, with only the speech engine, the audio focus
 * owner, and the conversation pager faked.
 */
internal class SessionHarness(
    testScope: TestScope,
) {
    val engine = FakeSessionEngine()
    val focus = FakeSessionFocus()
    val controller =
        TtsController(
            audioFocus = focus,
            maxChunkLength = 4_000,
        )
    val pager = FakeHistoryPager()
    var pagerAvailable = true

    // Not backgroundScope: this coroutines-test version does not advance
    // background tasks through advanceUntilIdle, page jobs would starve.
    val session =
        TtsHistorySession(
            controller = controller,
            scope = CoroutineScope(StandardTestDispatcher(testScope.testScheduler) + SupervisorJob()),
        ) { _, _ -> pager.takeIf { pagerAvailable } }

    init {
        controller.attachEngine(engine)
    }

    fun loadTimeline(vararg ids: String) {
        pager.loaded += ids.map(::record)
    }

    /** Appends a speakable row the engine has NOT projected yet. */
    fun loadLocalOnlyRow(id: String) {
        pager.localOnlyIds += id
        pager.loaded += record(id)
    }

    fun speakConversation(vararg ids: String) = speakEntries(ids.map(::entry))

    fun speakEntries(entries: List<TtsSpeakableEntry>) {
        check(controller.speak(entries, Locale.US)) { "speak must start" }
        session.onConversationSessionStarted("account", "group")
    }

    /**
     * Generation of every spoken utterance, in order. Each restart, requeue, or
     * completion advances it, so this is what tells "played straight through"
     * apart from "replayed something".
     */
    fun spokenGenerations(): List<Long> = engine.spoken.map { utteranceGeneration(it.utteranceId) }

    /** Text of every spoken utterance, in order. */
    fun spokenTexts(): List<String> = engine.spoken.map(FakeSessionEngine.Spoken::text)

    fun entry(
        id: String,
        sentences: Int = 1,
    ): TtsSpeakableEntry =
        TtsSpeakableEntry(
            senderKey = "s-$id",
            senderDisplayName = "N$id",
            text = speakableText(id, sentences),
            messageIdHex = id,
            timelineAt = timelinePosition(id),
        )

    fun record(
        id: String,
        sentences: Int = 1,
    ): AppMessageRecordFfi {
        pager.speakableTextById[id] = speakableText(id, sentences)
        return rawRecord(id, speakableText(id, sentences))
    }

    fun unspeakableRecord(id: String): AppMessageRecordFfi = rawRecord(id, speakableText(id, sentences = 1))

    private fun speakableText(
        id: String,
        sentences: Int,
    ): String =
        buildString {
            append("Text $id.")
            for (sentence in 2..sentences) append(" More $id $sentence.")
        }

    private fun utteranceGeneration(utteranceId: String): Long {
        val stamped = utteranceId.removePrefix("whitenoise.tts.")
        return stamped.substringBefore('.').toLong()
    }

    private fun rawRecord(
        id: String,
        plaintext: String,
    ): AppMessageRecordFfi =
        AppMessageRecordFfi(
            messageIdHex = id,
            direction = "received",
            groupIdHex = "group",
            sender = "s-$id",
            plaintext = plaintext,
            contentTokens =
                MarkdownDocumentFfi(
                    truncated = false,
                    blocks = emptyList(),
                    blankLinesBefore = byteArrayOf(),
                ),
            kind = 9uL,
            tags = emptyList(),
            sourceEpoch = null,
            retentionSeconds = null,
            retentionExpiresAt = null,
            recordedAt = timelinePosition(id),
            receivedAt = 1uL,
        )

    // Fake ids carry their timeline order in their digits, so recovery
    // direction decisions mirror the production timestamp comparison.
    private fun timelinePosition(id: String): ULong = id.filter(Char::isDigit).toULongOrNull() ?: 1uL
}

internal class FakeHistoryPager : TtsHistoryPager {
    val loaded = mutableListOf<AppMessageRecordFfi>()
    val localOnlyIds = mutableSetOf<String>()
    val olderPages = ArrayDeque<List<AppMessageRecordFfi>>()
    val newerPages = ArrayDeque<List<AppMessageRecordFfi>>()
    val speakableTextById = mutableMapOf<String, String>()
    var failNextOlderLoads = 0
    var failNextNewerLoads = 0
    var loadOlderCalls = 0
    var loadNewerCalls = 0
    var projectSpeakableCalls = 0

    // Suspend the matching load after counting the call, so a test can hold
    // a page job genuinely in flight instead of merely scheduled.
    var loadOlderGate: CompletableDeferred<Unit>? = null
    var loadNewerGate: CompletableDeferred<Unit>? = null

    // Runs synchronously inside ensureLoaded, from the job's own stack.
    var onEnsureLoaded: (() -> Unit)? = null

    // Runs before each projection, letting a test inject a live arrival
    // mid-walk.
    var onProjectSpeakable: ((String) -> Unit)? = null

    override val hasMoreBefore: Boolean get() = olderPages.isNotEmpty()
    override val hasMoreAfter: Boolean get() = newerPages.isNotEmpty()

    // Routed through the production filter, like the real pager: the loaded
    // window interleaves local-only rows with projected ones and only the
    // projected ids survive reconciliation.
    override fun timelineRecords(): List<AppMessageRecordFfi> =
        canonicalTimelineRecords(
            loaded.map { record ->
                if (record.messageIdHex in localOnlyIds) {
                    localTimelineMessage(record)
                } else {
                    projectedTimelineMessage(record)
                }
            },
        )

    override suspend fun loadOlder(): Boolean {
        loadOlderCalls += 1
        loadOlderGate?.await()
        val failing = failNextOlderLoads > 0
        if (failing) failNextOlderLoads -= 1
        val page = if (failing) null else olderPages.removeFirstOrNull()
        page?.let { loaded.addAll(0, it) }
        return page != null
    }

    override suspend fun loadNewer(): Boolean {
        loadNewerCalls += 1
        loadNewerGate?.await()
        val failing = failNextNewerLoads > 0
        if (failing) failNextNewerLoads -= 1
        val page = if (failing) null else newerPages.removeFirstOrNull()
        page?.let(loaded::addAll)
        return page != null
    }

    // Mirrors the production two-direction recovery: page toward the
    // target's timeline position until present or that side is exhausted.
    override suspend fun ensureLoaded(
        messageIdHex: String,
        timelineAt: ULong,
    ): Boolean {
        onEnsureLoaded?.invoke()
        while (loaded.none { it.messageIdHex == messageIdHex }) {
            val advanced =
                when {
                    loaded.isEmpty() -> false
                    timelineAt < loaded.first().recordedAt -> loadOlder()
                    timelineAt > loaded.last().recordedAt -> loadNewer()
                    else -> false
                }
            if (!advanced) break
        }
        return loaded.any { it.messageIdHex == messageIdHex }
    }

    override suspend fun projectSpeakable(record: AppMessageRecordFfi): TtsSpeakableEntry? {
        projectSpeakableCalls += 1
        onProjectSpeakable?.invoke(record.messageIdHex)
        return speakableTextById[record.messageIdHex]?.let { text ->
            TtsSpeakableEntry(
                senderKey = record.sender,
                senderDisplayName = "N${record.messageIdHex}",
                text = text,
                messageIdHex = record.messageIdHex,
                timelineAt = record.recordedAt,
            )
        }
    }
}

internal class FakeSessionEngine : TtsSpeechEngine {
    data class Spoken(
        val text: String,
        val utteranceId: String,
    )

    val spoken = mutableListOf<Spoken>()
    private var onDone: ((String?) -> Unit)? = null

    override fun setLanguage(locale: Locale): Int = TextToSpeech.LANG_AVAILABLE

    override fun setSpeechRate(rate: Float) = Unit

    override fun setCallbacks(
        onDone: (String?) -> Unit,
        onError: (String?, Int) -> Unit,
        onRangeStart: (String?, Int, Int) -> Unit,
    ) {
        this.onDone = onDone
    }

    override fun clearCallbacks() {
        onDone = null
    }

    override fun speak(
        text: String,
        utteranceId: String,
    ): Int {
        spoken += Spoken(text, utteranceId)
        return TextToSpeech.SUCCESS
    }

    override fun stop() = Unit

    fun complete(index: Int) {
        onDone?.invoke(spoken[index].utteranceId)
    }
}

internal class FakeSessionFocus : TtsAudioFocus {
    var acquires = 0
        private set
    var releases = 0
        private set

    override fun acquire(
        onFocusLoss: () -> Unit,
        onOwnerSurrender: () -> Unit,
    ): Boolean {
        acquires += 1
        return true
    }

    override fun release() {
        releases += 1
    }
}
