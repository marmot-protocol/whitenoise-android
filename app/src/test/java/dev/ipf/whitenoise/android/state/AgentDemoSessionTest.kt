package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.AppMessageRecordFfi
import dev.ipf.whitenoise.android.core.EMPTY_MARKDOWN_DOCUMENT
import dev.ipf.whitenoise.android.core.agent.AgentItem
import dev.ipf.whitenoise.android.core.agent.AgentItemBody
import dev.ipf.whitenoise.android.core.agent.AgentItemProjector
import dev.ipf.whitenoise.android.core.agent.AgentItemState
import dev.ipf.whitenoise.android.core.agent.AgentQuestionAnswer
import dev.ipf.whitenoise.android.core.agent.AgentRequest
import dev.ipf.whitenoise.android.core.agent.AgentToolKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The on-device agent demo writes carrier-shaped records and waits for the viewer's real answers. */
@OptIn(ExperimentalCoroutinesApi::class)
class AgentDemoSessionTest {
    @Test
    fun answeredTurnFinishesWithEveryItemSettled() =
        demoTest { session ->
            session.start()
            advanceUntilIdle()
            val question = latest(session).single { it.body is AgentItemBody.Question }
            assertEquals(AgentItemState.Waiting, question.state)
            assertFalse("an answer for another item is refused", session.receive("ff".repeat(32), bye()))

            assertTrue(session.receive(question.firstEventId, bye()))
            advanceUntilIdle()
            val approval = latest(session).single { it.body is AgentItemBody.Approval }
            assertEquals(AgentItemState.Waiting, approval.state)
            assertTrue(session.receive(approval.firstEventId, AgentRequest.Choice("c0", "Allow once")))
            advanceUntilIdle()

            assertEquals(AgentDemoPhase.Finished, session.phase)
            val items = latest(session)
            assertTrue(items.filterNot { it.body is AgentItemBody.Session }.none { it.state.isOpen })
            val run = items.single { (it.body as? AgentItemBody.Tool)?.command?.contains("greet.py") == true }
            assertEquals("Hello, Ada!\nBye, Ada!\n", (run.body as AgentItemBody.Tool).output)
            val turn = items.single { it.body is AgentItemBody.Turn }
            assertEquals(AgentItemState.Done, turn.state)
            assertTrue((turn.body as AgentItemBody.Turn).durationMs!! > 0)
            val reply = session.records.last { it.kind == CHAT_KIND && it.sender == AgentDemoRecords.AGENT_ID }
            assertTrue(reply.plaintext.contains("Bye, Ada!"))
            assertTrue(reply.tags.any { it.values.firstOrNull() == "turn" })
        }

    @Test
    fun laterVersionsNameTheFirstEventAndFoldIntoOneRow() =
        demoTest { session ->
            session.start()
            advanceUntilIdle()
            val read = session.records.filter { record -> toolPath(record) == "greet.py" }
            assertEquals(listOf("1", "2"), read.map { tag(it, "v") })
            assertEquals(read.first().messageIdHex, tag(read.last(), "item"))
            val rows = conversationRenderedTimeline(timeline(session.records))
            assertEquals(1, rows.count { toolPath(it.record) == "greet.py" })
            assertEquals(rows.size, session.records.count { !takesNoTranscriptRow(it) })
        }

    @Test
    fun stopClosesWhatIsOpenAndEndsTheTurn() =
        demoTest { session ->
            session.start()
            advanceUntilIdle()
            val question = latest(session).single { it.body is AgentItemBody.Question }
            session.receive(question.firstEventId, bye())
            advanceUntilIdle()

            assertTrue(session.receive(null, AgentRequest.Interrupt("Stop")))
            advanceUntilIdle()

            assertEquals(AgentDemoPhase.Stopped, session.phase)
            val items = latest(session)
            assertEquals(AgentItemState.Cancelled, items.single { it.body is AgentItemBody.Approval }.state)
            assertEquals(AgentItemState.Cancelled, items.single { it.body is AgentItemBody.Turn }.state)
            assertTrue(items.filterNot { it.body is AgentItemBody.Session }.none { it.state.isOpen })
            assertFalse("a second Stop has nothing to stop", session.receive(null, AgentRequest.Interrupt("Stop")))
        }

    @Test
    fun restartBeginsAFreshTurn() =
        demoTest { session ->
            session.start()
            advanceUntilIdle()
            val first = session.records.first().messageIdHex
            session.start()
            advanceUntilIdle()
            assertEquals(AgentDemoPhase.Running, session.phase)
            assertTrue(session.records.none { it.messageIdHex == first })
            assertEquals(1, latest(session).count { it.body is AgentItemBody.Turn })
        }

    /**
     * Runs [block] with a session on the test scheduler. The session's scope is not the test's own, so
     * advanceUntilIdle plays the script until it waits for an answer, and a waiting script never holds
     * the test open.
     */
    private fun demoTest(block: suspend TestScope.(AgentDemoSession) -> Unit) =
        runTest {
            val scope = CoroutineScope(StandardTestDispatcher(testScheduler))
            val session =
                AgentDemoSession(
                    scope = scope,
                    viewerId = VIEWER_ID,
                    parseMarkdown = { EMPTY_MARKDOWN_DOCUMENT },
                    stepMs = STEP_MS,
                    clockMs = { testScheduler.currentTime },
                )
            try {
                block(session)
            } finally {
                scope.cancel()
            }
        }

    private fun bye() = AgentRequest.Questions(listOf(AgentQuestionAnswer("farewell_word", listOf("o1"), null)), "Bye")

    private fun latest(session: AgentDemoSession): List<AgentItem> =
        conversationRenderedTimeline(timeline(session.records)).mapNotNull { AgentItemProjector.project(it.record) }

    private fun timeline(records: List<AppMessageRecordFfi>) = records.map(::received)

    private fun received(record: AppMessageRecordFfi): TimelineMessage {
        val id = "msg:${record.messageIdHex}"
        return TimelineMessage(id, record, MessageStatus.Received)
    }

    /** The path of a read step, or null for any other record. */
    private fun toolPath(record: AppMessageRecordFfi): String? =
        (AgentItemProjector.project(record)?.body as? AgentItemBody.Tool)
            ?.takeIf { it.kind == AgentToolKind.Read }
            ?.path

    private fun tag(
        record: AppMessageRecordFfi,
        name: String,
    ): String? =
        record.tags
            .firstOrNull { it.values.firstOrNull() == name }
            ?.values
            ?.getOrNull(1)

    private companion object {
        val VIEWER_ID = "11".repeat(32)
        const val STEP_MS = 1_000L
        const val CHAT_KIND = 9uL
    }
}
