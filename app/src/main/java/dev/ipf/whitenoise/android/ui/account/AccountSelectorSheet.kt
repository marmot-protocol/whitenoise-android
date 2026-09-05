package dev.ipf.whitenoise.android.ui.account

import android.content.ClipData
import android.util.Log
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.key
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.ipf.marmotkit.AccountSummaryFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.core.ProfileSanitizer
import dev.ipf.whitenoise.android.state.AccountSwitchPreloadPolicy
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.ui.common.AccountActionColors
import dev.ipf.whitenoise.android.ui.common.AppDivider
import dev.ipf.whitenoise.android.ui.common.Avatar
import dev.ipf.whitenoise.android.ui.common.UnreadCountBadge
import dev.ipf.whitenoise.android.ui.common.accountActionColors
import dev.ipf.whitenoise.android.ui.profile.AvatarFullScreenViewer
import dev.ipf.whitenoise.android.ui.profile.rememberAvatarImageAvailable
import dev.ipf.whitenoise.android.ui.settings.settingsRowAmoledSurfaceBorder
import dev.ipf.whitenoise.android.ui.theme.amoledSheetContainerColor
import dev.ipf.whitenoise.android.ui.theme.amoledSurfaceBorder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
fun SettingsAccountHeader(
    title: String,
    subtitle: String,
    seed: String,
    pictureUrl: String?,
    onOpenAccountSelector: () -> Unit,
    onOpenQr: () -> Unit,
    onEditProfilePicture: () -> Unit = {},
) {
    val protocolPictureUrl = ProfileSanitizer.protocolImageUrl(pictureUrl)
    val avatarImageAvailable = rememberAvatarImageAvailable(protocolPictureUrl)
    var viewerOpen by remember(protocolPictureUrl) { mutableStateOf(false) }
    val presentation =
        SettingsAccountHeaderPresentation(
            title = title,
            subtitle = subtitle,
            seed = seed,
            pictureUrl = protocolPictureUrl,
            avatarImageAvailable = avatarImageAvailable,
        )
    SettingsAccountHeaderRow(
        presentation = presentation,
        onOpenAccountSelector = onOpenAccountSelector,
        onOpenQr = onOpenQr,
        onOpenAvatar = { viewerOpen = true },
    )
    if (viewerOpen && protocolPictureUrl != null && avatarImageAvailable) {
        AvatarFullScreenViewer(
            title = title,
            seed = seed,
            pictureUrl = protocolPictureUrl,
            onDismiss = { viewerOpen = false },
            editActionLabel = stringResource(R.string.profile_picture_edit),
            onEditPicture = {
                viewerOpen = false
                onEditProfilePicture()
            },
        )
    }
}

private data class SettingsAccountHeaderPresentation(
    val title: String,
    val subtitle: String,
    val seed: String,
    val pictureUrl: String?,
    val avatarImageAvailable: Boolean,
)

@Suppress("FunctionNaming")
@Composable
private fun SettingsAccountHeaderRow(
    presentation: SettingsAccountHeaderPresentation,
    onOpenAccountSelector: () -> Unit,
    onOpenQr: () -> Unit,
    onOpenAvatar: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .settingsRowAmoledSurfaceBorder()
                .testTag(SETTINGS_ACCOUNT_HEADER_TAG),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SettingsAccountSelectorTarget(
            presentation = presentation,
            onOpenAccountSelector = onOpenAccountSelector,
            onOpenAvatar = onOpenAvatar,
            modifier = Modifier.weight(1f),
        )
        IconButton(
            onClick = onOpenQr,
            modifier = Modifier.size(48.dp).testTag(SETTINGS_ACCOUNT_QR_TARGET_TAG),
        ) {
            Icon(Icons.Default.QrCode, contentDescription = stringResource(R.string.my_qr_code))
        }
    }
}

@Suppress("FunctionNaming")
@Composable
private fun SettingsAccountSelectorTarget(
    presentation: SettingsAccountHeaderPresentation,
    onOpenAccountSelector: () -> Unit,
    onOpenAvatar: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val switchAccountDescription = stringResource(R.string.switch_account)
    val clipboard = LocalClipboard.current
    val clipboardLabel = stringResource(R.string.public_key)
    val scope = rememberCoroutineScope()
    ListItem(
        modifier =
            modifier
                .combinedClickable(
                    onClickLabel = switchAccountDescription,
                    onLongClickLabel = stringResource(R.string.copy),
                    role = Role.Button,
                    onLongClick = {
                        scope.launch {
                            val clip = ClipData.newPlainText(clipboardLabel, presentation.subtitle)
                            clipboard.setClipEntry(ClipEntry(clip))
                        }
                    },
                    onClick = onOpenAccountSelector,
                ).semantics(mergeDescendants = true) {
                    contentDescription = switchAccountDescription
                    stateDescription = presentation.subtitle
                }.testTag(SETTINGS_ACCOUNT_SELECTOR_TARGET_TAG),
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        leadingContent = {
            SettingsAccountHeaderAvatar(presentation = presentation, onOpenAvatar = onOpenAvatar)
        },
        headlineContent = { SettingsAccountHeaderTitle(presentation.title) },
        supportingContent = { SettingsAccountHeaderNpub(presentation.subtitle) },
        trailingContent = {
            Icon(
                Icons.Default.ExpandMore,
                contentDescription = null,
                modifier = Modifier.testTag(SETTINGS_ACCOUNT_EXPAND_TAG),
            )
        },
    )
}

@Suppress("FunctionNaming")
@Composable
private fun SettingsAccountHeaderAvatar(
    presentation: SettingsAccountHeaderPresentation,
    onOpenAvatar: () -> Unit,
) {
    val clickModifier =
        if (presentation.avatarImageAvailable) {
            Modifier.clickable(
                onClickLabel = stringResource(R.string.profile_view_picture),
                role = Role.Button,
                onClick = onOpenAvatar,
            )
        } else {
            Modifier
        }
    Box(
        modifier =
            Modifier
                .clip(CircleShape)
                .then(clickModifier)
                .testTag(SETTINGS_ACCOUNT_AVATAR_TAG),
    ) {
        Avatar(
            title = presentation.title,
            seed = presentation.seed,
            size = 52.dp,
            pictureUrl = presentation.pictureUrl,
        )
    }
}

@Suppress("FunctionNaming")
@Composable
private fun SettingsAccountHeaderTitle(title: String) {
    Text(
        text = title,
        modifier = Modifier.testTag(SETTINGS_ACCOUNT_TITLE_TAG),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

@Suppress("FunctionNaming")
@Composable
private fun SettingsAccountHeaderNpub(npub: String) {
    Text(
        text = npub,
        modifier = Modifier.fillMaxWidth().testTag(SETTINGS_ACCOUNT_NPUB_TAG),
        fontFamily = FontFamily.Monospace,
        maxLines = 1,
        overflow = TextOverflow.MiddleEllipsis,
        softWrap = false,
    )
}

internal const val SETTINGS_ACCOUNT_HEADER_TAG = "settings-account-header"
internal const val SETTINGS_ACCOUNT_SELECTOR_TARGET_TAG = "settings-account-selector-target"
internal const val SETTINGS_ACCOUNT_AVATAR_TAG = "settings-account-avatar"
internal const val SETTINGS_ACCOUNT_TITLE_TAG = "settings-account-title"
internal const val SETTINGS_ACCOUNT_NPUB_TAG = "settings-account-npub"
internal const val SETTINGS_ACCOUNT_EXPAND_TAG = "settings-account-expand"
internal const val SETTINGS_ACCOUNT_QR_TARGET_TAG = "settings-account-qr-target"

internal const val ACCOUNT_SELECTOR_CONTENT_TAG = "account-selector-content"

internal data class AccountSelectorAccountState(
    val label: String,
    val accountIdHex: String,
    val isReadOnly: Boolean,
    val isSignedOut: Boolean,
    val isActive: Boolean,
)

internal data class AccountSelectorState(
    val accounts: List<AccountSelectorAccountState>,
    val refreshing: Boolean,
)

internal fun accountSelectorState(
    accounts: List<AccountSummaryFfi>,
    activeAccountRef: String?,
    refreshing: Boolean,
): AccountSelectorState =
    AccountSelectorState(
        accounts =
            accounts.map { account ->
                AccountSelectorAccountState(
                    label = account.label,
                    accountIdHex = account.accountIdHex,
                    isReadOnly = !account.localSigning && !account.externalSigning,
                    isSignedOut = account.signedOut,
                    isActive = account.label == activeAccountRef,
                )
            },
        refreshing = refreshing,
    )

/**
 * Renders the current account snapshot while a background refresh reconciles it.
 *
 * Loading replaces the list only when no in-session snapshot exists, so opening the sheet never
 * hides accounts that are already ready to switch.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun AccountSelectorContent(
    state: AccountSelectorState,
    displayName: (String) -> String,
    shortNpub: (String) -> String,
    avatarUrl: (String) -> String?,
    unreadCountForAccount: (String) -> ULong,
    actionColorsForAccount: @Composable (String) -> AccountActionColors? = { null },
    onSwitchAccount: (String) -> Unit,
    onAddAccount: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth().testTag(ACCOUNT_SELECTOR_CONTENT_TAG).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(stringResource(R.string.switch_account), style = MaterialTheme.typography.titleLarge)
        if (state.refreshing && state.accounts.isEmpty()) {
            Box(Modifier.fillMaxWidth().heightIn(min = 120.dp), contentAlignment = Alignment.Center) {
                LoadingIndicator()
            }
        } else {
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 360.dp)) {
                items(state.accounts, key = { it.label }) { account ->
                    val unreadCount = unreadCountForAccount(account.label)
                    val actionColors = actionColorsForAccount(account.label)
                    ListItem(
                        modifier =
                            Modifier
                                .clip(RoundedCornerShape(12.dp))
                                .amoledSurfaceBorder(RoundedCornerShape(12.dp))
                                .clickable { onSwitchAccount(account.label) },
                        colors =
                            ListItemDefaults.colors(
                                // Tonal highlight so the active account reads at a
                                // glance, not only from the trailing check.
                                containerColor =
                                    if (account.isActive) {
                                        MaterialTheme.colorScheme.secondaryContainer
                                    } else {
                                        Color.Transparent
                                    },
                            ),
                        leadingContent = {
                            Avatar(
                                title = displayName(account.accountIdHex),
                                seed = account.accountIdHex,
                                size = 44.dp,
                                pictureUrl = avatarUrl(account.accountIdHex),
                            )
                        },
                        headlineContent = { Text(displayName(account.accountIdHex)) },
                        supportingContent = {
                            Text(
                                shortNpub(account.accountIdHex),
                                fontFamily = FontFamily.Monospace,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                        trailingContent = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (unreadCount > 0uL) {
                                    UnreadCountBadge(unreadCount, actionColors = actionColors)
                                    Spacer(Modifier.width(8.dp))
                                }
                                if (account.isReadOnly) {
                                    Text(stringResource(R.string.read_only), style = MaterialTheme.typography.labelSmall)
                                    Spacer(Modifier.width(8.dp))
                                }
                                if (account.isSignedOut) {
                                    Text(stringResource(R.string.signed_out), style = MaterialTheme.typography.labelSmall)
                                    Spacer(Modifier.width(8.dp))
                                }
                                if (account.isActive) {
                                    Icon(
                                        Icons.Default.Check,
                                        contentDescription = stringResource(R.string.active),
                                        tint = MaterialTheme.colorScheme.onSecondaryContainer,
                                    )
                                }
                            }
                        },
                    )
                    AppDivider()
                }
            }
        }
        FilledTonalButton(
            onClick = onAddAccount,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(Icons.Default.Add, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.add_account))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AccountSelectorSheet(
    appState: WhiteNoiseAppState,
    onDismiss: () -> Unit,
    onAddAccount: () -> Unit,
    onAccountSwitched: () -> Unit,
) {
    // Local in-flight signal for the expressive loading state: refreshAccounts
    // itself exposes none, and this sheet is the only caller that needs one.
    var refreshingAccounts by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        try {
            appState.refreshAccounts()
        } catch (error: kotlin.coroutines.cancellation.CancellationException) {
            throw error
        } catch (_: Exception) {
            // Keep the cached account list rather than failing the sheet.
            Log.w("AccountSelectorSheet", "account_refresh_failed_using_cache")
        } finally {
            refreshingAccounts = false
        }
    }
    val state =
        accountSelectorState(
            accounts = appState.accounts,
            activeAccountRef = appState.activeAccountRef,
            refreshing = refreshingAccounts,
        )
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = amoledSheetContainerColor(),
    ) {
        AccountSelectorContent(
            state = state,
            displayName = appState::displayName,
            shortNpub = appState::shortNpub,
            avatarUrl = appState::avatarUrl,
            unreadCountForAccount = appState::confirmedUnreadCountForAccount,
            actionColorsForAccount = { accountRef -> accountActionColors(appState, accountRef) },
            onSwitchAccount = { accountLabel ->
                // Run on the process-lifetime mutation scope, not this sheet's
                // composition. Dismiss/reset at setActiveAccount's local-ready
                // boundary; its profile/privacy/notification/push work keeps
                // running after the sheet is disposed (#547, #1698).
                appState.launchMutation {
                    appState.setActiveAccount(
                        label = accountLabel,
                        preloadPolicy = AccountSwitchPreloadPolicy.INTERACTIVE_LOCAL_ROWS,
                        onActivated = {
                            onDismiss()
                            // Land on the newly-active account's chat list instead of
                            // leaving the user on Settings (#316).
                            onAccountSwitched()
                        },
                    )
                }
            },
            onAddAccount = onAddAccount,
        )
    }
}
