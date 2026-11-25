package dev.ipf.whitenoise.android.core.agent

import org.json.JSONArray
import org.json.JSONObject

/**
 * An answer or a control a person sends to one agent (marmot-agents D-027, kind 1202 shape).
 *
 * [words] is the plain rendering a client that knows nothing about agents shows.
 */
sealed interface AgentRequest {
    val words: String

    /** Answers an approval with one of the choices it offered. */
    data class Choice(
        val choiceId: String,
        override val words: String,
    ) : AgentRequest

    /** Answers a question item, one entry per question. */
    data class Questions(
        val answers: List<AgentQuestionAnswer>,
        override val words: String,
    ) : AgentRequest

    /** Stops the agent's running turn in this chat. */
    data class Interrupt(
        override val words: String,
    ) : AgentRequest
}

/** One event ready for MDK to send. */
data class AgentRequestEvent(
    val kind: ULong,
    val content: String,
    val tags: List<List<String>>,
)

object AgentRequestEncoder {
    /**
     * Encodes [request] for the agent [agentPubkeyHex]. [targetFirstEventId] is the first event of the
     * item it answers; an interrupt names none and acts on the chat's running turn.
     */
    fun encode(
        agentPubkeyHex: String,
        targetFirstEventId: String?,
        request: AgentRequest,
    ): AgentRequestEvent {
        val tags =
            buildList {
                add(listOf("p", agentPubkeyHex))
                add(listOf("details", details(request).toString()))
                if (targetFirstEventId != null) add(listOf("e", targetFirstEventId))
            }
        return AgentRequestEvent(kind = AgentItemProjector.REQUEST_KIND, content = request.words, tags = tags)
    }

    private fun details(request: AgentRequest): JSONObject =
        when (request) {
            is AgentRequest.Choice -> JSONObject().put("action", "choice").put("choice", request.choiceId)
            is AgentRequest.Questions ->
                JSONObject().put("action", "questions").put(
                    "questions",
                    JSONArray(
                        request.answers.map { answer ->
                            JSONObject()
                                .put("question", answer.questionId)
                                .put("options", JSONArray(answer.optionIds))
                                .apply { answer.text?.let { put("text", it) } }
                        },
                    ),
                )
            is AgentRequest.Interrupt -> JSONObject().put("action", "interrupt")
        }
}
