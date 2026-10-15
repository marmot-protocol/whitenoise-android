package dev.ipf.whitenoise.android.ui.navigation

import dev.ipf.whitenoise.android.notifications.NotificationMessagePreload
import dev.ipf.whitenoise.android.notifications.NotificationMessagePreloadState
import dev.ipf.whitenoise.android.notifications.NotificationRouteFirstFrameGate
import dev.ipf.whitenoise.android.notifications.NotificationRouteTrace
import dev.ipf.whitenoise.android.notifications.NotificationRouteTraceSection
import dev.ipf.whitenoise.android.notifications.awaitNotificationAccountActivationBoundary
import dev.ipf.whitenoise.android.notifications.notificationPreloadStuckLoading
import dev.ipf.whitenoise.android.notifications.runInactiveNotificationRouteStage
import dev.ipf.whitenoise.android.state.AccountSwitchPreloadPolicy
import dev.ipf.whitenoise.android.state.isSignedInSigningAccount
import dev.ipf.whitenoise.android.state.pinnedShortcutTargetIsCurrent

/**
 * Whether a switch that never landed may close the conversation. A route that never opened one falls back
 * as before. One that opened early closes it only while the reader is still inside it, so navigation they
 * performed since then is never undone by a now-stale failure.
 */
internal fun notificationSwitchFailureClosesConversation(
    earlyOpened: Boolean,
    stillInsideEarlyOpen: Boolean,
): Boolean = !earlyOpened || stillInsideEarlyOpen

/**
 * One inactive-account switch for one notification request, run in the app's process scope.
 *
 * The routing pass is keyed on the active account, so an inline suspend switch would cancel itself the
 * moment the ref flips. This work therefore outlives the pass that started it, and every write it makes
 * afterwards is fenced: by the request and live runtime through [NotificationOpenCurrency], by the pin
 * authority recheck, and by releasing only the first-frame gate it began itself.
 */
internal class NotificationAccountSwitchRoute(
    private val owners: NotificationOpenOwners,
    private val completion: NotificationOpenCompletion,
    private val attempt: NotificationOpenAttempt,
    private val accountRef: String,
) {
    private val appState = owners.appState
    private val state = owners.state
    private val currency = state.currency
    private val preloadSlot = state.preloadSlot
    private val runtimeSlots = state.runtimeSlots
    private val requestId = attempt.requestId
    private val target = attempt.target
    private val routeRuntimeGeneration = appState.runtimeGeneration
    private val canUseTargetFirst = preloadSlot.key != null && appState.accounts.any { it.label == accountRef }
    private val canPreload =
        canUseTargetFirst && appState.accounts.any { it.label == accountRef && it.isSignedInSigningAccount() }
    private val ownedGate =
        if (canUseTargetFirst) NotificationRouteFirstFrameGate(requestId = requestId, accountRef = accountRef) else null

    /** Publishes the exact preload's Loading state, takes first-frame priority and starts the process-scoped switch. */
    fun launch() {
        if (canPreload) {
            preloadSlot.preload =
                NotificationMessagePreload(requireNotNull(preloadSlot.key), NotificationMessagePreloadState.Loading)
        }
        ownedGate?.let(owners.firstFrame::begin)
        appState.launchMutation {
            try {
                runSwitch()
            } finally {
                releaseStuckPreload()
            }
        }
    }

    /**
     * Allows only this notification request and runtime to finish its account switch, including its own early
     * open. An early-opened conversation consumes the tap (clearing the inbound target) while its switch is still
     * landing, so the switch stays current either while the target is still armed or after this exact request
     * committed an early open (#586).
     */
    private fun switchStillCurrent(): Boolean =
        currency.requestId == requestId &&
            currency.runtimeIsCurrent(routeRuntimeGeneration) &&
            (currency.target == target || runtimeSlots.earlyOpenRequestId == requestId) &&
            appState.pinnedShortcutTargetIsCurrent(target)

    /** Runs the owned preload and activation, then reconciles what activation left behind. */
    private suspend fun runSwitch() {
        val preloadKey = preloadSlot.key
        if (canPreload && preloadKey != null) {
            runInactiveNotificationRouteStage(
                key = preloadKey,
                loadTarget = {
                    NotificationRouteTrace.tracePhase(requestId, NotificationRouteTraceSection.TARGET_PROJECTION) {
                        appState.preloadNotificationChatListItem(
                            accountRef = preloadKey.accountRef,
                            groupIdHex = preloadKey.groupIdHex,
                        )
                    }
                },
                activateAccount = ::activateAccount,
                isCurrent = { currency.offers(requestId, target, routeRuntimeGeneration) },
                // Published before activation completes: a ready local read re-fires the
                // routing pass, which commits the conversation open immediately while the
                // switch keeps settling behind it.
                onPreload = { preloadSlot.preload = it },
            )
        } else {
            activateAccount()
        }
        reconcileAfterActivation()
    }

    /**
     * Activates the target account and returns at its local-ready boundary. The boundary is signalled from
     * setActiveAccount's callback, so its profile, privacy and push follow-up work keeps running in the process
     * scope without holding the route.
     */
    private suspend fun activateAccount() {
        NotificationRouteTrace.beginPhase(requestId, NotificationRouteTraceSection.ACCOUNT_ACTIVATION)
        awaitNotificationAccountActivationBoundary { onLocalReady ->
            appState.launchMutation {
                try {
                    appState.setActiveAccount(
                        label = accountRef,
                        shouldActivate = ::switchStillCurrent,
                        preloadPolicy =
                            if (ownedGate != null) {
                                AccountSwitchPreloadPolicy.TARGET_CONVERSATION_FIRST
                            } else {
                                AccountSwitchPreloadPolicy.FULL_LOCAL_SNAPSHOT
                            },
                        awaitPostActivationWork = { ownedGate?.awaitRelease() },
                        onActivated = {
                            NotificationRouteTrace.endPhase(requestId, NotificationRouteTraceSection.ACCOUNT_ACTIVATION)
                            onLocalReady()
                        },
                    )
                } finally {
                    NotificationRouteTrace.endPhase(requestId, NotificationRouteTraceSection.ACCOUNT_ACTIVATION)
                    onLocalReady()
                }
            }
        }
    }

    /** Fails a pin that lost authority, or a switch that never landed, but only while this request owns it. */
    private fun reconcileAfterActivation() {
        val offered = currency.offers(requestId, target, routeRuntimeGeneration)
        if (offered && completion.rejectUnavailablePin(attempt, releaseGate = ::releaseOwnedGate)) return
        if (!switchStillCurrent() || appState.activeAccountRef == accountRef) return
        val earlyOpened = runtimeSlots.earlyOpenRequestId == requestId
        val stillInsideEarlyOpen = owners.shell.selectedOpenContext.notificationRouteTraceRequestId == requestId
        preloadSlot.preload = null
        runtimeSlots.earlyOpenRequestId = 0L
        releaseOwnedGate()
        state.routing = false
        if (notificationSwitchFailureClosesConversation(earlyOpened, stillInsideEarlyOpen)) {
            owners.shell.fallBackToChatList()
        }
        owners.shell.targetHandled(target, requestId)
        NotificationRouteTrace.finishRequest(requestId)
    }

    /**
     * A route that died before publishing leaves this request's Loading preload in place. The direct-load branch
     * maps Loading to "keep waiting", so it would pin the loading overlay forever. Release both, but leave the
     * overlay to a replacement runtime's own pass of the same request.
     */
    private fun releaseStuckPreload() {
        if (notificationPreloadStuckLoading(preloadSlot.preload, preloadSlot.key)) {
            preloadSlot.preload = null
            releaseOwnedGate()
            if (currency.requestId == requestId && currency.runtimeIsCurrent(routeRuntimeGeneration)) {
                state.routing = false
            }
        }
    }

    /** Ends only the first-frame window this route began, never a newer route's. */
    private fun releaseOwnedGate() {
        ownedGate?.let(owners.firstFrame::releaseOwned)
    }
}
