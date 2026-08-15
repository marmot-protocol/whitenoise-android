@file:Suppress("FunctionName")

package dev.ipf.whitenoise.android.ui.share

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.ipf.marmotkit.AccountSummaryFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.ui.common.Avatar
import dev.ipf.whitenoise.android.ui.theme.amoledSheetContainerColor

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ShareChatPickerAccountSheet(
    appState: WhiteNoiseAppState,
    accounts: List<AccountSummaryFfi>,
    selectedAccountRef: String?,
    onChooseAccount: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = amoledSheetContainerColor(),
    ) {
        ShareChatPickerAccountSheetContent(
            appState = appState,
            accounts = accounts,
            selectedAccountRef = selectedAccountRef,
            onChooseAccount = onChooseAccount,
        )
    }
}

@Composable
internal fun ShareChatPickerAccountSheetContent(
    appState: WhiteNoiseAppState,
    accounts: List<AccountSummaryFfi>,
    selectedAccountRef: String?,
    onChooseAccount: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .testTag(SHARE_CHAT_PICKER_ACCOUNT_SHEET_TEST_TAG)
                .padding(start = 24.dp, end = 24.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            stringResource(R.string.share_choose_sending_account),
            style = MaterialTheme.typography.titleLarge,
        )
        Text(
            stringResource(R.string.share_choose_sending_account_description),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 360.dp)) {
            items(accounts, key = AccountSummaryFfi::label) { account ->
                ShareChatPickerAccountItem(
                    appState = appState,
                    account = account,
                    selected = account.label == selectedAccountRef,
                    onChooseAccount = onChooseAccount,
                )
            }
        }
    }
}

@Composable
private fun ShareChatPickerAccountItem(
    appState: WhiteNoiseAppState,
    account: AccountSummaryFfi,
    selected: Boolean,
    onChooseAccount: (String) -> Unit,
) {
    val accountTitle = appState.networkDisplayName(account.accountIdHex)
    ListItem(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .clickable(role = Role.RadioButton) { onChooseAccount(account.label) }
                .semantics { this.selected = selected },
        colors =
            ListItemDefaults.colors(
                containerColor =
                    if (selected) {
                        MaterialTheme.colorScheme.secondaryContainer
                    } else {
                        MaterialTheme.colorScheme.surface
                    },
            ),
        leadingContent = {
            Avatar(
                title = accountTitle,
                seed = account.accountIdHex,
                size = 44.dp,
                pictureUrl = appState.avatarUrl(account.accountIdHex),
            )
        },
        headlineContent = {
            Text(accountTitle, maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
        supportingContent = {
            Text(
                appState.shortNpub(account.accountIdHex),
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        trailingContent = {
            if (selected) {
                Icon(Icons.Default.Check, contentDescription = stringResource(R.string.selected))
            }
        },
    )
}
