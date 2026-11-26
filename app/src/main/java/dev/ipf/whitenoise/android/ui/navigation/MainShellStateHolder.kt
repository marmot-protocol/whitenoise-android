package dev.ipf.whitenoise.android.ui.navigation

import android.os.Bundle
import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.CreationExtras
import dev.ipf.whitenoise.android.share.ShareRequest
import dev.ipf.whitenoise.android.share.resolveShareDirectGroupId
import dev.ipf.whitenoise.android.state.AppPhase
import dev.ipf.whitenoise.android.state.ChatsController
import dev.ipf.whitenoise.android.state.ConversationController
import dev.ipf.whitenoise.android.state.RetainedComposerExpansionMode
import dev.ipf.whitenoise.android.state.SavedComposerExpansion
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.ui.chats.GlobalSearchRecentsOwner

/**
 * Activity-owned saved-route adapter over [MainShellProcessState]. Only
 * lightweight account/group keys enter [SavedStateHandle]; live controllers
 * and UI projections transfer by ownership within the surviving process.
 */
@Suppress("TooManyFunctions") // One Activity-owned adapter keeps saved routes and retained shell state coherent.
internal class MainShellStateHolder(
    appState: WhiteNoiseAppState,
    private val savedStateHandle: SavedStateHandle,
    private val processState: MainShellProcessState = MainShellProcessState(appState),
) : ViewModel() {
    private val composerExpansionStateSubscription: AutoCloseable

    /** The process-retained single route owner. */
    val shellNavigator = processState.shellNavigator

    /** This Activity's saved copy of the route's section, written from [shellNavigator]. */
    val sectionState = MainShellSectionState(savedStateHandle, shellNavigator)

    init {
        appState.composerExpansionStateRetention.restoreIfEmpty(
            decodeComposerExpansionSavedState(savedStateHandle[SAVED_COMPOSER_EXPANSION_KEY]),
        )
        composerExpansionStateSubscription =
            appState.composerExpansionStateRetention.observe { records ->
                if (records.isEmpty()) {
                    savedStateHandle.remove<Bundle>(SAVED_COMPOSER_EXPANSION_KEY)
                } else {
                    savedStateHandle[SAVED_COMPOSER_EXPANSION_KEY] = encodeComposerExpansionSavedState(records)
                }
            }
    }

    /**
     * Activity-task-owned inbound share. Keeping this on the retained holder
     * lets configuration recreation preserve the newest request without using
     * process-global state to bridge separate tasks.
     */
    val inboundShareRequest = mutableStateOf<ShareRequest?>(null)
    val pendingShareRequest = mutableStateOf<ShareRequest?>(null)

    /** Recent global-search destinations (#4004): kept across Activity recreation, never in saved state. */
    val globalSearchRecents = GlobalSearchRecentsOwner()

    // The account-switch handoff must outlive lock-driven shell disposal, but only for its own share.
    private var stagedShareOpen by mutableStateOf(
        restoreStagedShareOpen(savedStateHandle.get<Bundle>(SAVED_STAGED_SHARE_OPEN_KEY)).also { restored ->
            if (restored == null) savedStateHandle.remove<Bundle>(SAVED_STAGED_SHARE_OPEN_KEY)
        },
    )

    /** The staged share's pending cross-account chat open, persisted with its request identity and age. */
    var pendingStagedShareOpen: PendingStagedShareOpen?
        get() = stagedShareOpen
        set(value) {
            stagedShareOpen = value
            if (value == null) {
                savedStateHandle.remove<Bundle>(SAVED_STAGED_SHARE_OPEN_KEY)
            } else {
                savedStateHandle[SAVED_STAGED_SHARE_OPEN_KEY] =
                    Bundle().apply {
                        putString("request", value.requestId)
                        putString("account", value.accountRef)
                        putString("group", value.groupIdHex)
                        putInt("other_count", value.otherChatCount)
                        putLong("staged_at", value.stagedAtElapsedMillis)
                    }
            }
        }

    /** The application process owns an unfinished diagnostics dismissal across fresh Activities. */
    fun startupDiagnosticsPrompt(runtimeGeneration: Int) = processState.startupDiagnosticsPrompt(runtimeGeneration)

    val pendingCameraCaptureFiles = processState.pendingCameraCaptureFiles
    val selectedChat = processState.selectedChat
    val selectedChatOpenContext = processState.selectedChatOpenContext
    val selectedChatJustCreated = processState.selectedChatJustCreated
    val selectedChatOpenedAsDmHint = processState.selectedChatOpenedAsDmHint
    val conversationScrollSnapshots = processState.conversationScrollSnapshots
    val notificationFirstFrame = processState.notificationFirstFrame

    /** The account the route owner is bound to, which an unpinned conversation belongs to. */
    val routeOwnerAccountRef: String?
        get() = processState.routeOwnerAccountRef

    private var savedAccountRef: String? = savedStateHandle[SAVED_ACCOUNT_REF_KEY]
    private var savedGroupIdHex: String? = savedStateHandle[SAVED_GROUP_ID_KEY]
    private var savedRouteResolutionPending = savedGroupIdHex != null
    private var savedPendingShareRequestId: String? = savedStateHandle[SAVED_PENDING_SHARE_REQUEST_ID_KEY]

    /** Newest in-memory request that must own the picker route immediately. */
    val visibleShareRequest: ShareRequest?
        get() = inboundShareRequest.value ?: pendingShareRequest.value

    /** Encrypted-store token that can restore an unresolved picker after process death. */
    val pendingShareRequestId: String?
        get() = savedPendingShareRequestId

    /** True while either an in-memory request or its encrypted restore token remains unresolved. */
    val hasPendingShareRoute: Boolean
        get() =
            inboundShareRequest.value != null ||
                pendingShareRequest.value != null ||
                savedPendingShareRequestId != null

    /** Replaces any older unresolved route with the newest external share intent. */
    fun acceptInboundShareRequest(request: ShareRequest?) {
        if (request == null) {
            inboundShareRequest.value = null
            return
        }
        val replacesCurrent = visibleShareRequest?.requestId != request.requestId
        inboundShareRequest.value = request
        if (replacesCurrent) {
            pendingShareRequest.value = null
            savePendingShareRequestId(null)
        }
        // A newer share owns navigation now, so an older share's deferred chat open is retired.
        if (pendingStagedShareOpen?.requestId?.let { it != request.requestId } == true) pendingStagedShareOpen = null
    }

    /** Promotes one parsed intent into the picker-owned route after encrypted persistence is attempted. */
    fun promoteInboundShareRequest(
        request: ShareRequest,
        persisted: Boolean,
    ): Boolean {
        if (inboundShareRequest.value?.requestId != request.requestId) return false
        pendingShareRequest.value = request
        inboundShareRequest.value = null
        savePendingShareRequestId(request.requestId.takeIf { persisted })
        return true
    }

    /** Records successful encrypted persistence while Direct Share validation is still pending. */
    fun markInboundSharePersisted(requestId: String): Boolean {
        if (inboundShareRequest.value?.requestId != requestId) return false
        savePendingShareRequestId(requestId)
        return true
    }

    /** Restores only the request named by the retained saved-state token. */
    fun restorePendingShareRequest(
        expectedRequestId: String,
        request: ShareRequest?,
    ): Boolean {
        val routeIsOccupiedOrMismatched =
            inboundShareRequest.value != null ||
                pendingShareRequest.value != null ||
                savedPendingShareRequestId != expectedRequestId
        return when {
            routeIsOccupiedOrMismatched -> false
            request == null || request.requestId != expectedRequestId -> {
                savePendingShareRequestId(null)
                false
            }
            else -> {
                pendingShareRequest.value = request
                true
            }
        }
    }

    /** Acknowledges only the one-shot intent; the promoted picker route remains owned. */
    fun acknowledgeInboundShareRequest(requestId: String) {
        if (inboundShareRequest.value?.requestId == requestId) inboundShareRequest.value = null
    }

    /** Clears one matching inbound, promoted, or process-restored request. */
    fun clearPendingShareRequest(requestId: String?) {
        requestId ?: return
        if (inboundShareRequest.value?.requestId == requestId) inboundShareRequest.value = null
        if (pendingShareRequest.value?.requestId == requestId) pendingShareRequest.value = null
        if (savedPendingShareRequestId == requestId) savePendingShareRequestId(null)
    }

    val hasSavedConversationRoute: Boolean
        get() = savedAccountRef != null && savedGroupIdHex != null

    fun chatsController(
        accountRef: String?,
        runtimeGeneration: Int,
    ): ChatsController {
        val revision = shellNavigator.revision
        // The owner rebinds first: its boundary event clears the stack before this holder restores its section.
        val controller = processState.chatsController(accountRef, runtimeGeneration)
        sectionState.bind(accountRef, runtimeGeneration)
        if (shellNavigator.revision != revision) auditShellRoute(shellNavigator.lastEventName)
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
            val restored =
                (controller.items + controller.archivedItems)
                    .firstOrNull { it.group.groupIdHex.equals(groupIdHex, ignoreCase = true) }
            selectedChatJustCreated.value = false
            selectedChatOpenedAsDmHint.value = false
            savedRouteResolutionPending = false
            if (restored == null) {
                clearSavedConversationRoute()
            } else {
                dispatchShellNav(
                    ShellNavEvent.RestoreSavedConversation(conversationRoute(restored, ConversationOpenContext())),
                )
            }
        }
    }

    /** Keep only lightweight keys in Android saved state; the live item remains process-scoped UI state. */
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
    ): ConversationController =
        processState.conversationController(
            chatId,
            accountRef,
            runtimeGeneration,
            presentationKey,
            create,
        )

    fun retainConversationControllers(controllers: Collection<ConversationController>) {
        processState.retainConversationControllers(controllers)
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

    /**
     * Materialize the process-owned controller before the splash or Compose
     * routing decision. On process restoration this synchronously consumes the
     * local snapshot prepared by [WhiteNoiseAppState], avoiding a transient
     * startup surface before the first shell frame.
     */
    fun prepareFirstUsefulFrame(
        phase: AppPhase,
        activeAccountRef: String?,
        runtimeGeneration: Int,
        appLockScreenVisible: Boolean,
    ): Boolean {
        when {
            phase == AppPhase.Ready && !appLockScreenVisible ->
                chatsController(activeAccountRef, runtimeGeneration)
            phase == AppPhase.Onboarding || phase is AppPhase.Failed -> {
                sectionState.clear()
                processState.release()
                auditShellRoute(shellNavigator.lastEventName)
            }
        }
        return firstUsefulFrameReady(
            phase = phase,
            activeAccountRef = activeAccountRef,
            runtimeGeneration = runtimeGeneration,
            appLockScreenVisible = appLockScreenVisible,
        )
    }

    /**
     * Prepares the picker-owned local recipient projection without waiting for
     * relay freshness or profile enrichment. A completed local load failure is
     * also renderable because the picker presents an inline error beside its
     * direct empty state instead of replacing the route with a spinner.
     */
    fun prepareSharePickerFirstFrame(
        phase: AppPhase,
        activeAccountRef: String?,
        runtimeGeneration: Int,
        appLockScreenVisible: Boolean,
    ): Boolean =
        when {
            appLockScreenVisible -> true
            phase == AppPhase.Bootstrapping -> false
            phase != AppPhase.Ready -> true
            else -> {
                val controller = chatsController(activeAccountRef, runtimeGeneration)
                controller.boundAccountRef == activeAccountRef && !controller.isLoading
            }
        }

    /** Resolves a visible Direct Share only from the active account's loaded local rows. */
    fun visibleShareDirectGroupId(
        activeAccountRef: String?,
        runtimeGeneration: Int,
    ): String? {
        val request = visibleShareRequest
        return if (request == null || request.shortcutId.isNullOrBlank() || activeAccountRef == null) {
            null
        } else {
            val controller = chatsController(activeAccountRef, runtimeGeneration)
            if (controller.boundAccountRef != activeAccountRef || !controller.hasLoadedLocalSnapshot) {
                null
            } else {
                val localGroupIds =
                    (controller.items + controller.archivedItems)
                        .mapTo(mutableSetOf()) { it.group.groupIdHex }
                resolveShareDirectGroupId(request, activeAccountRef, localGroupIds)
            }
        }
    }

    fun localProjectionAvailable(
        activeAccountRef: String?,
        runtimeGeneration: Int,
    ): Boolean = processState.localProjectionAvailable(activeAccountRef, runtimeGeneration)

    /** Recovery never grants access to the protected shell or consumes its saved route. */
    fun startupRecoveryController(
        activeAccountRef: String?,
        runtimeGeneration: Int,
    ): ChatsController? = processState.startupRecoveryController(activeAccountRef, runtimeGeneration)

    /**
     * Whether the retained notification-routed conversation must keep the shell composed while the
     * target account's chat-list bind is still loading (#586). A shell that unmounts here leaves only a
     * loading surface, and the conversation controller, which only the shell builds, waits behind the bind.
     */
    fun notificationRouteOwnsShell(
        activeAccountRef: String?,
        runtimeGeneration: Int,
        appLockScreenVisible: Boolean,
    ): Boolean =
        notificationFirstFrame.ownsShell(
            RetainedNotificationRouteState(
                conversationSelected = selectedChat.value != null,
                openContext = selectedChatOpenContext.value,
                activeAccountRef = activeAccountRef,
                localProjectionAvailable = localProjectionAvailable(activeAccountRef, runtimeGeneration),
                appLockScreenVisible = appLockScreenVisible,
            ),
        )

    override fun onCleared() {
        // The Application-owned process state transfers to a fresh Activity.
        // Account/runtime replacement and process death remain its cleanup boundaries.
        composerExpansionStateSubscription.close()
        // The process-retained navigator must not keep writing into a finished Activity's saved state.
        sectionState.detach()
        super.onCleared()
    }

    /** Releases manually-owned holders used by isolated composable tests. */
    fun release() {
        composerExpansionStateSubscription.close()
        sectionState.clear()
        processState.release()
        auditShellRoute(shellNavigator.lastEventName)
    }

    private fun clearSavedConversationRoute() {
        savedAccountRef = null
        savedGroupIdHex = null
        savedRouteResolutionPending = false
        savedStateHandle.remove<String>(SAVED_ACCOUNT_REF_KEY)
        savedStateHandle.remove<String>(SAVED_GROUP_ID_KEY)
    }

    /** Saves only the opaque encrypted-store lookup token across process recreation. */
    private fun savePendingShareRequestId(requestId: String?) {
        savedPendingShareRequestId = requestId
        if (requestId == null) {
            savedStateHandle.remove<String>(SAVED_PENDING_SHARE_REQUEST_ID_KEY)
        } else {
            savedStateHandle[SAVED_PENDING_SHARE_REQUEST_ID_KEY] = requestId
        }
    }

    class Factory(
        private val appState: WhiteNoiseAppState,
        private val processState: MainShellProcessState = MainShellProcessState(appState),
        private val onHolderCreated: () -> Unit = {},
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(
            modelClass: Class<T>,
            extras: CreationExtras,
        ): T {
            require(modelClass.isAssignableFrom(MainShellStateHolder::class.java))
            onHolderCreated()
            return MainShellStateHolder(appState, extras.createSavedStateHandle(), processState) as T
        }
    }

    private companion object {
        const val SAVED_ACCOUNT_REF_KEY = "main_shell_selected_account_ref"
        const val SAVED_GROUP_ID_KEY = "main_shell_selected_group_id"
        const val SAVED_PENDING_SHARE_REQUEST_ID_KEY = "main_shell_pending_share_request_id"
        const val SAVED_STAGED_SHARE_OPEN_KEY = "pending_staged_share_open"
        const val SAVED_COMPOSER_EXPANSION_KEY = "main_shell_composer_expansion"
    }
}

/** Encodes only bounded primitive UI geometry in the shell's saved-state Bundle. */
private fun encodeComposerExpansionSavedState(records: List<SavedComposerExpansion>): Bundle =
    Bundle().apply {
        putStringArrayList(COMPOSER_EXPANSION_ACCOUNTS, ArrayList(records.map { it.accountRef }))
        putStringArrayList(COMPOSER_EXPANSION_GROUPS, ArrayList(records.map { it.groupIdHex }))
        putStringArrayList(COMPOSER_EXPANSION_MODES, ArrayList(records.map { it.mode.name }))
        putFloatArray(
            COMPOSER_EXPANSION_HEIGHTS,
            records.map { it.manualHeightDp ?: Float.NaN }.toFloatArray(),
        )
    }

/** Fails closed when a restored Bundle is malformed or its parallel fields do not match. */
@Suppress("ReturnCount") // Each schema field is mandatory; partial geometry must never be restored.
private fun decodeComposerExpansionSavedState(bundle: Bundle?): List<SavedComposerExpansion> {
    bundle ?: return emptyList()
    val accounts = bundle.getStringArrayList(COMPOSER_EXPANSION_ACCOUNTS) ?: return emptyList()
    val groups = bundle.getStringArrayList(COMPOSER_EXPANSION_GROUPS) ?: return emptyList()
    val modes = bundle.getStringArrayList(COMPOSER_EXPANSION_MODES) ?: return emptyList()
    val heights = bundle.getFloatArray(COMPOSER_EXPANSION_HEIGHTS) ?: return emptyList()
    if (accounts.size != groups.size || accounts.size != modes.size || accounts.size != heights.size) {
        return emptyList()
    }
    return accounts.indices.mapNotNull { index ->
        val mode =
            runCatching { RetainedComposerExpansionMode.valueOf(modes[index]) }.getOrNull()
                ?: return@mapNotNull null
        SavedComposerExpansion(
            accountRef = accounts[index],
            groupIdHex = groups[index],
            mode = mode,
            manualHeightDp = heights[index].takeUnless { it.isNaN() },
        )
    }
}

/**
 * Rebuilds a saved staged-share open only when it names its share request and is still fresh. A token
 * from an older build, or one restored long after the share or across a reboot, is dropped.
 */
internal fun restoreStagedShareOpen(
    state: Bundle?,
    nowElapsedMillis: Long = SystemClock.elapsedRealtime(),
): PendingStagedShareOpen? =
    state
        ?.takeIf { it.containsKey("staged_at") }
        ?.let { saved ->
            val request = saved.getString("request")
            val account = saved.getString("account")
            val group = saved.getString("group")
            if (request == null || account == null || group == null) {
                null
            } else {
                PendingStagedShareOpen(
                    requestId = request,
                    accountRef = account,
                    groupIdHex = group,
                    otherChatCount = saved.getInt("other_count"),
                    stagedAtElapsedMillis = saved.getLong("staged_at"),
                )
            }
        }?.takeIf { it.isFresh(nowElapsedMillis) }

private const val COMPOSER_EXPANSION_ACCOUNTS = "account_refs"
private const val COMPOSER_EXPANSION_GROUPS = "group_ids"
private const val COMPOSER_EXPANSION_MODES = "modes"
private const val COMPOSER_EXPANSION_HEIGHTS = "manual_heights_dp"

internal enum class WarmResumeLifecycleClass {
    SameActivity,
    RetainedViewModelActivity,
    FreshActivitySameProcess,
    ProcessRestoration,
    ColdProcessStart,
}

internal fun warmResumeActivityLifecycleClass(
    holderCreatedForActivity: Boolean,
    processProjectionAlreadyOwned: Boolean,
    savedStateAvailable: Boolean,
): WarmResumeLifecycleClass =
    when {
        !holderCreatedForActivity -> WarmResumeLifecycleClass.RetainedViewModelActivity
        processProjectionAlreadyOwned -> WarmResumeLifecycleClass.FreshActivitySameProcess
        savedStateAvailable -> WarmResumeLifecycleClass.ProcessRestoration
        else -> WarmResumeLifecycleClass.ColdProcessStart
    }

internal fun warmResumeForegroundLifecycleClass(
    activityClass: WarmResumeLifecycleClass,
    foregroundEpoch: Int,
): WarmResumeLifecycleClass = if (foregroundEpoch > 1) WarmResumeLifecycleClass.SameActivity else activityClass

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

internal const val ATTACHMENT_OPEN_DESTINATION_UNINITIALIZED = "attachment-open-uninitialized"
internal const val ATTACHMENT_OPEN_DESTINATION_NONE = "attachment-open-none"
