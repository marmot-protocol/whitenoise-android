package dev.ipf.whitenoise.android.ui.chats.newchat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseCompactSearchField
import dev.ipf.whitenoise.android.ui.icons.Icons
import dev.ipf.whitenoise.android.ui.icons.filled.CheckCircle
import dev.ipf.whitenoise.android.ui.icons.filled.RadioButtonUnchecked
import dev.ipf.whitenoise.android.ui.onboarding.PublicIdentifierFieldTrailingAction
import dev.ipf.whitenoise.android.ui.testing.PerformanceTestTags
import dev.ipf.whitenoise.android.ui.testing.performanceTestTag
import dev.ipf.whitenoise.android.ui.theme.Dimens
import dev.ipf.whitenoise.android.ui.theme.PillShape
import dev.ipf.whitenoise.android.ui.theme.amoledOutlineBorder

/** The new-chat flows' search field. */
@Composable
internal fun FlowSearchField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
    onScanQr: (() -> Unit)? = null,
    clipboardInput: ((String?) -> String?)? = null,
) {
    WhiteNoiseCompactSearchField(
        value = value,
        onValueChange = onValueChange,
        placeholder = placeholder,
        outlined = true,
        keyboardOptions = RecipientSearchKeyboardOptions,
        clearDescription = stringResource(R.string.clear),
        emptyTrailingIcon = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                PublicIdentifierFieldTrailingAction(
                    value = value,
                    clipboardInput = clipboardInput,
                    onValueChange = onValueChange,
                )
                if (onScanQr != null) {
                    IconButton(onClick = onScanQr) {
                        Icon(
                            painterResource(R.drawable.ic_qr_code_scanner),
                            contentDescription = stringResource(R.string.scan_qr_code),
                        )
                    }
                }
            }
        },
        modifier =
            modifier
                .let { if (focusRequester != null) it.focusRequester(focusRequester) else it }
                .fillMaxWidth(),
    )
}

@Composable
internal fun SelectionIndicator(
    selected: Boolean,
    modifier: Modifier = Modifier,
    dimmed: Boolean = false,
) {
    Icon(
        if (selected) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
        contentDescription = null,
        tint =
            when {
                selected -> MaterialTheme.colorScheme.primary
                dimmed -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            },
        modifier = modifier,
    )
}

@Composable
internal fun ResolvingContactRow(modifier: Modifier = Modifier) {
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .heightIn(min = 64.dp)
                .padding(horizontal = Dimens.spaceLg, vertical = Dimens.spaceSm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Dimens.spaceLg),
    ) {
        CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
        Text(
            stringResource(R.string.recipient_preview_resolving),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * One standalone action row on the group surface: leading glyph (or progress), title, optional value and
 * supporting text. Disabled rows keep a button semantic and explain themselves through [disabledReason].
 */
@Suppress("LongParameterList", "LongMethod", "FunctionNaming")
@Composable
internal fun SettingsActionRow(
    icon: ImageVector,
    title: String,
    modifier: Modifier = Modifier,
    value: String? = null,
    supportingText: String? = null,
    enabled: Boolean = true,
    comingSoon: Boolean = false,
    inProgress: Boolean = false,
    iconTint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    titleColor: Color = Color.Unspecified,
    disabledReason: String? = null,
    onClick: (() -> Unit)? = null,
) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = Dimens.spaceLg),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        border = amoledOutlineBorder(enabled),
    ) {
        Row(
            modifier =
                modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp)
                    .then(
                        if (onClick == null) {
                            Modifier
                        } else {
                            Modifier.clickable(enabled = enabled, role = Role.Button, onClick = onClick)
                        },
                    ).then(
                        if (!enabled && disabledReason != null) {
                            Modifier.semantics { stateDescription = disabledReason }
                        } else {
                            Modifier
                        },
                    ).padding(horizontal = Dimens.spaceLg, vertical = Dimens.spaceSm)
                    .alpha(if (enabled) 1f else 0.45f),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Dimens.spaceLg),
        ) {
            Box(
                modifier = Modifier.size(24.dp),
                contentAlignment = Alignment.Center,
            ) {
                if (inProgress) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    Icon(icon, contentDescription = null, tint = iconTint)
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Dimens.spaceXxs)) {
                Text(title, style = MaterialTheme.typography.bodyLarge, color = titleColor)
                if (value != null) {
                    Text(
                        value,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (supportingText != null) {
                    Text(
                        supportingText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (comingSoon && !inProgress) {
                ComingSoonBadge()
            }
        }
    }
}

@Composable
internal fun DangerActionRow(
    icon: ImageVector,
    title: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    inProgress: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    SettingsActionRow(
        icon = icon,
        title = title,
        modifier = modifier,
        enabled = enabled,
        inProgress = inProgress,
        iconTint = MaterialTheme.colorScheme.error,
        titleColor = MaterialTheme.colorScheme.error,
        onClick = onClick,
    )
}

@Composable
internal fun ComingSoonBadge(modifier: Modifier = Modifier) {
    Surface(
        shape = PillShape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = modifier,
    ) {
        Text(
            stringResource(R.string.coming_soon),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = Dimens.spaceSm, vertical = Dimens.spaceXxs),
        )
    }
}

@Composable
internal fun QuickActionButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    inProgress: Boolean = false,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Dimens.spaceXs),
    ) {
        FilledTonalIconButton(
            onClick = onClick,
            enabled = enabled && !inProgress,
            // The icon is decorative, so the button carries the visible caption; the caption itself is
            // skipped by the screen reader to keep one stop per action.
            modifier = Modifier.size(52.dp).semantics { contentDescription = label },
        ) {
            if (inProgress) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            } else {
                Icon(icon, contentDescription = null)
            }
        }
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color =
                if (enabled) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
                },
            maxLines = 1,
            modifier = Modifier.semantics { hideFromAccessibility() },
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
internal fun FlowQuickActionRow(
    icon: ImageVector,
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    iconContentDescription: String? = null,
    enabled: Boolean = true,
) {
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
                .padding(horizontal = Dimens.spaceLg, vertical = Dimens.spaceXs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Dimens.spaceLg),
    ) {
        Box(
            modifier =
                Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = iconContentDescription, tint = MaterialTheme.colorScheme.primary)
        }
        Text(
            title,
            style = MaterialTheme.typography.bodyLarge,
        )
    }
}

@Composable
@Suppress("FunctionNaming")
internal fun NewMessageQuickActions(
    query: String,
    showMyQrLabel: String,
    showMyQrEnabled: Boolean,
    onNewGroup: () -> Unit,
    onScanQr: () -> Unit,
    onShowMyQr: () -> Unit,
    onInviteFriends: () -> Unit,
) {
    if (query.isNotBlank()) return
    FlowQuickActionRow(
        icon = ImageVector.vectorResource(R.drawable.ic_group_add),
        title = stringResource(R.string.new_group),
        onClick = onNewGroup,
        modifier = Modifier.performanceTestTag(PerformanceTestTags.NEW_GROUP),
    )
    FlowQuickActionRow(
        icon = ImageVector.vectorResource(R.drawable.ic_share),
        title = stringResource(R.string.invite_friends),
        onClick = onInviteFriends,
    )
    FlowQuickActionRow(
        icon = ImageVector.vectorResource(R.drawable.ic_qr_code_scanner),
        title = stringResource(R.string.scan_qr_code),
        onClick = onScanQr,
    )
    FlowQuickActionRow(
        icon = ImageVector.vectorResource(R.drawable.ic_settings_qr_code_2),
        title = showMyQrLabel,
        onClick = onShowMyQr,
        enabled = showMyQrEnabled,
    )
}
