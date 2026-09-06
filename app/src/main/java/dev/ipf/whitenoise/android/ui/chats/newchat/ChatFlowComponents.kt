package dev.ipf.whitenoise.android.ui.chats.newchat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.ui.common.Avatar
import dev.ipf.whitenoise.android.ui.onboarding.PublicIdentifierFieldTrailingAction
import dev.ipf.whitenoise.android.ui.testing.PerformanceTestTags
import dev.ipf.whitenoise.android.ui.testing.performanceTestTag
import dev.ipf.whitenoise.android.ui.theme.Dimens
import dev.ipf.whitenoise.android.ui.theme.PillShape
import dev.ipf.whitenoise.android.ui.theme.amoledSurfaceBorder

@Composable
internal fun FlowSearchField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
    onScanQr: (() -> Unit)? = null,
) {
    val shape = RoundedCornerShape(28.dp)
    TextField(
        value = value,
        onValueChange = onValueChange,
        placeholder = { Text(placeholder, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
        trailingIcon = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                PublicIdentifierFieldTrailingAction(
                    value = value,
                    onValueChange = onValueChange,
                )
                if (value.isEmpty() && onScanQr != null) {
                    IconButton(onClick = onScanQr) {
                        Icon(Icons.Default.QrCodeScanner, contentDescription = stringResource(R.string.scan_qr_code))
                    }
                }
            }
        },
        singleLine = true,
        shape = shape,
        colors =
            TextFieldDefaults.colors(
                focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
                disabledIndicatorColor = Color.Transparent,
            ),
        keyboardOptions =
            KeyboardOptions(
                capitalization = KeyboardCapitalization.None,
                autoCorrectEnabled = false,
                imeAction = ImeAction.Search,
            ),
        modifier =
            modifier
                .let { if (focusRequester != null) it.focusRequester(focusRequester) else it }
                .fillMaxWidth()
                .amoledSurfaceBorder(shape),
    )
}

/**
 * Renders a reusable person row while keeping relationship and selection states independent.
 *
 * [isFollowed] adds the followed-person avatar badge and exports the localized `You follow`
 * state once from the row. [selectionState] remains separate so multi-select pickers can expose
 * selected and followed at the same time without making the badge look like a selection control.
 */
@Composable
@Suppress("FunctionNaming", "LongMethod")
internal fun ContactRow(
    title: String,
    subtitle: String?,
    avatarSeed: String,
    avatarUrl: String?,
    avatarImage: ImageBitmap? = null,
    isFollowed: Boolean = false,
    selectionState: Boolean? = null,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    onSubtitleClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val copyLabel = stringResource(R.string.copy)
    val followedStateDescription =
        if (isFollowed) {
            stringResource(R.string.user_search_you_follow)
        } else {
            null
        }
    val rowRole = if (selectionState == null) Role.Button else Role.Checkbox
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .heightIn(min = 64.dp)
                .let {
                    if (onClick != null && onLongClick != null) {
                        it.combinedClickable(
                            enabled = enabled,
                            role = rowRole,
                            onLongClick = onLongClick,
                            onClick = onClick,
                        )
                    } else if (onClick != null) {
                        it.clickable(
                            enabled = enabled,
                            role = rowRole,
                            onClick = onClick,
                        )
                    } else {
                        it
                    }
                }.recipientRelationshipSemantics(followedStateDescription, selectionState)
                .padding(horizontal = Dimens.spaceLg, vertical = Dimens.spaceSm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Dimens.spaceLg),
    ) {
        Box(modifier = Modifier.size(48.dp)) {
            Avatar(
                title = title,
                seed = avatarSeed,
                size = 48.dp,
                pictureUrl = avatarUrl?.takeIf { avatarImage == null },
                picture = avatarImage,
            )
            if (isFollowed) {
                FollowedPersonBadge(modifier = Modifier.align(Alignment.BottomEnd))
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Dimens.spaceXxs)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier =
                        if (followedStateDescription != null && subtitle == followedStateDescription) {
                            Modifier.clearAndSetSemantics { }
                        } else if (onSubtitleClick == null) {
                            Modifier
                        } else {
                            Modifier
                                .fillMaxWidth()
                                .clickable(
                                    enabled = enabled,
                                    onClickLabel = copyLabel,
                                    role = Role.Button,
                                    onClick = onSubtitleClick,
                                )
                        },
                )
            }
        }
        trailing?.invoke()
    }
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

@Composable
internal fun SectionHeader(
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier =
            modifier
                .fillMaxWidth()
                .padding(start = Dimens.spaceLg, end = Dimens.spaceLg, top = Dimens.spaceLg, bottom = Dimens.spaceSm),
    )
}

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
            modifier = Modifier.size(52.dp),
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
        Text(title, style = MaterialTheme.typography.bodyLarge)
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
        icon = Icons.Default.Group,
        title = stringResource(R.string.new_group),
        onClick = onNewGroup,
        modifier = Modifier.performanceTestTag(PerformanceTestTags.NEW_GROUP),
    )
    FlowQuickActionRow(
        icon = Icons.Default.Share,
        title = stringResource(R.string.invite_friends),
        onClick = onInviteFriends,
    )
    FlowQuickActionRow(
        icon = Icons.Default.QrCodeScanner,
        title = stringResource(R.string.scan_qr_code),
        onClick = onScanQr,
    )
    FlowQuickActionRow(
        icon = Icons.Default.QrCode,
        title = showMyQrLabel,
        onClick = onShowMyQr,
        enabled = showMyQrEnabled,
    )
}
