package dev.ipf.whitenoise.android.ui.conversation

import androidx.compose.ui.geometry.Rect
import dev.ipf.whitenoise.android.audio.tts.SessionHarness
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
            harness.speakConversation("m1")
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
            harness.speakConversation("m1")
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
}
