package dev.ipf.whitenoise.android.core.agent

import androidx.compose.runtime.Immutable
import dev.ipf.marmotkit.AgentItemStateFfi

/**
 * One marmot-agents item at the latest version MDK holds: a turn, a tool call, an approval and so on.
 *
 * MDK keeps one timeline row per item at its first event and applies every later version there, so
 * [rowId] (the row's message id) stays the same while the agent updates the item. [body] holds the
 * type-specific `fields`; [text] is the item's plain rendering.
 */
@Immutable
data class AgentItem(
    val rowId: String,
    val agentPubkeyHex: String,
    val version: Long,
    val state: AgentItemState,
    val title: String,
    val text: String,
    val itemId: String,
    val session: String?,
    val turn: String?,
    val body: AgentItemBody,
    /** Full output length when the agent cut a command's output to fit the 64 KB limit. */
    val outputCutLength: Long? = null,
    /** How many file diffs the agent left out to fit the 64 KB limit. */
    val diffsCut: Int = 0,
    val firstEventMissing: Boolean = false,
    val recordedAt: ULong = 0uL,
    val receivedAt: ULong = 0uL,
)

/** An item's state, as MDK reports it. */
enum class AgentItemState {
    Running,
    Waiting,
    Done,
    Failed,
    Declined,
    Cancelled,
    ;

    /** True while the item can still change on its own: running, or waiting for a person. */
    val isOpen: Boolean get() = this == Running || this == Waiting

    companion object {
        /** Maps MDK's state onto the app's. */
        fun of(state: AgentItemStateFfi): AgentItemState =
            when (state) {
                AgentItemStateFfi.RUNNING -> Running
                AgentItemStateFfi.WAITING -> Waiting
                AgentItemStateFfi.DONE -> Done
                AgentItemStateFfi.FAILED -> Failed
                AgentItemStateFfi.DECLINED -> Declined
                AgentItemStateFfi.CANCELLED -> Cancelled
            }
    }
}

/** The type-specific part of an item, one case per marmot-agents item type. */
@Immutable
sealed interface AgentItemBody {
    /** A turn: the agent's work on one request, with how long it took once it ends. */
    data class Turn(
        val durationMs: Long?,
    ) : AgentItemBody

    /** The agent's visible thinking; the text is the item's plain rendering. */
    data object Reasoning : AgentItemBody

    /** A tool call: a command, an edit, a read, a search and so on. */
    data class Tool(
        val kind: AgentToolKind,
        val command: String? = null,
        val cwd: String? = null,
        val exitCode: Long? = null,
        val durationMs: Long? = null,
        val output: String? = null,
        val changes: List<AgentFileChange> = emptyList(),
        val query: String? = null,
        val path: String? = null,
        val inputJson: String? = null,
        val error: String? = null,
    ) : AgentItemBody

    /** The agent asks before it acts, offering its own choices. */
    data class Approval(
        val choices: List<AgentApprovalChoice>,
        val answeredChoiceId: String?,
    ) : AgentItemBody

    /** The agent asks one or more questions, with options and optionally typed text. */
    data class Question(
        val answerViaMessage: Boolean,
        val questions: List<AgentQuestion>,
        val answers: List<AgentQuestionAnswer>?,
    ) : AgentItemBody

    /** The agent's plan or to-do list. */
    data class Plan(
        val text: String?,
        val entries: List<AgentPlanEntry>,
    ) : AgentItemBody

    /** Work handed to a subagent. */
    data class Task(
        val description: String?,
        val kind: String? = null,
    ) : AgentItemBody

    /** Something the agent reports, such as a retry, a warning or a refused request. */
    data class Notice(
        val kind: AgentNoticeKind,
        val durationMs: Long?,
        val reason: String?,
    ) : AgentItemBody

    /** A failure the agent reports, with the harness's error code when it gives one. */
    data class Error(
        val code: Long?,
    ) : AgentItemBody

    /** The start of a session, with settings such as the model. */
    data class Session(
        val settings: List<AgentSessionSetting>,
        val actions: List<AgentSessionAction> = emptyList(),
        val commands: List<AgentSessionCommand> = emptyList(),
    ) : AgentItemBody

    /** A type this app does not know yet. The row falls back to the plain rendering. */
    data class Unknown(
        val type: String,
    ) : AgentItemBody
}

enum class AgentToolKind {
    Command,
    Edit,
    Read,
    Search,
    Fetch,
    Mcp,
    Subagent,
    Other,
    ;

    companion object {
        fun parse(value: String?): AgentToolKind = entries.firstOrNull { it.name.lowercase() == value } ?: Other
    }
}

@Immutable
data class AgentFileChange(
    val path: String,
    val diff: String,
    val kind: String?,
)

@Immutable
data class AgentApprovalChoice(
    val id: String,
    val label: String,
    val allows: Boolean,
)

@Immutable
data class AgentQuestion(
    val id: String,
    val header: String?,
    val text: String,
    val options: List<AgentQuestionOption>,
    val multiple: Boolean,
    val allowText: Boolean,
    val secret: Boolean,
)

@Immutable
data class AgentQuestionOption(
    val id: String,
    val label: String,
    val description: String?,
)

@Immutable
data class AgentQuestionAnswer(
    val questionId: String,
    val optionIds: List<String>,
    val text: String?,
)

enum class AgentPlanEntryState { Pending, Running, Done }

@Immutable
data class AgentPlanEntry(
    val text: String,
    val state: AgentPlanEntryState,
)

enum class AgentNoticeKind {
    Compaction,
    Waiting,
    Retry,
    Warning,
    ModelChanged,
    CommandOutput,
    Refused,
    Other,
    ;

    companion object {
        fun parse(value: String?): AgentNoticeKind =
            when (value) {
                "compaction" -> Compaction
                "waiting" -> Waiting
                "retry" -> Retry
                "warning" -> Warning
                "model_changed" -> ModelChanged
                "command_output" -> CommandOutput
                "refused" -> Refused
                else -> Other
            }
    }
}

@Immutable
data class AgentSessionSetting(
    val id: String,
    val label: String,
    val value: String?,
    val options: List<String> = emptyList(),
)

@Immutable
data class AgentSessionAction(
    val id: String,
    val label: String,
    val description: String?,
)

@Immutable
data class AgentSessionCommand(
    val name: String,
    val hint: String,
    val description: String?,
)
