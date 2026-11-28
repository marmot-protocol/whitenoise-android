package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.LocalSendAcceptanceFfi
import dev.ipf.marmotkit.LocalSendStatusFfi
import dev.ipf.marmotkit.MarmotInterface
import dev.ipf.marmotkit.MarmotKitException
import dev.ipf.marmotkit.MessageTagFfi
import dev.ipf.whitenoise.android.core.agent.AgentRequest
import dev.ipf.whitenoise.android.core.agent.AgentRequestEncoder
import dev.ipf.whitenoise.android.ui.conversation.agent.AgentRecordingFixtures
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class AgentRequestAdmissionTest {
    /**
     * An admission that fails after MDK durably took the request resolves to the recorded request,
     * so the card keeps waiting for the agent instead of reporting a failure and offering a resend.
     */
    @Test
    fun recoveredOwnershipWithoutAnEventIdReadsTheRequestFromNativeHistory() =
        runTest {
            val approval =
                AgentRecordingFixtures
                    .load("codex_approval_allowed", events = 6)
                    .single { it.projected?.agentItem?.itemType == "approval" }
                    .record
            val target = AgentRequestTarget(approval.messageIdHex, approval.sender)
            val event = AgentRequestEncoder.encode(target.agent, target.rowId, AgentRequest.Choice("c0", "Allow"))
            val recorded =
                approval.copy(
                    messageIdHex = "a1".repeat(32),
                    direction = "sent",
                    kind = event.kind,
                    tags = event.tags.map(::MessageTagFfi),
                )
            val owned = "a1".repeat(32)
            for (status in listOf(LocalSendStatusFfi.Queued(owned), LocalSendStatusFfi.EngineOwned(owned))) {
                var admissions = 0
                val boundary =
                    nativeBoundary { method, args ->
                        when (method) {
                            "localSendStatus" -> status.takeIf { admissions > 0 }
                            "messages" -> {
                                @Suppress("UNCHECKED_CAST")
                                val kinds = args[3] as List<ULong>?
                                listOf(recorded).filter { admissions > 0 && (kinds == null || it.kind in kinds) }
                            }
                            else -> error(method)
                        }
                    }
                // A JVM proxy wraps checked native exceptions; this override preserves the binding's real failure.
                val engine =
                    object : MarmotInterface by boundary {
                        override suspend fun sendCustomEventWithClientToken(
                            accountRef: String,
                            groupIdHex: String,
                            kind: ULong,
                            tags: List<List<String>>,
                            content: String,
                            clientToken: String,
                        ): LocalSendAcceptanceFfi {
                            admissions += 1
                            throw MarmotKitException.Runtime("transport closed after durable admission")
                        }
                    }

                val admission = engine.admitAgentRequest("account", "group", target, event, "token")

                assertEquals(recorded.messageIdHex, admission.eventId)
                assertEquals(1, admissions)
            }
        }
}
