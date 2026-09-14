@file:Suppress("FunctionNaming")

package dev.ipf.whitenoise.android.ui.conversation.reactions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.core.RecentEmojiList
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseButton
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseSheetHeader
import dev.ipf.whitenoise.android.ui.conversation.composer.EmojiGlyph
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseSpacing
import dev.ipf.whitenoise.android.ui.theme.amoledOutlineBorder
import dev.ipf.whitenoise.android.ui.theme.amoledSheetContainerColor

private val ConfigureReactionsMaximumWidth = 560.dp
private val ReactionSlotSize = 56.dp
private val ConfigureReactionsButtonHeight = 56.dp

/**
 * Quick-reaction configuration: the six slots as 56dp discs, Reset back to the defaults, Done to
 * apply. Tapping a slot hands the current draft to [onPickSlot] so the caller can open the emoji
 * sheet for that slot and return here with the replacement.
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
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = WhiteNoiseSpacing.Section)
                        .padding(bottom = WhiteNoiseSpacing.Section),
                verticalArrangement = Arrangement.spacedBy(WhiteNoiseSpacing.FormField),
            ) {
                Text(
                    stringResource(R.string.configure_reactions_guidance),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                LazyRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(WhiteNoiseSpacing.Related),
                ) {
                    itemsIndexed(draft, key = { index, _ -> index }) { index, emoji ->
                        ReactionSlot(index = index, emoji = emoji, onClick = { onPickSlot(index, draft) })
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

@Composable
private fun ReactionSlot(
    index: Int,
    emoji: String,
    onClick: () -> Unit,
) {
    val description = stringResource(R.string.reaction_slot_description, index + 1, emoji)
    Surface(
        onClick = onClick,
        modifier = Modifier.size(ReactionSlotSize).semantics { contentDescription = description },
        border = amoledOutlineBorder(),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Box(contentAlignment = Alignment.Center) {
            EmojiGlyph(emoji)
        }
    }
}
