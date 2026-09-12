package dev.ipf.whitenoise.android.ui.account

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.ui.common.AccountActionColors
import dev.ipf.whitenoise.android.ui.common.Avatar
import dev.ipf.whitenoise.android.ui.common.ManualUnreadDot
import dev.ipf.whitenoise.android.ui.common.UnreadCountBadge
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseButton
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseListItemDefaults
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseSheetHeader
import dev.ipf.whitenoise.android.ui.settings.SettingsAction
import dev.ipf.whitenoise.android.ui.settings.SettingsGroup
import dev.ipf.whitenoise.android.ui.settings.SettingsRowContext
import dev.ipf.whitenoise.android.ui.settings.settingsRowBorder
import dev.ipf.whitenoise.android.ui.theme.isAmoledSurfaceTheme

private val PROFILE_SWITCHER_AVATAR_SIZE = 48.dp
private val PROFILE_SWITCHER_LIST_MAX_HEIGHT = 520.dp
private val PROFILE_SWITCHER_LOADING_MIN_HEIGHT = 120.dp
private val PROFILE_SWITCHER_CONTENT_INSET = 16.dp
private val PROFILE_SWITCHER_TRAILING_GAP = 12.dp
private val PROFILE_SWITCHER_ACTION_ICON_SIZE = 24.dp
private val PROFILE_SWITCHER_RELATED_GAP = 8.dp

/** Only presentation is active-first; stable native account order remains unchanged for M135 cycling. */
internal fun profileSwitcherPresentation(state: AccountSelectorState): List<AccountSelectorAccountState> =
    state.accounts.filter { it.isActive } + state.accounts.filterNot { it.isActive }

/** Shared sheet composition consumes native account snapshots and callbacks without deriving account eligibility. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Suppress("LongMethod", "LongParameterList", "FunctionNaming")
@Composable
internal fun ProfileSwitcherSheet(
    state: AccountSelectorState,
    displayName: (String) -> String,
    shortNpub: (String) -> String,
    avatarUrl: (String) -> String?,
    unreadCountForAccount: (String) -> ULong,
    onSelectProfile: (String) -> Unit,
    onAddProfile: () -> Unit,
    modifier: Modifier = Modifier,
    onDismiss: (() -> Unit)? = null,
    onSettings: (() -> Unit)? = null,
    enabled: Boolean = true,
    pendingLabel: String? = null,
    hasUnreadForAccount: (String) -> Boolean = { false },
    actionColorsForAccount: @Composable (String) -> AccountActionColors? = { null },
) {
    val profiles = profileSwitcherPresentation(state)
    Column(modifier.fillMaxWidth().testTag(ACCOUNT_SELECTOR_CONTENT_TAG)) {
        WhiteNoiseSheetHeader(stringResource(R.string.switch_profile), onClose = onDismiss)
        if (state.refreshing && profiles.isEmpty()) {
            Box(
                Modifier.fillMaxWidth().heightIn(min = PROFILE_SWITCHER_LOADING_MIN_HEIGHT),
                contentAlignment = Alignment.Center,
            ) {
                LoadingIndicator()
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f, fill = false).heightIn(max = PROFILE_SWITCHER_LIST_MAX_HEIGHT),
                contentPadding = PaddingValues(horizontal = PROFILE_SWITCHER_CONTENT_INSET),
                verticalArrangement = Arrangement.spacedBy(WhiteNoiseListItemDefaults.segmentedGap),
            ) {
                itemsIndexed(profiles, key = { _, account -> account.label }) { index, account ->
                    val count = unreadCountForAccount(account.label)
                    val shapes = WhiteNoiseListItemDefaults.segmentedShapes(index, profiles.size)
                    val rowContext =
                        SettingsRowContext(
                            shapes,
                            MaterialTheme.colorScheme.surfaceContainerLowest,
                            if (isAmoledSurfaceTheme()) Color.White else Color.Unspecified,
                        )
                    ListItem(
                        onClick = { onSelectProfile(account.label) },
                        enabled = enabled && pendingLabel != account.label,
                        shapes = shapes,
                        content = {
                            Text(displayName(account.accountIdHex), style = MaterialTheme.typography.titleMedium)
                        },
                        supportingContent = {
                            Column {
                                Text(shortNpub(account.accountIdHex), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                if (account.isReadOnly) Text(stringResource(R.string.read_only))
                                if (account.isSignedOut) Text(stringResource(R.string.signed_out))
                            }
                        },
                        leadingContent = {
                            Avatar(
                                displayName(account.accountIdHex),
                                account.accountIdHex,
                                size = PROFILE_SWITCHER_AVATAR_SIZE,
                                pictureUrl = avatarUrl(account.accountIdHex),
                            )
                        },
                        trailingContent = {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(PROFILE_SWITCHER_TRAILING_GAP),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                if (!account.isActive) {
                                    if (count > 0uL) {
                                        UnreadCountBadge(
                                            count,
                                            actionColors = actionColorsForAccount(account.label),
                                        )
                                    } else if (hasUnreadForAccount(account.label)) {
                                        ManualUnreadDot(
                                            actionColors = actionColorsForAccount(account.label),
                                        )
                                    }
                                }
                                if (account.isActive) {
                                    Icon(
                                        painterResource(R.drawable.ic_check),
                                        contentDescription = stringResource(R.string.active),
                                    )
                                }
                            }
                        },
                        colors =
                            ListItemDefaults.colors(
                                containerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
                            ),
                        modifier =
                            Modifier
                                .settingsRowBorder(rowContext, enabled && pendingLabel != account.label)
                                .testTag("profile_switcher.profile.${account.label}")
                                .semantics(mergeDescendants = true) { selected = account.isActive },
                    )
                }
            }
        }
        if (onSettings != null) {
            SettingsGroup(Modifier.padding(vertical = PROFILE_SWITCHER_CONTENT_INSET)) {
                row("add_profile") { context ->
                    SettingsAction(
                        context,
                        stringResource(R.string.add_profile),
                        onAddProfile,
                        modifier = Modifier.testTag("profile_switcher.add_profile"),
                        enabled = enabled,
                        leading = { Icon(painterResource(R.drawable.ic_settings_person_add), null) },
                    )
                }
                row("settings") { context ->
                    SettingsAction(
                        context,
                        stringResource(R.string.settings),
                        onSettings,
                        modifier = Modifier.testTag("profile_switcher.settings"),
                        enabled = enabled,
                        leading = { Icon(painterResource(R.drawable.ic_emoji_settings), null) },
                    )
                }
            }
        } else {
            WhiteNoiseButton(
                onClick = onAddProfile,
                enabled = enabled,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(PROFILE_SWITCHER_CONTENT_INSET)
                        .testTag("profile_switcher.add_profile"),
            ) {
                Icon(
                    painterResource(R.drawable.ic_settings_person_add),
                    null,
                    Modifier.size(PROFILE_SWITCHER_ACTION_ICON_SIZE),
                )
                Spacer(Modifier.width(PROFILE_SWITCHER_RELATED_GAP))
                Text(stringResource(R.string.add_profile))
            }
        }
    }
}
