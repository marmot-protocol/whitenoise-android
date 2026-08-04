package dev.ipf.whitenoise.android.audio.tts

import android.speech.tts.TextToSpeech
import dev.ipf.marmotkit.AppMessageRecordFfi
import dev.ipf.marmotkit.MarkdownDocumentFfi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/**
 * Session-level pagination behavior against the real controller, queue, and
 * chunker — only the speech engine and the conversation pager are fakes.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TtsHistorySessionTest {
    @Test
    fun previousMessageAtHeadSpeaksTheNearestOlderSpeakableFromTheLoadedTimeline() =
        runTest {
            val harness = SessionHarness(this)
            harness.loadTimeline("m1", "m2", "m3")
            harness.speakConversation("m3")

            harness.session.previousMessage()
            assertEquals(
                TtsHistoryEdgeState.Loading(TtsHistoryDirection.Older),
                harness.session.edgeState.value,
            )
            advanceUntilIdle()

            assertNull(harness.session.edgeState.value)
            assertEquals(listOf("m1", "m2", "m3"), harness.controller.queuedMessageIds())
            val state = harness.controller.state.value as TtsState.Speaking
            assertEquals(1, state.messageIndex)
            assertEquals(3, state.messageCount)
            assertEquals(
                "Nm2: Text m2.",
                harness.engine.spoken
                    .map { it.text }
                    .takeLast(2)
                    .first(),
            )
            assertEquals(0, harness.pager.loadOlderCalls)
        }

    @Test
    fun previousMessagePagesOlderWhenTheLoadedWindowIsExhausted() =
        runTest {
            val harness = SessionHarness(this)
            harness.loadTimeline("m3")
            harness.pager.olderPages.addLast(listOf(harness.record("m1"), harness.record("m2")))
            harness.speakConversation("m3")

            harness.session.previousMessage()
            advanceUntilIdle()

            assertEquals(1, harness.pager.loadOlderCalls)
            assertEquals(listOf("m1", "m2", "m3"), harness.controller.queuedMessageIds())
            assertEquals(1, (harness.controller.state.value as TtsState.Speaking).messageIndex)
        }

    @Test
    fun previousMessageSkipsUnspeakableRecordsAcrossMultiplePages() =
        runTest {
            val harness = SessionHarness(this)
            harness.loadTimeline("m4")
            harness.pager.olderPages.addLast(listOf(harness.unspeakableRecord("u3")))
            harness.pager.olderPages.addLast(listOf(harness.record("m2")))
            harness.speakConversation("m4")

            harness.session.previousMessage()
            advanceUntilIdle()

            assertEquals(2, harness.pager.loadOlderCalls)
            assertEquals(listOf("m2", "m4"), harness.controller.queuedMessageIds())
            assertEquals(
                "Nm2: Text m2.",
                harness.engine.spoken
                    .map { it.text }
                    .takeLast(2)
                    .first(),
            )
        }

    @Test
    fun trueBeginningOfHistoryRestartsTheFirstMessageWithoutErrorOrCompletion() =
        runTest {
            val harness = SessionHarness(this)
            harness.loadTimeline("m1")
            harness.speakConversation("m1")

            harness.session.previousMessage()
            advanceUntilIdle()

            assertNull(harness.session.edgeState.value)
            val state = harness.controller.state.value
            assertTrue(state is TtsState.Speaking)
            assertEquals(0, state.chunkIndex)
        }

    @Test
    fun trueEndOfHistoryCompletesTheSessionNaturally() =
        runTest {
            val harness = SessionHarness(this)
            harness.loadTimeline("m1")
            harness.speakConversation("m1")

            harness.session.nextMessage()
            advanceUntilIdle()

            assertNull(harness.session.edgeState.value)
            assertTrue(harness.controller.state.value is TtsState.Idle)
        }

    @Test
    fun nextMessageAtTailPagesNewerAndSpeaksTheTarget() =
        runTest {
            val harness = SessionHarness(this)
            harness.loadTimeline("m1")
            harness.pager.newerPages.addLast(listOf(harness.record("m2"), harness.record("m3")))
            harness.speakConversation("m1")

            harness.session.nextMessage()
            advanceUntilIdle()

            assertEquals(1, harness.pager.loadNewerCalls)
            assertEquals(listOf("m1", "m2", "m3"), harness.controller.queuedMessageIds())
            val state = harness.controller.state.value as TtsState.Speaking
            assertEquals(1, state.messageIndex)
            assertEquals(
                "Nm2: Text m2.",
                harness.engine.spoken
                    .map { it.text }
                    .takeLast(2)
                    .first(),
            )
        }

    @Test
    fun failedEdgeLoadKeepsTheCursorAndTheNextTapRetriesOnlyThatRequest() =
        runTest {
            val harness = SessionHarness(this)
            harness.loadTimeline("m2")
            harness.pager.olderPages.addLast(listOf(harness.record("m1")))
            harness.pager.failNextOlderLoads = 1
            harness.speakConversation("m2")
            val stateBefore = harness.controller.state.value

            harness.session.previousMessage()
            advanceUntilIdle()

            assertEquals(
                TtsHistoryEdgeState.Failed(TtsHistoryDirection.Older),
                harness.session.edgeState.value,
            )
            assertEquals(stateBefore, harness.controller.state.value)
            assertEquals(1, harness.pager.loadOlderCalls)
            assertEquals(listOf("m2"), harness.controller.queuedMessageIds())

            harness.session.previousMessage()
            advanceUntilIdle()

            assertNull(harness.session.edgeState.value)
            assertEquals(2, harness.pager.loadOlderCalls)
            assertEquals(listOf("m1", "m2"), harness.controller.queuedMessageIds())
        }

    @Test
    fun edgeTapsWhileLoadingCoalesceIntoASingleRequest() =
        runTest {
            val harness = SessionHarness(this)
            harness.loadTimeline("m2")
            harness.pager.olderPages.addLast(listOf(harness.record("m1")))
            harness.speakConversation("m2")

            harness.session.previousMessage()
            harness.session.previousMessage()
            harness.session.previousMessage()
            advanceUntilIdle()

            assertEquals(1, harness.pager.loadOlderCalls)
            assertEquals(listOf("m1", "m2"), harness.controller.queuedMessageIds())
        }

    @Test
    fun stopMidLoadCancelsThePageJobAndDropsItsCompletion() =
        runTest {
            val harness = SessionHarness(this)
            harness.loadTimeline("m2")
            harness.pager.olderPages.addLast(listOf(harness.record("m1")))
            harness.speakConversation("m2")

            harness.session.previousMessage()
            harness.controller.stop()
            harness.session.onSessionCleared()
            advanceUntilIdle()

            assertNull(harness.session.edgeState.value)
            assertTrue(harness.controller.state.value is TtsState.Idle)
            assertTrue(harness.controller.queuedMessageIds().isEmpty())
        }

    @Test
    fun aNewManualStartMidLoadInvalidatesTheStaleCompletion() =
        runTest {
            val harness = SessionHarness(this)
            harness.loadTimeline("m2")
            harness.pager.olderPages.addLast(listOf(harness.record("m1")))
            harness.speakConversation("m2")
            harness.session.previousMessage()

            harness.speakConversation("m9")
            advanceUntilIdle()

            assertNull(harness.session.edgeState.value)
            assertEquals(listOf("m9"), harness.controller.queuedMessageIds())
        }

    @Test
    fun pausedEdgeNavigationRepositionsSilentlyAndSpeaksOnResume() =
        runTest {
            val harness = SessionHarness(this)
            harness.loadTimeline("m1", "m2")
            harness.speakConversation("m2")
            harness.controller.pause()
            val spokenBefore = harness.engine.spoken.size

            harness.session.previousMessage()
            advanceUntilIdle()

            assertNull(harness.session.edgeState.value)
            val paused = harness.controller.state.value as TtsState.Paused
            assertEquals(0, paused.messageIndex)
            assertEquals(2, paused.messageCount)
            assertEquals(spokenBefore, harness.engine.spoken.size)

            harness.controller.resume()

            assertEquals(
                "Nm1: Text m1.",
                harness.engine.spoken
                    .map { it.text }
                    .takeLast(2)
                    .first(),
            )
        }

    @Test
    fun pagingAwayFromTheLiveTailRefusesLiveAppends() =
        runTest {
            val harness = SessionHarness(this)
            val queued = (11..61).map { "m%02d".format(it) }
            val older = (1..10).map { "m%02d".format(it) }
            harness.loadTimeline(*(older + queued).toTypedArray())
            harness.speakConversation(*queued.take(50).toTypedArray())
            assertTrue(harness.controller.appendSpeech(harness.entry("m61"), Locale.US))
            assertTrue(harness.session.allowsLiveAppend())

            harness.session.previousMessage()
            advanceUntilIdle()

            // 51 queued + 10 older overflows the window cap and evicts the
            // newest tail, detaching the session from the live edge.
            assertEquals(TTS_HISTORY_WINDOW_MAX_MESSAGES, harness.controller.queuedMessageIds().size)
            assertEquals("m01", harness.controller.queuedMessageIds().first())
            assertFalse(harness.session.allowsLiveAppend())
        }

    @Test
    fun ordinaryBoundedAutoReadStopsAtItsCapWithoutPaginating() =
        runTest {
            val harness = SessionHarness(this)
            val ids = (1..60).map { "m%02d".format(it) }
            harness.loadTimeline(*ids.toTypedArray())
            harness.speakConversation(*ids.toTypedArray())

            assertEquals(TTS_AUTO_READ_MAX_MESSAGES, harness.controller.queuedMessageIds().size)
            var next = 0
            while (harness.controller.state.value is TtsState.Speaking) {
                harness.engine.complete(next)
                next += 1
            }

            assertTrue(harness.controller.state.value is TtsState.Idle)
            assertEquals(0, harness.pager.loadOlderCalls)
            assertEquals(0, harness.pager.loadNewerCalls)
        }

    @Test
    fun aMissingConversationControllerFailsRetryablyInsteadOfCompleting() =
        runTest {
            val harness = SessionHarness(this)
            harness.loadTimeline("m1", "m2")
            harness.speakConversation("m2")
            harness.pagerAvailable = false
            val stateBefore = harness.controller.state.value

            harness.session.previousMessage()
            advanceUntilIdle()

            assertEquals(
                TtsHistoryEdgeState.Failed(TtsHistoryDirection.Older),
                harness.session.edgeState.value,
            )
            assertEquals(stateBefore, harness.controller.state.value)
        }

    private class SessionHarness(
        testScope: TestScope,
    ) {
        val engine = FakeSessionEngine()
        val controller =
            TtsController(
                audioFocus = FakeSessionFocus(),
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

        fun speakConversation(vararg ids: String) {
            check(controller.speak(ids.map(::entry), Locale.US)) { "speak must start" }
            session.onConversationSessionStarted("account", "group")
        }

        fun entry(id: String): TtsSpeakableEntry =
            TtsSpeakableEntry(
                senderKey = "s-$id",
                senderDisplayName = "N$id",
                text = "Text $id.",
                messageIdHex = id,
            )

        fun record(id: String): AppMessageRecordFfi {
            pager.speakableTextById[id] = "Text $id."
            return rawRecord(id)
        }

        fun unspeakableRecord(id: String): AppMessageRecordFfi = rawRecord(id)

        private fun rawRecord(id: String): AppMessageRecordFfi =
            AppMessageRecordFfi(
                messageIdHex = id,
                direction = "received",
                groupIdHex = "group",
                sender = "s-$id",
                plaintext = "Text $id.",
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
                recordedAt = 1uL,
                receivedAt = 1uL,
            )
    }

    private class FakeHistoryPager : TtsHistoryPager {
        val loaded = mutableListOf<AppMessageRecordFfi>()
        val olderPages = ArrayDeque<List<AppMessageRecordFfi>>()
        val newerPages = ArrayDeque<List<AppMessageRecordFfi>>()
        val speakableTextById = mutableMapOf<String, String>()
        var failNextOlderLoads = 0
        var loadOlderCalls = 0
        var loadNewerCalls = 0

        override val hasMoreBefore: Boolean get() = olderPages.isNotEmpty()
        override val hasMoreAfter: Boolean get() = newerPages.isNotEmpty()

        override fun timelineRecords(): List<AppMessageRecordFfi> = loaded.toList()

        override suspend fun loadOlder(): Boolean {
            loadOlderCalls += 1
            val failing = failNextOlderLoads > 0
            if (failing) failNextOlderLoads -= 1
            val page = if (failing) null else olderPages.removeFirstOrNull()
            page?.let { loaded.addAll(0, it) }
            return page != null
        }

        override suspend fun loadNewer(): Boolean {
            loadNewerCalls += 1
            val page = newerPages.removeFirstOrNull()
            page?.let(loaded::addAll)
            return page != null
        }

        override suspend fun ensureLoaded(id: String): Boolean = loaded.any { it.messageIdHex == id }

        override suspend fun projectSpeakable(record: AppMessageRecordFfi): TtsSpeakableEntry? =
            speakableTextById[record.messageIdHex]?.let { text ->
                TtsSpeakableEntry(
                    senderKey = record.sender,
                    senderDisplayName = "N${record.messageIdHex}",
                    text = text,
                    messageIdHex = record.messageIdHex,
                )
            }
    }

    private class FakeSessionEngine : TtsSpeechEngine {
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

    private class FakeSessionFocus : TtsAudioFocus {
        override fun acquire(
            onFocusLoss: () -> Unit,
            onOwnerSurrender: () -> Unit,
        ): Boolean = true

        override fun release() = Unit
    }
}
