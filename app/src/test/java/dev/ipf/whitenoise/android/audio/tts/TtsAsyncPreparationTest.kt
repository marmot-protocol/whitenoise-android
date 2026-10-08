package dev.ipf.whitenoise.android.audio.tts

import dev.ipf.whitenoise.android.audio.tts.speech.PreparedRenderedHit
import dev.ipf.whitenoise.android.audio.tts.speech.SpeechRole
import dev.ipf.whitenoise.android.audio.tts.speech.SpeechSourceRun
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class TtsAsyncPreparationTest {
    /** Rejects invalid rendered coordinates rather than speaking a guessed fallback sentence. */
    @Test
    fun invalidRenderedHitNeverFallsBackToTheDocumentTop() =
        runTest {
            val harness = SessionHarness(this)
            assertFalse(harness.controller.speak(emptyList(), Locale.US))
            assertEquals(TtsStartFailure.EmptyContent, harness.controller.lastStartFailure)
            val result =
                harness.controller.speakAsync(
                    listOf(harness.entry("m1")),
                    Locale.US,
                    startRenderedHit = PreparedRenderedHit("missing", "changed", 0),
                ) { true }
            assertFalse(result)
            assertTrue(harness.spokenTexts().isEmpty())
            assertEquals(TtsStartFailure.None, harness.controller.lastStartFailure)
        }

    /** Revocation before ticket creation preserves the already-playing queue. */
    @Test
    fun alreadyRevokedSourcePreservesTheExistingSession() =
        runTest {
            val harness = SessionHarness(this)
            harness.speakConversation("m1")
            val existing = harness.controller.state.value
            assertFalse(
                harness.controller.speakAsync(listOf(harness.entry("m2")), Locale.US, isCurrent = { false }) { true },
            )
            assertEquals(existing, harness.controller.state.value)
            assertEquals(1, harness.spokenTexts().size)
        }

    /** Revocation after ticket creation prevents commitment and releases Preparing state. */
    @Test
    fun revokedSourceDuringPreparationCannotStartSpeech() =
        runTest {
            val harness = SessionHarness(this)
            var current = true
            val result =
                harness.controller.speakAsync(
                    listOf(harness.entry("m1")),
                    Locale.US,
                    isCurrent = { current },
                ) {
                    current = false
                    true
                }
            assertFalse(result)
            assertTrue(harness.controller.state.value is TtsState.Idle)
            assertTrue(harness.spokenTexts().isEmpty())
        }

    /** Foreground ownership precedes preparation; commitment preserves the preparation session ID. */
    @Test
    fun naturalCompletionKeepsAnExplicitPendingStartValid() =
        runTest {
            val harness = SessionHarness(this)
            assertTrue(harness.controller.speak(listOf(harness.entry("old")), Locale.US))
            val canStart = harness.controller.speechStartProjectionGuard()
            harness.engine.complete(0)
            assertTrue(harness.controller.state.value is TtsState.Idle)
            assertTrue(canStart())
            assertTrue(harness.controller.speak(listOf(harness.entry("new")), Locale.US))
            assertFalse(canStart())
        }

    @Test
    fun explicitStopInvalidatesAPendingStartEvenWhenTheSessionIdIsRetained() =
        runTest {
            val harness = SessionHarness(this)
            assertTrue(harness.controller.speak(listOf(harness.entry("old")), Locale.US))
            val canStart = harness.controller.speechStartProjectionGuard()
            harness.controller.stop()
            assertTrue(harness.controller.state.value is TtsState.Idle)
            assertFalse(canStart())
        }

    @Test
    fun explicitSentenceNavigationInvalidatesAPendingStart() =
        runTest {
            val harness = SessionHarness(this)
            assertTrue(harness.controller.speak(listOf(harness.entry("old", sentences = 2)), Locale.US))
            val canStart = harness.controller.speechStartProjectionGuard()
            harness.controller.skipNextSentence()
            assertFalse(canStart())
        }

    @Test
    fun invalidSourceCannotReplaceAnExistingSession() =
        runTest {
            val harness = SessionHarness(this)
            assertTrue(harness.controller.speak(listOf(harness.entry("original")), Locale.US))
            val originalSession = harness.controller.state.value.sessionId
            val spoken = harness.spokenTexts()
            val result =
                harness.controller.speakAsync(
                    listOf(harness.entry("stale")),
                    Locale.US,
                    isCurrent = { false },
                ) { error("An invalid source must not acquire preparation ownership") }
            assertFalse(result)
            assertEquals(originalSession, harness.controller.state.value.sessionId)
            assertEquals(spoken, harness.spokenTexts())
        }

    @Test
    fun sourceChangedDuringPreparationCannotPublishOldText() =
        runTest {
            val harness = SessionHarness(this)
            var currentSource = true
            val result =
                harness.controller.speakAsync(
                    listOf(harness.entry("edited")),
                    Locale.US,
                    isCurrent = { currentSource },
                ) {
                    currentSource = false
                    true
                }
            assertFalse(result)
            assertTrue(harness.controller.state.value is TtsState.Idle)
            assertTrue(harness.spokenTexts().isEmpty())
        }

    @Test
    fun preparingOwnsTheServiceBeforeTextWorkAndCommitsTheSameSession() =
        runTest {
            val harness = SessionHarness(this)
            var sessionId = 0L
            val result =
                harness.controller.speakAsync(listOf(harness.entry("m1")), Locale.US) {
                    val state = harness.controller.state.value
                    sessionId = state.sessionId
                    assertTrue(state is TtsState.Preparing)
                    assertEquals(TtsPlaybackSessionModel(true, false, false, true), TtsPlaybackSessionModel.from(state))
                    true
                }
            assertTrue(result)
            assertEquals(sessionId, harness.controller.state.value.sessionId)
            assertTrue(harness.controller.state.value is TtsState.Speaking)
        }

    /** Explicit stop invalidates a pending ticket even after its foreground callback succeeds. */
    @Test
    fun stopDuringPreparationCannotPublishAfterwards() =
        runTest {
            val harness = SessionHarness(this)
            val result =
                harness.controller.speakAsync(listOf(harness.entry("m1")), Locale.US) {
                    harness.controller.stop()
                    true
                }
            assertFalse(result)
            assertTrue(harness.controller.state.value is TtsState.Idle)
            assertTrue(harness.spokenTexts().isEmpty())
        }

    /** Refusing foreground ownership leaves no private speech or lingering preparation. */
    @Test
    fun rejectedForegroundOwnerCancelsPreparation() =
        runTest {
            val harness = SessionHarness(this)
            val result = harness.controller.speakAsync(listOf(harness.entry("m1")), Locale.US) { false }
            assertFalse(result)
            assertTrue(harness.controller.state.value is TtsState.Idle)
            assertTrue(harness.spokenTexts().isEmpty())
        }

    /** Inline-code expansion does not move a prose hit into the wrong prepared sentence. */
    @Test
    fun renderedHitStartsAtPreparedSentenceAfterInlineCodeExpansion() =
        runTest {
            val harness = SessionHarness(this)
            val text = "Run foo() now. Then stop."
            val proseTail = " now. Then stop."
            val entry =
                TtsSpeakableEntry(
                    senderKey = "alice",
                    senderDisplayName = "Alice",
                    text = text,
                    messageIdHex = "m1",
                    projectionId = "projection-m1",
                    spokenTextSpans =
                        listOf(
                            TtsSpokenTextSpan(TtsTextRange(0, 4), TtsVisibleTextSpan("prose-before", 0, 4)),
                            TtsSpokenTextSpan(TtsTextRange(4, 9), TtsVisibleTextSpan("inline-code", 0, 5)),
                            TtsSpokenTextSpan(
                                TtsTextRange(9, text.length),
                                TtsVisibleTextSpan("prose-after", 0, proseTail.length),
                            ),
                        ),
                    visibleLeaves =
                        linkedMapOf(
                            "prose-before" to "Run ",
                            "inline-code" to "foo()",
                            "prose-after" to proseTail,
                        ),
                    speechRoles =
                        linkedMapOf(
                            "prose-before" to SpeechSourceRun("prose-before", "Run ", SpeechRole.Prose),
                            "inline-code" to SpeechSourceRun("inline-code", "foo()", SpeechRole.InlineCode),
                            "prose-after" to SpeechSourceRun("prose-after", proseTail, SpeechRole.Prose),
                        ),
                )

            assertTrue(
                harness.controller.speakAsync(
                    listOf(entry),
                    Locale.US,
                    startRenderedHit = PreparedRenderedHit("prose-after", proseTail, proseTail.indexOf("Then")),
                ) { true },
            )

            assertTrue(harness.spokenTexts().first().contains("Then stop"))
            assertFalse(harness.spokenTexts().first().contains("foo"))
        }
}
