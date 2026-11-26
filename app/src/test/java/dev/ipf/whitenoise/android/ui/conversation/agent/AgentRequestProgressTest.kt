package dev.ipf.whitenoise.android.ui.conversation.agent

import dev.ipf.marmotkit.MessageTagFfi
import dev.ipf.whitenoise.android.core.agent.AgentRequest
import dev.ipf.whitenoise.android.state.AgentNativeRequestHistory
import dev.ipf.whitenoise.android.state.AgentRequestAdmission
import dev.ipf.whitenoise.android.state.AgentRequestProgress
import dev.ipf.whitenoise.android.state.AgentRequestStatus
import dev.ipf.whitenoise.android.state.AgentRequestTarget
import dev.ipf.whitenoise.android.state.readAgentSettlements
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentRequestProgressTest {
    @Test
    fun keepsOneRequestPendingAcrossUpdatesUntilTheIntendedAgentConfirms() {
        val rows = AgentRecordingFixtures.load("codex_approval_allowed", events = 6).mapNotNull { it.projected }
        val approval = rows.single { it.agentItem?.itemType == "approval" }
        val requestId = "ab".repeat(32)
        val progress = AgentRequestProgress()
        val requests = mutableListOf<String>()
        var complete: (AgentRequestAdmission?) -> Unit = {}
        val sender =
            AgentRequestSender(progress, approval.messageIdHex, approval.sender) { _, token, onCompleted ->
                requests += token
                complete = onCompleted
            }
        val choice = AgentRequest.Choice("c0", "Allow once")
        sender.send(choice)
        assertEquals(AgentRequestStatus.Sending, sender.status)
        sender.send(choice)
        complete(AgentRequestAdmission(requestId))

        val item = requireNotNull(approval.agentItem)
        val fresh = approval.copy(agentItem = item.copy(version = item.version + 1uL))
        val unrelatedAgent = fresh.copy(sender = "66".repeat(32), agentItem = item.copy(settles = listOf(requestId)))
        progress.reconcile(sequenceOf(fresh, unrelatedAgent))
        assertEquals(AgentRequestStatus.AwaitingAgent, sender.status)
        sender.send(choice)
        assertEquals(1, requests.size)

        progress.reconcile(sequenceOf(fresh.copy(agentItem = item.copy(settles = listOf(requestId)))))
        assertEquals(AgentRequestStatus.Idle, sender.status)
    }

    @Test
    fun rawReplayFindsAnOldSettlementFromTheIntendedAgent() {
        val rows = AgentRecordingFixtures.load("codex_approval_allowed", events = 6)
        val approval = rows.single { it.projected?.agentItem?.itemType == "approval" }.record
        val requestId = "90".repeat(32)
        val confirmation =
            approval.copy(
                recordedAt = 1uL,
                kind = 1009uL,
                tags = listOf(MessageTagFfi(listOf("settles", requestId))),
            )
        val unrelated = confirmation.copy(sender = "91".repeat(32), recordedAt = 1001uL)
        val invalidated = confirmation.copy(invalidated = true, recordedAt = 1002uL)
        val newer = List(400) { unrelated.copy(messageIdHex = it.toString(16).padStart(64, '0')) }
        val history = listOf(confirmation) + newer + invalidated
        val limits = mutableListOf<UInt>()
        val matches =
            readAgentSettlements(setOf(approval.sender to requestId)) { limit ->
                limits += limit
                history.takeLast(limit.toInt())
            }

        assertEquals(listOf(400u, 800u), limits)
        assertEquals(listOf(confirmation), matches)
    }

    @Test
    fun restoresOnlyControlsForTheSameSessionFirstEvent() {
        val session =
            AgentRecordingFixtures
                .load("codex_approval_allowed", events = 6)
                .single { it.projected?.agentItem?.itemType == "session" }
                .record
        val request =
            session.copy(
                messageIdHex = "92".repeat(32),
                recordedAt = 102uL,
                receivedAt = 102uL,
                direction = "sent",
                kind = 1202uL,
                tags =
                    listOf(
                        MessageTagFfi(listOf("p", session.sender)),
                        MessageTagFfi(listOf("e", session.messageIdHex)),
                        MessageTagFfi(listOf("details", """{"action":"new_session"}""")),
                    ),
            )
        val target = AgentRequestTarget(session.messageIdHex, session.sender, isSession = true)
        val newer = target.copy(rowId = "93".repeat(32))
        val earlier = request.copy(messageIdHex = "ff".repeat(32))
        val settlement =
            session.copy(
                kind = 1009uL,
                tags = listOf(MessageTagFfi(listOf("settles", earlier.messageIdHex))),
            )
        val history = AgentNativeRequestHistory(listOf(earlier, request, settlement))

        assertEquals(request.messageIdHex, history.outstanding(target)?.eventId)
        assertNull(history.outstanding(newer))
        val older = request.copy(recordedAt = 101uL, receivedAt = 101uL)
        assertNull(AgentNativeRequestHistory(listOf(earlier, older, settlement)).outstanding(target))
        val legacy = request.copy(tags = request.tags.filterNot { it.values.firstOrNull() == "e" })
        assertNull(AgentNativeRequestHistory(listOf(legacy)).outstanding(target))
    }

    @Test
    fun finalMessageCanConfirmAnAcceptedRequest() {
        val rows = AgentRecordingFixtures.load("codex_approval_allowed").mapNotNull { it.projected }
        val words = rows.last { it.agentItem == null && it.sender == AgentRecordingFixtures.AGENT_ID }
        val turn = rows.single { it.agentItem?.itemType == "turn" }
        val progress = AgentRequestProgress()
        val requestId = "cd".repeat(32)
        progress.begin(turn.messageIdHex, turn.sender)
        progress.completeAdmission(turn.messageIdHex, AgentRequestAdmission(requestId))

        val confirmation = words.copy(tags = words.tags + MessageTagFfi(listOf("settles", requestId)))
        progress.reconcile(sequenceOf(confirmation.copy(deleted = true)))
        assertEquals(AgentRequestStatus.AwaitingAgent, progress.status(turn.messageIdHex))
        progress.reconcile(sequenceOf(confirmation))
        assertEquals(AgentRequestStatus.Idle, progress.status(turn.messageIdHex))
    }

    @Test
    fun reopeningRestoresDurableAnswerUntilAnAuthenticatedRawEditSettlesIt() {
        val rows = AgentRecordingFixtures.load("codex_approval_allowed", events = 6)
        val approval = rows.single { it.projected?.agentItem?.itemType == "approval" }.record
        val target = AgentRequestTarget(approval.messageIdHex, approval.sender)
        val request =
            approval.copy(
                messageIdHex = "a1".repeat(32),
                direction = "sent",
                kind = 1202uL,
                tags =
                    listOf(
                        MessageTagFfi(listOf("p", approval.sender)),
                        MessageTagFfi(listOf("e", approval.messageIdHex)),
                        MessageTagFfi(listOf("details", """{"action":"choice","choice":"c0"}""")),
                    ),
            )
        val settlement =
            approval.copy(
                messageIdHex = "a2".repeat(32),
                kind = 1009uL,
                tags = listOf(MessageTagFfi(listOf("settles", request.messageIdHex))),
            )
        val progress = AgentRequestProgress()
        progress.reconcileHistory(
            AgentNativeRequestHistory(listOf(request, settlement.copy(sender = "a3".repeat(32)))),
            listOf(target),
            progress.historyRevision,
        )
        assertEquals(AgentRequestStatus.AwaitingAgent, progress.status(target.rowId))
        val oldReadRevision = progress.historyRevision
        val settledHistory = AgentNativeRequestHistory(listOf(request, settlement))
        progress.reconcileHistory(settledHistory, listOf(target), oldReadRevision)
        assertEquals(AgentRequestStatus.Idle, progress.status(target.rowId))
        progress.reconcileHistory(AgentNativeRequestHistory(listOf(request)), listOf(target), oldReadRevision)
        assertEquals(AgentRequestStatus.Idle, progress.status(target.rowId))

        progress.begin(target.rowId, target.agent)
        progress.completeAdmission(target.rowId, AgentRequestAdmission(request.messageIdHex))
        val invalidated = AgentNativeRequestHistory(listOf(request.copy(invalidated = true)))
        val failed = progress.reconcileHistory(invalidated, listOf(target), progress.historyRevision)
        assertTrue(failed)
        assertFalse(progress.reject(setOf(request.messageIdHex)))
        assertEquals(AgentRequestStatus.Idle, progress.status(target.rowId))
    }
}
