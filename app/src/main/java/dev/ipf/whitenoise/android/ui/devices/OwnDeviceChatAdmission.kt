@file:Suppress("FunctionNaming") // Jetpack Compose functions intentionally use PascalCase.

package dev.ipf.whitenoise.android.ui.devices

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import dev.ipf.whitenoise.android.core.linkedDevicesAvailable
import dev.ipf.whitenoise.android.state.ChatListItem
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.state.runCatchingCancellable

/**
 * Whether a chat MDK still holds as an invitation was added by another device of the same account: it is [pending]
 * and the device that welcomed this one, [welcomerAccountIdHex], is [ownAccountIdHex] itself. The account is
 * already in such a chat, so there is nobody to accept or decline.
 */
internal fun addedByOwnDevice(
    pending: Boolean,
    welcomerAccountIdHex: String?,
    ownAccountIdHex: String?,
): Boolean {
    val own = ownAccountIdHex?.trim()?.takeIf { it.isNotEmpty() } ?: return false
    return pending && welcomerAccountIdHex?.trim().equals(own, ignoreCase = true)
}

/** The chats in [items] that another device of [accountIdHex] added this device to, by group id. */
internal fun chatsAddedByOwnDevice(
    items: List<ChatListItem>,
    accountIdHex: String?,
): List<String> =
    items
        .filter { addedByOwnDevice(it.group.pendingConfirmation, it.group.welcomerAccountIdHex, accountIdHex) }
        .map { it.group.groupIdHex }

/**
 * Admits the active account's chats that one of its own devices added this device to. Without this a newly linked
 * device lists the account's own chats as invitations from itself, with a Decline that would leave them. Each chat
 * is asked for once per change of the list, and a failure leaves the invitation in place for the user.
 */
@Composable
internal fun OwnDeviceChatAdmission(appState: WhiteNoiseAppState) {
    val account = appState.activeAccount?.takeIf { linkedDevicesAvailable() && !it.signedOut } ?: return
    val chats = chatsAddedByOwnDevice(appState.chatListItemsForAvatarDomains(), account.accountIdHex)
    LaunchedEffect(account.label, appState.runtimeGeneration, chats) {
        chats.forEach { groupIdHex ->
            runCatchingCancellable { appState.marmotIo { acceptGroupInvite(account.label, groupIdHex) } }
        }
    }
}
