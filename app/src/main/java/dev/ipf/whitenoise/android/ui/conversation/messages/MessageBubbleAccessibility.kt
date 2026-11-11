package dev.ipf.whitenoise.android.ui.conversation.messages

import androidx.compose.foundation.clickable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.core.ReactionTally
import dev.ipf.whitenoise.android.ui.emojiPlainLabel

/*
 * Outside batch and text selection a message row is one screen-reader stop (#3603). Android maps a
 * merging node to the platform merge: the reader speaks the row's descendants in order, but offers
 * only the row's own actions. So the nested sender, quote, footer and reaction controls become
 * plain readable text, and every action they or the body text carried is published on the row.
 */

/** One entry in a message row's screen-reader actions; unavailable entries are left out. */
internal class MessageRowAction(
    val label: String,
    val available: Boolean,
    val perform: () -> Unit,
)

/**
 * Row actions in menu order, followed by the actions forwarded from the message text. Compose refuses a node
 * with 32 or more custom actions by throwing while an accessibility service reads it, and a long message
 * forwards one read-aloud action per paragraph. So forwarded actions with the same label collapse into the
 * first, as a screen reader could not tell them apart, and the list stops at [MAX_ROW_CUSTOM_ACTIONS] with
 * the row's own actions kept first.
 */
internal fun messageRowCustomActions(
    actions: List<MessageRowAction>,
    forwarded: List<CustomAccessibilityAction>,
): List<CustomAccessibilityAction> {
    val own =
        actions
            .filter(MessageRowAction::available)
            .map { action ->
                CustomAccessibilityAction(action.label) {
                    action.perform()
                    true
                }
            }
    val ownLabels = own.mapTo(mutableSetOf(), CustomAccessibilityAction::label)
    val text = forwarded.filter { it.label !in ownLabels }.distinctBy(CustomAccessibilityAction::label)
    return (own + text).take(MAX_ROW_CUSTOM_ACTIONS)
}

/** Below Compose's limit of 32 custom actions per accessibility node. */
internal const val MAX_ROW_CUSTOM_ACTIONS = 31

/**
 * Replaces a nested control's semantics while the merged row owns its action. [label] stays as plain
 * text, so the row reads it in place; [description] labels a control that shows no words, such as
 * the reaction pills.
 */
internal fun Modifier.messageRowChrome(
    rowOwnsSemantics: Boolean,
    label: String? = null,
    description: String? = null,
): Modifier =
    if (rowOwnsSemantics) {
        clearAndSetSemantics {
            label?.takeIf(String::isNotBlank)?.let { text = AnnotatedString(it) }
            description?.takeIf(String::isNotBlank)?.let { contentDescription = it }
        }
    } else {
        this
    }

/**
 * The tappable "edited" footer label. Touch opens the edit history; for the screen reader it stays
 * text in the row summary, and the row offers Edit history. Batch selection covers the row with its
 * own tap layer and text selection drops [onClick], so the label is never a separate action there either.
 */
internal fun Modifier.editedLabelTarget(
    label: String,
    onClick: (() -> Unit)?,
): Modifier =
    if (onClick == null) {
        this
    } else {
        clearAndSetSemantics { text = AnnotatedString(label) }.clickable(onClick = onClick)
    }

/** Spoken summary of a reaction row, one entry per emoji with its count. */
@Composable
internal fun messageReactionSummaryDescription(tallies: List<ReactionTally>): String =
    tallies
        .map { tally ->
            pluralStringResource(R.plurals.reaction_count, tally.count, emojiPlainLabel(tally.emoji), tally.count)
        }.joinToString(", ")

/**
 * Text actions (read aloud from a sentence, copy a link) live on each rendered text leaf, which is
 * not focusable inside a merged row. Leaves register their labels here and the row republishes
 * them; each forwarded action runs the leaf's current action when performed.
 */
@Stable
internal class MessageRowTextActions {
    private class Leaf(
        val owner: Any,
        val labels: List<String>,
        val perform: (Int) -> Boolean,
    )

    private val leaves = mutableStateListOf<Leaf>()

    /** Forwarded actions in leaf registration order, which follows the rendered text order. */
    val actions: List<CustomAccessibilityAction>
        get() =
            leaves.flatMap { leaf ->
                leaf.labels.mapIndexed { index, label -> CustomAccessibilityAction(label) { leaf.perform(index) } }
            }

    internal fun update(
        owner: Any,
        labels: List<String>,
        perform: (Int) -> Boolean,
    ) {
        val index = leaves.indexOfFirst { it.owner === owner }
        when {
            index < 0 && labels.isEmpty() -> Unit
            index < 0 -> leaves += Leaf(owner, labels, perform)
            leaves[index].labels != labels -> leaves[index] = Leaf(owner, labels, perform)
        }
    }

    internal fun remove(owner: Any) {
        leaves.removeAll { it.owner === owner }
    }
}

/** The text-action registry of the message row being composed, absent outside a timeline row. */
internal val LocalMessageRowTextActions = staticCompositionLocalOf<MessageRowTextActions?> { null }

/** Publishes a text leaf's actions to its row; writes only when the labels change. */
@Suppress("FunctionNaming")
@Composable
internal fun ForwardTextActionsToMessageRow(actions: List<CustomAccessibilityAction>) {
    val registry = LocalMessageRowTextActions.current ?: return
    val owner = remember { Any() }
    val latest = rememberUpdatedState(actions)
    val labels = actions.map(CustomAccessibilityAction::label)
    DisposableEffect(registry, owner) {
        onDispose { registry.remove(owner) }
    }
    SideEffect {
        registry.update(owner, labels) { index ->
            latest.value
                .getOrNull(index)
                ?.action
                ?.invoke() == true
        }
    }
}
