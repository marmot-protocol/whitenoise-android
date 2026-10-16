package dev.ipf.whitenoise.android.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableLongState
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import dev.ipf.whitenoise.android.notifications.NotificationMessagePreload
import dev.ipf.whitenoise.android.notifications.NotificationMessagePreloadKey
import dev.ipf.whitenoise.android.notifications.NotificationTarget
import dev.ipf.whitenoise.android.notifications.notificationMessagePreloadKey
import dev.ipf.whitenoise.android.state.ChatListItem
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState

/*
 * Notification-open route lifetimes (#3309), recorded before the orchestration left MainShell.
 *
 * Entry contract: MainActivity parses a tap into [NotificationTarget] plus a monotonically
 * advancing request id held by its retained NotificationInboundState, WhiteNoiseApp passes both
 * to MainShell, and the shell acknowledges through onNotificationTargetHandled. The Activity
 * clears its pending target only when the acknowledged target and request id both still match.
 *
 * What survives what:
 * - Recomposition keeps every slot below. Each keyed slot starts fresh when its key changes, and
 *   a callback captured earlier keeps writing the retired instance, never its replacement:
 *   the routing overlay per request id, the invite flags per request id and target identity, the
 *   exact preload per preload key and runtime generation, and the switch, early-open and
 *   active-retry ids per runtime generation. The armed request id lives for the composition.
 * - Activity recreation and shell unmount drop all of those composition slots. What remains is
 *   the Activity's pending target and request id, the process-owned selection, open context and
 *   first-frame priority in MainShellProcessState, the saved section state in
 *   MainShellStateHolder, and any work already launched into the app's process scope.
 * - The other shell owners the port writes keep their own lifetimes: shellNavState and
 *   settingsHomeViewport are rememberSaveable in MainShell, so they survive Activity recreation
 *   through the saved instance state but are dropped on shell unmount, and chatListReturnHeadSnap
 *   is a plain remember, dropped on both.
 *
 * What is cancelled:
 * - The keyed routing effect is cancelled on every key change and on unmount. That cancels a
 *   card dismissal still in flight (so nothing is published before it returns), the exact read of
 *   an already-active account and the authoritative invite probe.
 * - Not cancelled: the inactive-account switch (its preload, activation and post-activation
 *   reconciliation) and the single post-activation exact retry run in the process scope,
 *   because the effect is keyed on the active account that the switch itself replaces.
 *
 * How stale completion is rejected:
 * - Request identity: process-scoped completions compare the Activity's current request id and
 *   target through [NotificationOpenCurrency] before they publish or reconcile anything.
 * - Runtime identity: the same check also requires the live runtime generation, so a callback
 *   whose shell has gone away still sees a runtime replacement.
 * - Account identity: activation runs only while the request still owns its switch, and an
 *   early-opened conversation is pinned to its target account.
 * - First-frame priority: a scoped release names its request id, and a process-scoped switch
 *   route releases only the gate object it began, so a retired route never ends a newer one. A
 *   switch whose gate a same-request successor released while a window stays held stands down
 *   entirely: a shell remounted after App Lock or Activity recreation re-runs the request with
 *   fresh slots, and request identity cannot tell the two routes apart.
 * - Card dismissal, reply-draft staging and the pinned-shortcut authority recheck stay inside the
 *   commit, around its only suspension.
 */

/** The Activity's pending tap as one value, so both halves are compared together. */
internal data class NotificationOpenRequest(
    val target: NotificationTarget?,
    val requestId: Long,
)

/**
 * Reads whether a route is still the one the Activity offers. The request reader follows the
 * composition that created it, and the runtime check also reads the live generation, so a retired
 * process-scoped callback rejects a replaced runtime even after its shell stopped composing.
 */
internal class NotificationOpenCurrency(
    private val request: State<NotificationOpenRequest>,
    private val composedRuntimeGeneration: State<Int>,
    private val liveRuntimeGeneration: () -> Int,
) {
    /** The request id the Activity currently offers. */
    val requestId: Long
        get() = request.value.requestId

    /** The target the Activity currently offers, or null once it was consumed or cleared. */
    val target: NotificationTarget?
        get() = request.value.target

    /** True while [runtimeGeneration] is both the composed and the live runtime. */
    fun runtimeIsCurrent(runtimeGeneration: Int): Boolean {
        val composed = composedRuntimeGeneration.value
        return composed == runtimeGeneration && liveRuntimeGeneration() == runtimeGeneration
    }

    /** True while the Activity still offers exactly [target] under [requestId] on [runtimeGeneration]. */
    fun offers(
        requestId: Long,
        target: NotificationTarget,
        runtimeGeneration: Int,
    ): Boolean = this.requestId == requestId && this.target == target && runtimeIsCurrent(runtimeGeneration)
}

/** Ids that belong to one runtime generation: a runtime replacement starts every one of them over. */
internal class NotificationRuntimeRouteSlots {
    /** The request whose account switch is already in flight, so a re-fired effect does not start it again. */
    var accountSwitchRequestId by mutableStateOf<Long?>(null)

    /** The request whose conversation opened before its account switch landed (#586). */
    var earlyOpenRequestId by mutableLongStateOf(0L)

    /** The request that already spent its one exact retry after activation. */
    var activeRetryRequestId by mutableStateOf<Long?>(null)
}

/** Authoritative invite probing for one request and target identity. */
internal class NotificationInviteRouteSlots {
    /** True once the authoritative read proved the invited group unavailable. */
    var authoritativelyUnavailable by mutableStateOf(false)

    /** Bounded authoritative probe attempts already spent on this request. */
    var probeAttempts by mutableIntStateOf(0)
}

/** The exact target read for one preload key and runtime generation, never a protocol cache. */
internal class NotificationPreloadSlot(
    val key: NotificationMessagePreloadKey?,
) {
    /** The exact read's latest published state for [key], or null before it starts. */
    var preload by mutableStateOf<NotificationMessagePreload<ChatListItem>?>(null)
}

/**
 * Transient notification-open route state, grouped by the lifetime each slot had inline in MainShell.
 * A fresh wrapper exists for every distinct set of slot instances, so a callback that captured one
 * keeps writing exactly the slots that were current when its pass started.
 */
internal class NotificationOpenRouteState(
    private val routingSlot: MutableState<Boolean>,
    private val armedRequestIdSlot: MutableLongState,
    val runtimeSlots: NotificationRuntimeRouteSlots,
    val inviteSlots: NotificationInviteRouteSlots,
    val preloadSlot: NotificationPreloadSlot,
    val currency: NotificationOpenCurrency,
) {
    /** True while the shell holds one stable loading surface over a multi-step route. */
    var routing by routingSlot

    /** The request id whose arming already revoked competing routes in this composition. */
    var armedRequestId by armedRequestIdSlot

    /** Request id whose conversation opened before its account switch landed, or 0L. */
    val earlyOpenRequestId: Long
        get() = runtimeSlots.earlyOpenRequestId
}

/**
 * Remembers the route slots with the exact keys MainShell used inline, plus the currency readers
 * that process-scoped completions consult.
 */
@Composable
internal fun rememberNotificationOpenRouteState(
    appState: WhiteNoiseAppState,
    inboundTarget: NotificationTarget?,
    inboundRequestId: Long,
): NotificationOpenRouteState {
    val runtimeGeneration = appState.runtimeGeneration
    val routingSlot = remember(inboundRequestId) { mutableStateOf(inboundTarget != null) }
    val armedRequestIdSlot = remember { mutableLongStateOf(0L) }
    val runtimeSlots = remember(runtimeGeneration) { NotificationRuntimeRouteSlots() }
    val inviteSlots =
        remember(inboundRequestId, inboundTarget?.accountRef, inboundTarget?.groupIdHex, inboundTarget?.kind) {
            NotificationInviteRouteSlots()
        }
    val preloadKey = notificationMessagePreloadKey(target = inboundTarget, requestId = inboundRequestId)
    val preloadSlot = remember(preloadKey, runtimeGeneration) { NotificationPreloadSlot(preloadKey) }
    val request = rememberUpdatedState(NotificationOpenRequest(inboundTarget, inboundRequestId))
    val composedRuntimeGeneration = rememberUpdatedState(runtimeGeneration)
    val currency =
        remember(appState) {
            NotificationOpenCurrency(request, composedRuntimeGeneration) { appState.runtimeGeneration }
        }
    return remember(routingSlot, runtimeSlots, inviteSlots, preloadSlot, currency) {
        NotificationOpenRouteState(routingSlot, armedRequestIdSlot, runtimeSlots, inviteSlots, preloadSlot, currency)
    }
}

/**
 * The shell state a notification open writes, injected so the route never owns a second copy of it.
 * Every write lands in the shell's existing owners: its retained selection, the saved section state
 * and the navigation reducer.
 */
internal interface NotificationOpenShell {
    /** The open context of the currently selected conversation, read live at call time. */
    val selectedOpenContext: ConversationOpenContext

    /** Revokes competing playback-return, create and profile routes when a new tap is armed. */
    fun armRequest()

    /** Closes the selected conversation before an account switch, without leaving Chats. */
    fun closeConversationForAccountSwitch()

    /** Returns a rejected route to Chats, revoking the selection and its open-time state. */
    fun fallBackToChatList()

    /** Publishes [item] as the routed conversation with [openContext], after card dismissal returned. */
    fun openRoutedConversation(
        item: ChatListItem,
        openContext: ConversationOpenContext,
    )

    /** Acknowledges [target] for [requestId] to the Activity, which clears only a matching pending tap. */
    fun targetHandled(
        target: NotificationTarget,
        requestId: Long,
    )
}

/**
 * Runs one notification-open pass whenever the tap, the account, the runtime or the chat-list state it
 * decides on changes. Every key matches the effect MainShell used to own, so the pass re-fires exactly
 * when it did before.
 */
@Composable
@Suppress("FunctionNaming") // Jetpack Compose functions use UpperCamelCase.
internal fun NotificationOpenRouteEffect(
    router: NotificationOpenRouter,
    inboundTarget: NotificationTarget?,
    inboundRequestId: Long,
) {
    val appState = router.appState
    val chats = router.chats
    val route = router.state
    LaunchedEffect(
        inboundTarget,
        inboundRequestId,
        inboundTarget?.replyDraft != null && appState.appInForeground,
        inboundTarget?.shortcutCapability != null && appState.appUnlockEvaluationPending,
        inboundTarget?.shortcutCapability != null && appState.appLockScreenVisible,
        appState.activeAccountRef,
        appState.runtimeGeneration,
        appState.accounts,
        chats,
        chats.boundAccountRef,
        chats.isLoading,
        chats.items,
        chats.materializedGroupsRevision,
        route.inviteSlots.authoritativelyUnavailable,
        route.preloadSlot.preload,
    ) {
        router.route(inboundTarget, inboundRequestId)
    }
}
