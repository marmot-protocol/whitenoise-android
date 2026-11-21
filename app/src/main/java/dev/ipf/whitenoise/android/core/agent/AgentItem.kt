package dev.ipf.whitenoise.android.core.agent

import androidx.compose.runtime.Immutable

/**
 * One marmot-agents item at its latest version: a turn, a tool call, an approval and so on.
 *
 * The agent sends the first version as its own event and every later version as a new event that
 * names the first. [firstEventId] is that first event, so a row keeps one identity while the agent
 * updates it. The fields mirror the kind 1201 tags (D-076); [body] holds the type-specific `fields`.
 */
@Immutable
data class AgentItem(
    val firstEventId: String,
    val version: Long,
    val state: AgentItemState,
    val title: String,
    val text: String,
    val harness: String,
    val itemId: String,
    val seq: Long,
    val session: String?,
    val turn: String?,
    /** On a turn, the chat message that started it. */
    val startedByEventId: String?,
    val body: AgentItemBody,
    /** Full output length when the agent cut a command's output to fit the 64 KB limit. */
    val outputCutLength: Long? = null,
    /** How many file diffs the agent left out to fit the 64 KB limit. */
    val diffsCut: Int = 0,
) {
    /** A person is needed: an approval or a question that nobody has answered yet. */
    val waitingForPerson: Boolean get() = state == AgentItemState.Waiting
}

enum class AgentItemState {
    Running,
    Waiting,
    Done,
    Failed,
    Declined,
    Cancelled,
    ;

    val isOpen: Boolean get() = this == Running || this == Waiting

    companion object {
        fun parse(value: String?): AgentItemState? =
            when (value) {
                "running" -> Running
                "waiting" -> Waiting
                "done" -> Done
                "failed" -> Failed
                "declined" -> Declined
                "cancelled" -> Cancelled
                else -> null
            }
    }
}

/** The type-specific part of an item, one case per marmot-agents item type. */
@Immutable
sealed interface AgentItemBody {
    data class Turn(
        val startedBy: String?,
        val durationMs: Long?,
        val inputTokens: Long?,
        val outputTokens: Long?,
    ) : AgentItemBody

    data object Reasoning : AgentItemBody

    data class Tool(
        val kind: AgentToolKind,
        val name: String,
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

    data class Approval(
        val subjectItemId: String?,
        val choices: List<AgentApprovalChoice>,
        val answeredChoiceId: String?,
    ) : AgentItemBody

    data class Question(
        val answerViaMessage: Boolean,
        val questions: List<AgentQuestion>,
        val answers: List<AgentQuestionAnswer>?,
    ) : AgentItemBody

    data class Plan(
        val text: String?,
        val entries: List<AgentPlanEntry>,
    ) : AgentItemBody

    data class Task(
        val kind: String,
        val description: String?,
    ) : AgentItemBody

    data class Notice(
        val kind: AgentNoticeKind,
        val durationMs: Long?,
        val reason: String?,
    ) : AgentItemBody

    data class Error(
        val request: String?,
        val code: Long?,
        val willRetry: Boolean?,
    ) : AgentItemBody

    data class Session(
        val harnessVersion: String?,
        val settings: List<AgentSessionSetting>,
        val commands: List<AgentSessionCommand>,
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
)

@Immutable
data class AgentSessionCommand(
    val name: String,
    val description: String?,
)
