package dev.ipf.whitenoise.android.ui.navigation

import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.ipf.whitenoise.android.state.ChatListItem
import dev.ipf.whitenoise.android.state.ChatsController
import dev.ipf.whitenoise.android.state.ConversationController
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.state.bindBlockListMirror
import dev.ipf.whitenoise.android.ui.conversation.ConversationScrollSnapshot
import dev.ipf.whitenoise.android.ui.conversation.media.PendingCameraCaptureFiles
import dev.ipf.whitenoise.android.ui.settings.StartupDiagnosticsCloseOwner

/**
 * Process-owned presentation state for the one active shell.
 *
 * This transfers the existing live controller across a fresh Activity; it is
 * not a second protocol cache. Account/runtime mismatches close the old
 * controller, and process death still restores from MDK's local projection.
 */
internal class MainShellProcessState(
    private val appState: WhiteNoiseAppState,
) {
    val startupDiagnosticsPrompt = StartupDiagnosticsCloseOwner()

    val pendingCameraCaptureFiles = PendingCameraCaptureFiles()

    /** The single route owner. Process-retained, so the open route survives Activity recreation and app lock. */
    val shellNavigator = ShellNavigator()

    /** The open conversation's row, read from [shellNavigator]. */
    val selectedChat: State<ChatListItem?> = shellNavigator.selectedChat

    /** The open conversation's open context, read from [shellNavigator]. */
    val selectedChatOpenContext: State<ConversationOpenContext> = shellNavigator.selectedChatOpenContext
    val selectedChatJustCreated = mutableStateOf(false)
    val selectedChatOpenedAsDmHint = mutableStateOf(false)
    val conversationScrollSnapshots = mutableStateMapOf<String, ConversationScrollSnapshot>()

    /** First-frame priority of the notification-routed conversation, which must outlive any shell unmount (#586). */
    val notificationFirstFrame = NotificationFirstFramePriority { appState.runtimeGeneration }

    private var chatsEntry by mutableStateOf<RetainedChatsController?>(null)
    private var initialConnectionPresentationAvailable = true
    private val conversationControllers = linkedMapOf<ConversationControllerKey, ConversationController>()
    private var conversationMediaUploadEpoch = appState.mediaUploadSessionEpoch()

    /** The account the retained route owner is bound to, which a conversation without a pinned account belongs to. */
    val routeOwnerAccountRef: String?
        get() = chatsEntry?.accountRef

    /**
     * Returns the retained controller for [accountRef] and [runtimeGeneration], or replaces it
     * while carrying the process-scoped cold-start presentation claim forward. A live same-process
     * replacement also transfers account-scoped optimistic state before the new owner is exposed.
     */
    fun chatsController(
        accountRef: String?,
        runtimeGeneration: Int,
    ): ChatsController {
        val current = chatsEntry
        if (current != null && current.accountRef == accountRef && current.runtimeGeneration == runtimeGeneration) {
            return current.controller
        }
        clearRetainedRoute()
        clearConversationControllers()
        // Only an exact notification-owned route survives a same-runtime account replacement, see the reducer.
        shellNavigator.dispatch(
            if (accountRef == null) {
                ShellNavEvent.OwnerLost(runtimeGeneration)
            } else {
                ShellNavEvent.OwnerReplaced(
                    previousAccountRef = current?.accountRef,
                    previousRuntimeGeneration = current?.runtimeGeneration,
                    accountRef = accountRef,
                    runtimeGeneration = runtimeGeneration,
                )
            },
        )
        val controller =
            ChatsController(
                appState = appState,
                initialConnectionAttemptClaim = ::claimInitialConnectionPresentation,
            )
        chatsEntry =
            RetainedChatsController(
                accountRef = accountRef,
                runtimeGeneration = runtimeGeneration,
                controller = controller,
            )
        if (current == null) {
            appState.attachChatsController(controller)
        } else {
            appState.replaceChatsController(current.controller, controller)
            current.controller.onCleared()
        }
        // The block list is account-scoped like the chats controller, so it follows the same replacement.
        appState.bindBlockListMirror(accountRef)
        return controller
    }

    /** Grants the cold-process connection presentation exactly once. */
    private fun claimInitialConnectionPresentation(): Boolean {
        if (!initialConnectionPresentationAvailable) return false
        initialConnectionPresentationAvailable = false
        return true
    }

    /** Reuses a conversation only within its captured media session, retaining its route and scroll hints. */
    fun conversationController(
        chatId: String,
        accountRef: String,
        runtimeGeneration: Int,
        presentationKey: Int,
        create: () -> ConversationController,
    ): ConversationController {
        val mediaUploadEpoch = appState.mediaUploadSessionEpoch()
        if (conversationMediaUploadEpoch != mediaUploadEpoch) {
            clearConversationControllers()
            conversationMediaUploadEpoch = mediaUploadEpoch
        }
        val key = ConversationControllerKey(chatId, accountRef, runtimeGeneration, presentationKey)
        return conversationControllers.getOrPut(key, create)
    }

    /**
     * Drop controllers after their navigation/animation owners are gone. This
     * is called from a committed composition, never from composition disposal,
     * so Activity recreation cannot destroy the warm first-frame projection.
     */
    fun retainConversationControllers(controllers: Collection<ConversationController>) {
        val retained = controllers.toSet()
        val iterator = conversationControllers.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (entry.value !in retained) {
                val retiredGroupId = entry.value.group.groupIdHex
                val keepsOrigin =
                    retained.any {
                        it.boundAccountRef == entry.key.accountRef && it.group.groupIdHex == retiredGroupId
                    }
                if (!keepsOrigin) pendingCameraCaptureFiles.releaseOrigin("${entry.key.accountRef}:$retiredGroupId")
                appState.detachConversationController(entry.value)
                entry.value.onCleared()
                iterator.remove()
            }
        }
    }

    fun localProjectionAvailable(
        activeAccountRef: String?,
        runtimeGeneration: Int,
    ): Boolean {
        val entry = chatsEntry ?: return false
        return entry.accountRef == activeAccountRef &&
            entry.runtimeGeneration == runtimeGeneration &&
            entry.controller.boundAccountRef == activeAccountRef &&
            entry.controller.hasLoadedLocalSnapshot
    }

    /** Returns only the current account/runtime's unfinished projection owner for startup recovery. */
    fun startupRecoveryController(
        activeAccountRef: String?,
        runtimeGeneration: Int,
    ): ChatsController? {
        val entry = chatsEntry ?: return null
        return entry.controller.takeIf {
            entry.accountRef == activeAccountRef &&
                entry.runtimeGeneration == runtimeGeneration &&
                it.boundAccountRef == activeAccountRef &&
                !it.hasLoadedLocalSnapshot
        }
    }

    /**
     * A removed Android task is a navigation boundary even when Keep Connected
     * retains this process. Drop conversation-only state so a later launcher
     * Activity starts at the warm chat list instead of reopening private content.
     */
    fun onTaskRemoved() {
        notificationFirstFrame.release()
        clearConversationControllers()
        clearRetainedRoute()
        shellNavigator.dispatch(ShellNavEvent.TaskRemoved)
    }

    /** Releases the first-frame priority, every retained controller and the retained route of this process state. */
    fun release() {
        notificationFirstFrame.release()
        clearConversationControllers()
        clearRetainedRoute()
        shellNavigator.dispatch(ShellNavEvent.ShellReleased)
        val controller = chatsEntry?.controller
        chatsEntry = null
        appState.runtimeMirrors.blocks.stop()
        if (controller != null) {
            appState.attachChatsController(null)
            controller.onCleared()
        }
    }

    private fun clearConversationControllers() {
        pendingCameraCaptureFiles.releaseAll()
        conversationControllers.values.forEach { controller ->
            appState.detachConversationController(controller)
            controller.onCleared()
        }
        conversationControllers.clear()
    }

    /** Clears the open-time flags and scroll hints that belong to a route the navigator is about to drop. */
    private fun clearRetainedRoute() {
        selectedChatJustCreated.value = false
        selectedChatOpenedAsDmHint.value = false
        conversationScrollSnapshots.clear()
    }

    private data class RetainedChatsController(
        val accountRef: String?,
        val runtimeGeneration: Int,
        val controller: ChatsController,
    )

    private data class ConversationControllerKey(
        val chatId: String,
        val accountRef: String,
        val runtimeGeneration: Int,
        val presentationKey: Int,
    )
}
