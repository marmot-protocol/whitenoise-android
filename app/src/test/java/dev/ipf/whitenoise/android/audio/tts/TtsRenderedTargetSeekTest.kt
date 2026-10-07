package dev.ipf.whitenoise.android.audio.tts

import dev.ipf.whitenoise.android.audio.tts.speech.PreparedRenderedHit
import dev.ipf.whitenoise.android.audio.tts.speech.PreparedSeekResolver
import dev.ipf.whitenoise.android.audio.tts.speech.PreparedSeekTarget
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class TtsRenderedTargetSeekTest {
    @Test
    fun finalUtteranceCanFinishWhileRenderedSeekPreparesWithoutLosingItsIntent() =
        runTest {
            val harness = SessionHarness(this)
            harness.speakConversation("m1")
            val session = harness.controller.state.value.sessionId
            val preparation = CompletableDeferred<Unit>()
            val committed = CompletableDeferred<Unit>()
            assertTrue(
                harness.session.requestRenderedSentenceSeek(
                    "m2",
                    2uL,
                    harness.session.conversationSource.value!!,
                    {
                        preparation.await()
                        request(harness, "m2")
                    },
                    { committed.complete(Unit) },
                ),
            )
            runCurrent()
            harness.engine.complete(0)
            runCurrent()
            assertEquals(session, harness.controller.state.value.sessionId)
            assertFalse(harness.controller.state.value is TtsState.Idle)
            preparation.complete(Unit)
            committed.await()
            assertEquals(session, harness.controller.state.value.sessionId)
            assertEquals(
                "m2",
                harness.controller.state.value.passage
                    ?.messageIdHex,
            )
            assertEquals(1, harness.controller.state.value.sentenceIndexWithinMessage)
            harness.engine.complete(harness.engine.spoken.lastIndex)
            runCurrent()
            assertTrue(harness.controller.state.value is TtsState.Idle)
        }

    @Test
    fun replacementKeepsSessionWhenFinalUtteranceCompletesDuringOldProjectionCancellation() =
        runTest {
            val harness = SessionHarness(this, UnconfinedTestDispatcher(testScheduler))
            harness.speakConversation("m1")
            val sessionId = harness.controller.state.value.sessionId
            val source = harness.session.conversationSource.value!!
            val oldGate = CompletableDeferred<Unit>()
            var cancellationCompletedSpeech = false
            assertTrue(
                harness.session.requestRenderedSentenceSeek(
                    "m2",
                    2uL,
                    source,
                    {
                        try {
                            oldGate.await()
                            request(harness, "m2")
                        } finally {
                            // Unconfined cancellation runs this callback before replacement re-arms.
                            harness.engine.complete(0)
                            cancellationCompletedSpeech = true
                        }
                    },
                    { error("The cancelled projection must not commit") },
                ),
            )
            val committed = CompletableDeferred<Unit>()
            assertTrue(
                harness.session.requestRenderedSentenceSeek(
                    "m3",
                    3uL,
                    source,
                    { request(harness, "m3") },
                    { committed.complete(Unit) },
                ),
            )
            committed.await()
            assertTrue(cancellationCompletedSpeech)
            assertEquals(sessionId, harness.controller.state.value.sessionId)
            assertEquals("m3", harness.controller.state.value.passage?.messageIdHex)
            harness.engine.complete(harness.engine.spoken.lastIndex)
            runCurrent()
            assertTrue(harness.controller.state.value is TtsState.Idle)
        }

    @Test
    fun renderedHitInstallsSecondRepeatedSentenceInTheSameSession() =
        runTest {
            val harness = SessionHarness(this)
            harness.speakConversation("m1")
            val session = harness.controller.state.value.sessionId
            assertTrue(harness.controller.installRenderedSeekTarget(request(harness, "m2"), session) { true })
            assertEquals(session, harness.controller.state.value.sessionId)
            assertEquals(
                "m2",
                harness.controller.state.value.passage
                    ?.messageIdHex,
            )
            assertEquals(1, harness.controller.state.value.sentenceIndexWithinMessage)
        }

    @Test
    fun staleSourceLeavesOriginalCursorAndSpeechUntouched() =
        runTest {
            val harness = SessionHarness(this)
            harness.speakConversation("m1")
            val state = harness.controller.state.value
            val spoken = harness.spokenTexts()
            assertFalse(
                harness.controller.installRenderedSeekTarget(
                    request(harness, "m2", canCommit = { false }),
                    state.sessionId,
                ) {
                    true
                },
            )
            assertEquals(state, harness.controller.state.value)
            assertEquals(spoken, harness.spokenTexts())
        }

    @Test
    fun pausedSeekKeepsExistingDirectSeekPlaybackIntent() =
        runTest {
            val harness = SessionHarness(this)
            harness.speakConversation("m1")
            harness.controller.pause()
            val session = harness.controller.state.value.sessionId
            val focusRequests = harness.focus.acquires
            assertTrue(harness.controller.installRenderedSeekTarget(request(harness, "m2"), session) { true })
            assertEquals(session, harness.controller.state.value.sessionId)
            assertTrue(harness.controller.state.value is TtsState.Speaking)
            assertEquals(focusRequests + 1, harness.focus.acquires)
        }

    @Test
    fun newerIntentCancelsOlderProjectionBeforeItCanReachPlayback() =
        runTest {
            val harness = SessionHarness(this)
            harness.speakConversation("m1")
            val state = harness.controller.state.value
            val gate = CompletableDeferred<Unit>()
            var oldProjectionFinished = false
            assertTrue(
                harness.session.requestRenderedSentenceSeek(
                    "m2",
                    2uL,
                    expectedSource = harness.session.conversationSource.value!!,
                    resolveTarget = {
                        gate.await()
                        oldProjectionFinished = true
                        null
                    },
                    onCommitted = { error("An unresolved target must not commit") },
                ),
            )
            runCurrent()
            harness.session.requestRenderedSentenceSeek(
                "m3",
                3uL,
                harness.session.conversationSource.value!!,
                { null },
                {},
            )
            advanceUntilIdle()
            gate.complete(Unit)
            advanceUntilIdle()
            assertFalse(oldProjectionFinished)
            assertEquals(state.sessionId, harness.controller.state.value.sessionId)
            assertEquals(listOf("m1"), harness.controller.queuedMessageIds())
        }

    @Test
    fun unresolvedRenderedTapSettlesAnInterruptedEdgeWalk() =
        runTest {
            val harness = SessionHarness(this)
            harness.loadTimeline("m1")
            val pageGate = CompletableDeferred<Unit>()
            harness.pager.newerPages.addLast(listOf(harness.record("m2")))
            harness.pager.loadNewerGate = pageGate
            harness.speakConversation("m1")
            harness.session.nextMessage()
            runCurrent()
            assertTrue(harness.session.edgeState.value is TtsHistoryEdgeState.Loading)
            harness.session.requestRenderedSentenceSeek(
                "m3",
                3uL,
                harness.session.conversationSource.value!!,
                { null },
                {},
            )
            advanceUntilIdle()
            harness.engine.complete(0)
            advanceUntilIdle()
            assertTrue(harness.controller.state.value is TtsState.Idle)
            assertEquals(null, harness.session.edgeState.value)
            pageGate.complete(Unit)
            advanceUntilIdle()
            assertEquals(listOf("m1"), harness.controller.queuedMessageIds())
        }

    @Test
    fun foreignConversationCannotCancelOrReplaceTheActiveSession() =
        runTest {
            val harness = SessionHarness(this)
            harness.speakConversation("m1")
            val state = harness.controller.state.value
            val owner = harness.session.conversationSource.value!!
            val foreignOwners =
                listOf(
                    owner.copy(accountRef = "another-account"),
                    owner.copy(groupIdHex = "another-group"),
                )
            for (foreignOwner in foreignOwners) {
                assertFalse(
                    harness.session.requestRenderedSentenceSeek(
                        "m2",
                        2uL,
                        foreignOwner,
                        { error("A foreign conversation must not project a target") },
                        { error("A foreign conversation must not commit") },
                    ),
                )
            }
            assertEquals(state, harness.controller.state.value)
            assertEquals(null, harness.session.edgeState.value)
        }

    @Test
    fun transportNavigationCancelsPendingRenderedProjection() =
        runTest {
            val harness = SessionHarness(this)
            harness.speakEntries(listOf(harness.entry("m1", sentences = 2)))
            val gate = CompletableDeferred<Unit>()
            var projectionFinished = false
            harness.session.requestRenderedSentenceSeek(
                "m2",
                2uL,
                harness.session.conversationSource.value!!,
                {
                    gate.await()
                    projectionFinished = true
                    null
                },
                { error("An unresolved target must not commit") },
            )
            runCurrent()
            harness.session.nextSentence()
            advanceUntilIdle()
            assertEquals(1, harness.controller.state.value.sentenceIndexWithinMessage)
            assertEquals(null, harness.session.edgeState.value)
            gate.complete(Unit)
            advanceUntilIdle()
            assertFalse(projectionFinished)
        }

    @Test
    fun transportAtFirstSentenceCancelsDeferralWithoutMovingTheCursor() =
        runTest {
            val harness = SessionHarness(this)
            harness.speakConversation("m1")
            val gate = CompletableDeferred<Unit>()
            harness.session.requestRenderedSentenceSeek(
                "m2",
                2uL,
                harness.session.conversationSource.value!!,
                {
                    gate.await()
                    null
                },
                { error("A cancelled target must not commit") },
            )
            runCurrent()
            harness.session.previousSentence()
            runCurrent()
            assertEquals(0, harness.controller.state.value.sentenceIndexWithinMessage)
            assertEquals(null, harness.session.edgeState.value)
            harness.engine.complete(harness.engine.spoken.lastIndex)
            runCurrent()
            assertTrue(harness.controller.state.value is TtsState.Idle)
            gate.complete(Unit)
            advanceUntilIdle()
            assertTrue(harness.controller.state.value is TtsState.Idle)
        }

    @Test
    fun unmappableRenderedSeekDoesNotLoseALiveArrivalDuringPreparation() =
        runTest {
            val harness = SessionHarness(this)
            harness.loadTimeline("m1")
            harness.speakConversation("m1")
            val gate = CompletableDeferred<Unit>()
            harness.session.requestRenderedSentenceSeek(
                "unmapped",
                0uL,
                harness.session.conversationSource.value!!,
                {
                    gate.await()
                    null
                },
                { error("An unmapped request cannot commit") },
            )
            runCurrent()
            assertTrue(harness.session.edgeState.value is TtsHistoryEdgeState.Loading)
            harness.pager.loaded.add(harness.record("m2"))
            assertTrue(harness.session.allowsLiveAppend())
            assertTrue(harness.controller.appendSpeech(harness.entry("m2"), java.util.Locale.US))
            gate.complete(Unit)
            advanceUntilIdle()
            assertEquals(listOf("m1", "m2"), harness.controller.queuedMessageIds())
            assertEquals(
                "m1",
                harness.controller.state.value.passage
                    ?.messageIdHex,
            )
            assertEquals(null, harness.session.edgeState.value)
        }

    private fun request(
        harness: SessionHarness,
        id: String,
        canCommit: () -> Boolean = { true },
    ): TtsRenderedSeekRequest {
        val original = harness.entry(id, sentences = 2).copy(text = "Repeat. Repeat.")
        val entry =
            original.copy(
                projectionId = "rendered-$id",
                spokenTextSpans =
                    listOf(
                        TtsSpokenTextSpan(
                            TtsTextRange(0, original.text.length),
                            TtsVisibleTextSpan("plain", 0, original.text.length),
                        ),
                    ),
                visibleLeaves = mapOf("plain" to original.text),
            )
        return TtsRenderedSeekRequest(
            entry,
            sentenceIndex = { prepared ->
                val hit = PreparedRenderedHit("plain", entry.text, entry.text.lastIndexOf("Repeat"))
                (PreparedSeekResolver.resolve(prepared, hit) as? PreparedSeekTarget.Sentence)?.ordinal
            },
            canCommit = canCommit,
        )
    }
}
