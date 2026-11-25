package dev.ipf.whitenoise.android.ui.conversation.agent

import dev.ipf.whitenoise.android.core.agent.AgentItem
import dev.ipf.whitenoise.android.core.agent.AgentItemBody
import dev.ipf.whitenoise.android.core.agent.AgentItemState
import dev.ipf.whitenoise.android.core.agent.AgentQuestion
import dev.ipf.whitenoise.android.core.agent.AgentQuestionAnswer

/** How a row draws an item. Pure, so the choice is unit-tested apart from Compose. */
internal enum class AgentItemLayout {
    /** A turn's start line: working, finished, failed or stopped. */
    Turn,

    /** A centred divider where the agent starts a session. */
    Session,

    /** A card that waits for the viewer: an open approval. */
    ApprovalCard,

    /** A card that waits for the viewer: an open question. */
    QuestionCard,

    /** The agent's plan or to-do list. */
    Plan,

    /** One expandable line: tools, reasoning, tasks, notices, errors and answered requests. */
    Activity,
}

internal fun agentItemLayout(item: AgentItem): AgentItemLayout =
    when (val body = item.body) {
        is AgentItemBody.Turn -> AgentItemLayout.Turn
        is AgentItemBody.Session -> AgentItemLayout.Session
        is AgentItemBody.Approval ->
            if (item.state == AgentItemState.Waiting && body.choices.isNotEmpty()) {
                AgentItemLayout.ApprovalCard
            } else {
                AgentItemLayout.Activity
            }
        is AgentItemBody.Question ->
            if (item.state == AgentItemState.Waiting) AgentItemLayout.QuestionCard else AgentItemLayout.Activity
        is AgentItemBody.Plan ->
            if (body.entries.isEmpty() && body.text.isNullOrBlank()) AgentItemLayout.Activity else AgentItemLayout.Plan
        else -> AgentItemLayout.Activity
    }

/** Whole seconds under a minute, else minutes and seconds, else hours and minutes. */
internal data class AgentDuration(
    val hours: Long,
    val minutes: Long,
    val seconds: Long,
)

internal fun agentDuration(durationMs: Long): AgentDuration {
    val totalSeconds = (durationMs.coerceAtLeast(0L) + MILLIS_PER_SECOND / 2) / MILLIS_PER_SECOND
    return AgentDuration(
        hours = totalSeconds / SECONDS_PER_HOUR,
        minutes = (totalSeconds % SECONDS_PER_HOUR) / SECONDS_PER_MINUTE,
        seconds = totalSeconds % SECONDS_PER_MINUTE,
    )
}

/** The text the line shows beside its title: the command, path, query or the plain rendering. */
internal fun agentActivitySubject(item: AgentItem): String {
    val body = item.body
    val fromBody =
        when (body) {
            is AgentItemBody.Tool -> body.command?.let(::agentDisplayCommand) ?: body.path ?: body.query
            is AgentItemBody.Task -> body.description
            // An answered request's text only repeats its title ("Answered: Allow once").
            is AgentItemBody.Approval, is AgentItemBody.Question -> return ""
            else -> null
        }
    return (fromBody ?: item.text)
        .lineSequence()
        .firstOrNull { it.isNotBlank() }
        ?.trim()
        .orEmpty()
}

/**
 * The command as the person typed it. Harnesses often wrap a command in a login shell
 * (`/usr/bin/zsh -lc 'python3 greet.py'`); the wrapper says nothing about what ran.
 */
internal fun agentDisplayCommand(command: String): String {
    val match = SHELL_WRAPPER.matchEntire(command.trim()) ?: return command
    val (single, double) = match.destructured
    return if (match.groups[1] != null) single.replace("'\\''", "'") else double.replace("\\\"", "\"")
}

/**
 * Plan text as readable plain text: headings lose their marks, bullets become dots and inline code
 * loses its backticks. The plan card has no Markdown renderer of its own.
 */
internal fun agentPlainPlanText(text: String): String =
    text
        .lineSequence()
        .map { line ->
            line
                .replace(HEADING_MARK, "")
                .replace(BULLET_MARK, "• ")
                .replace("`", "")
                .replace("**", "")
        }.joinToString("\n")
        .trim()

/** Whether the line has more to show when it is opened. */
internal fun agentActivityExpandable(item: AgentItem): Boolean =
    when (val body = item.body) {
        // Every tool row opens: at least the full command, path or query and where it ran.
        is AgentItemBody.Tool -> true
        is AgentItemBody.Approval, is AgentItemBody.Question -> false
        is AgentItemBody.Reasoning -> item.text.isNotBlank()
        is AgentItemBody.Error -> item.text.isNotBlank() || body.code != null
        else -> item.text.lines().size > 1
    }

/**
 * The answer a question card can send, or null while a question still lacks one. A question with
 * options needs a choice or, when it allows it, typed text; a question without options needs text.
 */
internal fun agentQuestionAnswers(
    questions: List<AgentQuestion>,
    selected: Map<String, Set<String>>,
    typed: Map<String, String>,
): List<AgentQuestionAnswer>? =
    questions.map { question ->
        val options = selected[question.id].orEmpty().filter { id -> question.options.any { it.id == id } }
        val text = typed[question.id]?.trim()?.takeIf { question.allowText && it.isNotEmpty() }
        if (options.isEmpty() && text == null) return null
        AgentQuestionAnswer(questionId = question.id, optionIds = options, text = text)
    }

/** The option labels an answer chose, then its typed text, for the answered line and the plain rendering. */
internal fun agentAnswerSummary(
    questions: List<AgentQuestion>,
    answers: List<AgentQuestionAnswer>,
): String =
    answers
        .flatMap { answer ->
            val question = questions.firstOrNull { it.id == answer.questionId }
            answer.optionIds.map { id -> question?.options?.firstOrNull { it.id == id }?.label ?: id } +
                listOfNotNull(answer.text)
        }.joinToString(", ")

/** Classifies a diff line for colouring. File headers are context, not changes. */
internal enum class AgentDiffLine { Added, Removed, Hunk, Context }

internal fun agentDiffLine(line: String): AgentDiffLine =
    when {
        line.startsWith("+++") || line.startsWith("---") -> AgentDiffLine.Context
        line.startsWith("@@") -> AgentDiffLine.Hunk
        line.startsWith("+") -> AgentDiffLine.Added
        line.startsWith("-") -> AgentDiffLine.Removed
        else -> AgentDiffLine.Context
    }

// The whole command must be a literal shell path and one quoted argument, so unwrapping it shows
// exactly what runs: single quotes allow only the '\'' escape, and double quotes nothing the
// shell would expand.
private val SHELL_WRAPPER =
    Regex("^(?:/[\\w.+-]+)*/(?:ba|z)?sh[ \\t]+-l?c[ \\t]+(?:'((?:[^']|'\\\\'')*)'|\"((?:[^\"\\\\$`]|\\\\\")*)\")$")
private val HEADING_MARK = Regex("^#{1,6}\\s+")
private val BULLET_MARK = Regex("^\\s*[-*]\\s+")
private const val MILLIS_PER_SECOND = 1000L
private const val SECONDS_PER_MINUTE = 60L
private const val SECONDS_PER_HOUR = 3600L
