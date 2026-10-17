package dev.ipf.whitenoise.android.ui.navigation

import androidx.annotation.StringRes
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.notifications.NotificationMessagePreloadState
import dev.ipf.whitenoise.android.notifications.NotificationNavStep
import dev.ipf.whitenoise.android.notifications.NotificationRouteTrace
import dev.ipf.whitenoise.android.notifications.NotificationRouteTraceSection
import dev.ipf.whitenoise.android.notifications.NotificationTarget
import dev.ipf.whitenoise.android.notifications.NotificationTargetKind
import dev.ipf.whitenoise.android.notifications.inviteAuthoritativeGroupAvailable
import dev.ipf.whitenoise.android.notifications.notificationMessageRouteChatListReady
import dev.ipf.whitenoise.android.notifications.resolveNotificationNav
import dev.ipf.whitenoise.android.notifications.stateFor
import dev.ipf.whitenoise.android.state.ChatListItem
import dev.ipf.whitenoise.android.state.ChatsController
import dev.ipf.whitenoise.android.state.PinnedShortcutLockDecision
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.state.pinnedShortcutLockDecision
import dev.ipf.whitenoise.android.state.pinnedShortcutTargetIsCurrent
import dev.ipf.whitenoise.android.ui.conversation.toLandingTarget

/** One tap the route acts on: the Activity's target and the request id it was delivered under. */
internal data class NotificationOpenAttempt(
    val target: NotificationTarget,
    val requestId: Long,
)

/** The existing owners one route pass acts through, all captured from the composition that ran it. */
internal class NotificationOpenOwners(
    val appState: WhiteNoiseAppState,
    val firstFrame: NotificationFirstFramePriority,
    val state: NotificationOpenRouteState,
    val shell: NotificationOpenShell,
)

/** The chat-list facts one pass decides on, read once so every branch sees the same snapshot. */
internal class NotificationRouteChatListView(
    private val chats: ChatsController,
    target: NotificationTarget,
) {
    // Archived conversations still exist, so an archived group is never treated as missing.
    private val allChats = chats.items + chats.archivedItems

    /** Group ids the bound broad list carries, archived ones included. */
    val availableGroupIds: Set<String> = allChats.mapTo(mutableSetOf()) { it.group.groupIdHex }

    /** True once the broad list is bound to the target account and finished loading. */
    val broadChatListReady: Boolean = chats.boundAccountRef == target.accountRef && !chats.isLoading

    /** The listed row for [groupIdHex], or the controller's materialized row for it. */
    fun chatItem(groupIdHex: String): ChatListItem? {
        val listed = allChats.firstOrNull { it.group.groupIdHex == groupIdHex }
        return listed ?: chats.chatItemForGroup(groupIdHex)
    }
}

/**
 * Notification-open orchestration, moved out of MainShell (#3309) without changing what it decides.
 *
 * Switch the target account if needed, read a message conversation directly (invites still await
 * their row), then open it, or fall back to the chat list with a notice for a stale or removed
 * target. Pure decisions stay in [resolveNotificationNav]. This class acts on each step and is re-run
 * by [NotificationOpenRouteEffect] as account and chat-list state changes. It writes only through
 * the injected [NotificationOpenShell] and the route slots, never a store of its own.
 */
internal class NotificationOpenRouter(
    val appState: WhiteNoiseAppState,
    val chats: ChatsController,
    firstFrame: NotificationFirstFramePriority,
    val state: NotificationOpenRouteState,
    private val shell: NotificationOpenShell,
) {
    private val owners = NotificationOpenOwners(appState, firstFrame, state, shell)
    private val completion = NotificationOpenCompletion(owners)
    private val reads = NotificationExactOpenReads(owners, completion)

    /** Runs one pass for the Activity's current [target] and [requestId], as the keyed effect did inline. */
    suspend fun route(
        target: NotificationTarget?,
        requestId: Long,
    ) {
        when {
            target == null -> retireClearedTarget()
            // A reply draft waits for the foreground so its handoff is never staged behind the app.
            target.replyDraft != null && !appState.appInForeground -> Unit
            // A cold-start pin tap arrives while the App Lock decision still waits for its unlock timestamp. Hold
            // the capability instead of consuming it: this pass re-runs on the decision, and a lock that shows
            // then fails closed through rejectUnavailablePin() while a cleared one opens the conversation.
            target.shortcutCapability != null && appState.appUnlockEvaluationPending -> Unit
            else -> routeArmed(NotificationOpenAttempt(target, requestId))
        }
    }

    /**
     * The pending target was cancelled or replaced mid-route (for example a White Noise deep link cleared it
     * while the route was still switching or awaiting the chat list). Nothing is left to resolve, so the
     * loading overlay comes down, or the shell would stay on a loading surface with no target to clear it (#585).
     */
    private fun retireClearedTarget() {
        state.routing = false
        val armed = state.armedRequestId
        if (armed != 0L && shell.selectedOpenContext.notificationRouteTraceRequestId != armed) {
            owners.firstFrame.release(armed)
            NotificationRouteTrace.finishRequest(armed)
        }
    }

    /**
     * Arms a newly delivered request, then resolves it once accounts are known. Arming takes the process-owned
     * route claim, so a switch still running for a request this shell no longer carries, or that an earlier
     * composition of this shell carried, stands down.
     */
    private suspend fun routeArmed(attempt: NotificationOpenAttempt) {
        if (attempt.requestId != state.armedRequestId) {
            owners.firstFrame.release(state.armedRequestId)
            state.runtimeSlots.activeRetryRequestId = null
            shell.armRequest()
            state.armedRequestId = attempt.requestId
            owners.firstFrame.claimRoute()
        }
        if (appState.accounts.isEmpty()) {
            awaitAccounts(attempt)
        } else {
            resolveAndAct(attempt)
        }
    }

    /**
     * Accounts are not loaded yet. Release any routing overlay so the shell does not stick on its loading
     * surface, without touching chat-list await semantics, because the pass re-runs when accounts arrive.
     * A lock that shows before accounts load still fails a pin closed here: an unlock made while the slow
     * start continues must not open a conversation the tap could not reach.
     */
    private fun awaitAccounts(attempt: NotificationOpenAttempt) {
        state.routing = false
        if (
            attempt.target.shortcutCapability != null &&
            appState.pinnedShortcutLockDecision() == PinnedShortcutLockDecision.LOCKED
        ) {
            shell.targetHandled(attempt.target, attempt.requestId)
            NotificationRouteTrace.finishRequest(attempt.requestId)
        }
    }

    /** Resolves the next step from one chat-list snapshot and acts on it, after the pin authority check. */
    private suspend fun resolveAndAct(attempt: NotificationOpenAttempt) {
        val view = NotificationRouteChatListView(chats, attempt.target)
        val step = resolveStep(attempt.target, view)
        if (completion.rejectUnavailablePin(attempt)) return
        when (step) {
            is NotificationNavStep.SwitchAccount -> switchAccount(attempt, step.accountRef)
            NotificationNavStep.LoadMessageDirectly -> reads.loadMessage(attempt)
            NotificationNavStep.AwaitChatList -> Unit // the invite route re-fires when list state settles
            NotificationNavStep.OpenChatList -> completion.abandon(attempt)
            NotificationNavStep.AwaitInviteRow -> reads.awaitInviteRow(attempt)
            is NotificationNavStep.OpenConversation -> completion.openListed(attempt, view.chatItem(step.groupIdHex))
            NotificationNavStep.MissingAccount ->
                completion.abandon(attempt, R.string.toast_notification_account_unavailable)
            NotificationNavStep.MissingConversation ->
                completion.abandon(attempt, R.string.toast_notification_conversation_unavailable)
        }
    }

    /** Applies the target's readiness facts to [resolveNotificationNav]. */
    private fun resolveStep(
        target: NotificationTarget,
        view: NotificationRouteChatListView,
    ): NotificationNavStep {
        val exactPreloadState = state.preloadSlot.preload.stateFor(state.preloadSlot.key)
        val chatListReady =
            if (target.kind == NotificationTargetKind.MESSAGE) {
                notificationMessageRouteChatListReady(
                    chatListReady = view.broadChatListReady,
                    targetPresent = target.groupIdHex in view.availableGroupIds,
                )
            } else {
                view.broadChatListReady
            }
        val inviteListItem =
            if (target.kind == NotificationTargetKind.INVITE) view.chatItem(target.groupIdHex) else null
        return resolveNotificationNav(
            target = target,
            knownAccountRefs =
                appState.accounts
                    .filter { (target.replyDraft == null && target.shortcutCapability == null) || !it.signedOut }
                    .mapTo(mutableSetOf()) { it.label },
            activeAccountRef = appState.activeAccountRef,
            chatListReady = chatListReady,
            availableGroupIds = view.availableGroupIds,
            inviteRowMaterialized = chats.containsGroup(target.groupIdHex),
            inviteRowMembershipOpenable =
                inviteListItem?.let { item ->
                    inviteAuthoritativeGroupAvailable(
                        pendingConfirmation = item.group.pendingConfirmation,
                        selfMembership = item.group.selfMembership,
                    )
                } ?: true,
            inviteAuthoritativelyUnavailable = state.inviteSlots.authoritativelyUnavailable,
            exactPreloadReady = exactPreloadState is NotificationMessagePreloadState.Ready,
        )
    }

    /**
     * Holds one stable loading surface over the whole switch-then-open route so the chat list never paints
     * between them. Any conversation open under the previous account closes first, so the destination is
     * never built mid-switch against a stale projection. The switch itself starts once per request and runtime.
     */
    private fun switchAccount(
        attempt: NotificationOpenAttempt,
        accountRef: String,
    ) {
        state.routing = true
        shell.closeConversationForAccountSwitch()
        if (state.runtimeSlots.accountSwitchRequestId == attempt.requestId) return
        state.runtimeSlots.accountSwitchRequestId = attempt.requestId
        NotificationAccountSwitchRoute(owners, completion, attempt, accountRef).launch()
    }
}

/** The terminal outcomes of a route: commit the conversation, abandon to the chat list, or keep the tap pending. */
internal class NotificationOpenCompletion(
    private val owners: NotificationOpenOwners,
) {
    private val appState get() = owners.appState
    private val state get() = owners.state
    private val shell get() = owners.shell

    /**
     * Ends the route on the chat list: releases its first-frame window through [releaseGate], drops the
     * overlay, presents [message] when there is one and consumes the tap. Process-scoped callers pass a
     * release that names only the gate they began.
     */
    fun abandon(
        attempt: NotificationOpenAttempt,
        @StringRes message: Int? = null,
        releaseGate: () -> Unit = { owners.firstFrame.release(attempt.requestId) },
    ) {
        releaseGate()
        state.routing = false
        shell.fallBackToChatList()
        message?.let { appState.present(it) }
        shell.targetHandled(attempt.target, attempt.requestId)
        NotificationRouteTrace.finishRequest(attempt.requestId)
    }

    /** A queued pin loses authority immediately after local removal, sign-out or an app-lock transition. */
    fun rejectUnavailablePin(
        attempt: NotificationOpenAttempt,
        releaseGate: () -> Unit = { owners.firstFrame.release(attempt.requestId) },
    ): Boolean =
        if (appState.pinnedShortcutTargetIsCurrent(attempt.target)) {
            false
        } else {
            abandon(attempt, releaseGate = releaseGate)
            true
        }

    /**
     * An inconclusive exact read is not terminal: release the overlay and leave the tap pending for a later
     * chat-list update, rather than claim a conversation that may well exist is gone. A pin falls back.
     */
    fun releaseInconclusive(attempt: NotificationOpenAttempt) {
        owners.firstFrame.release(attempt.requestId)
        state.routing = false
        if (attempt.target.shortcutCapability != null) {
            shell.fallBackToChatList()
            shell.targetHandled(attempt.target, attempt.requestId)
            NotificationRouteTrace.finishRequest(attempt.requestId)
        }
    }

    /** Opens the listed row, or consumes a tap whose row vanished between resolution and lookup. */
    suspend fun openListed(
        attempt: NotificationOpenAttempt,
        item: ChatListItem?,
    ) {
        if (item != null) {
            commit(attempt, item)
        } else {
            state.routing = false
            shell.targetHandled(attempt.target, attempt.requestId)
        }
    }

    /** Rechecks pinned-target authority around card dismissal before committing the captured conversation route. */
    suspend fun commit(
        attempt: NotificationOpenAttempt,
        chatItem: ChatListItem,
    ) {
        val target = attempt.target
        val requestId = attempt.requestId
        if (rejectUnavailablePin(attempt)) return
        if (target.replyDraft != null) state.routing = true
        appState.notificationReplyDraftHandoff.stage(target)
        // Await cancellation before publishing any route state. A superseded
        // pass must not partially commit while its platform call is pending.
        NotificationRouteTrace.tracePhase(requestId, NotificationRouteTraceSection.COMMIT_DISMISS) {
            appState.dismissNotificationRouteCards(target.accountRef, target.groupIdHex)
        }
        if (rejectUnavailablePin(attempt)) return
        // Keep ownership pinned for every open produced by this account-switch
        // request. The active ref can land a frame before this commit while the
        // shell still remembers the source account, and dropping the pin in that
        // window would let the account-change reset close the routed conversation
        // before it can claim notification ownership.
        val ownsAccountSwitch = state.runtimeSlots.accountSwitchRequestId == requestId
        val pinnedAccountRef = target.accountRef.takeIf { ownsAccountSwitch || it != appState.activeAccountRef }
        if (ownsAccountSwitch) state.runtimeSlots.earlyOpenRequestId = requestId
        beginConversationPhases(requestId)
        shell.openRoutedConversation(
            item = chatItem,
            openContext =
                nextNotificationConversationOpenContext(
                    current = shell.selectedOpenContext,
                    notificationRouteTraceRequestId = requestId,
                    pinnedAccountRef = pinnedAccountRef,
                    notificationReadThroughMessageId = target.messageIdHex,
                    notificationTarget = target.toLandingTarget(),
                ),
        )
        state.routing = false
        shell.targetHandled(target, requestId)
    }

    /** Opens the trace phases that the conversation's bind, timeline and first frame close. */
    private fun beginConversationPhases(requestId: Long) {
        NotificationRouteTrace.beginPhase(requestId, NotificationRouteTraceSection.CONTROLLER_BIND)
        NotificationRouteTrace.beginPhase(requestId, NotificationRouteTraceSection.TARGET_TIMELINE)
        NotificationRouteTrace.beginPhase(requestId, NotificationRouteTraceSection.FIRST_CONVERSATION_FRAME)
    }
}
