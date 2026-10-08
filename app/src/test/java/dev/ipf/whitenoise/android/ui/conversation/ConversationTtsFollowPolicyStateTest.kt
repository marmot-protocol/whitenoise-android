package dev.ipf.whitenoise.android.ui.conversation

import dev.ipf.whitenoise.android.audio.tts.TtsPassage
import dev.ipf.whitenoise.android.audio.tts.TtsState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationTtsFollowPolicyStateTest {
    @Test
    fun directDragStaysSuspendedAcrossSentencesUntilExplicitResume() {
        val policy = ConversationTtsFollowPolicy()
        policy.observe(speaking(sessionId = 1, sentenceIndex = 0), ownsSession = true)
        policy.claimPendingTarget()

        policy.onUserDrag()
        policy.observe(speaking(sessionId = 1, sentenceIndex = 1), ownsSession = true)

        assertFalse(policy.isFollowEnabled)
        assertTrue(policy.showResumeAction)
        assertNull(policy.claimPendingTarget())
        policy.observe(speaking(sessionId = 1, sentenceIndex = 2, messageIdHex = "m2"), ownsSession = true)
        assertNull(policy.claimPendingTarget())
        policy.resumeFollow()
        assertEquals("m2", policy.claimPendingTarget()?.messageIdHex)

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
    fun explicitResumeRevealsPausedSentenceWithoutWaitingForPlayback() {
        val policy = ConversationTtsFollowPolicy()
        val speaking = speaking(sessionId = 4, sentenceIndex = 2)
        policy.observe(speaking, ownsSession = true)
        policy.claimPendingTarget()
        policy.onUserDrag()
        policy.observe(paused(speaking), ownsSession = true)

        policy.resumeFollow()
        assertEquals(speaking.followTarget(), policy.claimPendingTarget())
        policy.onFollowSucceeded(speaking.followTarget())

        policy.observe(speaking, ownsSession = true)
        assertNull(policy.claimPendingTarget())
    }

    @Test
    fun manualSuspensionSurvivesPreparingAndSentenceSeek() {
        val policy = ConversationTtsFollowPolicy()
        val initial = speaking(sessionId = 8, sentenceIndex = 0)
        policy.observe(initial, ownsSession = true)
        policy.claimPendingTarget()
        policy.onUserDrag()
        policy.observe(TtsState.Preparing(initial), ownsSession = true)
        assertTrue(policy.showResumeAction)
        val sought = speaking(sessionId = 8, sentenceIndex = 2)
        policy.observe(sought, ownsSession = true)
        policy.suppressNextFollowFor(sought.followTarget())
        policy.observe(speaking(sessionId = 8, sentenceIndex = 3), ownsSession = true)
        assertFalse(policy.isFollowEnabled)
        assertTrue(policy.showResumeAction)
        assertNull(policy.claimPendingTarget())
    }

    @Test
    fun explicitResumeSurvivesPreparingAndRevealsTheNextLivePassage() {
        val policy = ConversationTtsFollowPolicy()
        val initial = speaking(sessionId = 9, sentenceIndex = 0)
        policy.observe(initial, ownsSession = true)
        policy.claimPendingTarget()
        policy.onUserDrag()
        policy.resumeFollow()
        policy.observe(TtsState.Preparing(initial), ownsSession = true)
        assertNull(policy.claimPendingRequest())
        val next = speaking(sessionId = 9, sentenceIndex = 1)
        policy.observe(next, ownsSession = true)
        val request = policy.claimPendingRequest()!!
        assertEquals(next.followTarget(), request.target)
        assertTrue(request.anchorAtTop)
    }

    @Test
    fun failedExplicitRevealDoesNotAnchorLaterSentences() {
        val policy = ConversationTtsFollowPolicy()
        val initial = speaking(sessionId = 9, sentenceIndex = 0)
        policy.observe(initial, ownsSession = true)
        policy.claimPendingTarget()
        policy.resumeFollow()
        assertTrue(policy.claimPendingRequest()!!.anchorAtTop)
        assertTrue(policy.retryFailedFollowAttempt(initial.followTarget()))
        assertTrue(policy.claimPendingRequest()!!.anchorAtTop)
        assertFalse(policy.retryFailedFollowAttempt(initial.followTarget()))
        policy.observe(speaking(sessionId = 9, sentenceIndex = 1), ownsSession = true)
        assertFalse(policy.claimPendingRequest()!!.anchorAtTop)
    }

    @Test
    fun unclaimedExplicitRevealDoesNotCarryAcrossOrdinaryProgress() {
        val policy = ConversationTtsFollowPolicy()
        policy.observe(speaking(sessionId = 9, sentenceIndex = 0), ownsSession = true)
        policy.claimPendingTarget()
        policy.resumeFollow()
        policy.observe(speaking(sessionId = 9, sentenceIndex = 1), ownsSession = true)
        assertFalse(policy.claimPendingRequest()!!.anchorAtTop)
    }

    @Test
    fun viewportRecheckCannotTakeBackManuallyOwnedScroll() {
        val policy = ConversationTtsFollowPolicy()
        val current = speaking(sessionId = 10, sentenceIndex = 1)
        policy.observe(current, ownsSession = true)
        policy.claimPendingTarget()
        assertTrue(policy.recheckViewport())
        assertFalse(policy.claimPendingRequest()!!.anchorAtTop)
        policy.onUserDrag()
        assertFalse(policy.recheckViewport())
        assertNull(policy.claimPendingRequest())
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
    fun fullyVisibleSentenceStaysPutAndAnyClippedSentenceGoesToTheTop() {
        assertEquals(
            TtsFollowViewportDecision.Stay,
            decide(itemOffset = 0, sentenceTop = 350, sentenceBottom = 450),
        )
        assertEquals(
            TtsFollowViewportDecision.ScrollToItemOffset(-50),
            decide(itemOffset = 0, sentenceTop = -50, sentenceBottom = 50),
        )
        // Clipped at the bottom, so the whole sentence comes to the top rather
        // than rising by its overflow and clipping again on the next words.
        assertEquals(
            TtsFollowViewportDecision.ScrollToItemOffset(850),
            decide(itemOffset = 0, sentenceTop = 850, sentenceBottom = 1_050),
        )
        assertEquals(
            TtsFollowViewportDecision.ScrollToItemOffset(900),
            decide(itemOffset = 0, sentenceTop = 900, sentenceBottom = 1_001),
        )
    }

    @Test
    fun bottomClippedSentenceGoesToTheTopWithNonZeroOrigin() {
        assertEquals(
            TtsFollowViewportDecision.ScrollToItemOffset(900),
            TtsFollowViewport.decide(
                viewportStart = 200,
                viewportEnd = 1_200,
                itemOffset = 0,
                sentenceTop = 1_100,
                sentenceBottom = 1_201,
                direction = TtsFollowDirection.Forward,
                anchorAtTop = false,
            ),
        )
    }

    @Test
    fun oversizedMeasuredSentenceUsesTopAnchorInEitherDirection() {
        assertEquals(
            TtsFollowViewportDecision.ScrollToItemOffset(100),
            decide(
                itemOffset = 0,
                sentenceTop = 100,
                sentenceBottom = 1_100,
                direction = TtsFollowDirection.Forward,
            ),
        )
        assertEquals(
            TtsFollowViewportDecision.ScrollToItemOffset(100),
            decide(
                itemOffset = 0,
                sentenceTop = 100,
                sentenceBottom = 1_100,
                direction = TtsFollowDirection.Reverse,
            ),
        )
    }

    @Test
    fun initialRevealTopAnchorsEvenWhenSentenceIsAlreadyFullyVisible() {
        assertEquals(
            TtsFollowViewportDecision.ScrollToItemOffset(350),
            decide(
                itemOffset = 0,
                sentenceTop = 350,
                sentenceBottom = 450,
                anchorAtTop = true,
            ),
        )
    }

    private fun decide(
        itemOffset: Int,
        sentenceTop: Int,
        sentenceBottom: Int,
        direction: TtsFollowDirection = TtsFollowDirection.Forward,
        anchorAtTop: Boolean = false,
    ): TtsFollowViewportDecision =
        TtsFollowViewport.decide(
            viewportStart = 0,
            viewportEnd = 1_000,
            itemOffset = itemOffset,
            sentenceTop = sentenceTop,
            sentenceBottom = sentenceBottom,
            direction = direction,
            anchorAtTop = anchorAtTop,
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
