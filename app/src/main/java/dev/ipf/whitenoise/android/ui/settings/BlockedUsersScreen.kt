package dev.ipf.whitenoise.android.ui.settings

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import dev.ipf.marmotkit.BlockedUserFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.BlockOutcome
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.state.setUserBlocked

internal const val BLOCKED_USERS_CONTENT_TAG = "blocked-users-content"

/** Lists the active account's blocked users from MDK's live block list and unblocks from each row. */
@Suppress("FunctionNaming")
@Composable
internal fun BlockedUsersScreen(
    appState: WhiteNoiseAppState,
    onBack: () -> Unit,
) {
    val mirror = appState.runtimeMirrors.blocks
    var busyUser by remember { mutableStateOf<String?>(null) }
    SettingsScaffold(title = stringResource(R.string.blocked_users), onBack = onBack) {
        SettingsList(modifier = Modifier.testTag(BLOCKED_USERS_CONTENT_TAG)) {
            item { SettingsExplainer(stringResource(R.string.blocked_users_detail)) }
            when {
                !mirror.available -> item { SettingsExplainer(stringResource(R.string.block_list_unavailable)) }
                mirror.users.isEmpty() -> item { SettingsExplainer(stringResource(R.string.blocked_users_empty)) }
                else ->
                    item {
                        BlockedUsersGroup(
                            appState = appState,
                            users = mirror.users,
                            busyUser = busyUser,
                            onUnblock = { user ->
                                val account = appState.activeAccountRef ?: return@BlockedUsersGroup
                                busyUser = user.publicKey
                                appState.launchMutation {
                                    try {
                                        val outcome = appState.setUserBlocked(account, user.publicKey, blocked = false)
                                        blockOutcomeMessage(outcome)?.let(appState::present)
                                    } finally {
                                        busyUser = null
                                    }
                                }
                            },
                        )
                    }
            }
        }
    }
}

/** One unblock action per blocked user, titled by the app's display name for that account. */
@Suppress("FunctionNaming")
@Composable
private fun BlockedUsersGroup(
    appState: WhiteNoiseAppState,
    users: List<BlockedUserFfi>,
    busyUser: String?,
    onUnblock: (BlockedUserFfi) -> Unit,
) {
    SettingsGroup(modifier = Modifier.testTag("blocked_users.group")) {
        users.forEach { user ->
            row(user.publicKey) { rowContext ->
                SettingsAction(
                    context = rowContext,
                    title = appState.chatMemberTitle(user.publicKey),
                    subtitle = stringResource(R.string.profile_unblock),
                    onClick = { onUnblock(user) },
                    enabled = busyUser == null,
                    leading = { Icon(Icons.Outlined.Block, contentDescription = null) },
                )
            }
        }
    }
}

/** The toast for an unconfirmed outcome; a confirmed change needs none because the list itself updates. */
internal fun blockOutcomeMessage(outcome: BlockOutcome): Int? =
    when (outcome) {
        BlockOutcome.Confirmed -> null
        BlockOutcome.Uncertain -> R.string.block_publication_uncertain
        BlockOutcome.Unavailable -> R.string.block_list_unavailable
        BlockOutcome.Failed -> R.string.block_change_failed
    }
