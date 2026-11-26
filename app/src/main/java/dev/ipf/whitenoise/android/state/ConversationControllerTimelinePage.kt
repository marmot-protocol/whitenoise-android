package dev.ipf.whitenoise.android.state

import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import dev.ipf.marmotkit.AppMessageRecordFfi
import dev.ipf.marmotkit.TimelineMessageChangeFfi
import dev.ipf.marmotkit.TimelineMessageQueryFfi
import dev.ipf.marmotkit.TimelineMessageRecordFfi
import dev.ipf.marmotkit.TimelinePageFfi
import dev.ipf.marmotkit.TimelineUpdateTriggerFfi
import dev.ipf.whitenoise.android.BuildConfig
import dev.ipf.whitenoise.android.ui.conversation.nostr.mayRenderNostrEventCard
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/** Read a raw message record from the in-memory window or recent store tail. */
internal suspend fun ConversationController.lookupMessageRecord(messageIdHex: String): AppMessageRecordFfi? {
    messageById[messageIdHex]?.let { return it.takeUnless(AppMessageRecordFfi::invalidated) }
    val account = conversationAccountRef ?: return null
    return runCatchingCancellable {
        withContext(Dispatchers.IO) {
            appState.marmotIo { messages(account, group.groupIdHex, 120u, null) }
        }
    }.onFailure {
        if (BuildConfig.DEBUG) Log.w("DMConversation", "lookup message failed", it)
    }.getOrNull()
        ?.validRawMessage(messageIdHex)
}

/**
 * Pages the window older, keeping the reader's oldest visible row as the window anchor.
 *
 * True only when new rows arrived, so callers that page in a loop still stop on no progress.
 */
internal suspend fun ConversationController.loadOlderPage(
    anchorMessageIdHex: String? = null,
    origin: PagingOrigin = PagingOrigin.EXPLICIT,
): Boolean = loadOlderPageInternal(anchorMessageIdHex, origin) == ConversationPageLoad.ADVANCED

/** Pages the window newer for [origin]; true only when new rows arrived. */
internal suspend fun ConversationController.loadNewerPage(origin: PagingOrigin = PagingOrigin.EXPLICIT): Boolean {
    val load = loadNewerPageInternal(origin)
    return load == ConversationPageLoad.ADVANCED
}

/** Replaces the timeline only when no newer page or live update superseded the read. */
internal suspend fun ConversationController.refreshCurrentTimeline(
    account: String,
    pageLoader: (suspend () -> TimelinePageFfi)? = null,
) {
    val refreshGeneration = timelineWindowGeneration.advance()
    val page =
        pageLoader?.invoke()
            ?: appState.marmotIo(MarmotTraceSection.TIMELINE_READ) {
                timelineMessages(
                    account,
                    TimelineMessageQueryFfi.page(
                        groupIdHex = group.groupIdHex,
                        search = null,
                        before = null,
                        beforeMessageId = null,
                        after = null,
                        afterMessageId = null,
                        limit = ConversationTimelinePageLimit,
                    ),
                )
            }
    if (!timelineWindowGeneration.isCurrent(refreshGeneration)) {
        return
    }
    hasLoadedOlderPages = false
    protectedTimelineMessageIds.clear()
    applyTimelinePage(warmLaterPageEventCards(page), replaceWindow = true, updatePagination = true)
}

/** Returns loaded ordinary-message ids eligible for the foreground expiry sweep. */
internal fun ConversationController.testSweepExpiryIds(): List<String> =
    loadedTimelineMessagesInDisplayOrder()
        .filter { shouldApplyLocalDisappearingExpiry(it.record) }
        .map { it.record.messageIdHex }

/** Drops indexes owned by the previous authoritative bounded window. */
internal fun ConversationController.trimStateForWindowReplacement() {
    timelineRecords.clear()
    timelineItemsById.clear()
    timelineOrder.clear()
    authoritativeTimelineOrderByMessageId.clear()
    projectedMessageIds.clear()
    // Drop stale projected records so messageById doesn't grow unbounded
    // as older pages are loaded; keep in-flight optimistic records so a
    // pending send still reconciles across a window replacement. See #68.
    val optimisticIds = optimisticMessages.values.mapTo(mutableSetOf()) { it.record.messageIdHex }
    messageById.keys.retainAll(optimisticIds)
    // Same unbounded-growth trim for the optimistic position/timestamp
    // overrides: the reconcile path below re-adds them for any optimistic
    // message that reconciles into this page, so scrolled-out entries
    // don't accumulate for the controller's lifetime.
    localTimelineOrderOverrides.keys.retainAll(optimisticIds)
    localTimelineTimestampOverrides.keys.retainAll(optimisticIds)
    optimisticSendPositionPreserves.retainAll(localTimelineTimestampOverrides.keys)
}

/**
 * Warms existing local profile presentation state and, for a page that names
 * them, cached event cards. MainShell keeps a prepared chat-list tap on its
 * source page until this initial warm completes, preventing sender metadata
 * from remeasuring bubbles during the route transition. A page with cards is
 * also held unpublished until then, so no open path shows a card unwarmed.
 */
internal fun ConversationController.scheduleProfilePresentationWarm(
    records: List<TimelineMessageRecordFfi>,
    hydratedRecords: Deferred<List<TimelineMessageRecordFfi>>?,
    markInitialPresentationReady: Boolean,
) {
    if (!markInitialPresentationReady) return
    initialPresentationWarmCoordinator.prepare(
        InitialPresentationWarmWork(
            senders = initialPresentationProfileSenders(records),
            eventCardRecords = records.filter(::mayRenderNostrEventCard),
            hydratedRecords = hydratedRecords,
        ),
    )
}

/**
 * Marks an applied page published, unless it is a first page that names event cards (#2238). That page, and any
 * page replacing it before readiness, stays held until its cached cards are read, so no row composes unwarmed.
 */
internal fun ConversationController.markPagePublishedUnlessHeldForEventCards(
    preparingInitialPresentation: Boolean,
    records: List<TimelineMessageRecordFfi>,
) {
    isHoldingInitialPageForEventCards =
        preparingInitialPresentation &&
        (isHoldingInitialPageForEventCards || records.any(::mayRenderNostrEventCard))
    if (isHoldingInitialPageForEventCards) return
    hasPublishedAuthoritativeTimeline = true
    initialTimelineSeedActive = false
}

/**
 * Suspends until no page is held unpublished for its event cards (#2238). Only a page committed before the first
 * presentation is ready is held, until readiness, which the card budget bounds, so this returns at once otherwise.
 */
internal suspend fun ConversationController.awaitEventCardHoldReleased() {
    if (isHoldingInitialPageForEventCards) initialPresentationWarmCoordinator.awaitReady()
}

/** Times the page's publication now, or, for a page held for its event cards, when the hold releases it. */
internal fun ConversationController.recordPagePublicationUnlessHeld() {
    if (!isHoldingInitialPageForEventCards) windowPresentationTiming.timelinePublished()
}

/**
 * Publishes the first page held for its event cards, together with the flags that say it is published. Called
 * once the card warm settled or its budget ran out, so a held row composes with its snapshot in place, or, past
 * the budget, as an ordinary unwarmed card.
 */
internal fun ConversationController.releaseInitialPageEventCardHold() {
    if (!isHoldingInitialPageForEventCards) return
    isHoldingInitialPageForEventCards = false
    hasPublishedAuthoritativeTimeline = true
    initialTimelineSeedActive = false
    publishTimelineFromIndexes()
    windowPresentationTiming.timelinePublished()
}

/**
 * Publishes [records], then hydrates their Markdown, and returns that hydration's parsed records when one runs.
 * With [warmEventCards], cards are read from MDK's cache before the hydrated rows apply. Otherwise the caller's
 * initial warm reads them from the returned records, so the same messages are never parsed twice. The returned
 * records complete only after they applied, so a first page held for its cards is released with them in place.
 */
internal fun ConversationController.publishTimelinePageBeforeMarkdownHydration(
    records: List<TimelineMessageRecordFfi>,
    warmEventCards: Boolean,
): Deferred<List<TimelineMessageRecordFfi>>? {
    val scope = conversationScope ?: return null.also { publishTimelineFromIndexes() }
    val hydratedRecords = CompletableDeferred<List<TimelineMessageRecordFfi>>()
    val hydration =
        publishTimelineBeforeMarkdownHydration(
            scope = scope,
            records = records,
            publish = ::publishTimelineFromIndexes,
            // Cards appear once their message's Markdown does, so their cached state is read before it applies.
            hydrate = { pending ->
                hydrateTimelineMarkdown(pending).also { hydrated ->
                    if (warmEventCards) {
                        eventCardPresentation.warmEventCardSnapshots(hydrated.filter(::mayRenderNostrEventCard))
                    }
                }
            },
            applyHydrated = { hydrated ->
                applyHydratedTimelineMarkdown(hydrated)
                hydratedRecords.complete(hydrated)
            },
        )
    // A cancelled or failed hydration still releases the warm waiting on it, with nothing parsed.
    hydration?.invokeOnCompletion { hydratedRecords.complete(emptyList()) }
    return hydratedRecords.takeIf { hydration != null }
}

/** Applies live timeline changes and invalidates suspended whole-window refreshes. */
internal fun ConversationController.applyTimelineChanges(changes: List<TimelineMessageChangeFfi>) {
    timelineWindowGeneration.advance()
    val reactionTargets = linkedSetOf<String>()
    changes.forEach { change ->
        when (change) {
            is TimelineMessageChangeFfi.Upsert -> {
                val record = change.message
                recordMembershipProjectionArrival(change)
                upsertProjectedRecord(
                    record,
                    reconcileOptimistic = change.trigger == TimelineUpdateTriggerFfi.NEW_MESSAGE,
                    allowDelayedProjection = change.trigger == TimelineUpdateTriggerFfi.NEW_MESSAGE,
                )
                if (change.trigger.recomputesReactions()) {
                    reactionTargets.add(record.messageIdHex)
                }
                appState.requestProfile(record.sender)
                record.replyPreview?.let { appState.requestProfile(it.sender) }
                appState.requestProfiles(record.reactions.userReactions.map { it.sender })
                if (record.deleted) {
                    deletedMessageIds = deletedMessageIds - record.messageIdHex
                }
            }
            is TimelineMessageChangeFfi.Remove -> {
                pendingTimelineRemovedMessageIds = pendingTimelineRemovedMessageIds + change.messageIdHex
                removeProjectedRecord(change.messageIdHex)
                messageById.remove(change.messageIdHex)
                reactionTargets.add(change.messageIdHex)
                optimisticMessages.remove("msg:${change.messageIdHex}")
            }
        }
    }
    pruneConfirmedOptimisticMessages()
    pruneConfirmedOptimisticReactions()
    recomputeReactions(reactionTargets)
    // The engine streams Upserts for new messages but never Removes ones that
    // scroll out, so the live indexes grow without bound for a conversation
    // kept open while messages arrive. Cap live rows to the newest entries;
    // after loadOlder(), deliberately-loaded history is preserved and only
    // post-pagination live Upserts are trimmed (#1163 / #537).
    trimLiveTimelineWindow(LIVE_TIMELINE_WINDOW_CAP)
    retentionAtSendByMessageId.retainTimelineWindow()
    // Live Upsert/Projection batches add to messageById but never trim it;
    // prune to the (now-bounded) window + optimistic records so it doesn't
    // grow unbounded for an actively-watched conversation (#373).
    messageById.retainTimelineWindow()
    optimisticEdits.retainTimelineWindow()
    pruneMessageOverlaysToWindow()
    publishTimelineFromIndexes()
}

private suspend fun ConversationController.hydrateTimelineMarkdown(records: List<TimelineMessageRecordFfi>): List<TimelineMessageRecordFfi> {
    if (records.none(::needsTimelineMarkdownHydration)) return records
    val parseGate = Semaphore(4)
    return coroutineScope {
        records
            .map { record ->
                async {
                    if (!needsTimelineMarkdownHydration(record)) return@async record
                    val existingDocument =
                        timelineRecords[record.messageIdHex]
                            ?.takeIf { existing -> existing.plaintext == record.plaintext }
                            ?.contentTokens
                            ?.takeIf { document -> document.blocks.isNotEmpty() }
                    val document =
                        existingDocument
                            ?: parseGate.withPermit {
                                appState.parseMarkdownOrEmpty(record.plaintext)
                            }
                    if (document.blocks.isEmpty()) record else record.withMarkdownTokens(document)
                }
            }.awaitAll()
    }
}

private fun ConversationController.applyHydratedTimelineMarkdown(records: List<TimelineMessageRecordFfi>) {
    assertMainThread { "applyHydratedTimelineMarkdown" }
    var changed = false
    records.forEach { hydrated ->
        val document = hydrated.contentTokens.takeIf { it.blocks.isNotEmpty() } ?: return@forEach
        val current = timelineRecords[hydrated.messageIdHex] ?: return@forEach
        if (!needsTimelineMarkdownHydration(current) || current.plaintext != hydrated.plaintext) {
            return@forEach
        }
        upsertProjectedRecord(current.withMarkdownTokens(document))
        changed = true
    }
    if (changed) publishTimelineFromIndexes()
}
