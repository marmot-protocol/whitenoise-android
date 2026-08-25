package dev.ipf.whitenoise.android.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.CreationExtras
import dev.ipf.whitenoise.android.state.AppPhase
import dev.ipf.whitenoise.android.state.ChatListItem
import dev.ipf.whitenoise.android.state.ChatsController
import dev.ipf.whitenoise.android.state.ConversationController
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.ui.conversation.ConversationScrollSnapshot

/**
 * Activity-owned state for the shell's useful route.
 *
 * Live FFI-backed projections stay in memory only. [SavedStateHandle] contains
 * lightweight account/group route keys so process restoration can re-resolve
 * the conversation from MDK's local projection without serializing protocol
 * records into Android saved state.
 */
internal class MainShellStateHolder(
    private val appState: WhiteNoiseAppState,
    private val savedStateHandle: SavedStateHandle,
) : ViewModel() {
    val selectedChat = mutableStateOf<ChatListItem?>(null)
    val selectedChatOpenContext = mutableStateOf(ConversationOpenContext())
    val selectedChatJustCreated = mutableStateOf(false)
    val selectedChatOpenedAsDmHint = mutableStateOf(false)
    val conversationScrollSnapshots = mutableStateMapOf<String, ConversationScrollSnapshot>()

    // Compose observes controller ownership so a cold/process-restored shell
    // re-evaluates readiness after [PrepareMainShellFirstFrame] installs the
    // controller. The controller's own snapshot state drives the next update.
    private var chatsEntry by mutableStateOf<RetainedChatsController?>(null)
    private val conversationControllers = linkedMapOf<ConversationControllerKey, ConversationController>()
    private var savedAccountRef: String? = savedStateHandle[SAVED_ACCOUNT_REF_KEY]
    private var savedGroupIdHex: String? = savedStateHandle[SAVED_GROUP_ID_KEY]
    private var savedRouteResolutionPending = savedGroupIdHex != null

    val hasSavedConversationRoute: Boolean
        get() = savedAccountRef != null && savedGroupIdHex != null

    fun chatsController(
        accountRef: String?,
        runtimeGeneration: Int,
    ): ChatsController {
        val current = chatsEntry
        if (current != null && current.accountRef == accountRef && current.runtimeGeneration == runtimeGeneration) {
            return current.controller
        }
        if (current != null) {
            appState.attachChatsController(null)
            current.controller.onCleared()
        }
        clearConversationControllers()
        val controller = ChatsController(appState)
        chatsEntry =
            RetainedChatsController(
                accountRef = accountRef,
                runtimeGeneration = runtimeGeneration,
                controller = controller,
            )
        appState.attachChatsController(controller)
        return controller
    }

    /** Resolve a process-restored route only after the active account's local projection is authoritative. */
    fun restoreConversationIfReady(
        controller: ChatsController,
        activeAccountRef: String?,
    ) {
        val groupIdHex = savedGroupIdHex ?: return
        val canResolve =
            savedRouteResolutionPending &&
                selectedChat.value == null &&
                activeAccountRef != null &&
                controller.boundAccountRef == activeAccountRef &&
                controller.hasLoadedLocalSnapshot
        if (!canResolve) return
        if (savedAccountRef == null || savedAccountRef != activeAccountRef) {
            clearSavedConversationRoute()
        } else {
            selectedChat.value =
                (controller.items + controller.archivedItems)
                    .firstOrNull { it.group.groupIdHex.equals(groupIdHex, ignoreCase = true) }
            selectedChatOpenContext.value = ConversationOpenContext()
            selectedChatJustCreated.value = false
            selectedChatOpenedAsDmHint.value = false
            savedRouteResolutionPending = false
            if (selectedChat.value == null) clearSavedConversationRoute()
        }
    }

    /** Keep only lightweight keys in Android saved state; the live item remains Activity-scoped. */
    fun persistConversationRoute(accountRef: String?) {
        if (savedRouteResolutionPending && selectedChat.value == null) return
        val groupIdHex = selectedChat.value?.group?.groupIdHex
        if (groupIdHex == null || accountRef == null) {
            clearSavedConversationRoute()
            return
        }
        savedAccountRef = accountRef
        savedGroupIdHex = groupIdHex
        savedRouteResolutionPending = false
        savedStateHandle[SAVED_ACCOUNT_REF_KEY] = accountRef
        savedStateHandle[SAVED_GROUP_ID_KEY] = groupIdHex
    }

    fun conversationController(
        chatId: String,
        accountRef: String,
        runtimeGeneration: Int,
        presentationKey: Int,
        create: () -> ConversationController,
    ): ConversationController {
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
                appState.detachConversationController(entry.value)
                entry.value.onCleared()
                iterator.remove()
            }
        }
    }

    fun firstUsefulFrameReady(
        phase: AppPhase,
        activeAccountRef: String?,
        runtimeGeneration: Int,
        appLockScreenVisible: Boolean,
    ): Boolean =
        when {
            appLockScreenVisible -> true
            phase == AppPhase.Bootstrapping -> false
            phase != AppPhase.Ready -> true
            else -> localProjectionAvailable(activeAccountRef, runtimeGeneration)
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

    override fun onCleared() {
        release()
        super.onCleared()
    }

    /** Releases manually-owned holders used by isolated composable tests. */
    fun release() {
        clearConversationControllers()
        val controller = chatsEntry?.controller
        chatsEntry = null
        if (controller != null) {
            appState.attachChatsController(null)
            controller.onCleared()
        }
    }

    private fun clearSavedConversationRoute() {
        savedAccountRef = null
        savedGroupIdHex = null
        savedRouteResolutionPending = false
        savedStateHandle.remove<String>(SAVED_ACCOUNT_REF_KEY)
        savedStateHandle.remove<String>(SAVED_GROUP_ID_KEY)
    }

    private fun clearConversationControllers() {
        conversationControllers.values.forEach { controller ->
            appState.detachConversationController(controller)
            controller.onCleared()
        }
        conversationControllers.clear()
    }

    class Factory(
        private val appState: WhiteNoiseAppState,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(
            modelClass: Class<T>,
            extras: CreationExtras,
        ): T {
            require(modelClass.isAssignableFrom(MainShellStateHolder::class.java))
            return MainShellStateHolder(appState, extras.createSavedStateHandle()) as T
        }
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

    private companion object {
        const val SAVED_ACCOUNT_REF_KEY = "main_shell_selected_account_ref"
        const val SAVED_GROUP_ID_KEY = "main_shell_selected_group_id"
    }
}

internal enum class WarmResumeFirstUsefulSurface {
    AppLock,
    InboundRoute,
    RestoredShell,
    Startup,
}

internal fun warmResumeFirstUsefulSurface(
    appLockScreenVisible: Boolean,
    inboundRoutePending: Boolean,
    shellReady: Boolean,
): WarmResumeFirstUsefulSurface =
    when {
        appLockScreenVisible -> WarmResumeFirstUsefulSurface.AppLock
        inboundRoutePending -> WarmResumeFirstUsefulSurface.InboundRoute
        shellReady -> WarmResumeFirstUsefulSurface.RestoredShell
        else -> WarmResumeFirstUsefulSurface.Startup
    }

internal fun shouldComposeProtectedMainShell(surface: WarmResumeFirstUsefulSurface): Boolean =
    surface == WarmResumeFirstUsefulSurface.InboundRoute ||
        surface == WarmResumeFirstUsefulSurface.RestoredShell

/** Load only the retained local projection while no protected shell content is composed. */
@Composable
@Suppress("FunctionNaming") // Jetpack Compose functions use UpperCamelCase.
internal fun PrepareMainShellFirstFrame(
    appState: WhiteNoiseAppState,
    stateHolder: MainShellStateHolder,
) {
    val controller =
        stateHolder.chatsController(
            accountRef = appState.activeAccountRef,
            runtimeGeneration = appState.runtimeGeneration,
        )
    LaunchedEffect(
        controller,
        appState.activeAccountRef,
        appState.runtimeGeneration,
        controller.retryGeneration,
    ) {
        controller.bind(
            accountRef = appState.activeAccountRef,
            preserveLoadedContent = controller.retryGeneration > 0L || controller.hasLoadedLocalSnapshot,
        )
    }
}
