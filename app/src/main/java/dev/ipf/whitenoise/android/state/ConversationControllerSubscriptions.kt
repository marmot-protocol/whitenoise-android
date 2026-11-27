package dev.ipf.whitenoise.android.state

import android.os.SystemClock
import android.util.Log
import androidx.annotation.VisibleForTesting
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import dev.ipf.marmotkit.AppGroupRecordFfi
import dev.ipf.marmotkit.AppMessageRecordFfi
import dev.ipf.marmotkit.GroupRosterFfi
import dev.ipf.marmotkit.HostPerformanceOperationFfi
import dev.ipf.whitenoise.android.BuildConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/** Presents startup outcomes while the timeline session owns admission and finalization. */
internal suspend fun ConversationController.runStart(account: String) {
    isLoading = true
    terminalConversationUnavailable = false
    subscriptionError = null
    pageError = null
    terminalLoadFailure = false
    try {
        timelineSession.runReadScope {
            // Global relay catch-up is owned by the chat-list/foreground
            // lifecycle. The timeline and group handles below subscribe to
            // local projections, so opening them does not need another
            // account-worker sync; reactions render their optimistic overlay
            // before any authoritative commit waits for that worker.
            // Local NIP-40 enforcement (#333) + secure delete (#334): on open
            // and then at the next loaded row's expiry boundary, securely wipe
            // plaintext past the retention window via the engine and re-publish
            // so rows leave the open timeline while the user is still watching.
            // The timer is lifecycle-bound to this conversation and is
            // rescheduled by timeline/group-state publishes; when there is no
            // loaded row near expiry it falls back to the old slow cadence.
            val foregroundSweepJob = launch { runForegroundDisappearingMessageSweep(account) }
            val convergenceEventsJob = launch { runGroupConvergenceEventLoop(account) }
            try {
                runConversationSubscriptionLoop(account)
            } finally {
                // A terminal initial-open failure returns normally from
                // the subscription loop. Stop its infinite sibling so
                // runStart can finish and release its lifecycle state.
                foregroundSweepJob.cancel()
                convergenceEventsJob.cancel()
            }
        }
    } catch (cancel: CancellationException) {
        // Expected when the conversation screen leaves the composition.
        // Re-throw so cancellation propagates and we don't log it as an
        // error.
        throw cancel
    } catch (throwable: Throwable) {
        windowPresentationTiming.fail()
        if (throwable.isUseAfterEviction()) {
            discardInitialTimelineSeedForFailure(preserveOptimisticMessages = false)
            markActiveAccountRemovedFromMembers(account)
            isLoading = false
            terminalConversationUnavailable = true
            subscriptionError = null
            pageError = null
            return
        }
        discardInitialTimelineSeedForFailure(preserveOptimisticMessages = true)
        isLoading = false
        subscriptionError = privacySafeErrorPresentation("CONVERSATION_LOAD", throwable)
        terminalLoadFailure = true
    }
}

private fun ConversationController.discardInitialTimelineSeedForFailure(preserveOptimisticMessages: Boolean) {
    if (!shouldDiscardInitialTimelineSeedForFailure(hasPublishedAuthoritativeTimeline)) return
    // A failure resolves the first presentation, so a page held for its cards must not stay hidden.
    isHoldingInitialPageForEventCards = false
    initialTimelineSeedActive = false
    if (preserveOptimisticMessages) {
        publishTimelineFromIndexes()
    } else {
        // Use-after-eviction means this account no longer owns the group.
        // Do not keep any plaintext row visible under an unavailable owner.
        timeline = emptyList()
    }
    // Failure is an authoritative resolution of the initial presentation:
    // there is no page left to await. Let the route reconcile/reveal the
    // retained optimistic rows or switch to its terminal-unavailable surface.
    hasPublishedAuthoritativeTimeline = true
    hasPreparedInitialPresentation = true
}

/** Publishes an empty first page, which names no card, so any earlier card hold ends with it. */
private fun ConversationController.publishAuthoritativeEmptyInitialTimeline() {
    isHoldingInitialPageForEventCards = false
    hasPublishedAuthoritativeTimeline = true
    hasPreparedInitialPresentation = true
    initialTimelineSeedActive = false
    publishTimelineFromIndexes()
    windowPresentationTiming.timelinePublished()
}

internal fun ConversationController.isAccountTeardownRequested(): Boolean = timelineSession.accountTeardownRequested

/**
 * Applies the subscription's one-shot snapshot as the first page. The initial page is a local read, but it crosses
 * the FFI boundary — a hung call would leave the chat-list tap permanently inert because navigation cannot promote
 * until the first page publishes. Bound it, and convert exhaustion into the ordinary load failure whose surface
 * already promotes navigation and offers retry. Every subscription open, including reconnects with a retained
 * window, consumes its one-shot snapshot before waiting for live updates.
 */
private suspend fun ConversationController.publishInitialTimelineSnapshot(
    account: String,
    timelineStream: ConversationTimelineSubscriptionHandle,
) {
    val snapshot = timelineSession.snapshot(timelineStream)
    windowPresentationTiming.begin(
        receivedAtElapsedMs = SystemClock.elapsedRealtime(),
        ticket = appState.productObservationTicket(),
    )
    val recoveryGeneration =
        appState.recoveryDiagnostics.recordTimelineSubscriptionReceived(
            count = snapshot?.messages?.size ?: 0,
        )
    if (snapshot == null) {
        if (timelineRecords.isEmpty()) publishAuthoritativeEmptyInitialTimeline()
        publishRecoveryTimelineProjection(recoveryGeneration)
    } else {
        hasLoadedOlderPages = false
        protectedTimelineMessageIds.clear()
        // A reconnect's snapshot reads its cards first; a first page has its own hold.
        val warmedSnapshot = warmLaterPageEventCards(snapshot)
        appState.measureHostPerformance(HostPerformanceOperationFfi.TIMELINE_APPLY) {
            applyTimelinePage(
                warmedSnapshot,
                replaceWindow = true,
                updatePagination = true,
            )
        }
        initializeReadState(account)
        publishRecoveryTimelineProjection(recoveryGeneration)
        publishReadyTimelineSubscription(timelineStream)
    }
}

/** Publishes a captured recovery generation only after timeline state changed. */
internal fun ConversationController.publishRecoveryTimelineProjection(generation: Long?) {
    if (
        generation != null &&
        appState.recoveryDiagnostics.recordTimelineProjectionPublished(generation, timeline.size)
    ) {
        recoveryProjectionGeneration = generation
    }
}

/**
 * Retry loop for the timeline + group-state live subscriptions. Extracted
 * from [start] so R8 can compile the smaller suspend entrypoint (the
 * monolithic method hit an invalid stack-map-table bug in release builds).
 */
private suspend fun ConversationController.runConversationSubscriptionLoop(account: String) {
    timelineSession.runReconnectLoop { runConversationSubscriptionIteration(account) }
}

/**
 * One connect/reconnect attempt. Returns whether the caller should exit
 * until explicit retry (account evicted or a native open timed out) and
 * whether the subscriptions connected successfully (so the retry backoff
 * can reset).
 */
private suspend fun ConversationController.runConversationSubscriptionIteration(account: String): Pair<Boolean, Boolean> {
    val timelineOpen = appState.beginHostPerformance(HostPerformanceOperationFfi.TIMELINE_OPEN)
    var groupSubscription: ConversationGroupStateSubscriptionHandle? = null
    var timelineStream: ConversationTimelineSubscriptionHandle? = null
    // A notification-routed group hides its transcript until the roster lands (#586).
    val rosterPrefetch = conversationScope?.prefetchGroupRoster(group.groupIdHex, account, groupRosterReader)
    try {
        timelineStream =
            timelineSession.openTimeline {
                liveSubscriptions.openTimeline(account, group.groupIdHex, ConversationTimelinePageLimit)
            } ?: return true to false
        publishInitialTimelineSnapshot(account, timelineStream)
        timelineOpen.success()
        // Don't blanket-mark the absolute newest as read here — the UI
        // layer now drives mark-read as the user scrolls so partial-read
        // sessions retain accurate unread counts on the chat list.

        var connected = false
        val initialMetadataReady = CompletableDeferred<Unit>()

        coroutineScope {
            runUntilFirstLiveSubscriptionEnds(
                first = {
                    runTimelineSubscriptionPipeline(timelineStream)
                    // Normal EOF must not cancel the initial roster before it can settle
                    // loading/disclosure. Failures still unwind the paired attempt immediately.
                    initialMetadataReady.await()
                },
                second = {
                    // The authoritative timeline is already installed. Keep consuming its
                    // bounded replacements while independent group/roster reads settle;
                    // transcript consent and membership presentation retain their own gates.
                    // Otherwise a slow roster can leave background messages queued until
                    // well after the retained conversation's first foreground frame (#3178).
                    val groupStream =
                        timelineSession.openGroup { liveSubscriptions.openGroupState(account, group.groupIdHex) }
                            ?: return@runUntilFirstLiveSubscriptionEnds
                    groupSubscription = groupStream
                    val groupSnapshot =
                        withContext(Dispatchers.IO) {
                            groupStream.snapshot()
                        }
                    groupSnapshot?.let(::applyGroupState)
                    refreshGroupRecoveryStatus()
                    refreshMembersAfterGroupState(prefetchedRoster = rosterPrefetch)
                    isLoading = false
                    groupConvergenceOpenSettled.complete(Unit)
                    subscriptionError = null
                    connected = true
                    initialMetadataReady.complete(Unit)
                    runGroupStateSubscriptionLoop(groupStream)
                },
            )
        }
        return false to connected
    } catch (cancel: CancellationException) {
        timelineOpen.cancel()
        throw cancel
    } catch (throwable: Throwable) {
        timelineOpen.failure()
        windowPresentationTiming.fail()
        if (throwable.isUseAfterEviction()) {
            discardInitialTimelineSeedForFailure(preserveOptimisticMessages = false)
            markActiveAccountRemovedFromMembers(account)
            isLoading = false
            terminalConversationUnavailable = true
            subscriptionError = null
            return true to false
        }
        val initialOpenTimedOut = isTerminalOpenFailure(throwable)
        discardInitialTimelineSeedForFailure(preserveOptimisticMessages = true)
        isLoading = false
        val operationCode = if (timelineRecords.isEmpty()) "CONVERSATION_LOAD" else "CONVERSATION_REFRESH"
        if (!BuildConfig.DEBUG) Log.e("DMConversation", releaseFailureMarker(operationCode, throwable))
        subscriptionError =
            privacySafeErrorPresentation(
                operationCode = operationCode,
                throwable = throwable,
                message = AppText.Resource(conversationLoadFailureMessage(throwable, initialOpenTimedOut)),
                retryable = shouldOfferConversationLoadRetry(throwable),
            )
        if (initialOpenTimedOut) {
            // A timed-out FFI call may still occupy its native blocking
            // worker. Automatic retries would accumulate more stuck calls;
            // stop here. The user must restart rather than launch another
            // native open while the single-flight owner is unresolved.
            timelineSession.cancelInitialOpen()
            terminalLoadFailure = true
            return true to false
        }
    } finally {
        timelineOpen.unavailable()
        rosterPrefetch?.cancel() // A no-op once awaited; an early return must not leave it running.
        timelineSession.closeHandles(groupSubscription, timelineStream)
    }
    return false to false
}

/** Applies a canonical group update while retaining only a still-unresolved stale action fence. */
internal fun ConversationController.applyGroupState(incoming: AppGroupRecordFfi) {
    val named = localGroupNames.reconciled(incoming)
    val description = localGroupDescriptions.reconcile(named.groupIdHex, named.description)
    val update = if (description == named.description) named else named.copy(description = description)
    val previousGroup = group
    if (update.leaveBlocked) observedBlockedLeave = true
    if (update.selfMembership.isGroupEviction()) {
        observedBlockedLeave = false
        cancelledLeaveAwaitingRoster = false
    }
    groupRecoveryLifetime.advance()
    val previousRetention = group.disappearingMessageSecs
    mutationPresentation.authoritativeGroupApplied()
    val reconciled =
        reconcileTerminalSelfMembership(
            update = update,
            previous = previousGroup,
        )
    val confirmed = inviteConfirmationAuthority.reconcile(reconciled)
    inviteConfirmationUnresolved = false
    val awaitingAuthority = inviteAcceptanceAwaitingAuthority
    val freshTerminalReinvite = isDistinctWelcomeReinvite(previousGroup, update)
    group =
        if (awaitingAuthority?.matches(confirmed) == true && confirmed.pendingConfirmation) {
            confirmed.copy(pendingConfirmation = false)
        } else {
            inviteAcceptanceAwaitingAuthority = null
            confirmed
        }
    if (!inviteAcceptanceResolutionPending) inviteAcceptanceResolutionState = GroupRosterLoadState.READY
    if (freshTerminalReinvite) {
        memberRosterRefreshGeneration.advance()
        selfMembership.clearSelfLeft()
        membersVerified = false
        memberRosterLoadTracker.transition(GroupRosterRefreshEvent.STARTED)
    }
    if (group.selfMembership.isNonMember()) recordSelfLeft()
    appState.releaseEvictedCapture(conversationAccountRef, previousGroup, group)
    if (previousRetention != update.disappearingMessageSecs) {
        publishTimelineFromIndexes()
    }
}

private suspend fun ConversationController.runGroupStateSubscriptionLoop(groupStream: ConversationGroupStateSubscriptionHandle) {
    while (coroutineContext.isActive) {
        val update =
            withContext(Dispatchers.IO) {
                groupStream.next()
            } ?: break
        val previousGroup = group
        applyGroupState(update)
        refreshGroupRecoveryStatus()
        if (groupStateUpdateRemovesSelf(previousGroup, update)) {
            conversationAccountRef?.let(::markActiveAccountRemovedFromMembers)
        }
        refreshMembersAfterGroupState()
    }
}

/** The first snapshot and live updates both require full readback after a blocked leave clears. */
@VisibleForTesting
internal suspend fun ConversationController.refreshMembersAfterGroupState(prefetchedRoster: Deferred<Result<GroupRosterFfi>>? = null) {
    if (!observedBlockedLeave || group.leaveRequestPending || !refreshManagementState()) {
        refreshMembers(prefetchedRoster = prefetchedRoster)
    }
}

/**
 * Parse the sent text into the same Markdown AST projected records carry
 * and rebind it onto the already-published optimistic bubble. The chat-list
 * preview is not published until this attempt finishes, so it never exposes
 * a raw-Markdown intermediate frame. Native admission runs independently;
 * only presentation reconciliation waits for this document, so it cannot
 * overwrite the styled bubble with the pre-parse snapshot.
 * A parse failure keeps the plain-text presentation. The chat list publishes
 * that fallback only after the parse attempt completes, so it cannot flash a
 * raw frame before replacing it with styled content. In either case,
 * a bubble already reconciled or rolled back is left alone. The parsed
 * record still backs preview reconciliation when an early echo removed
 * the optimistic bubble, so the chat list cannot lose its styled tokens.
 */
internal suspend fun ConversationController.hydrateOptimisticSendMarkdown(
    optimisticKey: String,
    optimistic: AppMessageRecordFfi,
): AppMessageRecordFfi {
    val tokens =
        runCatchingCancellable { markdownParser(optimistic.plaintext) }
            .getOrNull()
            ?.takeIf { it.blocks.isNotEmpty() }
            ?: return optimistic
    val pending = optimisticMessages[optimisticKey]
    return if (pending == null || pending.record.messageIdHex != optimistic.messageIdHex) {
        optimistic.copy(contentTokens = tokens)
    } else {
        val hydrated = pending.record.copy(contentTokens = tokens)
        optimisticMessages[optimisticKey] = pending.copy(record = hydrated)
        messageById[optimistic.messageIdHex] = hydrated
        publishTimelineFromIndexes()
        hydrated
    }
}
