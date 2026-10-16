package dev.ipf.whitenoise.android.ui.navigation

import dev.ipf.whitenoise.android.notifications.NotificationInviteAuthoritativeOutcome
import dev.ipf.whitenoise.android.notifications.NotificationMessageDirectLoadOutcome
import dev.ipf.whitenoise.android.notifications.NotificationMessagePreload
import dev.ipf.whitenoise.android.notifications.NotificationMessagePreloadState
import dev.ipf.whitenoise.android.notifications.NotificationRouteTrace
import dev.ipf.whitenoise.android.notifications.NotificationRouteTraceSection
import dev.ipf.whitenoise.android.notifications.inviteAuthoritativeGroupAvailable
import dev.ipf.whitenoise.android.notifications.loadNotificationMessageDirectly
import dev.ipf.whitenoise.android.notifications.retryInviteAuthoritativeLoad
import dev.ipf.whitenoise.android.notifications.shouldRetryNotificationMessageLoadAfterActivation
import dev.ipf.whitenoise.android.notifications.stateFor
import dev.ipf.whitenoise.android.state.ChatListItem
import dev.ipf.whitenoise.android.state.runCatchingCancellable

/**
 * The exact per-target reads of a notification route: the message conversation read on the active
 * account, its one post-activation retry, and the authoritative invite probe. Each read is MDK's own
 * local projection, and nothing here keeps its result beyond the request-keyed preload slot.
 */
internal class NotificationExactOpenReads(
    private val owners: NotificationOpenOwners,
    private val completion: NotificationOpenCompletion,
) {
    private val appState get() = owners.appState
    private val state get() = owners.state

    /** Opens a message target from its exact read, keeping the routing surface up while one is in flight. */
    suspend fun loadMessage(attempt: NotificationOpenAttempt) {
        state.routing = true
        when (val preloadState = state.preloadSlot.preload.stateFor(state.preloadSlot.key)) {
            NotificationMessagePreloadState.Loading -> Unit
            is NotificationMessagePreloadState.Ready -> completion.commit(attempt, preloadState.item)
            NotificationMessagePreloadState.Failed ->
                if (
                    shouldRetryNotificationMessageLoadAfterActivation(
                        preloadState = preloadState,
                        routingRequestId = attempt.requestId,
                        retriedRequestId = state.runtimeSlots.activeRetryRequestId,
                    )
                ) {
                    retryAfterActivation(attempt)
                } else {
                    // The exact read has answered inconclusively and its one retry is spent.
                    completion.releaseInconclusive(attempt)
                }
            null ->
                when (val outcome = loadNotificationMessageDirectly { readTarget(attempt) }) {
                    is NotificationMessageDirectLoadOutcome.OpenConversation -> completion.commit(attempt, outcome.item)
                    // A transient local-read failure does not consume the tap,
                    // the existing chat-list state will re-fire this route.
                    NotificationMessageDirectLoadOutcome.AwaitChatList -> completion.releaseInconclusive(attempt)
                }
        }
    }

    /**
     * The inactive-account read may fail while account activation is still taking ownership of the native
     * runtime. Retry the exact group once now that the target account is active, keeping the stable routing
     * surface up. If this retry also fails, the broad list remains the authoritative fallback without
     * flashing it first. The retry runs in the process scope and can outlive a remounted shell that re-runs
     * the same request, so it releases only the first-frame gate it captured as it started.
     */
    private fun retryAfterActivation(attempt: NotificationOpenAttempt) {
        val requestId = attempt.requestId
        state.runtimeSlots.activeRetryRequestId = requestId
        val preloadSlot = state.preloadSlot
        val retryKey = requireNotNull(preloadSlot.key)
        val retryRuntimeGeneration = appState.runtimeGeneration
        val currency = state.currency
        val retryGate = owners.firstFrame.gateFor(requestId)
        // Account activation can publish an empty broad list while this exact retry
        // is suspended. Keep that absence non-authoritative until the retry itself
        // returns. Run it in the process mutation scope because publishing Loading
        // re-composes and cancels the keyed routing pass.
        preloadSlot.preload = NotificationMessagePreload(retryKey, NotificationMessagePreloadState.Loading)
        appState.launchMutation {
            val outcome = loadNotificationMessageDirectly { readTarget(attempt) }
            if (currency.offers(requestId, attempt.target, retryRuntimeGeneration)) {
                when (outcome) {
                    is NotificationMessageDirectLoadOutcome.OpenConversation ->
                        preloadSlot.preload =
                            NotificationMessagePreload(retryKey, NotificationMessagePreloadState.Ready(outcome.item))
                    NotificationMessageDirectLoadOutcome.AwaitChatList -> {
                        preloadSlot.preload =
                            NotificationMessagePreload(retryKey, NotificationMessagePreloadState.Failed)
                        retryGate?.let(owners.firstFrame::releaseOwned)
                    }
                }
            }
        }
    }

    /** The exact local projection of the tapped conversation, traced as the route's target read. */
    private suspend fun readTarget(attempt: NotificationOpenAttempt): ChatListItem =
        NotificationRouteTrace.tracePhase(attempt.requestId, NotificationRouteTraceSection.TARGET_PROJECTION) {
            appState.loadNotificationChatListItem(
                accountRef = attempt.target.accountRef,
                groupIdHex = attempt.target.groupIdHex,
            )
        }

    /**
     * Opens an invite only once the authoritative read proves the group openable. A live row update can
     * still re-run an inconclusive probe, so only a proven unavailable invite may be consumed with an error.
     */
    suspend fun awaitInviteRow(attempt: NotificationOpenAttempt) {
        state.routing = true
        val inviteSlots = state.inviteSlots
        var authoritativeItem: ChatListItem? = null
        when (
            retryInviteAuthoritativeLoad(
                probeAttempts = inviteSlots.probeAttempts,
                onProbeAttempt = { inviteSlots.probeAttempts = it },
                load = {
                    runCatchingCancellable {
                        appState.loadCreatedChatListItem(attempt.target.groupIdHex)
                    }.onSuccess { authoritativeItem = it }
                        .map {
                            inviteAuthoritativeGroupAvailable(
                                pendingConfirmation = it.group.pendingConfirmation,
                                selfMembership = it.group.selfMembership,
                            )
                        }
                },
            )
        ) {
            NotificationInviteAuthoritativeOutcome.OpenConversation ->
                completion.commit(attempt, requireNotNull(authoritativeItem))
            NotificationInviteAuthoritativeOutcome.Unavailable -> inviteSlots.authoritativelyUnavailable = true
            // Release the overlay without consuming the target.
            NotificationInviteAuthoritativeOutcome.Inconclusive -> state.routing = false
        }
    }
}
