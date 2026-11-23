package dev.ipf.whitenoise.android.ui.group

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.ui.chats.chatsExplanation
import dev.ipf.whitenoise.android.ui.common.Explanation
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseListItemDefaults
import dev.ipf.whitenoise.android.ui.common.explainOnLongPress
import dev.ipf.whitenoise.android.ui.settings.SettingsRowContext
import dev.ipf.whitenoise.android.ui.settings.rememberMeasuredControlClick
import dev.ipf.whitenoise.android.ui.settings.rememberSettingsNativeRow
import dev.ipf.whitenoise.android.ui.theme.ConnectedRowShape
import dev.ipf.whitenoise.android.ui.theme.amoledOutlineBorder
import dev.ipf.whitenoise.android.ui.theme.connectedRowBorder

/** Equal-width overview action with one accessible tonal target and an optional visual caption. */
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
    showCaption: Boolean = true,
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
        if (showCaption) {
            Text(
                text = label,
                modifier = Modifier.clearAndSetSemantics {},
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.labelLarge,
            )
        }
    }
}

/**
 * Overview action row under the chat header. Adding the group Add Members action makes four
 * targets, so that row drops its captions; long-press and accessibility labels still name each action.
 */
@Suppress("FunctionNaming", "LongParameterList")
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
    val showCaptions = onAddMembers == null
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (onOpenProfile != null) {
            val profileLaunch = rememberMeasuredControlClick(onOpenProfile)
            QuickInfoAction(
                label = stringResource(R.string.about),
                icon = R.drawable.ic_person,
                onClick = profileLaunch.second,
                modifier = Modifier.weight(1f),
                buttonModifier = profileLaunch.first,
            )
        }
        if (onAddMembers != null) {
            QuickInfoAction(
                label = stringResource(R.string.add_member),
                icon = R.drawable.ic_group_add,
                onClick = onAddMembers,
                enabled = addMembersEnabled,
                explanation = chatsExplanation(R.string.add_member, R.string.explanation_chat_info_add_members),
                showCaption = showCaptions,
                modifier = Modifier.weight(1f),
            )
        }
        QuickInfoAction(
            label = stringResource(muteControl.labelRes),
            icon = muteControl.iconRes,
            enabled = !muteControl.pending,
            explanation = chatsExplanation(muteControl.labelRes, muteControl.explanationRes),
            onClick = muteControl::toggle,
            showCaption = showCaptions,
            modifier = Modifier.weight(1f),
        )
        QuickInfoAction(
            label = stringResource(R.string.disappearing),
            icon = R.drawable.ic_timer,
            state = disappearingState,
            enabled = disappearingEnabled,
            explanation = chatsExplanation(R.string.disappearing, R.string.explanation_chat_info_disappearing),
            onClick = onOpenDisappearing,
            showCaption = showCaptions,
            modifier = Modifier.weight(1f),
        )
        if (onOpenSearch != null) {
            QuickInfoAction(
                label = stringResource(R.string.quick_action_search),
                icon = R.drawable.ic_search,
                onClick = onOpenSearch,
                showCaption = showCaptions,
                modifier = Modifier.weight(1f),
            )
        }
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
