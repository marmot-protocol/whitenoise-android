package dev.ipf.whitenoise.android.ui.onboarding

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.ui.common.Avatar
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseButton
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseSheetHeader
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseAlertDialog as AlertDialog
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseModalBottomSheet as ModalBottomSheet

data class OnboardingSavedAccountUi(
    val label: String,
    val accountIdHex: String,
    val displayName: String,
    val shortIdentity: String,
    val avatarUrl: String?,
    val recoveryRequired: Boolean = false,
)

internal fun onboardingSavedAccounts(appState: WhiteNoiseAppState): List<OnboardingSavedAccountUi> =
    appState.accounts
        .filter { it.localSigning || it.externalSigning }
        .sortedBy { !it.signedOut }
        .map { account ->
            val shortIdentity = appState.shortNpub(account.accountIdHex)
            OnboardingSavedAccountUi(
                label = account.label,
                accountIdHex = account.accountIdHex,
                displayName = appState.displayName(account.accountIdHex).ifBlank { shortIdentity },
                shortIdentity = shortIdentity,
                avatarUrl = appState.avatarUrl(account.accountIdHex),
                recoveryRequired = appState.onboardingRecoveryRequired(account.label),
            )
        }

internal const val ONBOARDING_SAVED_ACCOUNT_TAG = "onboarding-saved-account"

/** Existing retained-account resume and destructive-recovery consent behind the primary action. */
@Composable
@Suppress("FunctionNaming")
internal fun OnboardingSavedAccountActions(
    accounts: List<OnboardingSavedAccountUi>,
    reactivatingAccountLabel: String?,
    enabled: Boolean,
    onContinue: (String) -> Unit,
    onRecover: (String) -> Unit = {},
) {
    var pickerVisible by remember { mutableStateOf(false) }
    var recoveryAccountLabel by remember { mutableStateOf<String?>(null) }
    val continuing = accounts.firstOrNull { it.label == reactivatingAccountLabel } ?: accounts.firstOrNull()

    fun select(account: OnboardingSavedAccountUi) {
        if (!enabled || reactivatingAccountLabel != null) return
        pickerVisible = false
        if (account.recoveryRequired) recoveryAccountLabel = account.label else onContinue(account.label)
    }
    continuing?.let { account ->
        WhiteNoiseButton(
            onClick = { select(account) },
            enabled = enabled,
            loading = reactivatingAccountLabel == account.label,
            loadingLabel = stringResource(R.string.onboarding_signing_in),
            modifier = Modifier.fillMaxWidth().testTag(ONBOARDING_SAVED_ACCOUNT_TAG),
        ) { Text(onboardingSavedAccountActionLabel(account)) }
        if (accounts.size > 1) {
            TextButton(onClick = { pickerVisible = true }, enabled = enabled) {
                Text(stringResource(R.string.onboarding_choose_profile))
            }
        }
    }
    if (pickerVisible) {
        RetainedProfilesSheet(
            accounts = accounts,
            enabled = enabled && reactivatingAccountLabel == null,
            onSelect = ::select,
            onDismiss = { pickerVisible = false },
        )
    }
    val recoveryAccount = accounts.firstOrNull { it.label == recoveryAccountLabel }
    if (recoveryAccount != null && enabled && reactivatingAccountLabel == null) {
        AlertDialog(
            onDismissRequest = { recoveryAccountLabel = null },
            title = { Text(stringResource(R.string.onboarding_recover_setup_title)) },
            text = { Text(stringResource(R.string.onboarding_recover_setup_explanation)) },
            confirmButton = {
                TextButton(onClick = {
                    recoveryAccountLabel = null
                    onRecover(recoveryAccount.label)
                }) { Text(stringResource(R.string.onboarding_recover_setup_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { recoveryAccountLabel = null }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}

/**
 * Native retained identities use the shared sheet and 48 dp production avatars; selection never creates an
 * identity.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
@Suppress("FunctionNaming")
internal fun RetainedProfilesSheet(
    accounts: List<OnboardingSavedAccountUi>,
    enabled: Boolean,
    onSelect: (OnboardingSavedAccountUi) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        WhiteNoiseSheetHeader(stringResource(R.string.onboarding_choose_profile), onClose = onDismiss)
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 520.dp)) {
            items(accounts, key = OnboardingSavedAccountUi::label) { account ->
                ListItem(
                    headlineContent = { Text(account.displayName) },
                    supportingContent = { Text(account.shortIdentity) },
                    leadingContent = {
                        Avatar(
                            title = account.displayName,
                            seed = account.accountIdHex,
                            size = 48.dp,
                            pictureUrl = account.avatarUrl,
                        )
                    },
                    colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                    modifier = Modifier.clickable(enabled = enabled, role = Role.Button) { onSelect(account) },
                )
            }
        }
    }
}

/** Recovery labels continue to name the potentially destructive operation instead of implying an ordinary resume. */
@Composable
private fun onboardingSavedAccountActionLabel(account: OnboardingSavedAccountUi): String =
    if (account.recoveryRequired) {
        stringResource(R.string.onboarding_recover_setup_for, account.displayName)
    } else {
        stringResource(R.string.onboarding_continue_as, account.displayName)
    }
