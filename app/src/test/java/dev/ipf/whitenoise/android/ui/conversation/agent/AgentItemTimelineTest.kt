package dev.ipf.whitenoise.android.ui.conversation.agent

import androidx.compose.runtime.mutableStateListOf
import dev.ipf.whitenoise.android.core.agent.AgentItem
import dev.ipf.whitenoise.android.core.agent.AgentItemBody
import dev.ipf.whitenoise.android.core.agent.AgentItemReader
import dev.ipf.whitenoise.android.core.agent.AgentItemState
import dev.ipf.whitenoise.android.core.agent.AgentQuestionAnswer
import dev.ipf.whitenoise.android.core.agent.AgentRequest
import dev.ipf.whitenoise.android.core.agent.AgentRequestEncoder
import dev.ipf.whitenoise.android.core.agent.AgentToolKind
import dev.ipf.whitenoise.android.state.TimelineMessage
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Reads real recorded sessions the way the transcript does: MDK's rows, folded under their turns. */
class AgentItemTimelineTest {
    @Test
    fun finishedCodexTurnReadsEveryItemAtItsLatestVersion() {
        val rows = AgentRecordingFixtures.load("codex_approval_allowed", prompt = "Run greet.py")

        val items = rows.mapNotNull(::read)
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
        assertEquals(rows.single { it.record.messageIdHex == approval.rowId }.record.sender, approval.agentPubkeyHex)
    }

    @Test
    fun openApprovalAndQuestionStayWaitingMidTurn() {
        val approval =
            AgentRecordingFixtures
                .load("codex_approval_allowed", events = 6)
                .mapNotNull(::read)
                .single { it.body is AgentItemBody.Approval }
        assertEquals(AgentItemLayout.ApprovalCard, agentItemLayout(approval))
        assertEquals(
            listOf("Allow once", "Allow, and don't ask again for this command", "Deny and stop", "Deny"),
            (approval.body as AgentItemBody.Approval).choices.map { it.label },
        )

        val question = question(events = 3)
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
        val question = question()
        val body = question.body as AgentItemBody.Question
        assertEquals(AgentItemLayout.Activity, agentItemLayout(question))
        assertEquals("Hello", agentAnswerSummary(body.questions, body.answers!!))
    }

    @Test
    fun rowsWithoutAReadableItemKeepTheGenericRow() {
        val row = AgentRecordingFixtures.load("claude_reasoning").first { it.projected?.agentItem != null }
        val retiredShape = row.copy(projected = row.projected?.copy(agentItem = null))
        assertNull(AgentItemReader.read(retiredShape.record, retiredShape.projected))

        val malformedItem = row.projected?.agentItem?.copy(fieldsJson = "{")
        val malformed = row.copy(projected = row.projected?.copy(agentItem = malformedItem))
        assertNull(AgentItemReader.read(malformed.record, malformed.projected))

        val deleted = row.copy(projected = row.projected?.copy(deleted = true))
        assertNull(AgentItemReader.read(deleted.record, deleted.projected))
    }

    @Test
    fun routineStepsFoldUnderTheirTurnAndAttentionStaysInView() {
        val rows = AgentRecordingFixtures.load("codex_plan_mode", prompt = "Plan it")
        val turnRow = rows.single { read(it)?.body is AgentItemBody.Turn }.record.messageIdHex
        val folded = agentTurnStepRows(rows)

        val foldedTypes = rows.filter { it.record.messageIdHex in folded }.map { read(it)!!.body.typeName() }
        assertEquals(listOf("tool", "tool", "plan"), foldedTypes)
        assertTrue(folded.values.all { it == turnRow })
        // The answered question, the session and the agent's words stay in the transcript.
        assertTrue(rows.filter { read(it)?.body is AgentItemBody.Question }.none { it.record.messageIdHex in folded })
        assertTrue(rows.filter { it.projected?.agentItem == null }.none { it.record.messageIdHex in folded })
    }

    @Test
    fun failedStepsAndStepsWithoutALoadedTurnStayInView() {
        val failing = AgentRecordingFixtures.load("claude_failing_command")
        assertTrue(agentTurnStepRows(failing).isEmpty())

        val withoutTurn =
            AgentRecordingFixtures.load("claude_file_edit").filterNot { read(it)?.body is AgentItemBody.Turn }
        assertTrue(agentTurnStepRows(withoutTurn).isEmpty())
    }

    @Test
    fun anotherSendersStepsNeverFoldUnderAnAgentsTurn() {
        val rows = AgentRecordingFixtures.load("claude_file_edit")
        val impostor =
            rows.map { row ->
                val tool = read(row)?.body is AgentItemBody.Tool
                if (tool) row.copy(record = row.record.copy(sender = "33".repeat(32))) else row
            }
        assertTrue(agentTurnStepRows(impostor).isEmpty())
    }

    @Test
    fun aClosedTurnHidesItsStepsAndItsNeighboursAbsorbTheirSpacing() {
        // Rows: the session, the turn, three tool steps and the agent's reply.
        val rows = AgentRecordingFixtures.load("claude_file_edit")
        val ids = rows.map { it.record.messageIdHex }
        val turnRow = rows.single { read(it)?.body is AgentItemBody.Turn }
        val reply = rows.last { it.projected?.agentItem == null }
        val steps = AgentTurnSteps(agentTurnStepRows(rows), mutableStateListOf(), rows)
        val turnId = turnRow.record.messageIdHex

        assertEquals(3, steps.stepCount(turnId))
        assertEquals(3, ids.count(steps::isFolded))
        // Three folded rows leave three spacings: the turn draws over two, the reply over one.
        assertEquals(FoldedSpacing(olderRows = 0, newerRows = 2), steps.foldedSpacing(turnId))
        assertEquals(FoldedSpacing(olderRows = 1, newerRows = 0), steps.foldedSpacing(reply.record.messageIdHex))
        // The reply's sender run continues from the turn, past the folded steps.
        val olderOfReply = rows[ids.indexOf(reply.record.messageIdHex) - 1]
        assertEquals(turnRow, steps.shownOlder(reply, olderOfReply))
        assertEquals(reply, steps.shownNewer(turnRow, rows[ids.indexOf(turnId) + 1]))

        steps.toggle(turnId)
        assertTrue(steps.isOpen(turnId))
        assertEquals(FoldedSpacing.None, steps.foldedSpacing(turnId))
        assertTrue(ids.none(steps::isFolded))

        steps.toggle(turnId)
        assertFalse(steps.isOpen(turnId))
    }

    @Test
    fun foldedStepsAtTheNewestEndAreAbsorbedByTheTurnAlone() {
        // Mid-turn: session, turn and a running read, nothing after it yet.
        val rows = AgentRecordingFixtures.load("claude_file_edit", events = 3)
        val turnId = rows.single { read(it)?.body is AgentItemBody.Turn }.record.messageIdHex
        val steps = AgentTurnSteps(agentTurnStepRows(rows), mutableStateListOf(), rows)

        assertEquals(1, steps.stepCount(turnId))
        assertEquals(FoldedSpacing(olderRows = 0, newerRows = 1), steps.foldedSpacing(turnId))
    }

    @Test
    fun requestsEncodeTheShapeMdkValidates() {
        val choice =
            AgentRequestEncoder.encode("ab".repeat(32), "cd".repeat(32), AgentRequest.Choice("c0", "Allow once"))
        assertEquals(1202uL, choice.kind)
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

        val stop = AgentRequestEncoder.encode("ab".repeat(32), "ef".repeat(32), AgentRequest.Interrupt("Stop"))
        assertEquals("interrupt", JSONObject(stop.tags.single { it[0] == "details" }[1]).getString("action"))
        assertEquals(listOf("e", "ef".repeat(32)), stop.tags.single { it[0] == "e" })
        // MDK refuses a request with more than one of p, e or details.
        listOf(choice, answer, stop).forEach { event ->
            assertEquals(listOf("details", "e", "p"), event.tags.map { it[0] }.sorted())
        }
    }

    @Test
    fun questionAnswersNeedEveryQuestionAnswered() {
        val question = question(events = 3).body as AgentItemBody.Question
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
        // A wrapper is removed only when the whole command is its single quoted argument.
        val compound = "/bin/sh -c 'printf ok'; printf 'extra'"
        assertEquals(compound, agentDisplayCommand(compound))
        val prefixed = "true;/bin/sh -c 'echo ok'"
        assertEquals(prefixed, agentDisplayCommand(prefixed))
        assertEquals("/bin/bash -lc \"echo \$HOME\"", agentDisplayCommand("/bin/bash -lc \"echo \$HOME\""))
        assertEquals("say \"hi\"", agentDisplayCommand("/bin/zsh -lc \"say \\\"hi\\\"\""))
        assertEquals(AgentDuration(0, 1, 12), agentDuration(72_400))
        assertEquals(AgentDuration(0, 0, 9), agentDuration(9_341))
        assertEquals("Add farewell(name)\n• Add a test", agentPlainPlanText("## Add `farewell(name)`\n- Add a test"))
        assertEquals(AgentDiffLine.Added, agentDiffLine("+  return x"))
        assertEquals(AgentDiffLine.Context, agentDiffLine("+++ b/greet.py"))
        assertEquals(AgentDiffLine.Removed, agentDiffLine("-  return y"))
    }

    private fun read(row: TimelineMessage): AgentItem? = AgentItemReader.read(row.record, row.projected)

    private fun question(events: Int = Int.MAX_VALUE): AgentItem =
        AgentRecordingFixtures
            .load("claude_question", events = events)
            .mapNotNull(::read)
            .single { it.body is AgentItemBody.Question }

    private fun AgentItemBody.typeName(): String = javaClass.simpleName.lowercase()
}
