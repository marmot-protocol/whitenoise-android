package dev.ipf.whitenoise.android.ui.conversation.agent

import dev.ipf.marmotkit.AgentItemStateFfi
import dev.ipf.marmotkit.MessageTagFfi
import dev.ipf.marmotkit.TimelineMessageRecordFfi
import dev.ipf.marmotkit.TimelinePageFfi
import dev.ipf.marmotkit.TimelineThreadRefFfi
import dev.ipf.whitenoise.android.state.isUnreadEligible
import dev.ipf.whitenoise.android.ui.conversation.conversationTimelineRowVisible
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentThreadPresentationTest {
    private val recording = AgentRecordingFixtures.load("codex_approval_allowed", events = 6, prompt = "Run greet.py")
    private val approval = recording.single { it.projected?.agentItem?.itemType == "approval" }
    private val item = requireNotNull(approval.projected?.agentItem)
    private val root = "ae".repeat(32)
    private val child = "be".repeat(32)

    @Test
    fun nativeThreadParentsPlaceNestedWorkAndPeopleReplies() {
        val base = requireNotNull(approval.projected)
        val task = base.copy(messageIdHex = root, agentItem = item.copy(itemType = "task"))
        val nested = base.copy(messageIdHex = child, agentItem = item.copy(itemType = "task")).inThread(root)
        val waiting = base.inThread(child)
        val words =
            base
                .copy(messageIdHex = "cc".repeat(32), kind = 1111uL, agentItem = null)
                .inThread(child)
                .let { it.copy(tags = it.tags + MessageTagFfi(listOf("P", it.sender))) }
        val reply = words.copy(messageIdHex = "dd".repeat(32), sender = "ad".repeat(32)).inThread(words.messageIdHex)
        val replyToApproval =
            reply
                .copy(
                    messageIdHex = "ee".repeat(32),
                    tags = reply.tags + MessageTagFfi(listOf("k", "1201")),
                ).inThread(waiting.messageIdHex)
        val rows = listOf(task, nested, waiting, words, reply, replyToApproval)
        val page = TimelinePageFfi(rows, false, false)

        assertEquals(listOf(root), activityHistoryRows(page, emptySet()).map { it.id })
        assertEquals(listOf(child), activityHistoryRows(page, emptySet(), threadTaskId = root).map { it.id })
        assertEquals(
            listOf(waiting.messageIdHex, words.messageIdHex, reply.messageIdHex, replyToApproval.messageIdHex),
            activityHistoryRows(page, emptySet(), threadTaskId = child).map { it.id },
        )
        val olderParentOutsidePage = TimelinePageFfi(listOf(waiting, words, reply, replyToApproval), true, false)
        assertTrue(activityHistoryRows(olderParentOutsidePage, emptySet(), threadTaskId = root).isEmpty())
        assertEquals(
            listOf(waiting.messageIdHex, words.messageIdHex, reply.messageIdHex, replyToApproval.messageIdHex),
            activityHistoryRows(olderParentOutsidePage, emptySet(), threadTaskId = child).map { it.id },
        )
    }

    @Test
    fun readsAnOffPageParentAndRestoresAThreadFromItsRetainedEdit() =
        runTest {
            val base = requireNotNull(approval.projected)
            val nested =
                base
                    .copy(messageIdHex = child, agentItem = item.copy(itemType = "task", itemId = "inner"))
                    .inThread(root)
            val waiting = base.inThread(child)
            val oldNote = base.copy(messageIdHex = "59".repeat(32), agentItem = null, kind = 1111uL).inThread(waiting.messageIdHex)
            val reply = oldNote.copy(messageIdHex = "67".repeat(32)).inThread(oldNote.messageIdHex)
            val held =
                base
                    .copy(messageIdHex = "68".repeat(32), agentItemFirstEventMissing = true)
                    .inThread(root)
                    .let { it.copy(tags = it.tags + MessageTagFfi(listOf("subagent", "inner"))) }
            val loaded = listOf(reply, held)
            val retained = listOf(nested, waiting, oldNote).associateBy { it.messageIdHex }
            val extra = agentThreadParentRecords(loaded, child) { retained[it] }
            val page = TimelinePageFfi(loaded, true, false)

            assertEquals(
                listOf(reply.messageIdHex, held.messageIdHex),
                activityHistoryRows(page, emptySet(), threadTaskId = child, threadParents = extra).map { it.id },
            )
        }

    @Test
    fun waitingThreadRequestRemainsVisibleInTheMainChat() {
        val base = requireNotNull(approval.projected).inThread(root)
        assertTrue(conversationTimelineRowVisible(approval.copy(projected = base)))
        assertFalse(
            conversationTimelineRowVisible(approval.copy(projected = base.copy(agentItem = item.copy(state = AgentItemStateFfi.DONE)))),
        )
        val message = recording.first()
        val words = requireNotNull(message.projected).copy(kind = 1111uL)
        assertTrue(conversationTimelineRowVisible(message.copy(projected = words)))
        val humanReply = words.inThread(root)
        assertTrue(conversationTimelineRowVisible(message.copy(projected = humanReply)))
        val agentWords =
            humanReply.copy(
                sender = AgentRecordingFixtures.AGENT_ID,
                tags = humanReply.tags + MessageTagFfi(listOf("P", AgentRecordingFixtures.AGENT_ID)),
            )
        assertFalse(conversationTimelineRowVisible(message.copy(projected = agentWords)))
        assertFalse(
            message.copy(record = message.record.copy(kind = 1111uL, direction = "received"), projected = agentWords).isUnreadEligible(),
        )
    }

    @Test
    fun latestSessionRemainsActionableWhenPagingToItsOpeningRow() {
        val base = requireNotNull(approval.projected)
        val session = base.copy(agentItem = item.copy(itemType = "session"), timelineAt = 20uL)
        val older = session.copy(messageIdHex = "88".repeat(32), timelineAt = 10uL)

        val visitedLatest = latestAgentSessions(emptyMap(), listOf(base))
        val visitedSession = latestAgentSessions(visitedLatest, listOf(older, session))
        assertEquals(session.messageIdHex, visitedSession[session.sender]?.messageIdHex)
        assertEquals(visitedSession, latestAgentSessions(visitedSession, listOf(older)))
    }

    private fun TimelineMessageRecordFfi.inThread(parent: String) =
        copy(
            thread = TimelineThreadRefFfi(root, parent),
            tags = tags + MessageTagFfi(listOf("K", "1201")),
        )
}
