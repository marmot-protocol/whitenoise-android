package dev.ipf.whitenoise.android.state

import dev.ipf.whitenoise.android.core.agent.AgentRequest
import dev.ipf.whitenoise.android.state.AgentDemoScriptContent.CWD
import dev.ipf.whitenoise.android.state.AgentDemoScriptContent.DENY
import dev.ipf.whitenoise.android.state.AgentDemoScriptContent.GOODBYE
import dev.ipf.whitenoise.android.state.AgentDemoScriptContent.GREET_PATH
import dev.ipf.whitenoise.android.state.AgentDemoScriptContent.GREET_PY
import dev.ipf.whitenoise.android.state.AgentDemoScriptContent.MODEL
import dev.ipf.whitenoise.android.state.AgentDemoScriptContent.NO_TESTS_EXIT
import dev.ipf.whitenoise.android.state.AgentDemoScriptContent.NO_TESTS_OUTPUT
import dev.ipf.whitenoise.android.state.AgentDemoScriptContent.NO_TESTS_REPLY
import dev.ipf.whitenoise.android.state.AgentDemoScriptContent.PLAN_STEPS
import dev.ipf.whitenoise.android.state.AgentDemoScriptContent.PROMPT
import dev.ipf.whitenoise.android.state.AgentDemoScriptContent.RUN_COMMAND
import dev.ipf.whitenoise.android.state.AgentDemoScriptContent.RUN_DURATION_MS
import dev.ipf.whitenoise.android.state.AgentDemoScriptContent.SESSION_ITEM
import dev.ipf.whitenoise.android.state.AgentDemoScriptContent.TEST_COMMAND
import dev.ipf.whitenoise.android.state.AgentDemoScriptContent.TEST_DURATION_MS
import dev.ipf.whitenoise.android.state.AgentDemoScriptContent.THINKING
import dev.ipf.whitenoise.android.state.AgentDemoScriptContent.WORD_OPTIONS
import dev.ipf.whitenoise.android.state.AgentDemoScriptContent.WORD_QUESTION
import dev.ipf.whitenoise.android.state.AgentDemoScriptContent.WORD_QUESTION_ID
import dev.ipf.whitenoise.android.state.AgentDemoScriptContent.approvalBody
import dev.ipf.whitenoise.android.state.AgentDemoScriptContent.farewellDiff
import dev.ipf.whitenoise.android.state.AgentDemoScriptContent.notRunReply
import dev.ipf.whitenoise.android.state.AgentDemoScriptContent.questionBody
import dev.ipf.whitenoise.android.state.AgentDemoScriptContent.ranReply
import dev.ipf.whitenoise.android.state.AgentDemoScriptContent.sessionBody
import org.json.JSONArray
import org.json.JSONObject

/** What the demo script can do: write items and messages, wait, and take the viewer's answers. */
internal interface AgentDemoStage {
    val turn: String

    suspend fun pause(steps: Int = 1)

    fun emit(item: AgentDemoItem)

    /** Adds the viewer's prompt and returns its event id. */
    suspend fun prompt(text: String): String

    /** Adds the agent's chat message for this turn. */
    suspend fun say(text: String)

    /** Waits until the viewer answers [itemId] from its row. */
    suspend fun awaitRequest(itemId: String): AgentRequest

    fun turnDurationMs(): Long

    /** Claims the end of the turn; false when an accepted Stop got there first. */
    fun claimFinish(): Boolean
}

/**
 * One turn of a coding agent: it reads a file, asks which word to use, edits the file, a test run
 * fails, it asks before running the script, then reports. Every kind of row appears once.
 */
internal suspend fun AgentDemoStage.playAgentDemo() {
    emit(AgentDemoItem(SESSION_ITEM, "session", "running", "Session", "Model: $MODEL", sessionBody()))
    pause()
    val promptId = prompt(PROMPT)
    pause()
    val turnItem = AgentDemoItem("turn:$turn", "turn", "running", "Working", turn = turn, startedByEventId = promptId)
    emit(turnItem.copy(body = JSONObject().put("started_by", "user")))
    readTheFile()
    val word = askForTheWord()
    editTheFile(word)
    runTheTests()
    val ran = askToRunTheScript(word)
    // A declined run leaves its step undone; the plan still ends with the turn.
    emit(plan(done = if (ran) PLAN_STEPS.size else PLAN_STEPS.lastIndex, settled = true))
    pause()
    say(if (ran) ranReply(word) else notRunReply(word))
    pause()
    // Stop may still win here; it then closes the turn as stopped.
    if (!claimFinish()) return
    val body = JSONObject().put("started_by", "user").put("duration_ms", turnDurationMs())
    emit(turnItem.copy(state = "done", title = "Finished", body = body))
}

private suspend fun AgentDemoStage.readTheFile() {
    val thinking = AgentDemoItem("reasoning:1", "reasoning", "running", "Thinking", THINKING, turn = turn)
    emit(thinking)
    pause()
    emit(thinking.copy(state = "done"))
    emit(plan(done = 0))
    val read = tool("tool:read", "read", "Reading a file", GREET_PATH, JSONObject().put("path", GREET_PATH))
    emit(read)
    pause()
    val readBody = JSONObject(read.body.toString()).put("output", GREET_PY)
    emit(read.copy(state = "done", title = "Read a file", body = readBody))
    emit(plan(done = 1))
}

private suspend fun AgentDemoStage.editTheFile(word: String) {
    val edit = tool("tool:edit", "edit", "Editing a file", GREET_PATH, JSONObject().put("path", GREET_PATH))
    emit(edit)
    pause(2)
    val change = JSONObject().put("path", GREET_PATH).put("change", "update").put("diff", farewellDiff(word))
    val body = JSONObject(edit.body.toString()).put("changes", JSONArray().put(change))
    emit(edit.copy(state = "done", title = "Edited a file", body = body))
}

private suspend fun AgentDemoStage.runTheTests() {
    emit(plan(done = 2))
    val tests = command("tool:tests", TEST_COMMAND)
    emit(tests)
    pause(2)
    val body = JSONObject(tests.body.toString()).put("exit_code", NO_TESTS_EXIT).put("duration_ms", TEST_DURATION_MS)
    emit(tests.copy(state = "failed", title = "Ran a command", body = body.put("output", NO_TESTS_OUTPUT)))
    pause()
    say(NO_TESTS_REPLY)
    pause()
}

/** Asks before running the script; returns whether the viewer allowed it. */
private suspend fun AgentDemoStage.askToRunTheScript(word: String): Boolean {
    emit(plan(done = 3))
    val run = command("tool:run", RUN_COMMAND)
    emit(run)
    val approval =
        AgentDemoItem("approval:run", "approval", "waiting", "Allow this command?", RUN_COMMAND, approvalBody(), turn)
    emit(approval)
    var choice: AgentRequest.Choice? = null
    while (choice == null) choice = awaitRequest(approval.itemId) as? AgentRequest.Choice
    val allowed = choice.choiceId != DENY
    val answered = approvalBody().put("answer", JSONObject().put("choice", choice.choiceId))
    val state = if (allowed) "done" else "declined"
    emit(approval.copy(state = state, text = "Answered: ${choice.words}", body = answered))
    if (allowed) {
        pause()
        val body = JSONObject(run.body.toString()).put("exit_code", 0).put("duration_ms", RUN_DURATION_MS)
        emit(run.copy(state = "done", title = "Ran a command", body = body.put("output", "Hello, Ada!\n$word, Ada!\n")))
    } else {
        emit(run.copy(state = "declined"))
    }
    return allowed
}

/** Asks which farewell word to use; returns the chosen or typed word. */
private suspend fun AgentDemoStage.askForTheWord(): String {
    val question =
        AgentDemoItem("question:word", "question", "waiting", "Question", WORD_QUESTION, questionBody(), turn)
    emit(question)
    var answer: AgentRequest.Questions? = null
    while (answer == null) answer = awaitRequest(question.itemId) as? AgentRequest.Questions
    val given = answer.answers.firstOrNull()
    val word =
        given?.text?.takeIf(String::isNotBlank)?.trim()
            ?: WORD_OPTIONS.firstOrNull { it.first in given?.optionIds.orEmpty() }?.second
            ?: GOODBYE
    val answered =
        JSONArray().put(
            JSONObject()
                .put("question", WORD_QUESTION_ID)
                .put("options", JSONArray(given?.optionIds.orEmpty()))
                .apply { given?.text?.let { put("text", it) } },
        )
    emit(question.copy(state = "done", text = "Answered", body = questionBody().put("answer", answered)))
    pause()
    return word
}

private fun AgentDemoStage.tool(
    itemId: String,
    kind: String,
    title: String,
    text: String,
    body: JSONObject,
) = AgentDemoItem(itemId, "tool", "running", title, text, body.put("kind", kind).put("name", kind), turn)

private fun AgentDemoStage.command(
    itemId: String,
    command: String,
) = tool(itemId, "command", "Running a command", command, JSONObject().put("command", command).put("cwd", CWD))

/** The plan with [done] steps finished; the next one is under way unless the plan has [settled]. */
private fun AgentDemoStage.plan(
    done: Int,
    settled: Boolean = false,
): AgentDemoItem {
    val entries = JSONArray()
    PLAN_STEPS.forEachIndexed { index, step ->
        val state =
            when {
                index < done -> "done"
                index == done && !settled -> "running"
                else -> "pending"
            }
        entries.put(JSONObject().put("text", step).put("state", state))
    }
    val state = if (settled) "done" else "running"
    return AgentDemoItem("plan:1", "plan", state, "Plan", body = JSONObject().put("entries", entries), turn = turn)
}
