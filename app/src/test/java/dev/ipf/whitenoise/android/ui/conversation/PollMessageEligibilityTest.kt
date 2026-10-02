package dev.ipf.whitenoise.android.ui.conversation

import dev.ipf.marmotkit.DeletionSourceFfi
import dev.ipf.marmotkit.TimelineReplyPreviewFfi
import dev.ipf.whitenoise.android.audio.tts.resolveTtsSpeakableSource
import dev.ipf.whitenoise.android.core.MessageProjector
import dev.ipf.whitenoise.android.core.MessageTextCopy
import dev.ipf.whitenoise.android.core.TimelineProjector
import dev.ipf.whitenoise.android.state.MessageStatus
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Discussion eligibility is separate from the voting deadline and rejects stale target snapshots. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PollMessageEligibilityTest : PollMessageTestFixtures() {
    @Before fun bindRoster() = runTest { pollController.retryMembers() }

    @After fun clearController() {
        pollController.onCleared()
    }

    @Test fun closedVotingDoesNotDisableDiscussion() {
        val item = pollMessage(closed = true)
        assertTrue(pollMessageActionsEligible(item, owner(item), 100uL))
    }

    @Test fun nativePendingPollStillHasItsOriginalActionTarget() {
        val item = pollMessage(mine = true, status = MessageStatus.Pending)
        retain(item)
        assertEquals(item, currentPollActionTarget(pollController, owner(item), 100uL))
    }

    @Test fun failedDeletedInvalidatedAndMissingProjectionAreExcluded() {
        val item = pollMessage()
        val projection = checkNotNull(item.projected)
        val excluded =
            listOf(
                item.copy(status = MessageStatus.Failed),
                item.copy(projected = projection.copy(deleted = true)),
                item.copy(projected = projection.copy(invalidationStatus = "BeyondAppRetention")),
                item.copy(projected = projection.copy(poll = null)),
                item.copy(projected = null),
            )
        excluded.forEach { assertFalse(pollMessageActionsEligible(it, owner(item), 100uL)) }
    }

    @Test fun wrongGroupWrongMessageAndExpiredContentAreExcluded() {
        val item = pollMessage()
        assertFalse(pollMessageActionsEligible(item, owner(item).copy(groupId = "other"), 100uL))
        assertFalse(pollMessageActionsEligible(item, owner(item).copy(messageId = "other"), 100uL))
        val expiring = item.copy(record = item.record.copy(retentionExpiresAt = 100uL))
        assertTrue(pollMessageActionsEligible(expiring, owner(item), 99uL))
        assertFalse(pollMessageActionsEligible(expiring, owner(item), 100uL))
    }

    @Test fun disposedConversationCannotAcceptAnOldPollCallback() {
        val item = pollMessage()
        retain(item)
        assertNotNull(currentPollActionTarget(pollController, owner(item), 100uL))
        pollController.onCleared()
        assertNull(currentPollActionTarget(pollController, owner(item), 100uL))
    }

    @Test fun wrongAccountAndConversationCannotAcceptOldCallback() {
        val item = pollMessage()
        retain(item)
        assertNotNull(currentPollActionTarget(pollController, owner(item), 100uL))
        assertNull(currentPollActionTarget(pollController, owner(item).copy(accountRef = "work"), 100uL))
        assertNull(currentPollActionTarget(pollController, owner(item).copy(groupId = "other"), 100uL))
    }

    @Test fun readOnlyConversationCannotAcceptCallback() {
        val item = pollMessage()
        retain(item)
        assertNotNull(currentPollActionTarget(pollController, owner(item), 100uL))
        pollController.applyGroupStateForTest(pollController.group.copy(disbanded = true))
        assertNull(currentPollActionTarget(pollController, owner(item), 100uL))
    }

    @Test fun pollReplyCopyNeverExposesEnvelope() {
        val item = pollMessage()
        assertTrue(MessageProjector.displayBody(item.record, MessageTextCopy.Default) == "Poll")
        assertNull(MessageProjector.copyableText(item.record))
        assertNull(resolveTtsSpeakableSource(item.record, editedText = null))
    }

    @Test fun composerAndReceivedQuoteUseLocalizedPollCopy() {
        val item = pollMessage()
        val projected = checkNotNull(item.projected)
        val copy = MessageTextCopy.Default.copy(poll = "Localized poll")
        assertEquals("Localized poll", TimelineProjector.replyTargetPreview(projected, copy = copy).body)
        val reply =
            projected.copy(
                kind = 9uL,
                replyToMessageIdHex = item.record.messageIdHex,
                replyPreview =
                    TimelineReplyPreviewFfi(
                        messageIdHex = item.record.messageIdHex,
                        sender = item.record.sender,
                        plaintext = item.record.plaintext,
                        contentTokens = item.record.contentTokens,
                        kind = item.record.kind,
                        mediaJson = null,
                        media = emptyList(),
                        agentTextStreamJson = null,
                        deleted = false,
                        deletionSource = DeletionSourceFfi.UNKNOWN,
                        invalidationStatus = null,
                    ),
            )
        assertEquals("Localized poll", TimelineProjector.replyPreview(reply, copy)?.body)
        assertTrue(TimelineProjector.replyPreview(reply.copy(replyPreview = null), copy)?.originalUnavailable == true)
    }

    @Test fun rejectedVoteOwnerStillCompletesCardCleanup() =
        runTest {
            val item = pollMessage()
            retain(item)
            pollController.onCleared()
            var completions = 0
            submitOwnedPollVote(pollController, owner(item), listOf("a")) {
                completions++
                assertNull(it)
            }
            assertEquals(1, completions)
            assertTrue(recordedCalls().none { it.first == "castPollVote" })
        }

    private fun owner(item: dev.ipf.whitenoise.android.state.TimelineMessage) =
        PollMessageActionOwner("personal", item.record.groupIdHex, item.record.messageIdHex)
}
