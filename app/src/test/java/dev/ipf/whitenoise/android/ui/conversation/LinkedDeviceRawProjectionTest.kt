package dev.ipf.whitenoise.android.ui.conversation

import dev.ipf.marmotkit.ConversationMessageReferencesFfi
import dev.ipf.marmotkit.ConversationReactionFfi
import dev.ipf.marmotkit.ConversationReactionsFfi
import dev.ipf.marmotkit.MarmotKitException
import dev.ipf.marmotkit.SendAcceptDispositionFfi
import dev.ipf.marmotkit.TimelineUserReactionFfi
import dev.ipf.whitenoise.android.notifications.notificationUpdate
import dev.ipf.whitenoise.android.state.MarmotWindowTestFakes
import dev.ipf.whitenoise.android.state.lookupMessageRecord
import dev.ipf.whitenoise.android.state.notificationMessageRecord
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class LinkedDeviceRawProjectionTest : PollMessageTestFixtures() {
    @Before fun bindRoster() = runTest { pollController.retryMembers() }

    @After fun clearController() = pollController.onCleared()

    /** The same invalidated raw event cannot supply either notification artwork/content or a reaction deep link. */
    @Test
    fun invalidatedRawNotificationEventsStayUnavailable() =
        runTest {
            val record = pollMessage(closed = true).record
            val update =
                notificationUpdate(
                    accountRef = requireNotNull(pollController.boundAccountRef),
                    groupIdHex = record.groupIdHex,
                    messageIdHex = record.messageIdHex,
                )
            rawMessages = listOf(record)
            assertEquals(record, pollState.notificationMessageRecord(update))
            assertEquals(record, pollController.lookupMessageRecord(record.messageIdHex))
            rawMessages = listOf(record.copy(invalidated = true))
            assertNull(pollState.notificationMessageRecord(update))
            assertNull(pollController.lookupMessageRecord(record.messageIdHex))
            pollController.messageById[record.messageIdHex] = rawMessages.single()
            assertNull(pollController.lookupMessageRecord(record.messageIdHex))
        }

    /** Visible and omitted chips both retract duplicate sibling events and preserve other reactions. */
    @Test fun visibleOwnReactionRetractsAllMatchingSiblingEvents() = runTest { assertMatchingRemoval(visible = true) }

    @Test fun omittedOwnReactionRetractsAllMatchingSiblingEvents() = runTest { assertMatchingRemoval(visible = false) }

    private suspend fun assertMatchingRemoval(visible: Boolean) {
        val item = pollMessage(closed = true).also(::retain)
        val id = item.record.messageIdHex
        val own = ownReaction(id)
        val first = own.copy(reactionMessageIdHex = "01".repeat(32))
        val otherEmoji = own.copy(emoji = "🔥", reactionMessageIdHex = "02".repeat(32))
        val peer = own.copy(sender = "peer", reactionMessageIdHex = "03".repeat(32))
        val events = mutableListOf(first, own, otherEmoji, peer)
        installReactionReferences(id, visible)
        // MDK's exact details collapse sibling events to the latest row per sender/emoji.
        reactionDetailsResponder = { events.asReversed().distinctBy { it.sender to it.emoji } }
        removeMatchingReaction = { args ->
            assertEquals(listOf("personal", item.record.groupIdHex, id, EMOJI), args.take(4))
            events.removeAll { it.sender == own.sender && it.targetMessageIdHex == args[2] && it.emoji == args[3] }
        }
        val intents = mutableListOf<Boolean>()
        pollController.toggleReaction(EMOJI, item.record, onLocalIntent = { intents += it })
        assertEquals(listOf(false), intents)
        assertEquals(listOf(otherEmoji, peer), events)
        assertEquals(if (visible) 0 else 1, recordedCalls().count { it.first == "messageReactions" })
        assertEquals(1, recordedCalls().count { it.first == "unreactFromMessageMatching" })
        assertFalse(recordedCalls().any { it.first == "deleteMessage" || it.first == "unreactFromMessage" })
    }

    /** A locally accepted add can be removed before its chip echo, even when MDK returned no published ID. */
    @Test
    fun pendingAddWithoutIdIsRemovedByTargetAndEmoji() =
        runTest {
            val item = pollMessage(closed = true).also(::retain)
            reactionSummary =
                reactionSummary.copy(
                    messageIds = emptyList(),
                    acceptDisposition = SendAcceptDispositionFfi.ACCEPTED_PENDING,
                )
            pollController.toggleReaction(EMOJI, item.record)
            pollController.toggleReaction(EMOJI, item.record)
            assertEquals(1, recordedCalls().count { it.first == "reactToMessage" })
            val removed = recordedCalls().single { it.first == "unreactFromMessageMatching" }
            val expected = listOf("personal", item.record.groupIdHex, item.record.messageIdHex, EMOJI)
            assertEquals(expected, removed.second.take(4))
            assertFalse(recordedCalls().any { it.first == "messages" || it.first == "deleteMessage" })
        }

    /** A sibling raced the removal. Only a fresh exact read can confirm local convergence. */
    @Test
    fun alreadyRemovedReactionChecksLocalStateBeforeSettling() =
        runTest {
            val item = pollMessage(closed = true).also(::retain)
            installReactionReferences(item.record.messageIdHex, visible = true)
            removeMatchingReaction = { throw MarmotKitException.ReactionNotFound() }
            pollController.toggleReaction(EMOJI, item.record)
            assertEquals(1, recordedCalls().count { it.first == "messageReactions" })
            assertNull(pollState.toast)
        }

    /** If a fresh effective read still has our reaction, a rejected removal must roll back and surface the failure. */
    @Test
    fun rejectedRemovalWithPresentReactionRollsBack() =
        runTest {
            val item = pollMessage(closed = true).also(::retain)
            val id = item.record.messageIdHex
            installReactionReferences(id, visible = true)
            removeMatchingReaction = { throw MarmotKitException.ReactionNotFound() }
            reactionDetailsResponder = { listOf(ownReaction(id)) }
            pollController.toggleReaction(EMOJI, item.record)
            assertTrue(pollController.reactions[id].orEmpty().any { it.emoji == EMOJI && it.mine })
            assertNotNull(pollState.toast)
        }

    /** An exact empty local set admits an add even when some other emoji kinds were omitted. */
    @Test
    fun omittedAbsentReactionIsAdded() =
        runTest {
            val item = pollMessage(closed = true).also(::retain)
            installReactionReferences(item.record.messageIdHex, visible = false)
            val intents = mutableListOf<Boolean>()
            pollController.toggleReaction(EMOJI, item.record, onLocalIntent = { intents += it })
            assertEquals(listOf(true), intents)
            assertTrue(recordedCalls().any { it.first == "messageReactions" })
            assertEquals(EMOJI, recordedCalls().single { it.first == "reactToMessage" }.second[3])
        }

    /** A second tap arriving during the exact read cancels the first intent before any native mutation. */
    @Test
    fun twoTapsDuringOmittedReactionLookupCancelEachOther() =
        runTest {
            val item = pollMessage(closed = true).also(::retain)
            val id = item.record.messageIdHex
            installReactionReferences(id, visible = false)
            val started = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            reactionDetailsResponder = {
                started.complete(Unit)
                runBlocking { release.await() }
                listOf(ownReaction(id))
            }
            val intents = mutableListOf<Boolean>()

            suspend fun tap() = pollController.toggleReaction(EMOJI, item.record, onLocalIntent = { intents += it })
            val first = async(start = CoroutineStart.UNDISPATCHED) { tap() }
            try {
                started.await()
                val second = async(start = CoroutineStart.UNDISPATCHED) { tap() }
                release.complete(Unit)
                first.await()
                second.await()
            } finally {
                release.complete(Unit)
            }
            assertEquals(listOf(false, true), intents)
            assertEquals(1, recordedCalls().count { it.first == "messageReactions" })
            val writes = setOf("reactToMessage", "unreactFromMessageMatching")
            assertFalse(recordedCalls().any { it.first in writes })
        }

    /** Installs the viewer-owned chip or a bounded summary that omits its kind. */
    private fun installReactionReferences(
        target: String,
        visible: Boolean,
    ) {
        val chips =
            if (visible) {
                listOf(ConversationReactionFfi(EMOJI, 2uL, emptyList(), true, EVENT))
            } else {
                emptyList()
            }
        val row =
            ConversationMessageReferencesFfi(
                messageIdHex = target,
                sender = null,
                replyAuthor = null,
                mentions = emptyList(),
                mentionsTruncated = false,
                replyMentions = emptyList(),
                replyMentionsTruncated = false,
                system = null,
                reactions = ConversationReactionsFfi(2uL, 1uL, chips, if (visible) 0uL else 1uL),
            )
        val frame = MarmotWindowTestFakes.conversationFrame("Poll").copy(references = mapOf(target to row))
        pollController.window.install(frame)
    }

    /** The exact historical event the bound account owns for this target. */
    private fun ownReaction(target: String) =
        TimelineUserReactionFfi(
            reactionMessageIdHex = EVENT,
            targetMessageIdHex = target,
            sender = requireNotNull(pollController.boundAccountIdHex),
            emoji = EMOJI,
            reactedAt = 1uL,
        )

    private companion object {
        const val EMOJI = "👍"
        val EVENT = "aa".repeat(32)
    }
}
