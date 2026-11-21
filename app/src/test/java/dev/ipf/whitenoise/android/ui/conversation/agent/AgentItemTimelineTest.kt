package dev.ipf.whitenoise.android.ui.conversation.agent

import dev.ipf.marmotkit.MessageTagFfi
import dev.ipf.whitenoise.android.core.agent.AgentItemBody
import dev.ipf.whitenoise.android.core.agent.AgentItemProjector
import dev.ipf.whitenoise.android.core.agent.AgentItemState
import dev.ipf.whitenoise.android.core.agent.AgentQuestionAnswer
import dev.ipf.whitenoise.android.core.agent.AgentRequest
import dev.ipf.whitenoise.android.core.agent.AgentRequestEncoder
import dev.ipf.whitenoise.android.core.agent.AgentToolKind
import dev.ipf.whitenoise.android.state.conversationRenderedTimeline
import dev.ipf.whitenoise.android.state.takesNoTranscriptRow
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Reads real recorded sessions the way the transcript does: one row per item, at its latest version. */
class AgentItemTimelineTest {
    @Test
    fun finishedCodexTurnShowsOneRowPerItemAtItsLatestVersion() {
        val records = AgentRecordingFixtures.load("codex_approval_allowed", prompt = "Run greet.py")
        val rows = conversationRenderedTimeline(AgentRecordingFixtures.timeline(records))

        val items = rows.mapNotNull { AgentItemProjector.project(it.record) }
        assertEquals(listOf("session", "turn", "tool", "approval"), items.map { it.body.typeName() })
        val turn = items.first { it.body is AgentItemBody.Turn }
        assertEquals(AgentItemState.Done, turn.state)
        assertEquals(9341L, (turn.body as AgentItemBody.Turn).durationMs)
        val tool = items.first { it.body is AgentItemBody.Tool }.body as AgentItemBody.Tool
        assertEquals(AgentToolKind.Command, tool.kind)
        assertEquals(0L, tool.exitCode)
        assertEquals("Hello, world\n", tool.output)
        val approval = items.first { it.body is AgentItemBody.Approval }
        assertEquals(AgentItemState.Done, approval.state)
        assertEquals("c0", (approval.body as AgentItemBody.Approval).answeredChoiceId)
        // The prompt, two agent messages and four items (session, turn, tool, approval).
        assertEquals(7, rows.size)
        assertEquals(rows.size, records.count { !takesNoTranscriptRow(it) })
    }

    @Test
    fun laterVersionKeepsTheFirstEventsRowIdentity() {
        val records = AgentRecordingFixtures.load("claude_failing_command")
        val firstToolEvent = records.first { AgentItemProjector.project(it)?.body is AgentItemBody.Tool }
        val rows = conversationRenderedTimeline(AgentRecordingFixtures.timeline(records))

        val toolRow = rows.single { AgentItemProjector.project(it.record)?.body is AgentItemBody.Tool }
        assertEquals(firstToolEvent.messageIdHex, toolRow.record.messageIdHex)
        val tool = AgentItemProjector.project(toolRow.record)!!
        assertEquals(AgentItemState.Failed, tool.state)
        assertEquals(firstToolEvent.messageIdHex, tool.firstEventId)
    }

    @Test
    fun openApprovalAndQuestionStayWaitingMidTurn() {
        val approval =
            rendered("codex_approval_allowed", events = 6)
                .mapNotNull { AgentItemProjector.project(it.record) }
                .single { it.body is AgentItemBody.Approval }
        assertEquals(AgentItemLayout.ApprovalCard, agentItemLayout(approval))
        assertEquals(
            listOf("Allow once", "Allow, and don't ask again for this command", "Deny and stop", "Deny"),
            (approval.body as AgentItemBody.Approval).choices.map { it.label },
        )

        val question =
            rendered("claude_question", events = 3)
                .mapNotNull { AgentItemProjector.project(it.record) }
                .single { it.body is AgentItemBody.Question }
        assertEquals(AgentItemLayout.QuestionCard, agentItemLayout(question))
        val body = question.body as AgentItemBody.Question
        assertEquals(
            listOf("Hello", "Hi"),
            body.questions
                .single()
                .options
                .map { it.label },
        )
        assertTrue(body.questions.single().allowText)
    }

    @Test
    fun answeredQuestionSummarisesTheChosenOption() {
        val question =
            rendered("claude_question")
                .mapNotNull { AgentItemProjector.project(it.record) }
                .single { it.body is AgentItemBody.Question }
        val body = question.body as AgentItemBody.Question
        assertEquals(AgentItemLayout.Activity, agentItemLayout(question))
        assertEquals("Hello", agentAnswerSummary(body.questions, body.answers!!))
    }

    @Test
    fun requestsAndOrphanVersionsTakeNoRow() {
        val request =
            record("aa".repeat(32), AgentItemProjector.REQUEST_KIND, listOf(listOf("p", "x"), listOf("details", "{}")))
        val orphan =
            record(
                "bb".repeat(32),
                AgentItemProjector.ITEM_KIND,
                listOf(listOf("v", "2"), listOf("item", "cc".repeat(32)), listOf("state", "done")),
            )
        assertTrue(takesNoTranscriptRow(request))
        assertTrue(takesNoTranscriptRow(orphan))
        assertTrue(conversationRenderedTimeline(AgentRecordingFixtures.timeline(listOf(request, orphan))).isEmpty())
    }

    @Test
    fun sessionItemIsRecognisedWhateverTheFieldOrder() {
        val reordered =
            record(
                "ee".repeat(32),
                AgentItemProjector.ITEM_KIND,
                listOf(
                    listOf("v", "1"),
                    listOf("state", "running"),
                    listOf("item_id", "session:1"),
                    listOf("fields", "{\"body\":{\"settings\":[]},\"type\":\"session\"}"),
                ),
            )
        assertTrue(AgentItemProjector.isSessionItem(reordered))
        assertTrue(AgentItemProjector.project(reordered)!!.body is AgentItemBody.Session)
    }

    @Test
    fun oldAgentActivityShapeIsNotAnAgentItem() {
        val legacy = record("dd".repeat(32), 1201uL, listOf(listOf("status", "working")))
        assertFalse(AgentItemProjector.isAgentItem(legacy))
        assertNull(AgentItemProjector.project(legacy))
    }

    @Test
    fun requestsEncodeTheCarrierShape() {
        val choice =
            AgentRequestEncoder.encode("ab".repeat(32), "cd".repeat(32), AgentRequest.Choice("c0", "Allow once"))
        assertEquals(AgentItemProjector.REQUEST_KIND, choice.kind)
        assertEquals("Allow once", choice.content)
        assertEquals(listOf("p", "ab".repeat(32)), choice.tags[0])
        assertEquals(listOf("e", "cd".repeat(32)), choice.tags.last())
        val details = JSONObject(choice.tags.single { it[0] == "details" }[1])
        assertEquals("choice", details.getString("action"))
        assertEquals("c0", details.getString("choice"))

        val answer =
            AgentRequestEncoder.encode(
                "ab".repeat(32),
                "cd".repeat(32),
                AgentRequest.Questions(listOf(AgentQuestionAnswer("q0", listOf("o1"), "Hi there")), "Hi"),
            )
        val asked = JSONObject(answer.tags.single { it[0] == "details" }[1]).getJSONArray("questions").getJSONObject(0)
        assertEquals("q0", asked.getString("question"))
        assertEquals("o1", asked.getJSONArray("options").getString(0))
        assertEquals("Hi there", asked.getString("text"))

        val stop = AgentRequestEncoder.encode("ab".repeat(32), null, AgentRequest.Interrupt("Stop"))
        assertEquals("interrupt", JSONObject(stop.tags.single { it[0] == "details" }[1]).getString("action"))
        assertTrue(stop.tags.none { it[0] == "e" })
    }

    @Test
    fun questionAnswersNeedEveryQuestionAnswered() {
        val question =
            AgentItemProjector
                .project(AgentRecordingFixtures.load("claude_question", events = 3).last())!!
                .body as AgentItemBody.Question
        val asked = question.questions.single()
        assertNull(agentQuestionAnswers(question.questions, emptyMap(), emptyMap()))
        assertNull(agentQuestionAnswers(question.questions, emptyMap(), mapOf(asked.id to "   ")))
        assertEquals(
            listOf(AgentQuestionAnswer(asked.id, listOf("o1"), null)),
            agentQuestionAnswers(question.questions, mapOf(asked.id to setOf("o1")), emptyMap()),
        )
        assertEquals(
            listOf(AgentQuestionAnswer(asked.id, emptyList(), "Hey")),
            agentQuestionAnswers(question.questions, emptyMap(), mapOf(asked.id to " Hey ")),
        )
    }

    @Test
    fun presentationHelpersReadLikeAPerson() {
        assertEquals("python3 greet.py", agentDisplayCommand("/usr/bin/zsh -lc 'python3 greet.py'"))
        assertEquals("echo 'hi'", agentDisplayCommand("/bin/bash -lc 'echo '\\''hi'\\'''"))
        assertEquals("git status", agentDisplayCommand("git status"))
        assertEquals(AgentDuration(0, 1, 12), agentDuration(72_400))
        assertEquals(AgentDuration(0, 0, 9), agentDuration(9_341))
        assertEquals("Add farewell(name)\n• Add a test", agentPlainPlanText("## Add `farewell(name)`\n- Add a test"))
        assertEquals(AgentDiffLine.Added, agentDiffLine("+  return x"))
        assertEquals(AgentDiffLine.Context, agentDiffLine("+++ b/greet.py"))
        assertEquals(AgentDiffLine.Removed, agentDiffLine("-  return y"))
    }

    private fun rendered(
        recording: String,
        events: Int = Int.MAX_VALUE,
    ) = conversationRenderedTimeline(
        AgentRecordingFixtures.timeline(AgentRecordingFixtures.load(recording, events = events)),
    )

    private fun AgentItemBody.typeName(): String = javaClass.simpleName.lowercase()

    private fun record(
        id: String,
        kind: ULong,
        tags: List<List<String>>,
    ) = AgentRecordingFixtures.load("claude_reasoning", events = 1).first().copy(
        messageIdHex = id,
        kind = kind,
        tags = tags.map { MessageTagFfi(it) },
    )
}
