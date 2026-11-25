package dev.ipf.whitenoise.android.ui.conversation.agent

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Dp
import dev.ipf.marmotkit.AgentItemFfi
import dev.ipf.marmotkit.AgentItemStateFfi
import dev.ipf.whitenoise.android.core.MessageProjector
import dev.ipf.whitenoise.android.state.TimelineMessage
import org.json.JSONObject

/**
 * Which agent rows sit folded under their turn, which turns the viewer opened, and the progress of
 * answers the viewer sent, for one conversation.
 *
 * Routine steps (tool calls, thinking, plans, subagent tasks and quiet notices) stay folded so the
 * chat shows the turn, what needs the viewer, what failed and the agent's replies. Opening a turn
 * shows its steps in place. Every row keeps its slot in the list; a folded row draws nothing, so
 * scrolling, paging and jumps see the same rows as without agents. Sender runs and avatars skip
 * folded rows ([shownOlder], [shownNewer]), and the shown rows around a run of folded rows draw over
 * the list spacing the run leaves ([foldedSpacing]).
 */
@Stable
internal class AgentTurnSteps(
    /** Folded step row id to its turn's row id. */
    private val turnOfStep: Map<String, String>,
    private val opened: SnapshotStateList<String>,
    /** The transcript's rows, oldest first. */
    private val rows: List<TimelineMessage> = emptyList(),
    /** Answer and Stop progress by item row and version, kept while rows scroll out and back. */
    internal val requests: SnapshotStateMap<String, AgentRequestStatus> = mutableStateMapOf(),
) {
    private val stepCounts: Map<String, Int> = turnOfStep.values.groupingBy { it }.eachCount()
    private val positions: Map<String, Int> =
        if (turnOfStep.isEmpty()) {
            emptyMap()
        } else {
            rows.withIndex().associate { (index, row) -> row.record.messageIdHex to index }
        }

    /** True when [rowId] is a step whose turn is closed, so the row draws nothing. */
    fun isFolded(rowId: String): Boolean = turnOfStep[rowId]?.let { it !in opened } == true

    /** How many steps fold under the turn at [turnRowId]. */
    fun stepCount(turnRowId: String): Int = stepCounts[turnRowId] ?: 0

    /** True when the viewer opened the turn at [turnRowId]. */
    fun isOpen(turnRowId: String): Boolean = turnRowId in opened

    /** Opens or closes the turn at [turnRowId]. */
    fun toggle(turnRowId: String) {
        if (!opened.remove(turnRowId)) opened.add(turnRowId)
    }

    /** The nearest row older than [item] that draws, given its direct neighbour [older]. */
    fun shownOlder(
        item: TimelineMessage,
        older: TimelineMessage?,
    ): TimelineMessage? = shownNeighbour(item, older, step = -1)

    /** The nearest row newer than [item] that draws, given its direct neighbour [newer]. */
    fun shownNewer(
        item: TimelineMessage,
        newer: TimelineMessage?,
    ): TimelineMessage? = shownNeighbour(item, newer, step = 1)

    /**
     * The list spacing, in rows, that [rowId] draws over: half of the folded run on its older side and
     * the other half of the run on its newer side, or all of that run when no shown row follows it.
     */
    fun foldedSpacing(rowId: String): FoldedSpacing {
        val index = positions[rowId]?.takeUnless { isFolded(rowId) } ?: return FoldedSpacing.None
        val olderRun = foldedRun(index, step = -1)
        val newerRun = foldedRun(index, step = 1)
        val newerRunEndsTranscript = index + newerRun + 1 >= rows.size
        return FoldedSpacing(
            olderRows = olderRun / 2,
            newerRows = if (newerRunEndsTranscript) newerRun else newerRun - newerRun / 2,
        )
    }

    /** How many folded rows directly follow (or, with a negative [step], precede) the row at [index]. */
    private fun foldedRun(
        index: Int,
        step: Int,
    ): Int {
        var next = index + step
        while (next in rows.indices && isFolded(rows[next].record.messageIdHex)) next += step
        return (next - index) * step - 1
    }

    private fun shownNeighbour(
        item: TimelineMessage,
        neighbour: TimelineMessage?,
        step: Int,
    ): TimelineMessage? {
        val index = positions[item.record.messageIdHex]
        return if (neighbour == null || index == null || !isFolded(neighbour.record.messageIdHex)) {
            neighbour
        } else {
            rows.getOrNull(index + step * (foldedRun(index, step) + 1))
        }
    }

    /** Same folding over the same rows: readers skip recomposition when a timeline update changes neither. */
    override fun equals(other: Any?): Boolean =
        other is AgentTurnSteps &&
            other.opened === opened &&
            other.requests === requests &&
            other.turnOfStep == turnOfStep &&
            other.rows == rows

    override fun hashCode(): Int = turnOfStep.hashCode()

    companion object {
        /** No agent steps to fold. */
        val None = AgentTurnSteps(emptyMap(), mutableStateListOf())
    }
}

/** Rows of list spacing a shown row draws over, on its older and newer sides. */
internal data class FoldedSpacing(
    val olderRows: Int,
    val newerRows: Int,
) {
    companion object {
        /** Nothing folded beside the row. */
        val None = FoldedSpacing(0, 0)
    }
}

/** The conversation's agent state. Outside a conversation nothing folds. */
internal val LocalAgentTurnSteps = compositionLocalOf { AgentTurnSteps.None }

/**
 * Folds [timeline]'s steps under their turns. The opened turns survive configuration changes; they and
 * the request progress are forgotten with [conversationKey].
 */
@Composable
internal fun rememberAgentTurnSteps(
    timeline: List<TimelineMessage>,
    conversationKey: Any,
): AgentTurnSteps {
    val opened =
        rememberSaveable(
            conversationKey,
            saver = listSaver(save = { it.toList() }, restore = { it.toMutableStateList() }),
        ) { mutableStateListOf<String>() }
    val requests = remember(conversationKey) { mutableStateMapOf<String, AgentRequestStatus>() }
    return remember(timeline, opened, requests) {
        val rows = timeline.filterNot { MessageProjector.isEdit(it.record) }
        val steps = agentTurnStepRows(rows)
        AgentTurnSteps(steps, opened, if (steps.isEmpty()) emptyList() else rows, requests)
    }
}

private fun List<String>.toMutableStateList(): SnapshotStateList<String> =
    mutableStateListOf<String>().also { it.addAll(this) }

/**
 * Lets a shown row draw over the list spacing that folded rows beside it leave, so a closed turn reads
 * as if its steps were absent. The row reports itself shorter by that spacing and moves its content up
 * by the older side's share; the content overflows onto the empty folded rows. Each side is capped by
 * the row's height, so a run longer than its two neighbours can absorb still leaves some space.
 */
internal fun Modifier.overlapFoldedAgentRows(
    spacing: FoldedSpacing,
    rowSpacing: Dp,
): Modifier =
    if (spacing == FoldedSpacing.None) {
        this
    } else {
        layout { measurable, constraints ->
            val placeable = measurable.measure(constraints)
            val rowPx = rowSpacing.roundToPx()
            val newer = (rowPx * spacing.newerRows).coerceAtMost(placeable.height)
            val older = (rowPx * spacing.olderRows).coerceAtMost(placeable.height - newer)
            layout(placeable.width, placeable.height - newer - older) { placeable.place(0, -older) }
        }
    }

/**
 * Maps each foldable step row to the row of its turn: the turn item from the same agent with the same
 * turn id. A step whose turn row is not loaded stays visible. Reads only MDK's projected fields, and
 * parses fields JSON for notices alone, so it is cheap on every timeline update.
 */
internal fun agentTurnStepRows(timeline: List<TimelineMessage>): Map<String, String> {
    val items = timeline.mapNotNull { row -> row.projected?.agentItem?.takeIf { it.turn != null }?.let { row to it } }
    val turnRows =
        items
            .filter { (_, item) -> item.itemType == TURN_TYPE }
            .associate { (row, item) -> turnKey(row, item) to row.record.messageIdHex }
    return items
        .filter { (_, item) -> item.foldsUnderTurn() }
        .mapNotNull { (row, item) ->
            turnRows[turnKey(row, item)]?.let { turnRow -> row.record.messageIdHex to turnRow }
        }
        .toMap()
}

private fun turnKey(
    row: TimelineMessage,
    item: AgentItemFfi,
): Pair<String, String?> = row.record.sender.lowercase() to item.turn

/**
 * Routine steps fold. Turns, sessions, approvals, questions, errors, refusals, warnings and failed
 * steps stay in view.
 */
private fun AgentItemFfi.foldsUnderTurn(): Boolean =
    state != AgentItemStateFfi.FAILED &&
        when (itemType) {
            "tool", "reasoning", "plan", "task", "message" -> true
            "notice" -> noticeKind(fieldsJson) !in VISIBLE_NOTICE_KINDS
            else -> false
        }

private fun noticeKind(fieldsJson: String): String? =
    runCatching { JSONObject(fieldsJson).optJSONObject("body")?.optString("kind") }.getOrNull()

private const val TURN_TYPE = "turn"
private val VISIBLE_NOTICE_KINDS = setOf("refused", "warning")
