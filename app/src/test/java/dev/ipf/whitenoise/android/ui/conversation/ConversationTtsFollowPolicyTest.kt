package dev.ipf.whitenoise.android.ui.conversation

import dev.ipf.whitenoise.android.audio.tts.TtsPassage
import dev.ipf.whitenoise.android.audio.tts.TtsState
import dev.ipf.whitenoise.android.audio.tts.TtsVisibleTextSpan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationTtsFollowPolicyTest {
    @Test
    fun eachSentenceIsClaimedOnceAndWordProgressDoesNotCreateAnotherScroll() {
        val policy = ConversationTtsFollowPolicy()
        val first = speaking(sessionId = 1, sentenceIndex = 0)

        policy.observe(first, ownsSession = true)
        assertEquals(first.followTarget(), policy.claimPendingTarget())
        assertNull(policy.claimPendingTarget())

        policy.observe(
            first.copy(
                passage =
                    first.passage?.copy(
                        visibleWord = listOf(TtsVisibleTextSpan("plain", 0, 5)),
                    ),
            ),
            ownsSession = true,
        )
        assertNull(policy.claimPendingTarget())

        val second = speaking(sessionId = 1, sentenceIndex = 1)
        policy.observe(second, ownsSession = true)
        assertEquals(second.followTarget(), policy.claimPendingTarget())
    }

    @Test
    fun wordProgressDoesNotChangeTheConversationFollowSignal() {
        val sentenceFallback = speaking(sessionId = 1, sentenceIndex = 0)
        val exactWord =
            sentenceFallback.copy(
                passage =
                    sentenceFallback.passage?.copy(
                        visibleWord = listOf(TtsVisibleTextSpan("plain", 0, 5)),
                    ),
            )

        assertEquals(sentenceFallback.conversationFollowSignal(), exactWord.conversationFollowSignal())
    }

    @Test
    fun followRelevantTransitionsChangeTheConversationFollowSignal() {
        val firstSentence = speaking(sessionId = 1, sentenceIndex = 0)

        assertNotEquals(firstSentence.conversationFollowSignal(), paused(firstSentence).conversationFollowSignal())
        assertNotEquals(
            firstSentence.conversationFollowSignal(),
            speaking(sessionId = 1, sentenceIndex = 1).conversationFollowSignal(),
        )
        assertNotEquals(
            firstSentence.conversationFollowSignal(),
            TtsState.Idle(sessionId = 1).conversationFollowSignal(),
        )
    }

    @Test
    fun directDragSuspendsFollowingUntilExplicitResumeOrANewSession() {
        val policy = ConversationTtsFollowPolicy()
        policy.observe(speaking(sessionId = 1, sentenceIndex = 0), ownsSession = true)
        policy.claimPendingTarget()

        policy.onUserDrag()
        policy.observe(speaking(sessionId = 1, sentenceIndex = 1), ownsSession = true)

        assertFalse(policy.isFollowEnabled)
        assertTrue(policy.showResumeAction)
        assertNull(policy.claimPendingTarget())

        policy.resumeFollow()
        assertTrue(policy.isFollowEnabled)
        assertEquals(1, policy.claimPendingTarget()?.sentenceIndex)

        policy.onUserDrag()
        val restarted = speaking(sessionId = 2, sentenceIndex = 1)
        policy.observe(restarted, ownsSession = true)
        assertTrue(policy.isFollowEnabled)
        assertEquals(restarted.followTarget(), policy.claimPendingTarget())
    }

    @Test
    fun pausePreservesFollowStateWithoutRepeatingAnEvaluatedSentence() {
        val policy = ConversationTtsFollowPolicy()
        val speaking = speaking(sessionId = 3, sentenceIndex = 2)
        policy.observe(speaking, ownsSession = true)
        policy.claimPendingTarget()

        policy.observe(paused(speaking), ownsSession = true)
        assertTrue(policy.isFollowEnabled)
        assertFalse(policy.showResumeAction)
        assertNull(policy.claimPendingTarget())

        policy.observe(speaking, ownsSession = true)
        assertNull(policy.claimPendingTarget())
    }

    @Test
    fun explicitResumeWhilePausedWaitsForPlaybackBeforeClaiming() {
        val policy = ConversationTtsFollowPolicy()
        val speaking = speaking(sessionId = 4, sentenceIndex = 2)
        policy.observe(speaking, ownsSession = true)
        policy.claimPendingTarget()
        policy.onUserDrag()
        policy.observe(paused(speaking), ownsSession = true)

        policy.resumeFollow()
        assertNull(policy.claimPendingTarget())

        policy.observe(speaking, ownsSession = true)
        assertEquals(speaking.followTarget(), policy.claimPendingTarget())
    }

    @Test
    fun terminalStateAndOwnerLossClearSessionLocalFollowState() {
        val policy = ConversationTtsFollowPolicy()
        val speaking = speaking(sessionId = 5, sentenceIndex = 0)
        policy.observe(speaking, ownsSession = true)
        policy.onUserDrag()

        policy.observe(TtsState.Idle(sessionId = 5), ownsSession = true)
        assertFalse(policy.isFollowEnabled)
        assertFalse(policy.showResumeAction)
        assertNull(policy.claimPendingTarget())

        policy.observe(speaking, ownsSession = false)
        assertFalse(policy.isFollowEnabled)
        assertFalse(policy.showResumeAction)
        assertNull(policy.claimPendingTarget())
    }

    @Test
    fun viewportUsesTheMiddleSixtyPercentAsItsNoScrollBand() {
        assertEquals(
            TtsFollowViewportDecision.Stay,
            decide(itemOffset = 0, sentenceIndex = 4, sentenceCount = 10),
        )
        assertEquals(
            TtsFollowViewportDecision.ScrollToItemOffset(-450),
            decide(itemOffset = 0, sentenceIndex = 0, sentenceCount = 10),
        )
        assertEquals(
            TtsFollowViewportDecision.ScrollToItemOffset(450),
            decide(itemOffset = 0, sentenceIndex = 9, sentenceCount = 10),
        )
    }

    @Test
    fun viewportDecisionUsesMountedRowCoordinatesButReturnsAStableTargetOffset() {
        assertEquals(
            TtsFollowViewportDecision.ScrollToItemOffset(-150),
            TtsFollowViewport.decide(
                viewportStart = 100,
                viewportEnd = 900,
                itemOffset = -200,
                itemSize = 500,
                sentenceIndex = 0,
                sentenceCount = 1,
            ),
        )
        assertEquals(
            TtsFollowViewportDecision.Stay,
            TtsFollowViewport.decide(
                viewportStart = 100,
                viewportEnd = 900,
                itemOffset = 350,
                itemSize = 100,
                sentenceIndex = 0,
                sentenceCount = 1,
            ),
        )
    }

    private fun decide(
        itemOffset: Int,
        sentenceIndex: Int,
        sentenceCount: Int,
    ): TtsFollowViewportDecision =
        TtsFollowViewport.decide(
            viewportStart = 0,
            viewportEnd = 1_000,
            itemOffset = itemOffset,
            itemSize = 1_000,
            sentenceIndex = sentenceIndex,
            sentenceCount = sentenceCount,
        )

    private fun speaking(
        sessionId: Long,
        sentenceIndex: Int,
    ): TtsState.Speaking =
        TtsState.Speaking(
            sessionId = sessionId,
            chunkIndex = sentenceIndex,
            chunkCount = 3,
            messageIndex = 0,
            messageCount = 1,
            sentenceIndexWithinMessage = sentenceIndex,
            sentenceCountWithinMessage = 3,
            messagePreview = "preview",
            passage =
                TtsPassage(
                    messageIdHex = "m1",
                    sentenceIndex = sentenceIndex,
                    projectionId = "projection-1",
                    timelineAt = 42uL,
                ),
        )

    private fun paused(speaking: TtsState.Speaking): TtsState.Paused =
        TtsState.Paused(
            sessionId = speaking.sessionId,
            chunkIndex = speaking.chunkIndex,
            chunkCount = speaking.chunkCount,
            messageIndex = speaking.messageIndex,
            messageCount = speaking.messageCount,
            sentenceIndexWithinMessage = speaking.sentenceIndexWithinMessage,
            sentenceCountWithinMessage = speaking.sentenceCountWithinMessage,
            messagePreview = speaking.messagePreview,
            passage = speaking.passage,
        )

    private fun TtsState.followTarget(): ConversationTtsFollowTarget =
        checkNotNull(passage).let { passage ->
            ConversationTtsFollowTarget(
                sessionId = sessionId,
                messageIdHex = passage.messageIdHex,
                sentenceIndex = passage.sentenceIndex,
                sentenceCount = sentenceCountWithinMessage,
                projectionId = passage.projectionId,
                timelineAt = passage.timelineAt,
            )
        }
}
