package dev.ipf.whitenoise.android.ui.chats

import dev.ipf.whitenoise.android.state.ChatListItem
import dev.ipf.whitenoise.android.state.ChatsController
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState

/** Captured when the action opens the dialog, not recreated by later composition. */
internal class PendingLocalChatDelete private constructor(
    val groupIds: List<String>,
    val reviewItems: List<ChatListItem>,
    val requireNativeEligibility: Boolean,
    private val controller: ChatsController,
    private val ownerState: WhiteNoiseAppState,
    private val account: String?,
    private val runtime: Int,
    private val bindEpoch: Long,
) {
    val actingAccountRef: String? get() = account

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

    fun remaining(deleted: Int) =
        PendingLocalChatDelete(
            groupIds.drop(deleted),
            reviewItems.filter { it.group.groupIdHex in groupIds.drop(deleted) },
            requireNativeEligibility,
            controller,
            ownerState,
            account,
            runtime,
            bindEpoch,
        )

    companion object {
        fun capture(
            items: List<ChatListItem>,
            controller: ChatsController,
            appState: WhiteNoiseAppState,
            requireNativeEligibility: Boolean = false,
        ): PendingLocalChatDelete {
            val targets =
                items
                    .filter { !requireNativeEligibility || it.actions?.canDeleteLocal == true }
                    .distinctBy { it.group.groupIdHex.lowercase() }
            return PendingLocalChatDelete(
                targets.map { it.group.groupIdHex },
                targets.toList(),
                requireNativeEligibility,
                controller,
                appState,
                controller.accountRef,
                appState.runtimeGeneration,
                controller.bindEpoch,
            )
        }
    }
}
