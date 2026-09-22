package dev.ipf.whitenoise.android.ui.conversation.reactions

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.core.ReactionTally

// Distinct emojis shown as pills; the "+N" pill carries every reaction beyond them.
internal const val MAX_VISIBLE_REACTIONS = 4

/** One rendered reaction pill: an emoji with its count, or the "+N" overflow of the omitted emoji types. */
internal data class ReactionPill(
    val emoji: String?,
    val count: Int,
    val selected: Boolean,
    val omittedTypes: Int = 0,
)

/** The prototype's summary: up to [maximumPills] emoji pills, then one "+N" pill for the rest. */
internal fun reactionPills(
    tallies: List<ReactionTally>,
    maximumPills: Int = MAX_VISIBLE_REACTIONS,
): List<ReactionPill> {
    if (tallies.size <= maximumPills) return tallies.map { ReactionPill(it.emoji, it.count, it.mine) }
    val visible = tallies.take(maximumPills).map { ReactionPill(it.emoji, it.count, it.mine) }
    val omitted = tallies.drop(maximumPills)
    return visible +
        ReactionPill(
            emoji = null,
            count = omitted.sumOf { it.count },
            selected = omitted.any { it.mine },
            omittedTypes = omitted.size,
        )
}

/** Selected pills sit on the primary container; the rest on the high surface container. */
@Composable
internal fun reactionPillContainerColor(selected: Boolean): Color {
    val scheme = MaterialTheme.colorScheme
    return if (selected) scheme.primaryContainer else scheme.surfaceContainerHigh
}

/** Selected pills draw the primary outline; the rest the outline variant. */
@Composable
internal fun reactionPillBorderColor(selected: Boolean): Color {
    val scheme = MaterialTheme.colorScheme
    return if (selected) scheme.primary else scheme.outlineVariant
}

/**
 * The prototype's reaction row under a bubble: 23dp pills, one per emoji, each inside a 48dp touch row.
 * Every tap opens the unfiltered reactor list so the details surface consistently starts on All.
 */
@Suppress("FunctionNaming")
@Composable
internal fun ReactionPillRow(
    tallies: List<ReactionTally>,
    enabled: Boolean,
    onOpenDetails: (String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val pills = remember(tallies) { reactionPills(tallies) }
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(ReactionPillSpacing),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        pills.forEachIndexed { index, pill ->
            ReactionPillItem(pill, index, enabled, onOpenDetails)
        }
    }
}

/** One reaction pill in the row. */
@Suppress("LongParameterList", "FunctionNaming")
@Composable
private fun ReactionPillItem(
    pill: ReactionPill,
    index: Int,
    enabled: Boolean,
    onOpenDetails: (String?) -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val emoji = pill.emoji
    val description =
        if (emoji != null) {
            pluralStringResource(R.plurals.reaction_count, pill.count, emoji, pill.count)
        } else {
            pluralStringResource(R.plurals.more_reaction_types, pill.omittedTypes, pill.count, pill.omittedTypes)
        }
    val selectedState =
        stringResource(if (pill.selected) R.string.selection_state_selected else R.string.selection_state_not_selected)
    val viewReactors = stringResource(R.string.view_reactors)
    val single = emoji != null && pill.count == 1
    Box(
        modifier =
            Modifier
                .heightIn(min = ReactionTouchTargetHeight)
                .testTag("$REACTION_PILL_TEST_TAG:$index")
                .then(
                    if (enabled) {
                        Modifier.combinedClickable(
                            interactionSource = interactionSource,
                            indication = null,
                            role = Role.Button,
                            onClick = { onOpenDetails(null) },
                            onClickLabel = viewReactors,
                            onLongClickLabel = viewReactors,
                            onLongClick = { onOpenDetails(null) },
                        )
                    } else {
                        Modifier
                    },
                ).semantics {
                    contentDescription = description
                    stateDescription = selectedState
                    selected = pill.selected
                },
        contentAlignment = Alignment.Center,
    ) {
        ReactionPillSurface(pill, single, interactionSource)
    }
}

/** The 23dp pill itself: emoji (or "+N") with the count, on the selection colours, inside a circular outline. */
@Suppress("FunctionNaming")
@Composable
private fun ReactionPillSurface(
    pill: ReactionPill,
    single: Boolean,
    interactionSource: MutableInteractionSource,
) {
    val emoji = pill.emoji
    Row(
        modifier =
            Modifier
                .height(ReactionPillHeight)
                .then(
                    if (single) {
                        Modifier.requiredWidth(ReactionPillMinimumWidth)
                    } else {
                        Modifier.widthIn(min = ReactionPillMinimumWidth)
                    },
                ).clip(CircleShape)
                .indication(interactionSource, ripple(color = MaterialTheme.colorScheme.onSurface))
                .background(reactionPillContainerColor(pill.selected))
                .border(1.dp, reactionPillBorderColor(pill.selected), CircleShape)
                .padding(horizontal = if (single) 3.dp else 7.dp),
        horizontalArrangement = Arrangement.spacedBy(ReactionContentSpacing, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (emoji != null) {
            Text(
                text = emoji,
                fontSize = with(LocalDensity.current) { ReactionEmojiSize.toSp() },
                lineHeight = with(LocalDensity.current) { ReactionPillHeight.toSp() },
                maxLines = 1,
                softWrap = false,
            )
        } else {
            Text(
                text = "+${pill.omittedTypes}",
                style = MaterialTheme.typography.labelMedium.copy(fontSize = 12.sp, lineHeight = 14.sp),
                fontWeight = FontWeight.Bold,
            )
        }
        if (emoji != null && pill.count > 1) {
            Text(
                text = pill.count.toString(),
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp, lineHeight = 13.sp),
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

internal const val REACTION_PILL_TEST_TAG = "conversation.reaction.pill"
private val ReactionPillHeight = 23.dp
private val ReactionPillMinimumWidth = 31.dp
private val ReactionPillSpacing = 3.dp
private val ReactionContentSpacing = 2.dp
private val ReactionEmojiSize = 14.dp
private val ReactionTouchTargetHeight = 48.dp
