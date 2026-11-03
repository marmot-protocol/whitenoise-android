@file:Suppress("FunctionNaming")

package dev.ipf.whitenoise.android.ui.conversation.reactions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.core.RecentEmojiList
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseButton
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseSheetHeader
import dev.ipf.whitenoise.android.ui.common.fadingVerticalScroll
import dev.ipf.whitenoise.android.ui.conversation.composer.EmojiGlyph
import dev.ipf.whitenoise.android.ui.emojiPlainLabel
import dev.ipf.whitenoise.android.ui.icons.Icons
import dev.ipf.whitenoise.android.ui.icons.filled.Add
import dev.ipf.whitenoise.android.ui.icons.filled.Close
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseSpacing
import dev.ipf.whitenoise.android.ui.theme.amoledOutlineBorder
import dev.ipf.whitenoise.android.ui.theme.amoledSheetContainerColor

private val ConfigureReactionsMaximumWidth = 560.dp
private val ReactionSlotSize = 56.dp
private val ConfigureReactionsButtonHeight = 56.dp
internal const val CONFIGURE_REACTIONS_ADD_SLOT_TAG = "configure-reactions-add-slot"

/**
 * Quick-reaction configuration: the six standard slots as 56dp discs, up to two optional slots that can
 * also be removed, an add slot while fewer than eight are configured (#3279), Reset back to the six
 * defaults, and Done to apply. The slots wrap rather than scroll, so every one stays visible at compact
 * widths. Tapping a slot — or the add slot, as the next index — hands the current draft to [onPickSlot]
 * so the caller can open the emoji sheet for that slot and return here with the choice.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ConfigureReactionsSheet(
    current: List<String>,
    onDismiss: () -> Unit,
    onApply: (List<String>) -> Unit,
    onPickSlot: (Int, List<String>) -> Unit,
) {
    var draft by remember(current) { mutableStateOf(RecentEmojiList.normalizeQuickChoices(current)) }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = amoledSheetContainerColor()) {
        WhiteNoiseSheetHeader(stringResource(R.string.configure_reactions))
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .widthIn(max = ConfigureReactionsMaximumWidth)
                        .fadingVerticalScroll(rememberScrollState())
                        .padding(horizontal = WhiteNoiseSpacing.Section)
                        .padding(bottom = WhiteNoiseSpacing.Section),
                verticalArrangement = Arrangement.spacedBy(WhiteNoiseSpacing.FormField),
            ) {
                Text(
                    stringResource(R.string.configure_reactions_guidance),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(WhiteNoiseSpacing.Related),
                    verticalArrangement = Arrangement.spacedBy(WhiteNoiseSpacing.Related),
                ) {
                    draft.forEachIndexed { index, emoji ->
                        if (index < RecentEmojiList.QuickLimit) {
                            ReactionSlot(index = index, emoji = emoji, onClick = { onPickSlot(index, draft) })
                        } else {
                            OptionalReactionSlot(
                                index = index,
                                emoji = emoji,
                                onReplace = { onPickSlot(index, draft) },
                                onRemove = { draft = RecentEmojiList.withoutQuickChoiceAt(draft, index) },
                            )
                        }
                    }
                    if (draft.size < RecentEmojiList.QuickMaximum) {
                        AddReactionSlot(onClick = { onPickSlot(draft.size, draft) })
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(WhiteNoiseSpacing.Related),
                ) {
                    TextButton(
                        onClick = { draft = RecentEmojiList.DefaultQuickChoices },
                        modifier = Modifier.weight(1f).heightIn(min = ConfigureReactionsButtonHeight),
                    ) {
                        Text(stringResource(R.string.reset))
                    }
                    WhiteNoiseButton(onClick = { onApply(draft) }, modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.done))
                    }
                }
            }
        }
    }
}

/** One editable quick-reaction slot, announced once by its slot description rather than again by its glyph. */
@Composable
private fun ReactionSlot(
    index: Int,
    emoji: String,
    onClick: () -> Unit,
) {
    val description = stringResource(R.string.reaction_slot_description, index + 1, emojiPlainLabel(emoji))
    Surface(
        onClick = onClick,
        modifier = Modifier.size(ReactionSlotSize).semantics { contentDescription = description },
        border = amoledOutlineBorder(),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Box(contentAlignment = Alignment.Center) {
            EmojiGlyph(emoji, modifier = Modifier.clearAndSetSemantics {})
        }
    }
}

/** An optional seventh or eighth slot: replaceable like the others, with its own remove button. */
@Composable
private fun OptionalReactionSlot(
    index: Int,
    emoji: String,
    onReplace: () -> Unit,
    onRemove: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        ReactionSlot(index = index, emoji = emoji, onClick = onReplace)
        val removeDescription = stringResource(R.string.configure_reactions_remove, index + 1, emojiPlainLabel(emoji))
        IconButton(onClick = onRemove) {
            Icon(
                Icons.Default.Close,
                contentDescription = removeDescription,
            )
        }
    }
}

/** Appends another optional slot, opening the emoji sheet for it. */
@Composable
private fun AddReactionSlot(onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = Modifier.size(ReactionSlotSize).testTag(CONFIGURE_REACTIONS_ADD_SLOT_TAG),
        border = amoledOutlineBorder(),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                Icons.Default.Add,
                contentDescription = stringResource(R.string.configure_reactions_add),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
