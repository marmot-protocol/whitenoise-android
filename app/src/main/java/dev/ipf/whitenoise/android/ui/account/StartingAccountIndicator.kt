@file:Suppress("MatchingDeclarationName") // The creation cue and its presentation model stay together.

package dev.ipf.whitenoise.android.ui.account

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.core.text.BidiFormatter
import androidx.core.text.TextDirectionHeuristicsCompat
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseDropdownMenu

/** Identity of the captured creation owner, separate from recipients and the proposed group. */
internal data class StartingAccount(
    val accountIdHex: String,
    val name: String,
    val pictureUrl: String?,
    val identifier: String,
)

/** Read the existing presentation only while the captured owner still matches the active runtime. */
internal fun startingAccount(
    appState: WhiteNoiseAppState,
    accountRef: String?,
    runtime: Int,
): StartingAccount? {
    val sameOwner =
        accountRef != null && appState.activeAccountRef == accountRef && appState.runtimeGeneration == runtime
    if (!sameOwner || appState.signOutInProgress || appState.wipeInProgress) return null
    val account = appState.accounts.firstOrNull { it.label == accountRef }
    return account?.let {
        StartingAccount(
            it.accountIdHex,
            appState.accountDisplayNameCached(it.accountIdHex),
            appState.avatarUrl(it.accountIdHex),
            appState.shortNpub(it.accountIdHex),
        )
    }
}

/** Pinned header cue; tapping discloses the account name without switching accounts or adding a confirmation. */
@Suppress("FunctionNaming")
@Composable
internal fun StartingAccountIndicator(account: StartingAccount?) {
    if (account == null) return
    var expanded by remember(account.accountIdHex) { mutableStateOf(false) }
    val label = stringResource(R.string.starting_as_account, account.name)
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val formatter = remember(rtl) { BidiFormatter.Builder(rtl).build() }
    Box {
        AccountAvatarButton(
            title = account.name,
            seed = account.accountIdHex,
            pictureUrl = account.pictureUrl,
            size = 32.dp,
            touchTargetSize = 48.dp,
            actionDescription = stringResource(R.string.starting_as),
            onClick = { expanded = true },
            modifier = Modifier.testTag("creation.starting_account"),
        )
        WhiteNoiseDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            items = emptyList(),
            header = {
                Column(
                    Modifier
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                        .testTag("creation.starting_account.disclosure"),
                ) {
                    Text(label, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        formatter.unicodeWrap(account.identifier, TextDirectionHeuristicsCompat.LTR),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            },
        )
    }
}
