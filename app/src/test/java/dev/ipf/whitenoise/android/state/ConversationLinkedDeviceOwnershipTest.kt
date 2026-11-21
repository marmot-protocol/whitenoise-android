package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.HistoryImportProvenanceFfi
import dev.ipf.marmotkit.HistoryImportSourceFfi
import dev.ipf.marmotkit.TimelinePageFfi
import dev.ipf.whitenoise.android.core.MessageProjector
import dev.ipf.whitenoise.android.core.TimelineProjector
import dev.ipf.whitenoise.android.core.isSentFromThisDevice
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The account's own messages from another of its devices, live or imported, read as its own through the production
 * conversation controller, while only this installation's sends carry a local delivery state.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en")
class ConversationLinkedDeviceOwnershipTest {
    private val localSend =
        timelineRecord(messageId = "local-send", timelineAt = 1uL).copy(
            sourceMessageIdHex = null,
            direction = "sent",
            sender = ConversationTimelineTestIds.ACCOUNT_ID,
        )
    private val siblingLive =
        timelineRecord(messageId = "sibling-live", timelineAt = 2uL).copy(
            sourceMessageIdHex = "source-sibling-live",
            direction = "received",
            sender = ConversationTimelineTestIds.ACCOUNT_ID,
        )
    private val importedOwn =
        timelineRecord(messageId = "imported-own", timelineAt = 3uL).copy(
            sourceMessageIdHex = null,
            direction = "received",
            sender = ConversationTimelineTestIds.ACCOUNT_ID,
            importedFrom = HistoryImportSourceFfi(deviceSlotIdHex = "slot", deviceLabel = "Tablet"),
            historyImportProvenance = HistoryImportProvenanceFfi.Device("slot"),
        )

    /** All three rows are the account's own, but only the local send is pending or awaiting delivery. */
    @Test
    fun ownRowsFromAnotherDeviceAreMineAndAlreadyDelivered() =
        runBlocking {
            val messages = listOf(localSend, siblingLive, importedOwn)
            val page = TimelinePageFfi(messages, hasMoreBefore = false, hasMoreAfter = false)
            val scripted =
                ScriptedConversationLiveSubscriptions(
                    timelineScripts = listOf(ScriptedConversationTimelineSubscription(page)),
                    group = conversationTimelineTestGroup(),
                )
            val controller =
                ConversationController(
                    appState = conversationTimelineTestAppState(scripted.subscriptions),
                    initialGroup = conversationTimelineTestGroup(),
                    initialMemberSnapshot = conversationTimelineMemberSnapshot(),
                    groupRosterReader = { _, _ -> conversationTimelineGroupRoster() },
                    startOnConstruction = true,
                )
            try {
                awaitConversationCondition { controller.timeline.size == 3 }
                val rows = controller.timeline.associateBy { it.record.messageIdHex }
                val local = rows.getValue("local-send")
                val sibling = rows.getValue("sibling-live")
                val imported = rows.getValue("imported-own")

                listOf(local, sibling, imported).forEach { assertTrue(controller.isMessageMine(it.record)) }
                assertEquals(MessageStatus.Pending, local.status)
                assertEquals(MessageStatus.Sent, sibling.status)
                assertEquals(MessageStatus.Sent, imported.status)
                assertTrue(controller.awaitingDelivery(local))
                assertFalse(controller.awaitingDelivery(sibling))
                assertFalse(controller.awaitingDelivery(imported))
            } finally {
                controller.onCleared()
                awaitOpenedTimelineSubscriptionsClosed(scripted)
            }
        }

    /** An imported own row without a source event is never mistaken for an unpublished local send. */
    @Test
    fun anImportedOwnRowIsNotAnUnpublishedLocalSend() {
        val sameText = importedOwn.copy(plaintext = localSend.plaintext)
        val records = listOf(localSend, sameText).associateBy { it.messageIdHex }
        val message = TimelineProjector.toAppMessageRecord(localSend)
        val account = ConversationTimelineTestIds.ACCOUNT_ID

        val unpublished = unpublishedProjectionIdsMatchingMessage(records, message, account)

        assertEquals(listOf("local-send"), unpublished)
    }

    /** Authorship and local sending are separate facts about a row. */
    @Test
    fun authorshipAndLocalSendingAreSeparate() {
        val account = ConversationTimelineTestIds.ACCOUNT_ID
        val local = TimelineProjector.toAppMessageRecord(localSend)
        val sibling = TimelineProjector.toAppMessageRecord(siblingLive)
        val peer = sibling.copy(sender = "someone-else")

        assertTrue(MessageProjector.isMine(local, account) && local.isSentFromThisDevice())
        assertTrue(MessageProjector.isMine(sibling, account) && !sibling.isSentFromThisDevice())
        assertFalse(MessageProjector.isMine(peer, account) || peer.isSentFromThisDevice())
    }
}
