package dev.ipf.whitenoise.android.ui.chats

import dev.ipf.whitenoise.android.state.ChatListItem
import dev.ipf.whitenoise.android.state.ChatsController
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState

/** Captured when the action opens the dialog, not recreated by later composition. */
internal class PendingLocalChatDelete private constructor(
    val groupIds: List<String>,
    private val controller: ChatsController,
    private val ownerState: WhiteNoiseAppState,
    private val account: String?,
    private val runtime: Int,
    private val bindEpoch: Long,
) {
    fun isCurrent(
        appState: WhiteNoiseAppState,
        currentController: ChatsController,
    ): Boolean {
        if (account == null || appState !== ownerState || currentController !== controller) return false
        return controller.accountRef == account &&
            controller.isActiveBindEpoch(bindEpoch) &&
            accountIsCurrent(appState)
    }

    private fun accountIsCurrent(appState: WhiteNoiseAppState): Boolean =
        appState.activeAccountRef == account &&
            appState.runtimeGeneration == runtime &&
            !accountIsSuspended(appState)

    private fun accountIsSuspended(appState: WhiteNoiseAppState): Boolean =
        appState.signOutInProgress || appState.wipeInProgress || appState.retainedAccountReactivationRef != null

    companion object {
        fun capture(
            items: List<ChatListItem>,
            controller: ChatsController,
            appState: WhiteNoiseAppState,
        ) = PendingLocalChatDelete(
            items.map { it.group.groupIdHex }.distinctBy { it.lowercase() },
            controller,
            appState,
            controller.accountRef,
            appState.runtimeGeneration,
            controller.bindEpoch,
        )
    }
}
