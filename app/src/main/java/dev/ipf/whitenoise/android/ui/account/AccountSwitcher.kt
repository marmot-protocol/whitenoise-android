package dev.ipf.whitenoise.android.ui.account

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.core.ProfileSanitizer
import dev.ipf.whitenoise.android.state.MAX_TOP_BAR_OTHER_ACCOUNTS
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.state.otherAccountAvatars
import dev.ipf.whitenoise.android.ui.common.Avatar
import dev.ipf.whitenoise.android.ui.common.accountActionColors

internal const val ACTIVE_ACCOUNT_UNREAD_DOT_TAG = "active-account-unread-dot"

/** Avatar that acts as the account button, with an optional unread dot in the account colour. */
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
    actionDescription: String? = null,
    touchTargetSize: Dp = 56.dp,
) {
    val openSettingsDescription = actionDescription ?: stringResource(R.string.open_settings)
    val accountUnreadDescription =
        stringResource(R.string.account_unread_indicator)
    val safePictureUrl = ProfileSanitizer.protocolImageUrl(pictureUrl)
    val avatarContentDescription =
        if (showUnreadDot) {
            "$openSettingsDescription, $title, $accountUnreadDescription"
        } else {
            "$openSettingsDescription, $title"
        }
    IconButton(
        onClick = onClick,
        modifier =
            modifier
                .size(touchTargetSize)
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
                            // Keep the complete marker inside IconButton's circular clip.
                            .offset(x = (-2).dp, y = (-2).dp)
                            .size(12.dp)
                            .testTag(ACTIVE_ACCOUNT_UNREAD_DOT_TAG)
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

// Each quick-switch target owns a Material-minimum slot. The avatar is centred
// in it, so the slack between two adjacent circles becomes visible separation
// instead of the overlap the stack used to draw (#2796).
private val TOP_BAR_OTHER_ACCOUNT_SLOT_WIDTH = 48.dp

// Top-bar chrome the quick-switch slots may never crowd: the active account
// button and its gap, the trailing action icons, and a readable title.
private const val TOP_BAR_QUICK_SWITCH_RESERVED_DP = 208f

private val TOP_BAR_OTHER_ACCOUNT_UNREAD_DOT_SIZE = 10.dp

internal const val OTHER_ACCOUNT_AVATAR_TAG_PREFIX = "other-account-avatar-"

internal const val OTHER_ACCOUNT_STACK_TAG = "other-account-stack"

internal const val OTHER_ACCOUNT_OVERFLOW_TAG = "other-account-overflow"

internal const val OTHER_ACCOUNT_UNREAD_DOT_TAG_PREFIX = "other-account-unread-dot-"

internal fun otherAccountAvatarTag(accountLabel: String): String = "$OTHER_ACCOUNT_AVATAR_TAG_PREFIX$accountLabel"

internal fun otherAccountUnreadDotTag(accountLabel: String): String = OTHER_ACCOUNT_UNREAD_DOT_TAG_PREFIX + accountLabel

/**
 * The quick-switch target under [positionX], or null outside the stack.
 *
 * Every target now occupies one equal-width slot, so the row divides into
 * [targetCount] non-overlapping slices and each avatar sits wholly inside its
 * own slice: a tap on an avatar's centre or either edge can only reach that
 * account. Routing stays parent-owned, as PR #2217 established.
 */
internal fun accountStackTargetIndex(
    positionX: Float,
    width: Float,
    targetCount: Int,
    layoutDirection: LayoutDirection,
): Int? {
    if (targetCount <= 0 || width <= 0f || positionX !in 0f..width) return null
    val sliceWidth = width / targetCount
    val visualIndex = (positionX / sliceWidth).toInt().coerceIn(0, targetCount - 1)
    return if (layoutDirection == LayoutDirection.Ltr) visualIndex else targetCount - 1 - visualIndex
}

/**
 * Quick-switch slots — avatars plus any overflow chip — that fit beside the
 * rest of the top bar at [barWidthDp]. Always leaves at least one slot so the
 * overflow chip can still reach the full switcher on the narrowest window.
 */
internal fun quickSwitchSlotCapacity(barWidthDp: Float): Int {
    val available = barWidthDp - TOP_BAR_QUICK_SWITCH_RESERVED_DP
    return (available / TOP_BAR_OTHER_ACCOUNT_SLOT_WIDTH.value)
        .toInt()
        .coerceIn(1, MAX_TOP_BAR_OTHER_ACCOUNTS + 1)
}

/**
 * Avatars rendered before the remaining accounts collapse into the overflow
 * chip. A bar too narrow for every account gives up quick targets rather than
 * compressing them, because the chip opens the full switcher instead.
 */
internal fun quickSwitchVisibleAvatarCount(
    slotCapacity: Int,
    otherAccountCount: Int,
): Int {
    val capped = minOf(otherAccountCount, MAX_TOP_BAR_OTHER_ACCOUNTS).coerceAtLeast(0)
    val everyAccountFits = capped == otherAccountCount && capped <= slotCapacity
    return if (everyAccountFits) capped else minOf(capped, slotCapacity - 1).coerceAtLeast(0)
}

// Other signed-in accounts, stacked beside the active-account avatar (#343): tap
// to switch (lands on that account's chat list), long-press for the full
// switcher, each carrying its own unread dot. Hidden when the active account is
// the only one signed in.
@Suppress("FunctionNaming", "LongMethod")
@Composable
internal fun OtherAccountAvatarsRow(
    appState: WhiteNoiseAppState,
    onSwitchAccount: (String) -> Unit,
    onOpenSwitcher: () -> Unit,
    // The top bar's own measured width, not the full window: on a multi-pane layout this bar
    // can be narrower than the window, and reserving space against the wrong width can crowd
    // the rest of the bar (#2796 follow-up). Callers measure this with BoxWithConstraints.
    barWidthDp: Dp,
) {
    // Signed-in accounts other than the active one. Empty while a destructive
    // wipe transiently nulls the active account, so no frame can flash the
    // just-wiped (or a still-stale previously-wiped) account (#809).
    val others = otherAccountAvatars(appState.accounts, appState.activeAccount?.label)
    if (others.isEmpty()) return
    val slotCapacity = quickSwitchSlotCapacity(barWidthDp.value)
    val shown = others.take(quickSwitchVisibleAvatarCount(slotCapacity, others.size))
    val overflow = others.size - shown.size
    val layoutDirection = LocalLayoutDirection.current
    val currentOnSwitchAccount by rememberUpdatedState(onSwitchAccount)
    val currentOnOpenSwitcher by rememberUpdatedState(onOpenSwitcher)
    val switchAccountLabel = stringResource(R.string.switch_account)
    val unreadLabel = stringResource(R.string.account_unread_indicator)
    val actionLabels =
        shown.map { account ->
            val title = appState.accountDisplayNameCached(account.accountIdHex)
            buildString {
                append(switchAccountLabel)
                append(": ")
                append(title)
                if (appState.accountShowsUnreadDot(account.label)) {
                    append(", ")
                    append(unreadLabel)
                }
            }
        }
    val targetCount = shown.size + if (overflow > 0) 1 else 0
    Row(
        modifier =
            Modifier
                .testTag(OTHER_ACCOUNT_STACK_TAG)
                .heightIn(min = 48.dp)
                .pointerInput(shown.map { it.label }, overflow, layoutDirection) {
                    detectTapGestures(
                        onTap = { position ->
                            val target =
                                accountStackTargetIndex(
                                    positionX = position.x,
                                    width = size.width.toFloat(),
                                    targetCount = targetCount,
                                    layoutDirection = layoutDirection,
                                )
                            when {
                                target == null -> Unit
                                target in shown.indices -> currentOnSwitchAccount(shown[target].label)
                                target == shown.size -> currentOnOpenSwitcher()
                            }
                        },
                        onLongPress = { currentOnOpenSwitcher() },
                    )
                }.semantics(mergeDescendants = true) {
                    contentDescription = actionLabels.joinToString()
                    onClick(label = switchAccountLabel) {
                        onOpenSwitcher()
                        true
                    }
                    onLongClick(label = switchAccountLabel) {
                        onOpenSwitcher()
                        true
                    }
                    customActions =
                        shown.mapIndexed { index, account ->
                            CustomAccessibilityAction(actionLabels[index]) {
                                onSwitchAccount(account.label)
                                true
                            }
                        } +
                        if (overflow > 0) {
                            listOf(
                                CustomAccessibilityAction("$switchAccountLabel: +$overflow") {
                                    onOpenSwitcher()
                                    true
                                },
                            )
                        } else {
                            emptyList()
                        }
                },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        shown.forEach { account ->
            OtherAccountAvatar(
                accountLabel = account.label,
                title = appState.accountDisplayNameCached(account.accountIdHex),
                seed = account.accountIdHex,
                pictureUrl = appState.avatarUrl(account.accountIdHex),
                showUnreadDot = appState.accountShowsUnreadDot(account.label),
                unreadDotColor = accountActionColors(appState, account.label).container,
            )
        }
        if (overflow > 0) {
            OverflowAccountChip(count = overflow)
        }
    }
}

@Suppress("FunctionNaming")
@Composable
private fun OtherAccountAvatar(
    accountLabel: String,
    title: String,
    seed: String,
    pictureUrl: String?,
    showUnreadDot: Boolean,
    unreadDotColor: Color,
) {
    // The Material-width slot is the account's own tap slice; centring the
    // smaller circle in it leaves visible air on both sides of every neighbour.
    Box(
        modifier = Modifier.width(TOP_BAR_OTHER_ACCOUNT_SLOT_WIDTH),
        contentAlignment = Alignment.Center,
    ) {
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
                        // Ring in the bar background so each avatar keeps a clean edge.
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surface),
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
                // Bottom-center keeps the whole marker on its own avatar, and so
                // inside that account's slice, in both LTR and RTL.
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
}

/** Remaining accounts collapse into one chip that owns the same slot width as an avatar. */
@Composable
private fun OverflowAccountChip(count: Int) {
    Box(
        modifier =
            Modifier
                .width(TOP_BAR_OTHER_ACCOUNT_SLOT_WIDTH)
                .testTag(OTHER_ACCOUNT_OVERFLOW_TAG),
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
