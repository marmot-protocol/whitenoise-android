package dev.ipf.whitenoise.android.notifications

import android.content.Context
import android.content.Intent
import android.os.Bundle
import dev.ipf.whitenoise.android.ui.EmojiShortcodes
import dev.ipf.whitenoise.android.ui.RecentEmojiPreferences

// RemoteInput choices are rendered as inline choice chips rather than separate
// notification actions, so all six app quick reactions can remain available.
internal const val MAX_NOTIFICATION_QUICK_REACTIONS = 6
private const val EXTRA_INLINE_REACTION_LABELS = "dev.ipf.whitenoise.android.extra.INLINE_REACTION_LABELS"

internal fun notificationQuickReactionChoices(context: Context): List<String> =
    notificationQuickReactionChoices(RecentEmojiPreferences.loadQuickReactions(context))

internal fun notificationQuickReactionChoices(stored: List<String>): List<String> =
    stored
        .mapNotNull(::normalizeNotificationReaction)
        .distinct()
        .take(MAX_NOTIFICATION_QUICK_REACTIONS)

/**
 * Text for each SystemUI choice chip, which can only draw plain text: shortcodes show a readable name.
 * A name another choice would share keeps its exact value, so every chip still names one reaction.
 */
internal fun notificationQuickReactionLabels(
    context: Context,
    choices: List<String>,
): List<String> {
    val labels = choices.map { EmojiShortcodes.plainLabel(it, context.resources) }
    return choices.mapIndexed { index, choice ->
        val label = labels[index]
        val ambiguous = labels.count { it == label } > 1 || (label != choice && label in choices)
        if (ambiguous) choice else label
    }
}

/** Records which reaction each renamed choice chip stands for, since SystemUI returns only the chip text. */
internal fun applyInlineReactionLabels(
    intent: Intent,
    valuesByLabel: Map<String, String>,
) {
    if (valuesByLabel.isEmpty()) return
    intent.putExtra(EXTRA_INLINE_REACTION_LABELS, Bundle().apply { valuesByLabel.forEach(::putString) })
}

/** The reaction a returned chip text stands for, or that text itself when the chip showed its exact value. */
internal fun inlineReactionValue(
    intent: Intent,
    returned: String,
): String = intent.getBundleExtra(EXTRA_INLINE_REACTION_LABELS)?.getString(returned) ?: returned
