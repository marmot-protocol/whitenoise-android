package dev.ipf.whitenoise.android.core.agent

import org.json.JSONArray
import org.json.JSONObject

/** Parses the `fields` tag of an agent item: `{"type": ..., "body": {...}}`. Unknown fields are ignored. */
internal object AgentItemBodyParser {
    fun parse(fields: JSONObject): AgentItemBody {
        val type = fields.optString("type")
        val body = fields.optJSONObject("body") ?: JSONObject()
        return when (type) {
            "turn" -> turn(body)
            "reasoning" -> AgentItemBody.Reasoning
            "tool" -> tool(body)
            "approval" -> approval(body)
            "question" -> question(body)
            "plan" -> plan(body)
            "task" -> AgentItemBody.Task(kind = body.optString("kind"), description = body.stringOrNull("description"))
            "notice" ->
                AgentItemBody.Notice(
                    kind = AgentNoticeKind.parse(body.stringOrNull("kind")),
                    durationMs = body.longOrNull("duration_ms"),
                    reason = body.stringOrNull("reason"),
                )
            "error" ->
                AgentItemBody.Error(
                    request = body.stringOrNull("request"),
                    code = body.longOrNull("code"),
                    willRetry = if (body.has("will_retry")) body.optBoolean("will_retry") else null,
                )
            "session" -> session(body)
            else -> AgentItemBody.Unknown(type)
        }
    }

    private fun turn(body: JSONObject): AgentItemBody.Turn {
        val usage = body.optJSONObject("usage")
        return AgentItemBody.Turn(
            startedBy = body.stringOrNull("started_by"),
            durationMs = body.longOrNull("duration_ms"),
            inputTokens = usage?.longOrNull("input_tokens"),
            outputTokens = usage?.longOrNull("output_tokens"),
        )
    }

    private fun tool(body: JSONObject): AgentItemBody.Tool =
        AgentItemBody.Tool(
            kind = AgentToolKind.parse(body.stringOrNull("kind")),
            name = body.optString("name"),
            command = body.stringOrNull("command"),
            cwd = body.stringOrNull("cwd"),
            exitCode = body.longOrNull("exit_code"),
            durationMs = body.longOrNull("duration_ms"),
            output = body.stringOrNull("output"),
            changes =
                body.optJSONArray("changes").objects().map { change ->
                    AgentFileChange(
                        path = change.optString("path"),
                        diff = change.optString("diff"),
                        kind = change.stringOrNull("change"),
                    )
                },
            query = body.stringOrNull("query"),
            path = body.stringOrNull("path"),
            inputJson = body.opt("input")?.takeUnless { it == JSONObject.NULL }?.toString(),
            error = body.stringOrNull("error"),
        )

    private fun approval(body: JSONObject): AgentItemBody.Approval =
        AgentItemBody.Approval(
            subjectItemId = body.stringOrNull("subject"),
            choices =
                body.optJSONArray("choices").objects().mapNotNull { choice ->
                    val id = choice.stringOrNull("id") ?: return@mapNotNull null
                    AgentApprovalChoice(
                        id = id,
                        label = choice.stringOrNull("label") ?: id,
                        allows = choice.optString("effect") == "allow",
                    )
                },
            answeredChoiceId = body.optJSONObject("answer")?.stringOrNull("choice"),
        )

    private fun question(body: JSONObject): AgentItemBody.Question =
        AgentItemBody.Question(
            answerViaMessage = body.optString("answer_via") == "message",
            questions =
                body.optJSONArray("questions").objects().mapNotNull { asked ->
                    AgentQuestion(
                        id = asked.stringOrNull("id") ?: return@mapNotNull null,
                        header = asked.stringOrNull("header"),
                        text = asked.optString("text"),
                        options =
                            asked.optJSONArray("options").objects().mapNotNull { option ->
                                val id = option.stringOrNull("id") ?: return@mapNotNull null
                                AgentQuestionOption(
                                    id = id,
                                    label = option.stringOrNull("label") ?: id,
                                    description = option.stringOrNull("description"),
                                )
                            },
                        multiple = asked.optBoolean("multiple"),
                        allowText = asked.optBoolean("allow_text"),
                        secret = asked.optBoolean("secret"),
                    )
                },
            answers =
                body.optJSONArray("answer")?.objects()?.map { answer ->
                    AgentQuestionAnswer(
                        questionId = answer.optString("question"),
                        optionIds = answer.optJSONArray("options").strings(),
                        text = answer.stringOrNull("text"),
                    )
                },
        )

    private fun plan(body: JSONObject): AgentItemBody.Plan =
        AgentItemBody.Plan(
            text = body.stringOrNull("text"),
            entries =
                body.optJSONArray("entries").objects().map { entry ->
                    AgentPlanEntry(
                        text = entry.optString("text"),
                        state =
                            when (entry.optString("state")) {
                                "running" -> AgentPlanEntryState.Running
                                "done" -> AgentPlanEntryState.Done
                                else -> AgentPlanEntryState.Pending
                            },
                    )
                },
        )

    private fun session(body: JSONObject): AgentItemBody.Session =
        AgentItemBody.Session(
            harnessVersion = body.stringOrNull("harness_version"),
            settings =
                body.optJSONArray("settings").objects().mapNotNull { setting ->
                    AgentSessionSetting(
                        id = setting.stringOrNull("id") ?: return@mapNotNull null,
                        label = setting.optString("label"),
                        value = setting.stringOrNull("value"),
                    )
                },
            commands =
                body.optJSONArray("commands").objects().mapNotNull { command ->
                    AgentSessionCommand(
                        name = command.stringOrNull("name") ?: return@mapNotNull null,
                        description = command.stringOrNull("description"),
                    )
                },
        )

    private fun JSONArray?.objects(): List<JSONObject> {
        val array = this ?: return emptyList()
        return (0 until array.length()).mapNotNull { array.optJSONObject(it) }
    }

    private fun JSONArray?.strings(): List<String> {
        val array = this ?: return emptyList()
        return (0 until array.length()).mapNotNull { index -> array.optString(index).takeIf(String::isNotEmpty) }
    }

    private fun JSONObject.stringOrNull(name: String): String? {
        val present = has(name) && !isNull(name)
        return optString(name).takeIf { present && it.isNotEmpty() }
    }

    private fun JSONObject.longOrNull(name: String): Long? {
        val present = has(name) && !isNull(name)
        return (opt(name) as? Number)?.toLong()?.takeIf { present }
    }
}
