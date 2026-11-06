package dev.ipf.whitenoise.android.ui.conversation.messages

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import dev.ipf.marmotkit.AppMessageRecordFfi
import dev.ipf.marmotkit.MarkdownDocumentFfi
import dev.ipf.marmotkit.MediaAttachmentReferenceFfi
import dev.ipf.marmotkit.MessageTagFfi
import dev.ipf.whitenoise.android.core.ForwardAttachmentSource
import dev.ipf.whitenoise.android.core.ForwardBlockedReason
import dev.ipf.whitenoise.android.core.ForwardEligibility
import dev.ipf.whitenoise.android.core.ForwardMessagePayload
import dev.ipf.whitenoise.android.core.MessageProjector
import dev.ipf.whitenoise.android.state.ForwardOperationPhase
import dev.ipf.whitenoise.android.state.ForwardSession
import dev.ipf.whitenoise.android.state.ForwardTransport
import dev.ipf.whitenoise.android.state.MessageStatus
import dev.ipf.whitenoise.android.state.PendingAttachment
import dev.ipf.whitenoise.android.state.PreparedForwardMessage
import dev.ipf.whitenoise.android.ui.conversation.BatchMessageActionItem
import dev.ipf.whitenoise.android.ui.conversation.batchForwardPayloads
import dev.ipf.whitenoise.android.ui.conversation.batchForwardSnapshotFor
import dev.ipf.whitenoise.android.ui.conversation.batchSelectionActionAvailability
import dev.ipf.whitenoise.android.ui.conversation.composer.ComposerGate
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Invariant gate for the pending-text half of #2794: a locally authored text send still marked Sending can be
 * forwarded from its accepted content, as a fresh destination message, while pending media stays blocked.
 *
 * The forward picker holds a snapshot of the text, so the source confirming, failing or reconciling while it is open
 * cannot rewrite or close it, and a batch holding a pending send offers only Copy and Forward.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-rUS-w360dp-h780dp-mdpi")
@OptIn(ExperimentalCoroutinesApi::class)
class PendingTextForwardCoverageTest {
    @get:Rule val composeRule = createComposeRule()

    /** A pending text send offers Forward in its own action menu, and Forward opens the destination picker. */
    @Test
    fun pendingTextOffersForwardFromTheMessageMenu() {
        val robot = PendingBubbleRobot()
        robot.render()

        composeRule.onNodeWithText("Forward", substring = false).performClick()
        composeRule.onNodeWithTag(FORWARD_CHAT_PICKER_SCREEN_TEST_TAG).assertIsDisplayed()
    }

    /** The open picker keeps its text snapshot when the source send fails underneath it. */
    @Test
    fun theOpenPickerSurvivesTheSourceFailing() {
        val robot = PendingBubbleRobot()
        robot.render()
        composeRule.onNodeWithText("Forward", substring = false).performClick()
        composeRule.onNodeWithTag(FORWARD_CHAT_PICKER_SCREEN_TEST_TAG).assertIsDisplayed()

        composeRule.runOnIdle { robot.item = robot.item.copy(status = MessageStatus.Failed) }

        composeRule.onNodeWithTag(FORWARD_CHAT_PICKER_SCREEN_TEST_TAG).assertIsDisplayed()
    }

    /** A sent message whose edit is in flight is not a pending send, so its Forward stays blocked as before. */
    @Test
    fun aSentMessageWithAnEditInFlightIsNotAPendingSend() {
        val robot = PendingBubbleRobot()
        robot.item = robot.item.copy(statusFromEdit = true)
        assertFalse(robot.item.isPendingSend)
        robot.render()

        if (composeRule.onAllNodesWithText("Forward", substring = false).fetchSemanticsNodes().isNotEmpty()) {
            composeRule.onNodeWithText("Forward", substring = false).performClick()
        }
        composeRule.onNodeWithTag(FORWARD_CHAT_PICKER_SCREEN_TEST_TAG).assertDoesNotExist()
    }

    /**
     * A mixed batch picker forwards the payloads it opened with, even after a pending send drops out of the live
     * selection, and closes once nothing forwardable is left.
     */
    @Test
    fun aMixedBatchPickerKeepsItsOpeningSnapshot() {
        val committed = actionItem("m1", "first", pending = false)
        val pending = actionItem(TEMP_ID, "second", pending = true)
        val opened = batchForwardPayloads(listOf(committed, pending))

        val afterFailure = batchForwardSnapshotFor(opened, batchForwardPayloads(listOf(committed)))
        assertEquals(listOf("first", "second"), afterFailure?.texts())
        assertNull(batchForwardSnapshotFor(opened, emptyList()))
        assertNull(batchForwardSnapshotFor(null, opened))
    }

    /** Pending text forwards exactly the accepted body, while pending media keeps its pending-attachment reason. */
    @Test
    fun pendingTextForwardsItsBodyAndPendingMediaStaysBlocked() {
        val text = MessageProjector.pendingForwardEligibility(record(TEMP_ID, "accepted body"), emptyList(), null, 1uL)
        val payload = (text as ForwardEligibility.Eligible).payload as ForwardMessagePayload.Text
        assertEquals("accepted body", payload.text)

        val media =
            MessageProjector.pendingForwardEligibility(
                record(TEMP_ID, "photo.jpg", tags = listOf(MessageTagFfi(listOf("_media_pending", "photo.jpg")))),
                emptyList(),
                null,
                1uL,
            )
        assertEquals(ForwardEligibility.Blocked(ForwardBlockedReason.PendingAttachment), media)
    }

    /** An edited pending text forwards its latest edit, and a non-chat pending row stays unsupported. */
    @Test
    fun pendingForwardUsesTheLatestEditAndRejectsNonChatRows() {
        val edited =
            MessageProjector.pendingForwardEligibility(record(TEMP_ID, "first"), emptyList(), "edited", 1uL)
        assertEquals("edited", ((edited as ForwardEligibility.Eligible).payload as ForwardMessagePayload.Text).text)

        val reaction =
            MessageProjector.pendingForwardEligibility(record(TEMP_ID, "+", kind = 7uL), emptyList(), null, 1uL)
        assertEquals(ForwardEligibility.Blocked(ForwardBlockedReason.Unsupported), reaction)
    }

    /** A batch of committed and pending text forwards in order but offers no reply, info, save or delete. */
    @Test
    fun aBatchWithPendingTextForwardsInOrderAndOffersOnlyCopyAndForward() {
        val committed = actionItem("m1", "first", pending = false)
        val pending = actionItem(TEMP_ID, "second", pending = true)

        assertEquals(listOf("first", "second"), batchForwardPayloads(listOf(committed, pending)).texts())
        val availability = batchSelectionActionAvailability(listOf(pending), ComposerGate.COMPOSER)
        assertTrue(availability.canCopy)
        assertTrue(availability.canForward)
        assertFalse(availability.canReply)
        assertFalse(availability.canInfo)
        assertFalse(availability.canSave)
        assertFalse(availability.canDelete)
        assertTrue(batchSelectionActionAvailability(listOf(committed), ComposerGate.COMPOSER).canDelete)
    }

    /** One pending media selection disables the whole batch forward instead of silently dropping it. */
    @Test
    fun pendingMediaInABatchDisablesForwardAtomically() {
        val pendingMedia =
            BatchMessageActionItem(
                messageId = "pending-media",
                senderId = "me",
                senderDisplayName = "Me",
                copyableText = null,
                forwardableText = null,
                canDeleteForEveryone = false,
                forwardBlockedReason = ForwardBlockedReason.PendingAttachment,
                pendingSend = true,
            )
        val items = listOf(actionItem("m1", "first", pending = false), pendingMedia)

        assertTrue(batchForwardPayloads(items).isEmpty())
        assertFalse(batchSelectionActionAvailability(items, ComposerGate.COMPOSER).canForward)
    }

    /**
     * An accepted forward of a pending text carries only the body to each destination, never source identity, and the
     * source later confirming, failing or reconciling cannot make it publish again.
     */
    @Test
    fun anAcceptedPendingTextForwardPublishesOnlyItsBodyOnce() =
        runTest {
            val published = mutableListOf<Pair<String, PreparedForwardMessage>>()
            val transport = TextOnlyTransport(published)
            val source = record(TEMP_ID, "accepted body")
            val payload =
                (
                    MessageProjector.pendingForwardEligibility(source, emptyList(), null, 1uL)
                        as ForwardEligibility.Eligible
                ).payload
            val session =
                ForwardSession(
                    scope = this,
                    messages = listOf(payload),
                    targetGroupIds = listOf("target-a", "target-b"),
                    transport = transport,
                )

            session.start()
            advanceUntilIdle()
            // The source confirming under its canonical id, or failing, never reaches the accepted session again.
            session.start()
            advanceUntilIdle()

            assertEquals(ForwardOperationPhase.Completed, session.state.value.phase)
            assertEquals(
                listOf(
                    "target-a" to PreparedForwardMessage.Text("accepted body"),
                    "target-b" to PreparedForwardMessage.Text("accepted body"),
                ),
                published.sortedBy { it.first },
            )
            session.release()
        }

    /** A destination that only publishes text, failing loudly if the session ever reached for source media. */
    private class TextOnlyTransport(
        private val published: MutableList<Pair<String, PreparedForwardMessage>>,
    ) : ForwardTransport {
        /** Pending text has no attachment to read, so materializing is a contract violation. */
        override suspend fun materialize(
            sourceGroupIdHex: String,
            sourceMessageIdHex: String,
            source: ForwardAttachmentSource,
        ): PendingAttachment = error("pending text must not materialize source media")

        /** Pending text has no attachment to upload. */
        override suspend fun upload(
            targetGroupIdHex: String,
            message: PreparedForwardMessage.Media,
        ): List<MediaAttachmentReferenceFfi> = error("pending text must not upload media")

        /** Records each destination message exactly as the session hands it over. */
        override suspend fun publishBatch(
            targetGroupIdHex: String,
            messages: List<PreparedForwardMessage>,
            uploadedReferences: Map<Int, List<MediaAttachmentReferenceFfi>>,
            startIndex: Int,
            clientTokenFor: (messageIndex: Int) -> String,
            onBeforeMessagePublished: (messageIndex: Int) -> Unit,
            onMessagePublished: (messageIndex: Int) -> Unit,
        ) {
            for (index in startIndex until messages.size) {
                onBeforeMessagePublished(index)
                published += targetGroupIdHex to messages[index]
                onMessagePublished(index)
            }
        }
    }

    /** Hosts the production bubble for an own text send that has not been confirmed yet, with its menu open. */
    private inner class PendingBubbleRobot {
        private val surface =
            swipeTestSurface(ApplicationProvider.getApplicationContext(), reacted = false, mine = true, media = false)
        var item by mutableStateOf(surface.item.copy(status = MessageStatus.Pending, projected = null))

        /** Renders the bubble with its action menu open. */
        fun render() {
            composeRule.setContent {
                SwipeTestBubbleHost(surface = surface.copy(item = item), isActionMenuOpen = true)
            }
            composeRule.waitForIdle()
        }
    }

    private companion object {
        const val TEMP_ID = "pending-temp-id"
        val EMPTY_DOCUMENT =
            MarkdownDocumentFfi(truncated = false, blocks = emptyList(), blankLinesBefore = ByteArray(0))

        /** A chat record for [id] with [plaintext], as an optimistic send holds it. */
        fun record(
            id: String,
            plaintext: String,
            kind: ULong = 9uL,
            tags: List<MessageTagFfi> = emptyList(),
        ) = AppMessageRecordFfi(
            messageIdHex = id,
            direction = "sent",
            groupIdHex = "source-group",
            sender = "me",
            plaintext = plaintext,
            contentTokens = EMPTY_DOCUMENT,
            kind = kind,
            tags = tags,
            sourceEpoch = null,
            retentionSeconds = null,
            retentionExpiresAt = null,
            recordedAt = 1uL,
            receivedAt = 1uL,
            sticker = null,
        )

        /** A selected text row with its forward snapshot, marked pending when it is still being sent. */
        fun actionItem(
            id: String,
            text: String,
            pending: Boolean,
        ) = BatchMessageActionItem(
            messageId = id,
            senderId = "me",
            senderDisplayName = "Me",
            copyableText = text,
            forwardableText = text,
            canDeleteForEveryone = !pending,
            forwardPayload = ForwardMessagePayload.Text("source-group", id, text),
            pendingSend = pending,
        )

        /** The forwarded bodies of a text-only batch, in batch order. */
        fun List<ForwardMessagePayload>.texts() = map { (it as ForwardMessagePayload.Text).text }
    }
}
