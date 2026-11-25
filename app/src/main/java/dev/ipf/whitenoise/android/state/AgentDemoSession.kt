package dev.ipf.whitenoise.android.state

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.ipf.marmotkit.AppMessageRecordFfi
import dev.ipf.marmotkit.MarkdownDocumentFfi
import dev.ipf.whitenoise.android.core.agent.AgentRequest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean

internal enum class AgentDemoPhase { Idle, Running, Finished, Stopped }

/**
 * A scripted agent turn that runs on the device, for trying the agent rows without a running agent.
 *
 * The script writes the same records a marmot-agents agent would send, and waits for the viewer's
 * real answers from the rows: an approval waits for a choice, a question for an answer, and Stop
 * ends the turn and closes whatever was still open. Nothing is sent to a relay.
 */
@Stable
internal class AgentDemoSession(
    private val scope: CoroutineScope,
    private val viewerId: String,
    private val parseMarkdown: suspend (String) -> MarkdownDocumentFfi,
    private val stepMs: Long = STEP_MS,
    private val clockMs: () -> Long = System::currentTimeMillis,
) {
    private val rows = mutableStateListOf<AppMessageRecordFfi>()

    /** The demo chat's records, oldest first, in the order an agent would have sent them. */
    val records: List<AppMessageRecordFfi> get() = rows

    var phase by mutableStateOf(AgentDemoPhase.Idle)
        private set

    // Playback and Stop live in the session's own scope, so closing the session ends both.
    private val sessionScope = CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job]))
    private var job: Job? = null
    private var turnNumber = 0
    private val chatRecords = AgentDemoRecords(viewerId, clockMs)

    // Read by requests arriving from the mutation scope; written on the screen's scope.
    @Volatile private var stage: Stage? = null

    /** Starts the turn from the beginning, discarding an earlier run. */
    fun start() = startTurn(prompt = null, reset = true)

    fun sendPrompt(prompt: String) {
        if (phase != AgentDemoPhase.Running) startTurn(prompt, reset = false)
    }

    fun addMessage(
        sender: String,
        text: String,
    ) {
        sessionScope.launch { rows += chatRecords.chat(sender, text, parseMarkdown(text)) }
    }

    private fun startTurn(
        prompt: String?,
        reset: Boolean,
    ) {
        job?.cancel()
        if (reset) rows.clear()
        turnNumber += 1
        val next = Stage(AgentDemoRecords(viewerId, clockMs), turnNumber, prompt)
        stage = next
        phase = AgentDemoPhase.Running
        job =
            sessionScope.launch {
                next.playAgentDemo()
                // The script claimed its end before writing it; an accepted Stop settles the turn itself.
                if (stage === next && next.finished) phase = AgentDemoPhase.Finished
            }
    }

    /** Ends playback and any pending Stop; the session takes no more requests. */
    fun close() {
        stage = null
        sessionScope.cancel()
    }

    /**
     * Takes a request from a row as the agent would. The first Stop of a running turn is accepted;
     * an answer is accepted only for the item the script is waiting on. Returns false for a request
     * the demo cannot use.
     */
    fun receive(
        targetFirstEventId: String?,
        request: AgentRequest,
    ): Boolean {
        val current = stage
        return when {
            current == null -> false
            request !is AgentRequest.Interrupt -> current.answer(targetFirstEventId, request)
            !current.settled.compareAndSet(false, true) -> false
            else -> {
                sessionScope.launch { stop(current) }
                true
            }
        }
    }

    /** Playback stops at once; the cancelled versions follow after an agent's usual delay. */
    private suspend fun stop(current: Stage) {
        if (stage !== current) return
        job?.cancelAndJoin()
        delay(stepMs / 2)
        if (stage !== current) return
        current.closeOpenItems()
        phase = AgentDemoPhase.Stopped
    }

    private inner class Stage(
        private val records: AgentDemoRecords,
        number: Int,
        private val userPrompt: String?,
    ) : AgentDemoStage {
        override val turn = "${AgentDemoRecords.SESSION}:turn-$number"

        /** Claimed once, by the script finishing or by an accepted Stop. */
        val settled = AtomicBoolean(false)
        var finished = false
            private set

        private val open = LinkedHashMap<String, AgentDemoItem>()

        @Volatile private var waiting: Pair<String, CompletableDeferred<AgentRequest>>? = null
        private var turnStartedAtMs = 0L

        override suspend fun pause(steps: Int) = delay(stepMs * steps)

        override fun emit(item: AgentDemoItem) {
            if (item.type == TURN_TYPE && item.itemId !in open) turnStartedAtMs = clockMs()
            rows += records.item(item)
            // A session outlives its turns, so Stop never closes it.
            val stays = item.type != SESSION_TYPE && (item.state == "running" || item.state == "waiting")
            if (stays) open[item.itemId] = item else open -= item.itemId
        }

        override suspend fun prompt(text: String): String {
            val message = userPrompt ?: text
            val record = records.chat(viewerId, message, parseMarkdown(message))
            rows += record
            return record.messageIdHex
        }

        override suspend fun say(text: String) {
            rows += records.chat(AgentDemoRecords.AGENT_ID, text, parseMarkdown(text), turn)
        }

        override suspend fun awaitRequest(itemId: String): AgentRequest {
            val answer = CompletableDeferred<AgentRequest>()
            waiting = itemId to answer
            return try {
                answer.await().also { pause() }
            } finally {
                waiting = null
            }
        }

        override fun turnDurationMs(): Long = clockMs() - turnStartedAtMs

        override fun claimFinish(): Boolean = settled.compareAndSet(false, true).also { finished = it }

        fun answer(
            targetFirstEventId: String?,
            request: AgentRequest,
        ): Boolean {
            val (itemId, answer) = waiting ?: return false
            val target = records.firstEventId(itemId)
            return target != null && target == targetFirstEventId && answer.complete(request)
        }

        /**
         * What an agent does on Stop: every open item ends as cancelled, the turn with its duration,
         * and a plan step that was under way goes back to waiting.
         */
        fun closeOpenItems() {
            open.values.toList().forEach { item ->
                val body = JSONObject(item.body.toString())
                if (item.type == TURN_TYPE) body.put("duration_ms", turnDurationMs())
                body.optJSONArray("entries")?.let { entries ->
                    for (index in 0 until entries.length()) {
                        val entry = entries.getJSONObject(index)
                        if (entry.optString("state") == "running") entry.put("state", "pending")
                    }
                }
                emit(item.copy(state = "cancelled", body = body))
            }
        }
    }

    private companion object {
        const val STEP_MS = 1_400L
        const val TURN_TYPE = "turn"
        const val SESSION_TYPE = "session"
    }
}
