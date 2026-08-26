package dev.ipf.whitenoise.android.ui.onboarding

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.ui.common.Avatar

data class OnboardingSavedAccountUi(
    val label: String,
    val accountIdHex: String,
    val displayName: String,
    val shortIdentity: String,
    val avatarUrl: String?,
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
            )
        }

internal const val ONBOARDING_SAVED_ACCOUNT_TAG = "onboarding-saved-account"

@Composable
@Suppress("FunctionNaming")
internal fun OnboardingSavedAccountActions(
    accounts: List<OnboardingSavedAccountUi>,
    reactivatingAccountLabel: String?,
    enabled: Boolean,
    onContinue: (String) -> Unit,
) {
    var pickerVisible by remember { mutableStateOf(false) }
    accounts.firstOrNull()?.let { account ->
        OnboardingSavedAccountCard(
            account = account,
            loading = reactivatingAccountLabel == account.label,
            enabled = enabled,
            onClick = { onContinue(account.label) },
        )
        if (accounts.size > 1) {
            TextButton(
                onClick = { pickerVisible = true },
                enabled = enabled,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.switch_account))
            }
        }
    }
    if (pickerVisible) {
        OnboardingSavedAccountPicker(
            accounts = accounts,
            reactivatingAccountLabel = reactivatingAccountLabel,
            onSelect = { account ->
                pickerVisible = false
                onContinue(account.label)
            },
            onDismiss = { pickerVisible = false },
        )
    }
}

@Composable
@Suppress("FunctionNaming")
private fun OnboardingSavedAccountCard(
    account: OnboardingSavedAccountUi,
    loading: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val actionLabel = stringResource(R.string.onboarding_continue_as, account.displayName)
    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier =
            Modifier
                .fillMaxWidth()
                .testTag(ONBOARDING_SAVED_ACCOUNT_TAG)
                .semantics {
                    role = Role.Button
                    contentDescription = actionLabel
                },
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Avatar(
                title = account.displayName,
                seed = account.accountIdHex,
                size = 44.dp,
                pictureUrl = account.avatarUrl,
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = actionLabel,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (account.shortIdentity.isNotBlank()) {
                    Text(
                        text = account.shortIdentity,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (loading) {
                CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
            } else {
                Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
@Suppress("FunctionNaming")
private fun OnboardingSavedAccountPicker(
    accounts: List<OnboardingSavedAccountUi>,
    reactivatingAccountLabel: String?,
    onSelect: (OnboardingSavedAccountUi) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.switch_account), style = MaterialTheme.typography.titleLarge)
            LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp)) {
                items(accounts, key = OnboardingSavedAccountUi::label) { account ->
                    val loading = reactivatingAccountLabel == account.label
                    val actionLabel = stringResource(R.string.onboarding_continue_as, account.displayName)
                    ListItem(
                        headlineContent = {
                            Text(account.displayName, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        },
                        supportingContent = {
                            Text(
                                account.shortIdentity,
                                fontFamily = FontFamily.Monospace,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                        leadingContent = {
                            Avatar(
                                title = account.displayName,
                                seed = account.accountIdHex,
                                size = 44.dp,
                                pictureUrl = account.avatarUrl,
                            )
                        },
                        trailingContent = {
                            if (loading) {
                                CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
                            } else {
                                Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null)
                            }
                        },
                        colors =
                            ListItemDefaults.colors(
                                containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                            ),
                        modifier =
                            Modifier
                                .clickable(enabled = reactivatingAccountLabel == null) { onSelect(account) }
                                .semantics {
                                    role = Role.Button
                                    contentDescription = actionLabel
                                },
                    )
                }
            }
        }
    }
}
