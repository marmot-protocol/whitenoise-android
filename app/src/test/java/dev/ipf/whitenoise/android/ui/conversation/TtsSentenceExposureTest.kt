package dev.ipf.whitenoise.android.ui.conversation

import androidx.compose.ui.geometry.Rect
import dev.ipf.whitenoise.android.audio.tts.SessionHarness
import dev.ipf.whitenoise.android.audio.tts.TtsSpokenTextSpan
import dev.ipf.whitenoise.android.audio.tts.TtsTextRange
import dev.ipf.whitenoise.android.audio.tts.TtsVisibleTextSpan
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TtsSentenceExposureTest {
    private val viewport = Rect(0f, 100f, 300f, 600f)

    @Test
    fun unavailableClippedAndOffscreenSentencesAlwaysOfferRecovery() {
        assertTrue(ttsSentenceNeedsReveal(null, viewport))
        assertTrue(ttsSentenceNeedsReveal(Rect(0f, 80f, 300f, 120f), viewport))
        assertTrue(ttsSentenceNeedsReveal(Rect(0f, 580f, 300f, 640f), viewport))
        assertTrue(ttsSentenceNeedsReveal(Rect(0f, 700f, 300f, 740f), viewport))
        assertFalse(ttsSentenceNeedsReveal(Rect(0f, 120f, 300f, 160f), viewport))
    }

    @Test
    fun anOversizedSentenceCanRevealItsBeginningWithoutAnImpossibleFullFit() {
        assertTrue(ttsSentenceWasRevealed(Rect(0f, 100f, 300f, 1_000f), viewport))
        assertFalse(ttsSentenceWasRevealed(Rect(0f, 90f, 300f, 1_000f), viewport))
        assertTrue(ttsSentenceNeedsReveal(Rect(0f, 100f, 300f, 1_000f), viewport))
    }

    @Test
    fun aFailedFollowDoesNotHideResumeOrTakeManualScrollOwnership() =
        runTest {
            val harness = SessionHarness(this)
            harness.speakMappedConversation()
            val policy = ConversationTtsFollowPolicy()
            val handle = ConversationTtsFollowHandle(policy)
            policy.observe(harness.controller.state.value, true)
            val target = requireNotNull(policy.claimPendingTarget())
            assertTrue(policy.retryFailedFollowAttempt(target))
            policy.claimPendingTarget()
            assertFalse(policy.retryFailedFollowAttempt(target))
            assertTrue(policy.isFollowEnabled)
            assertTrue(handle.showResumeAction)
            policy.onUserDrag()
            assertFalse(policy.isFollowEnabled)
            assertTrue(handle.showResumeAction)
        }

    @Test
    fun repeatedExplicitRevealIsObservableEvenForTheSamePausedTarget() =
        runTest {
            val harness = SessionHarness(this)
            harness.speakMappedConversation()
            harness.controller.pause()
            val policy = ConversationTtsFollowPolicy()
            policy.observe(harness.controller.state.value, true)
            val before = harness.spokenTexts().size
            assertTrue(policy.requestExplicitReveal())
            val revision = policy.requestRevision
            val first = requireNotNull(policy.claimPendingRequest())
            policy.onFollowSucceeded(first.target)
            assertTrue(policy.requestExplicitReveal())
            assertTrue(policy.requestRevision > revision)
            assertEquals(first.target, policy.claimPendingRequest()?.target)
            assertEquals(before, harness.spokenTexts().size)
        }

    @Test
    fun identicalPlacementReportsDoNotInvalidateExposureAgain() {
        val layouts = ConversationTtsSentenceLayoutRegistry()
        val row = Any()
        val target = ConversationTtsFollowTarget(1L, "m1", 0, 1, "projection", 1uL)
        layouts.mountRow("m1", row)
        val report =
            ConversationTtsSentenceLayoutReport(
                target = target,
                rowInstance = row,
                renderedLeafId = "b0/n0",
                boundsInWindow = Rect(0f, 120f, 300f, 160f),
                coverage = emptySet(),
                expectedCoverage = emptySet(),
            )
        layouts.report(report)
        val firstRevision = layouts.revision
        repeat(10) { layouts.report(report.copy()) }
        assertEquals(firstRevision, layouts.revision)
        layouts.report(report.copy(boundsInWindow = report.boundsInWindow.translate(0f, 40f)))
        assertTrue(layouts.revision > firstRevision)
    }

    /** The controller only publishes renderer passages for entries with actual text coordinates. */
    private fun SessionHarness.speakMappedConversation() {
        val entry = entry("m1")
        speakEntries(
            listOf(
                entry.copy(
                    projectionId = "projection",
                    visibleLeaves = mapOf("b0/n0" to entry.text),
                    spokenTextSpans =
                        listOf(
                            TtsSpokenTextSpan(
                                TtsTextRange(0, entry.text.length),
                                TtsVisibleTextSpan("b0/n0", 0, entry.text.length),
                            ),
                        ),
                ),
            ),
        )
    }
}
