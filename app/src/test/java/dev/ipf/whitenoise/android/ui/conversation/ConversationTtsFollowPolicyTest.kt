package dev.ipf.whitenoise.android.ui.conversation

import androidx.compose.runtime.saveable.SaverScope
import androidx.compose.ui.geometry.Rect
import dev.ipf.whitenoise.android.audio.tts.TtsPassage
import dev.ipf.whitenoise.android.audio.tts.TtsState
import dev.ipf.whitenoise.android.audio.tts.TtsVisibleTextSpan
import dev.ipf.whitenoise.android.ui.conversation.messages.TtsSentenceProjectionSegment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationTtsFollowPolicyTest {
    private val saveableScope = SaverScope { true }

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
    fun failedFollowAttemptGetsOneBoundedRetry() {
        val policy = ConversationTtsFollowPolicy()
        val speaking = speaking(sessionId = 1, sentenceIndex = 0)
        val target = speaking.followTarget()
        policy.observe(speaking, ownsSession = true)

        assertEquals(target, policy.claimPendingTarget())
        assertTrue(policy.retryFailedFollowAttempt(target))
        assertEquals(target, policy.claimPendingTarget())
        assertFalse(policy.retryFailedFollowAttempt(target))

        policy.observe(speaking, ownsSession = true)
        assertNull(policy.claimPendingTarget())
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
    fun reverseSentenceNavigationKeepsOneReverseDecisionAcrossWordCallbacks() {
        val policy = ConversationTtsFollowPolicy()
        val later = speaking(sessionId = 1, sentenceIndex = 2)
        policy.observe(later, ownsSession = true)
        policy.claimPendingRequest()

        val earlier = speaking(sessionId = 1, sentenceIndex = 1)
        policy.observe(earlier, ownsSession = true)
        assertEquals(
            ConversationTtsFollowRequest(earlier.followTarget(), TtsFollowDirection.Reverse),
            policy.claimPendingRequest(),
        )

        policy.observe(
            earlier.copy(
                passage =
                    earlier.passage?.copy(
                        visibleWord = listOf(TtsVisibleTextSpan("plain", 0, 5)),
                    ),
            ),
            ownsSession = true,
        )
        assertNull(policy.claimPendingRequest())
    }

    @Test
    fun reverseMessageNavigationUsesQueueOrderWhenTimestampsTie() {
        val policy = ConversationTtsFollowPolicy()
        policy.observe(
            speaking(
                sessionId = 1,
                sentenceIndex = 0,
                messageIdHex = "later",
                messageIndex = 2,
            ),
            ownsSession = true,
        )
        policy.claimPendingRequest()

        val earlier =
            speaking(
                sessionId = 1,
                sentenceIndex = 0,
                messageIdHex = "earlier",
                messageIndex = 1,
            )
        policy.observe(earlier, ownsSession = true)

        assertEquals(
            ConversationTtsFollowRequest(earlier.followTarget(), TtsFollowDirection.Reverse),
            policy.claimPendingRequest(),
        )
    }

    @Test
    fun restoredReverseTargetKeepsBottomAnchorForOversizedSentence() {
        val policy = ConversationTtsFollowPolicy()
        val later = speaking(sessionId = 1, sentenceIndex = 2)
        policy.observe(later, ownsSession = true)
        policy.claimPendingRequest()

        val earlier = speaking(sessionId = 1, sentenceIndex = 1)
        policy.observe(earlier, ownsSession = true)
        assertEquals(TtsFollowDirection.Reverse, policy.claimPendingRequest()?.direction)

        val saved = with(ConversationTtsFollowPolicy.Saver) { saveableScope.save(policy) }!!
        val restored = ConversationTtsFollowPolicy.Saver.restore(saved)!!
        restored.observe(earlier, ownsSession = true)
        val restoredRequest = restored.claimPendingRequest()!!

        assertEquals(TtsFollowDirection.Reverse, restoredRequest.direction)
        assertEquals(
            TtsFollowViewportDecision.ScrollToItemOffset(100),
            decide(
                itemOffset = 0,
                sentenceTop = 100,
                sentenceBottom = 900,
                direction = restoredRequest.direction,
            ),
        )
    }

    @Test
    fun staleTargetCannotConsumeNewTargetsScrollBudgets() {
        val policy = ConversationTtsFollowPolicy()
        val first = speaking(sessionId = 1, sentenceIndex = 0).followTarget()
        val secondState = speaking(sessionId = 1, sentenceIndex = 1)
        val second = secondState.followTarget()
        policy.observe(speaking(sessionId = 1, sentenceIndex = 0), ownsSession = true)
        policy.claimPendingRequest()
        policy.observe(secondState, ownsSession = true)

        assertFalse(policy.claimPreposition(first))
        assertFalse(policy.claimCorrectiveScroll(first))
        assertTrue(policy.claimPreposition(second))
        assertTrue(policy.claimCorrectiveScroll(second))
        assertFalse(policy.claimPreposition(second))
        assertFalse(policy.claimCorrectiveScroll(second))
    }

    @Test
    fun sentenceLayoutRequiresCompleteCoverageFromCurrentRowInstance() {
        val registry = ConversationTtsSentenceLayoutRegistry()
        val target = speaking(sessionId = 1, sentenceIndex = 0).followTarget()
        val oldRow = Any()
        val currentRow = Any()
        val firstCoverage = TtsSentenceProjectionSegment("a", 0, 5)
        val secondCoverage = TtsSentenceProjectionSegment("b", 5, 10)
        val expected = setOf(firstCoverage, secondCoverage)
        registry.mountRow(target.messageIdHex, oldRow)
        registry.report(
            ConversationTtsSentenceLayoutReport(
                target = target,
                rowInstance = oldRow,
                renderedLeafId = "a",
                boundsInWindow = Rect(0f, 100f, 100f, 140f),
                coverage = setOf(firstCoverage),
                expectedCoverage = expected,
            ),
        )
        assertNull(registry.completeSentenceBounds(target))

        registry.mountRow(target.messageIdHex, currentRow)
        registry.report(
            ConversationTtsSentenceLayoutReport(
                target = target,
                rowInstance = oldRow,
                renderedLeafId = "b",
                boundsInWindow = Rect(0f, 140f, 100f, 180f),
                coverage = setOf(secondCoverage),
                expectedCoverage = expected,
            ),
        )
        assertNull(registry.completeSentenceBounds(target))

        registry.report(
            ConversationTtsSentenceLayoutReport(
                target = target,
                rowInstance = currentRow,
                renderedLeafId = "a",
                boundsInWindow = Rect(0f, 200f, 100f, 240f),
                coverage = setOf(firstCoverage),
                expectedCoverage = expected,
            ),
        )
        registry.report(
            ConversationTtsSentenceLayoutReport(
                target = target,
                rowInstance = currentRow,
                renderedLeafId = "b",
                boundsInWindow = Rect(0f, 240f, 100f, 280f),
                coverage = setOf(secondCoverage),
                expectedCoverage = expected,
            ),
        )

        assertEquals(Rect(0f, 200f, 100f, 280f), registry.completeSentenceBounds(target))
    }

    @Test
    fun sentenceLayoutRejectsReportsFromPreviousViewportGeometry() {
        val registry = ConversationTtsSentenceLayoutRegistry()
        val target = speaking(sessionId = 1, sentenceIndex = 0).followTarget()
        val row = Any()
        val coverage = setOf(TtsSentenceProjectionSegment("plain", 0, 10))
        registry.mountRow(target.messageIdHex, row)
        registry.updateViewportBounds(Rect(0f, 0f, 100f, 1_000f))
        registry.report(
            ConversationTtsSentenceLayoutReport(
                target = target,
                rowInstance = row,
                renderedLeafId = "plain",
                boundsInWindow = Rect(0f, 300f, 100f, 400f),
                coverage = coverage,
                expectedCoverage = coverage,
            ),
        )
        assertEquals(Rect(0f, 300f, 100f, 400f), registry.completeSentenceBounds(target))

        registry.updateViewportBounds(Rect(0f, 100f, 100f, 1_000f))
        assertNull(registry.completeSentenceBounds(target))

        registry.report(
            ConversationTtsSentenceLayoutReport(
                target = target,
                rowInstance = row,
                renderedLeafId = "plain",
                boundsInWindow = Rect(0f, 400f, 100f, 500f),
                coverage = coverage,
                expectedCoverage = coverage,
            ),
        )
        assertEquals(Rect(0f, 400f, 100f, 500f), registry.completeSentenceBounds(target))
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
    fun explicitTransportReturnCanRevealAPausedPassageExactlyOnce() {
        val policy = ConversationTtsFollowPolicy()
        val speaking = speaking(sessionId = 6, sentenceIndex = 3)
        val paused = paused(speaking)
        policy.observe(speaking, ownsSession = true)
        policy.claimPendingTarget()
        policy.observe(paused, ownsSession = true)

        assertTrue(policy.requestExplicitReveal())
        val target = paused.followTarget()
        assertEquals(target, policy.claimPendingTarget())
        assertTrue(policy.isCurrentTarget(target))

        policy.onFollowSucceeded(target)
        assertFalse(policy.isCurrentTarget(target))
        assertNull(policy.claimPendingTarget())
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
    fun measuredSentenceUsesMinimumDeltaToEnterTheMiddleSixtyPercentBand() {
        assertEquals(
            TtsFollowViewportDecision.Stay,
            decide(itemOffset = 0, sentenceTop = 350, sentenceBottom = 450),
        )
        assertEquals(
            TtsFollowViewportDecision.ScrollToItemOffset(-150),
            decide(itemOffset = 0, sentenceTop = 50, sentenceBottom = 150),
        )
        assertEquals(
            TtsFollowViewportDecision.ScrollToItemOffset(100),
            decide(itemOffset = 0, sentenceTop = 850, sentenceBottom = 900),
        )
    }

    @Test
    fun oversizedMeasuredSentenceUsesStableDirectionAnchor() {
        assertEquals(
            TtsFollowViewportDecision.ScrollToItemOffset(-100),
            decide(
                itemOffset = 0,
                sentenceTop = 100,
                sentenceBottom = 900,
                direction = TtsFollowDirection.Forward,
            ),
        )
        assertEquals(
            TtsFollowViewportDecision.ScrollToItemOffset(100),
            decide(
                itemOffset = 0,
                sentenceTop = 100,
                sentenceBottom = 900,
                direction = TtsFollowDirection.Reverse,
            ),
        )
    }

    private fun decide(
        itemOffset: Int,
        sentenceTop: Int,
        sentenceBottom: Int,
        direction: TtsFollowDirection = TtsFollowDirection.Forward,
    ): TtsFollowViewportDecision =
        TtsFollowViewport.decide(
            viewportStart = 0,
            viewportEnd = 1_000,
            itemOffset = itemOffset,
            sentenceTop = sentenceTop,
            sentenceBottom = sentenceBottom,
            direction = direction,
        )

    private fun speaking(
        sessionId: Long,
        sentenceIndex: Int,
        messageIdHex: String = "m1",
        messageIndex: Int = 0,
        messageCount: Int = 3,
    ): TtsState.Speaking =
        TtsState.Speaking(
            sessionId = sessionId,
            chunkIndex = sentenceIndex,
            chunkCount = 3,
            messageIndex = messageIndex,
            messageCount = messageCount,
            sentenceIndexWithinMessage = sentenceIndex,
            sentenceCountWithinMessage = 3,
            messagePreview = "preview",
            passage =
                TtsPassage(
                    messageIdHex = messageIdHex,
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
