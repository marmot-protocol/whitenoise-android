package dev.ipf.whitenoise.android.ui.group

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.ui.chats.chatsExplanation
import dev.ipf.whitenoise.android.ui.common.Explanation
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseListItemDefaults
import dev.ipf.whitenoise.android.ui.common.explainOnLongPress
import dev.ipf.whitenoise.android.ui.common.rememberMeasuredControlClick
import dev.ipf.whitenoise.android.ui.settings.SettingsRowContext
import dev.ipf.whitenoise.android.ui.settings.rememberSettingsNativeRow
import dev.ipf.whitenoise.android.ui.theme.ConnectedRowShape
import dev.ipf.whitenoise.android.ui.theme.Typography
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseSpacing
import dev.ipf.whitenoise.android.ui.theme.amoledOutlineBorder
import dev.ipf.whitenoise.android.ui.theme.connectedRowBorder
import kotlin.math.ceil

/** Equal-width overview action with one accessible tonal target and a visual caption. */
@Suppress("FunctionNaming", "LongParameterList")
@Composable
internal fun QuickInfoAction(
    label: String,
    @DrawableRes icon: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    state: String? = null,
    enabled: Boolean = true,
    buttonModifier: Modifier = Modifier,
    explanation: Explanation? = null,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FilledTonalIconButton(
            onClick = onClick,
            enabled = enabled,
            modifier =
                buttonModifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .semantics { state?.let { stateDescription = it } }
                    .explainOnLongPress(explanation),
        ) {
            Icon(painterResource(icon), contentDescription = label, modifier = Modifier.size(24.dp))
        }
        Text(
            text = label,
            modifier = Modifier.clearAndSetSemantics {},
            color = if (enabled) Color.Unspecified else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.labelLarge,
        )
    }
}

/** Overview actions wrap as available width and caption size require. */
@Suppress("FunctionNaming", "LongMethod", "LongParameterList")
@Composable
internal fun ChatInfoQuickActions(
    onOpenProfile: ((Rect?) -> Unit)?,
    onAddMembers: (() -> Unit)?,
    addMembersEnabled: Boolean,
    muteControl: ConversationMuteControl,
    disappearingState: String,
    disappearingEnabled: Boolean,
    onOpenDisappearing: () -> Unit,
    onOpenSearch: (() -> Unit)?,
) {
    val gap = WhiteNoiseSpacing.Related
    val profileLabel = stringResource(R.string.about)
    val addMembersLabel = stringResource(R.string.add_member)
    val muteLabel = stringResource(muteControl.labelRes)
    val disappearingLabel = stringResource(R.string.disappearing)
    val searchLabel = stringResource(R.string.quick_action_search)
    val labels =
        listOfNotNull(
            profileLabel.takeIf { onOpenProfile != null },
            addMembersLabel.takeIf { onAddMembers != null },
            muteLabel,
            disappearingLabel,
            searchLabel.takeIf { onOpenSearch != null },
        )
    BoxWithConstraints(
        modifier = Modifier.fillMaxWidth().padding(horizontal = WhiteNoiseSpacing.CompactScreenMargin, vertical = gap),
    ) {
        val actionModifier = Modifier.width(quickActionWidth(maxWidth, gap, labels))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(gap), verticalArrangement = Arrangement.spacedBy(gap)) {
            if (onOpenProfile != null) {
                val profileLaunch = rememberMeasuredControlClick(onOpenProfile)
                QuickInfoAction(
                    label = profileLabel,
                    icon = R.drawable.ic_person,
                    onClick = profileLaunch.second,
                    modifier = actionModifier,
                    buttonModifier = profileLaunch.first,
                )
            }
            if (onAddMembers != null) {
                QuickInfoAction(
                    label = addMembersLabel,
                    icon = R.drawable.ic_group_add,
                    onClick = onAddMembers,
                    enabled = addMembersEnabled,
                    explanation = chatsExplanation(R.string.add_member, R.string.explanation_chat_info_add_members),
                    modifier = actionModifier,
                )
            }
            QuickInfoAction(
                label = muteLabel,
                icon = muteControl.iconRes,
                enabled = !muteControl.pending,
                explanation = chatsExplanation(muteControl.labelRes, muteControl.explanationRes),
                onClick = muteControl::toggle,
                modifier = actionModifier,
            )
            QuickInfoAction(
                label = disappearingLabel,
                icon = R.drawable.ic_timer,
                state = disappearingState,
                enabled = disappearingEnabled,
                explanation = chatsExplanation(R.string.disappearing, R.string.explanation_chat_info_disappearing),
                onClick = onOpenDisappearing,
                modifier = actionModifier,
            )
            if (onOpenSearch != null) {
                QuickInfoAction(
                    label = searchLabel,
                    icon = R.drawable.ic_search,
                    onClick = onOpenSearch,
                    modifier = actionModifier,
                )
            }
        }
    }
}

/** Fits unbreakable caption runs and balances the actions across the rows that fit. */
@Composable
private fun quickActionWidth(
    availableWidth: Dp,
    gap: Dp,
    labels: List<String>,
): Dp {
    val captionStyle = MaterialTheme.typography.labelLarge
    val measurer = rememberTextMeasurer()
    val captionWidthPx = labels.maxOf { measurer.measure(it, captionStyle).multiParagraph.minIntrinsicWidth }
    return with(LocalDensity.current) {
        val captionScale = captionStyle.fontSize.toDp().value / Typography.labelLarge.fontSize.value
        val minimumWidthPx = ceil(maxOf((72.dp * captionScale.coerceAtLeast(1f)).toPx(), captionWidthPx)).toInt()
        val availableWidthPx = availableWidth.roundToPx()
        // FlowRow rounds spacing up when deciding whether another action fits.
        val gapPx = ceil(gap.toPx()).toInt()
        val maxColumns = ((availableWidthPx + gapPx) / (minimumWidthPx + gapPx)).coerceIn(1, labels.size)
        val rows = (labels.size + maxColumns - 1) / maxColumns
        val columns = (labels.size + rows - 1) / rows
        ((availableWidthPx - gapPx * (columns - 1)) / columns).toDp()
    }
}

/** Segmented member presentation around the existing identity, copy and role-mutation controls. */
@Suppress("FunctionNaming")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ChatInfoMemberCard(
    index: Int,
    count: Int,
    modifier: Modifier = Modifier,
    suppressChrome: () -> Boolean = { false },
    nativeGroup: Any? = null,
    nativeKey: String = index.toString(),
    content: @Composable () -> Unit,
) {
    val shapes = WhiteNoiseListItemDefaults.segmentedShapes(index, count)
    val shape = shapes.shape
    val border = amoledOutlineBorder()
    val nativeRow =
        if (nativeGroup != null) {
            rememberSettingsNativeRow(
                nativeGroup,
                nativeKey,
                SettingsRowContext(
                    shapes,
                    MaterialTheme.colorScheme.surfaceContainerLowest,
                    border?.let { Color.White } ?: Color.Unspecified,
                ),
            )
        } else {
            Modifier
        }
    Surface(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(
                    bottom = if (index == count - 1) 0.dp else WhiteNoiseListItemDefaults.segmentedGap,
                ).then(nativeRow)
                .then(
                    if (shape is ConnectedRowShape && border != null) {
                        Modifier.connectedRowBorder(
                            shape,
                            Color.White,
                            suppressChrome = suppressChrome,
                            suppressSeam = { false },
                        )
                    } else {
                        Modifier
                    },
                ),
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        border = if (shape is ConnectedRowShape) null else border,
    ) {
        Box(Modifier.heightIn(min = MemberRowMinimumHeight)) { content() }
    }
}

/**
 * Trailing glyph for a chat-info row that opens another destination. Terminal actions such as
 * Archive and Leave deliberately omit it, so the chevron alone signals "this navigates forward".
 */
@Suppress("FunctionNaming")
@Composable
internal fun ChatInfoRowChevron() {
    Icon(
        painter = painterResource(R.drawable.ic_chevron_right),
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * Supporting line under a member's name. A roster mutation in flight replaces the role with the
 * updating notice, so the row reports progress in text rather than swapping its trailing glyph.
 */
@Composable
internal fun memberRoleLabel(
    isAdmin: Boolean,
    updating: Boolean,
): String =
    stringResource(
        when {
            updating -> R.string.group_member_updating
            isAdmin -> R.string.admin
            else -> R.string.member
        },
    )

/** Material's two-line list item height, which the prototype's member rows use. */
private val MemberRowMinimumHeight = 72.dp
