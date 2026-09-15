@file:Suppress("MatchingDeclarationName") // The screen owns its small presentation state declaration.

package dev.ipf.whitenoise.android.ui.settings

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.ipf.marmotkit.AccountRelayListsFfi
import dev.ipf.marmotkit.MissingRelayListKindFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.core.SupportContact
import dev.ipf.whitenoise.android.state.ChatListItem
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.state.isAcceptableRelayUrl
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseButton
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseSpacing
import dev.ipf.whitenoise.android.ui.theme.amoledOutlineBorder
import kotlinx.coroutines.CancellationException

/**
 * Configuration evidence only: connection, KeyPackage and invitation readiness remain with the
 * ordinary Message flow.
 */
internal enum class SupportRelayState { Loading, Unavailable, Missing, Configured }

/** Reads the account's native receiving list; bootstrap or another account's connected relay count is not evidence. */
internal fun supportRelayState(lists: AccountRelayListsFfi?): SupportRelayState =
    when {
        lists == null -> SupportRelayState.Unavailable
        MissingRelayListKindFfi.INBOX in lists.missing -> SupportRelayState.Missing
        lists.inbox.relays.none(::isAcceptableRelayUrl) -> SupportRelayState.Missing
        else -> SupportRelayState.Configured
    }

/**
 * Explains the canonical support contact before the user explicitly opens an existing DM or the normal
 * profile flow.
 */
@Suppress("FunctionNaming", "LongParameterList", "TooGenericExceptionCaught")
@Composable
internal fun SupportScreen(
    appState: WhiteNoiseAppState,
    onBack: () -> Unit,
    onOpenSupportChat: (ChatListItem) -> Unit,
    onRelays: () -> Unit,
    loadRelayLists: suspend (String) -> AccountRelayListsFfi? = appState::loadAccountRelayLists,
    existingChat: (String) -> ChatListItem? = appState::existingDirectChat,
    presentProfile: (String) -> Unit = appState::presentProfile,
) {
    val account = appState.activeAccountRef
    var relayState by remember(appState, account) { mutableStateOf(SupportRelayState.Loading) }
    var refresh by remember(appState, account) { mutableIntStateOf(0) }
    val hasExistingChat = account != null && existingChat(SupportContact.NPUB) != null
    LaunchedEffect(appState, account, refresh) {
        if (account == null) return@LaunchedEffect
        relayState = SupportRelayState.Loading
        val lists =
            try {
                loadRelayLists(account)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
        if (appState.activeAccountRef == account) relayState = supportRelayState(lists)
    }
    SupportContent(
        hasAccount = account != null,
        hasExistingChat = hasExistingChat,
        relayState = relayState,
        busy = appState.signOutInProgress,
        onBack = { if (!appState.signOutInProgress) onBack() },
        onRelays = { if (!appState.signOutInProgress) onRelays() },
        onRetry = { if (!appState.signOutInProgress) refresh++ },
        onStart = {
            // Re-read both ownership and canonical chat selection at the actual tap boundary.
            if (account != null && appState.activeAccountRef == account && !appState.signOutInProgress) {
                val existing = existingChat(SupportContact.NPUB)
                if (existing != null) {
                    onOpenSupportChat(existing)
                } else if (relayState == SupportRelayState.Configured) {
                    presentProfile(SupportContact.NPUB)
                }
            }
        },
    )
}

/** Prototype support identity, explainer, relay-recovery rows and scrolling action with truthful production status. */
@Suppress("FunctionNaming", "LongMethod", "LongParameterList")
@Composable
internal fun SupportContent(
    hasAccount: Boolean,
    hasExistingChat: Boolean,
    relayState: SupportRelayState,
    busy: Boolean,
    onBack: () -> Unit,
    onStart: () -> Unit,
    onRelays: () -> Unit,
    onRetry: () -> Unit,
) {
    val canStart = hasAccount && (hasExistingChat || relayState == SupportRelayState.Configured)
    SettingsScaffold(title = stringResource(R.string.chat_with_support), onBack = onBack) {
        SettingsList {
            item {
                SettingsGroup(modifier = Modifier.padding(top = WhiteNoiseSpacing.Section)) {
                    row("support_identity") { context ->
                        SettingsGroupPanel(context) {
                            ListItem(
                                headlineContent = {
                                    Text(
                                        stringResource(R.string.support_contact_title),
                                        style = MaterialTheme.typography.titleMedium,
                                    )
                                },
                                supportingContent = {
                                    Text(
                                        stringResource(R.string.support_contact_subtitle),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                },
                                leadingContent = {
                                    Surface(
                                        modifier = Modifier.size(48.dp),
                                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                                        contentColor = MaterialTheme.colorScheme.onSurface,
                                        border = amoledOutlineBorder(),
                                        shape = CircleShape,
                                    ) {
                                        Icon(
                                            painterResource(R.drawable.ic_settings_chat_bubble_outline),
                                            contentDescription = null,
                                            modifier = Modifier.padding(12.dp),
                                        )
                                    }
                                },
                                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                            )
                        }
                    }
                }
                SettingsExplainer(stringResource(R.string.support_contact_explainer))
            }
            if (!hasAccount) {
                item { SettingsCallout(text = stringResource(R.string.no_active_account_period)) }
            } else if (!hasExistingChat && relayState != SupportRelayState.Configured) {
                item {
                    SettingsCallout(
                        title = stringResource(R.string.support_relays_attention),
                        icon = R.drawable.ic_warning,
                        text =
                            stringResource(
                                when (relayState) {
                                    SupportRelayState.Loading -> R.string.support_relays_loading
                                    SupportRelayState.Unavailable -> R.string.support_relays_unavailable
                                    else -> R.string.support_receiving_relay_required
                                },
                            ),
                        modifier = Modifier.padding(top = WhiteNoiseSpacing.Section).testTag("support.relay_status"),
                    )
                }
                if (relayState != SupportRelayState.Loading) {
                    item {
                        SettingsGroup(modifier = Modifier.padding(top = WhiteNoiseSpacing.Related)) {
                            row("relays") { context ->
                                SettingsLink(
                                    context = context,
                                    title = stringResource(R.string.support_open_relays),
                                    subtitle = stringResource(R.string.support_relays_review),
                                    enabled = !busy,
                                    onClick = onRelays,
                                    modifier = Modifier.testTag("support.relays"),
                                )
                            }
                            if (relayState == SupportRelayState.Unavailable) {
                                row("retry") { context ->
                                    SettingsLink(
                                        context = context,
                                        title = stringResource(R.string.retry),
                                        onClick = onRetry,
                                        enabled = !busy,
                                        modifier = Modifier.testTag("support.retry"),
                                    )
                                }
                            }
                        }
                    }
                }
            }
            item {
                WhiteNoiseButton(
                    onClick = onStart,
                    enabled = canStart && !busy,
                    modifier =
                        Modifier
                            .padding(
                                horizontal = WhiteNoiseSpacing.CompactScreenMargin,
                                vertical = WhiteNoiseSpacing.Section,
                            ).fillMaxWidth()
                            .testTag("support.start"),
                ) { Text(stringResource(R.string.start_chat)) }
            }
        }
    }
}
