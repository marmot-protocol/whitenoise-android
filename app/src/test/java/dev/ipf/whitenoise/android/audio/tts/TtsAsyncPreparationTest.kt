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

    @Test
    fun rejectedForegroundOwnerCancelsPreparation() =
        runTest {
            val harness = SessionHarness(this)
            val result = harness.controller.speakAsync(listOf(harness.entry("m1")), Locale.US) { false }
            assertFalse(result)
            assertTrue(harness.controller.state.value is TtsState.Idle)
            assertTrue(harness.spokenTexts().isEmpty())
        }

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
