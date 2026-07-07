package dev.ipf.whitenoise.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Search
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.core.RecipientSearch
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.ui.common.Avatar
import dev.ipf.whitenoise.android.ui.onboarding.PublicIdentifierFieldTrailingAction
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

@Composable
internal fun ContactRow(
    title: String,
    subtitle: String?,
    avatarSeed: String,
    avatarUrl: String?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .heightIn(min = 64.dp)
                .let {
                    if (onClick != null) {
                        it.combinedClickable(
                            enabled = enabled,
                            role = Role.Button,
                            onLongClick = onLongClick,
                            onClick = onClick,
                        )
                    } else {
                        it
                    }
                }.padding(horizontal = Dimens.spaceLg, vertical = Dimens.spaceSm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Dimens.spaceLg),
    ) {
        Avatar(title = title, seed = avatarSeed, size = 48.dp, pictureUrl = avatarUrl)
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
                )
            }
        }
        trailing?.invoke()
    }
}

@Composable
internal fun SelectedMemberRail(
    members: List<RecipientSearch.Candidate>,
    appState: WhiteNoiseAppState,
    onRemove: (RecipientSearch.Candidate) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyRow(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = Dimens.spaceLg, vertical = Dimens.spaceSm),
        horizontalArrangement = Arrangement.spacedBy(Dimens.spaceMd),
    ) {
        items(members, key = { it.accountIdHex }) { member ->
            val name = appState.displayName(member.accountIdHex)
            val removeLabel = stringResource(R.string.remove_member)
            Column(
                modifier =
                    Modifier
                        .width(64.dp)
                        .animateItem()
                        .clickable(role = Role.Button, onClickLabel = removeLabel) { onRemove(member) },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(Dimens.spaceXs),
            ) {
                Box {
                    Avatar(
                        title = name,
                        seed = member.accountIdHex,
                        size = 56.dp,
                        pictureUrl = appState.avatarUrl(member.accountIdHex),
                    )
                    Box(
                        modifier =
                            Modifier
                                .align(Alignment.TopEnd)
                                .size(20.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Text(
                    name,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
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
    enabled: Boolean = true,
    comingSoon: Boolean = false,
    inProgress: Boolean = false,
    iconTint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    titleColor: Color = Color.Unspecified,
    onClick: (() -> Unit)? = null,
) {
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .let { if (onClick != null && enabled) it.clickable(role = Role.Button, onClick = onClick) else it }
                .padding(horizontal = Dimens.spaceLg, vertical = Dimens.spaceSm)
                .alpha(if (enabled) 1f else 0.45f),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Dimens.spaceLg),
    ) {
        Icon(icon, contentDescription = null, tint = iconTint)
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
        }
        when {
            inProgress -> CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            comingSoon -> ComingSoonBadge()
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
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        }
        Text(title, style = MaterialTheme.typography.bodyLarge)
    }
}
