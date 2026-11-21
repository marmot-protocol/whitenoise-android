package dev.ipf.whitenoise.android.ui.navigation

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.window.SecureFlagPolicy
import dev.ipf.whitenoise.android.state.ChatListItem
import dev.ipf.whitenoise.android.state.ConversationController
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.ui.chats.newchat.NewGroupFlow
import dev.ipf.whitenoise.android.ui.common.WindowSecureFlag
import dev.ipf.whitenoise.android.ui.profile.ProfileSheet

/**
 * The profile sheet and the new-group flow it starts, drawn over [content] (the shell or a conversation). Moved out
 * of MainShell unchanged.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Suppress("FunctionNaming")
@Composable
internal fun ProfileGroupForegroundCoordinator(
    appState: WhiteNoiseAppState,
    conversationController: ConversationController?,
    profileGroupForegroundState: ProfileGroupForegroundState,
    secureWindowEnabled: Boolean?,
    profileSecurePolicy: SecureFlagPolicy,
    onOpenConversation: (ChatListItem, Boolean) -> Unit,
    onGroupCreateSubmitted: () -> Long = { 0L },
    onGroupCreateCompletedOpen: (ChatListItem, Long) -> Unit = { item, _ -> onOpenConversation(item, false) },
    onGroupCreateFlowSuperseded: () -> Unit = {},
    onDismissProfile: () -> Unit,
    onClosePicker: () -> Unit,
    content: @Composable () -> Unit,
) {
    val foregroundState = profileGroupForegroundState
    val route =
        profileForegroundRoute(
            pendingProfileNpub = appState.pendingProfileNpub,
            startGroupMember = foregroundState.initialMember,
            conversationOpen = conversationController != null,
        )
    when (route) {
        is ProfileForegroundRoute.NewGroup -> {
            secureWindowEnabled?.let { WindowSecureFlag(enabled = it) }
            NewGroupFlow(
                appState = appState,
                initialMembers = listOf(route.initialMember),
                onCreateCompletedOpen = { item, requestToken ->
                    foregroundState.close()
                    onGroupCreateCompletedOpen(item, requestToken)
                },
                onCreateSubmitted = onGroupCreateSubmitted,
                onCreateFlowSuperseded = onGroupCreateFlowSuperseded,
                onClose = {
                    onClosePicker()
                    foregroundState.close()
                    onGroupCreateFlowSuperseded()
                },
            )
            return
        }
        else -> content()
    }
    val profileNpub =
        when (route) {
            is ProfileForegroundRoute.ShellProfile -> route.npub
            is ProfileForegroundRoute.ConversationProfile -> route.npub
            else -> null
        }
    profileNpub?.let {
        ProfileSheet(
            appState = appState,
            npub = it,
            onOpenGroup = onOpenConversation,
            onStartGroup = { candidate ->
                appState.clearPresentedProfile()
                foregroundState.open(candidate)
            },
            onDismiss = onDismissProfile,
            adminController = conversationController,
            securePolicy = profileSecurePolicy,
            openGroupsInCommon = appState.pendingProfileGroupsInCommon,
        )
    }
}
