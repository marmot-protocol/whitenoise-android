package dev.ipf.whitenoise.android.ui.account

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.core.ProfileSanitizer
import dev.ipf.whitenoise.android.state.MAX_TOP_BAR_OTHER_ACCOUNTS
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.state.otherAccountAvatars
import dev.ipf.whitenoise.android.ui.common.Avatar
import dev.ipf.whitenoise.android.ui.common.accountActionColors

@Composable
fun AccountAvatarButton(
    title: String,
    seed: String,
    pictureUrl: String?,
    size: Dp = 40.dp,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    // Per-account unread dot (#592, #805): a small accent dot on the active
    // account's avatar when that account itself has unread — the same
    // per-account aggregate the other-account avatars use. Hidden when the
    // active account has no unread (the caller passes false).
    showUnreadDot: Boolean = false,
    unreadDotColor: Color? = null,
) {
    val openSettingsDescription = stringResource(R.string.open_settings)
    val accountUnreadDescription =
        stringResource(R.string.account_unread_indicator)
    val safePictureUrl = ProfileSanitizer.protocolImageUrl(pictureUrl)
    val avatarContentDescription =
        if (showUnreadDot) {
            "$openSettingsDescription, $accountUnreadDescription"
        } else {
            openSettingsDescription
        }
    IconButton(
        onClick = onClick,
        modifier =
            modifier
                .size(56.dp)
                .semantics { contentDescription = avatarContentDescription },
    ) {
        Box {
            Avatar(
                title = title,
                seed = seed,
                size = size,
                pictureUrl = safePictureUrl,
            )
            if (showUnreadDot) {
                Box(
                    modifier =
                        Modifier
                            .align(Alignment.BottomEnd)
                            .offset(x = 1.dp, y = 1.dp)
                            .size(12.dp)
                            // Border in the bar background so the dot reads as
                            // a separate marker against a busy avatar.
                            .border(2.dp, MaterialTheme.colorScheme.surface, CircleShape)
                            .clip(CircleShape)
                            .background(unreadDotColor ?: MaterialTheme.colorScheme.primary),
                )
            }
        }
    }
}

private val TOP_BAR_OTHER_ACCOUNT_SIZE = 34.dp

private val TOP_BAR_OTHER_ACCOUNT_RING = 2.dp

// How far each avatar overlaps the previous one to read as a single stacked group.
private val TOP_BAR_OTHER_ACCOUNT_OVERLAP = 12.dp

private val TOP_BAR_OTHER_ACCOUNT_UNREAD_DOT_SIZE = 10.dp

internal const val OTHER_ACCOUNT_AVATAR_TAG_PREFIX = "other-account-avatar-"

internal const val OTHER_ACCOUNT_UNREAD_DOT_TAG_PREFIX = "other-account-unread-dot-"

internal fun otherAccountAvatarTag(accountLabel: String): String = "$OTHER_ACCOUNT_AVATAR_TAG_PREFIX$accountLabel"

internal fun otherAccountUnreadDotTag(accountLabel: String): String = OTHER_ACCOUNT_UNREAD_DOT_TAG_PREFIX + accountLabel

// Other signed-in accounts, stacked beside the active-account avatar (#343): tap
// to switch (lands on that account's chat list), long-press for the full
// switcher, each carrying its own unread dot. Hidden when the active account is
// the only one signed in.
@Composable
internal fun OtherAccountAvatarsRow(
    appState: WhiteNoiseAppState,
    onSwitchAccount: (String) -> Unit,
    onOpenSwitcher: () -> Unit,
) {
    // Signed-in accounts other than the active one. Empty while a destructive
    // wipe transiently nulls the active account, so no frame can flash the
    // just-wiped (or a still-stale previously-wiped) account (#809).
    val others = otherAccountAvatars(appState.accounts, appState.activeAccount?.label)
    if (others.isEmpty()) return
    val shown = others.take(MAX_TOP_BAR_OTHER_ACCOUNTS)
    val overflow = others.size - shown.size
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(-TOP_BAR_OTHER_ACCOUNT_OVERLAP),
    ) {
        shown.forEach { account ->
            OtherAccountAvatar(
                accountLabel = account.label,
                title = appState.displayName(account.accountIdHex),
                seed = account.accountIdHex,
                pictureUrl = appState.avatarUrl(account.accountIdHex),
                showUnreadDot = appState.accountShowsUnreadDot(account.label),
                unreadDotColor = accountActionColors(appState, account.label).container,
                onClick = { onSwitchAccount(account.label) },
                onLongClick = onOpenSwitcher,
            )
        }
        if (overflow > 0) {
            OverflowAccountChip(count = overflow, onClick = onOpenSwitcher)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun OtherAccountAvatar(
    accountLabel: String,
    title: String,
    seed: String,
    pictureUrl: String?,
    showUnreadDot: Boolean,
    unreadDotColor: Color,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val switchDescription = "${stringResource(R.string.switch_account)}: $title"
    val accountUnreadDescription = stringResource(R.string.account_unread_indicator)
    val avatarContentDescription =
        if (showUnreadDot) {
            "$switchDescription, $accountUnreadDescription"
        } else {
            switchDescription
        }
    Box(
        modifier =
            Modifier
                .testTag(otherAccountAvatarTag(accountLabel))
                .size(TOP_BAR_OTHER_ACCOUNT_SIZE),
    ) {
        Box(
            modifier =
                Modifier
                    .matchParentSize()
                    // Ring in the bar background so stacked avatars read as separate.
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surface)
                    .combinedClickable(onClick = onClick, onLongClick = onLongClick)
                    .semantics { contentDescription = avatarContentDescription },
            contentAlignment = Alignment.Center,
        ) {
            Avatar(
                title = title,
                seed = seed,
                size = TOP_BAR_OTHER_ACCOUNT_SIZE - TOP_BAR_OTHER_ACCOUNT_RING * 2,
                pictureUrl = pictureUrl,
            )
        }
        if (showUnreadDot) {
            // Bottom-center sits in each avatar's exposed strip between stacked
            // neighbors so the full marker stays on its owner in LTR and RTL.
            Box(
                modifier =
                    Modifier
                        .testTag(otherAccountUnreadDotTag(accountLabel))
                        .align(Alignment.BottomCenter)
                        .size(TOP_BAR_OTHER_ACCOUNT_UNREAD_DOT_SIZE)
                        .border(TOP_BAR_OTHER_ACCOUNT_RING, MaterialTheme.colorScheme.surface, CircleShape)
                        .clip(CircleShape)
                        .background(unreadDotColor),
            )
        }
    }
}

@Composable
private fun OverflowAccountChip(
    count: Int,
    onClick: () -> Unit,
) {
    val description = stringResource(R.string.switch_account)
    Box(
        modifier =
            Modifier
                .size(TOP_BAR_OTHER_ACCOUNT_SIZE)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surface)
                .clickable(onClick = onClick)
                .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier =
                Modifier
                    .size(TOP_BAR_OTHER_ACCOUNT_SIZE - TOP_BAR_OTHER_ACCOUNT_RING * 2)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                "+$count",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
