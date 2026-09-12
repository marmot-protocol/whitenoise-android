package dev.ipf.whitenoise.android.ui.account

import android.util.Log
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import dev.ipf.marmotkit.AccountSummaryFfi
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.ui.common.AccountActionColors
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseModalBottomSheet
import dev.ipf.whitenoise.android.ui.common.accountActionColors
import kotlinx.coroutines.CancellationException

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

/** Existing content callers retain their native snapshot and callbacks while using the shared switcher composition. */
@Suppress("LongParameterList", "FunctionNaming")
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
    ProfileSwitcherSheet(
        state,
        displayName,
        shortNpub,
        avatarUrl,
        unreadCountForAccount,
        onSwitchAccount,
        onAddAccount,
        modifier,
        actionColorsForAccount = actionColorsForAccount,
    )
}

/** Native refresh retains cached rows; the visible sheet owns guarded selection intent, never background completion. */
@OptIn(ExperimentalMaterial3Api::class)
@Suppress("LongMethod", "FunctionNaming")
@Composable
internal fun AccountSelectorSheet(
    appState: WhiteNoiseAppState,
    onDismiss: () -> Unit,
    onAddAccount: () -> Unit,
    onAccountSwitched: () -> Unit,
    onSettings: (() -> Unit)? = null,
) {
    val runtime = appState.runtimeGeneration
    val selection = remember(appState, runtime) { ProfileSwitcherSelection() }
    val dismiss by rememberUpdatedState(onDismiss)
    val switched by rememberUpdatedState(onAccountSwitched)
    val add by rememberUpdatedState(onAddAccount)
    val settings by rememberUpdatedState(onSettings)
    var refreshing by remember(appState, runtime) { mutableStateOf(true) }

    fun close() {
        selection.close()
        dismiss()
    }
    DisposableEffect(selection) { onDispose { selection.close() } }
    LaunchedEffect(appState.signOutInProgress, appState.wipeInProgress, appState.retainedAccountReactivationRef) {
        if (appState.profileSwitcherBlocked()) close()
    }
    LaunchedEffect(appState, runtime) {
        try {
            appState.refreshAccounts()
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            Log.w("AccountSelectorSheet", "account_refresh_failed_using_cache")
        } finally {
            refreshing = false
        }
    }
    WhiteNoiseModalBottomSheet(onDismissRequest = ::close) {
        ProfileSwitcherSheet(
            state = accountSelectorState(appState.accounts, appState.activeAccountRef, refreshing),
            displayName = appState::accountDisplayNameCached,
            shortNpub = appState::shortNpub,
            avatarUrl = appState::avatarUrl,
            unreadCountForAccount = appState::confirmedUnreadCountForAccount,
            hasUnreadForAccount = appState::accountShowsUnreadDot,
            actionColorsForAccount = { accountActionColors(appState, it) },
            enabled = !appState.profileSwitcherBlocked(),
            pendingLabel = selection.pendingLabel,
            onSelectProfile = { label ->
                selection.select(appState, label) {
                    close()
                    switched()
                }
            },
            onAddProfile = {
                if (!appState.profileSwitcherBlocked()) {
                    selection.close()
                    add()
                }
            },
            onDismiss = ::close,
            onSettings =
                settings?.let {
                    {
                        if (!appState.profileSwitcherBlocked()) {
                            close()
                            settings?.invoke()
                        }
                    }
                },
        )
    }
}
