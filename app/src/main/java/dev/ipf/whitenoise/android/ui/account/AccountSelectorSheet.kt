package dev.ipf.whitenoise.android.ui.account

import android.util.Log
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import dev.ipf.marmotkit.AccountSummaryFfi
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.ui.common.AccountActionColors
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseModalBottomSheet
import dev.ipf.whitenoise.android.ui.common.accountActionColors
import dev.ipf.whitenoise.android.ui.common.rememberSheetTerminalMotion
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

/** Presentation state for the account selector: ordered accounts, the active one and the refresh flag. */
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
// Refresh, selection and Material exit share one account-scoped composition.
@Suppress("LongMethod", "FunctionNaming", "CyclomaticComplexMethod")
@Composable
internal fun AccountSelectorSheet(
    appState: WhiteNoiseAppState,
    onDismiss: () -> Unit,
    onAddAccount: () -> Unit,
    onAccountSwitched: () -> Unit,
    onSettings: (() -> Unit)? = null,
    onAddAccountOrigin: (Rect?) -> Unit = {},
) {
    val runtime = appState.runtimeGeneration
    val sourceAccount = appState.activeAccountRef
    val selection = remember(appState, runtime, sourceAccount) { ProfileSwitcherSelection() }
    val dismiss by rememberUpdatedState(onDismiss)
    val switched by rememberUpdatedState(onAccountSwitched)
    val add by rememberUpdatedState(onAddAccount)
    val settings by rememberUpdatedState(onSettings)
    var refreshing by remember(appState, runtime) { mutableStateOf(true) }

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val terminal = rememberSheetTerminalMotion(sheetState, selection to sourceAccount)

    // Account/runtime invalidation and successful activation must dispose immediately.

    fun close() {
        selection.close()
        dismiss()
    }

    fun closeWithMotion(action: () -> Unit) {
        if (!terminal.acceptsInput || appState.profileSwitcherBlocked()) return
        selection.cancelPending()
        terminal.close {
            if (appState.runtimeGeneration == runtime &&
                appState.activeAccountRef == sourceAccount &&
                !appState.profileSwitcherBlocked()
            ) {
                action()
            }
        }
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
    WhiteNoiseModalBottomSheet(
        onDismissRequest = { closeWithMotion { dismiss() } },
        sheetState = sheetState,
        interactionEnabled = terminal.acceptsInput && !appState.profileSwitcherBlocked(),
    ) {
        ProfileSwitcherSheet(
            state = accountSelectorState(appState.accounts, appState.activeAccountRef, refreshing),
            displayName = appState::accountDisplayNameCached,
            shortNpub = appState::shortNpub,
            npub = appState::npubForDisplay,
            avatarUrl = appState::avatarUrl,
            unreadCountForAccount = appState::confirmedUnreadCountForAccount,
            hasUnreadForAccount = appState::accountShowsUnreadDot,
            actionColorsForAccount = { accountRef -> accountActionColors(appState, accountRef) },
            enabled = terminal.acceptsInput && !appState.profileSwitcherBlocked(),
            pendingLabel = selection.pendingLabel,
            onSelectProfile = { label ->
                selection.select(appState, label) {
                    close()
                    switched()
                }
            },
            onAddProfileOrigin = onAddAccountOrigin,
            onAddProfile = {
                if (!appState.profileSwitcherBlocked()) {
                    closeWithMotion { add() }
                }
            },
            onDismiss = { closeWithMotion { dismiss() } },
            onSettings =
                settings?.let {
                    {
                        if (!appState.profileSwitcherBlocked()) {
                            closeWithMotion {
                                dismiss()
                                settings?.invoke()
                            }
                        }
                    }
                },
        )
    }
}
