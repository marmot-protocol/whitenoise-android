package dev.ipf.whitenoise.android.state

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.ipf.marmotkit.ConversationAnchorOutcomeFfi
import dev.ipf.marmotkit.ConversationCapabilitiesFfi
import dev.ipf.marmotkit.ConversationHeaderFfi
import dev.ipf.marmotkit.ConversationIdentityFfi
import dev.ipf.marmotkit.ConversationMessageReferencesFfi
import dev.ipf.marmotkit.ConversationOpenReadStateFfi
import dev.ipf.marmotkit.ConversationPageDirectionFfi
import dev.ipf.marmotkit.ConversationReactionsFfi
import dev.ipf.marmotkit.ConversationWindowRevisionFfi
import dev.ipf.marmotkit.ConversationWindowSnapshotFfi
import dev.ipf.marmotkit.ConversationWindowSubscriptionInterface
import dev.ipf.marmotkit.MarmotKitException
import dev.ipf.marmotkit.SelectedMessageDraftFfi
import dev.ipf.marmotkit.TimelinePageFfi
import dev.ipf.whitenoise.android.core.ReactionTally
import kotlinx.coroutines.delay

/** Rows requested per conversation page and at opening; MDK accepts 1–200 and retains at most 200. */
internal const val CONVERSATION_WINDOW_MAX_ROWS: UInt = 200u

/** `timeoutMs = 0` selects MDK's 30-second deadline for opening and every window command. */
internal const val CONVERSATION_WINDOW_DEFAULT_DEADLINE: UInt = 0u

/**
 * Deadline for opening a window. MDK's default is 30 seconds, which is how long a reader would stare at a
 * spinner before the timeline fallback engages; eight seconds is ample for a healthy open of 50 rows.
 */
internal const val CONVERSATION_WINDOW_OPEN_DEADLINE_MS: UInt = 8_000u

/** Pause before receiving again while MDK reports the window not ready, so a repair cannot spin the loop. */
internal const val CONVERSATION_WINDOW_NOT_READY_RETRY_MS = 250L

/**
 * Everything one installed conversation window replacement carries beyond its compatibility
 * timeline page: the revision every command must quote, the prepared header, the bounded display
 * identities, read state, the selected draft, the anchor outcome and per-message references.
 */
@Immutable
internal data class ConversationWindowFrame(
    val revision: ConversationWindowRevisionFfi,
    val header: ConversationHeaderFfi,
    val identities: Map<String, ConversationIdentityFfi>,
    val readState: ConversationOpenReadStateFfi,
    val draft: SelectedMessageDraftFfi,
    val anchor: ConversationAnchorOutcomeFfi,
    val references: Map<String, ConversationMessageReferencesFfi>,
)

/** The compatibility page and sidecar of one installed replacement, read together so they never mix revisions. */
internal data class InstalledConversationWindow(
    val page: TimelinePageFfi,
    val frame: ConversationWindowFrame,
)

/** Observable owner of the newest installed frame for one conversation controller. */
internal class ConversationWindowState {
    /** Newest installed frame, or null before the window delivered one or when the seam has no window. */
    var frame by mutableStateOf<ConversationWindowFrame?>(null)
        private set

    /** Prepared header of the newest frame. */
    val header: ConversationHeaderFfi? get() = frame?.header

    /** Action availability MDK computed for the viewer; null keeps the app's own gating alone. */
    val capabilities: ConversationCapabilitiesFfi? get() = frame?.header?.capabilities

    /** Whether MDK lets the viewer send here; true while no window frame exists. */
    val allowsSend: Boolean get() = capabilities?.canSend != false

    /** The revision-safe selected draft that opened with, or last replaced in, this window. */
    val selectedDraft: SelectedMessageDraftFfi? get() = frame?.draft

    /** Bounded references for one displayed message, or null when the window does not retain it. */
    fun references(messageIdHex: String): ConversationMessageReferencesFfi? = frame?.references?.get(messageIdHex)

    /** Installs a newer frame. */
    fun install(frame: ConversationWindowFrame) {
        this.frame = frame
    }

    /** Forgets the frame when the window is retired. */
    fun clear() {
        frame = null
    }
}

/** The compatibility timeline page inside a window replacement. */
internal fun ConversationWindowSnapshotFfi.toTimelinePage(): TimelinePageFfi =
    TimelinePageFfi(
        messages = messages.map { it.timeline },
        hasMoreBefore = hasMoreBefore,
        hasMoreAfter = hasMoreAfter,
    )

/** The sidecar of a window replacement, keyed for the controller's per-message lookups. */
internal fun ConversationWindowSnapshotFfi.toFrame(): ConversationWindowFrame =
    ConversationWindowFrame(
        revision = revision,
        header = header,
        identities = identities.associateBy { it.accountIdHex },
        readState = readState,
        draft = draft,
        anchor = anchor,
        references = messages.associate { it.timeline.messageIdHex to it.references },
    )

/**
 * Orders replacements from one native conversation window by revision. An equal or older sequence
 * within the generation is a duplicate echo; a foreign generation belongs to another handle.
 */
internal class ConversationWindowCursor(
    initial: ConversationWindowRevisionFfi,
) {
    /** Revision of the newest installed replacement. */
    var revision: ConversationWindowRevisionFfi = initial
        private set

    /** True when [next] was produced by a different handle. */
    fun isForeign(next: ConversationWindowRevisionFfi): Boolean = next.generation != revision.generation

    /** Accepts only a newer replacement from the same generation. */
    fun accept(next: ConversationWindowRevisionFfi): Boolean {
        if (isForeign(next) || next.sequence <= revision.sequence) return false
        revision = next
        return true
    }
}

/**
 * Revision bookkeeping shared by the handle's receive path and its commands: installs only newer
 * replacements, remembers the newest page and sidecar, and turns superseded or not-ready command
 * outcomes into "keep what you have".
 */
private class ConversationWindowInstaller {
    private val lock = Any()
    private var cursor: ConversationWindowCursor? = null
    private var latest: InstalledConversationWindow? = null

    /** Page and sidecar of the newest installed replacement, captured under one lock. */
    val installed: InstalledConversationWindow? get() = synchronized(lock) { latest }

    /** Compatibility page of the newest installed replacement. */
    val page: TimelinePageFfi? get() = installed?.page

    /** Installs [snapshot] when it is newer than everything seen so far and returns its page. */
    fun install(snapshot: ConversationWindowSnapshotFfi): TimelinePageFfi? =
        synchronized(lock) {
            val current = cursor
            if (current == null) {
                cursor = ConversationWindowCursor(snapshot.revision)
            } else if (!current.accept(snapshot.revision)) {
                return null
            }
            val page = snapshot.toTimelinePage()
            latest = InstalledConversationWindow(page, snapshot.toFrame())
            page
        }

    // Superseded, not-ready, timed-out, outside-anchor, foreign-generation and malformed-argument results
    // carry no detail the app can act on: the contract is to reassess from the newest installed replacement,
    // which the receive loop keeps delivering. Only a missing jump target is surfaced, and only when asked.
    // Letting any of them escape would take the process down from a scroll-settle effect (seen on device
    // when an optimistic row's local id reached `setVisibleAnchor`).
    @Suppress("SwallowedException", "ReturnCount")
    suspend fun command(
        rethrowMissingTarget: Boolean = false,
        block: suspend (ConversationWindowRevisionFfi) -> ConversationWindowSnapshotFfi,
    ): TimelinePageFfi? {
        val revision = synchronized(lock) { cursor?.revision } ?: return null
        val result =
            try {
                block(revision)
            } catch (missing: MarmotKitException.ConversationWindowMessageNotRetained) {
                if (rethrowMissingTarget) throw missing
                return null
            } catch (windowOutcome: MarmotKitException) {
                return null
            }
        return install(result)
    }
}

/**
 * Presents MDK's conversation window through the page-shaped timeline seam the controller already
 * consumes. Every replacement is deduplicated by revision before it becomes a page; superseded, not
 * ready, timed out, anchor-outside and foreign-generation command outcomes fall back to the newest
 * installed page instead of being repeated, and a missing jump target is reported to the caller.
 */
internal class FfiConversationWindowHandle(
    private val window: ConversationWindowSubscriptionInterface,
    private val release: () -> Unit,
) : ConversationTimelineSubscriptionHandle {
    private val installer = ConversationWindowInstaller()

    /**
     * Consumes the initial replacement once. A window that cannot answer yet yields no page rather than an
     * error: the receive loop below delivers the first replacement once MDK is ready.
     */
    @Suppress("SwallowedException", "TooGenericExceptionCaught")
    override fun snapshot(): TimelinePageFfi? =
        try {
            window.snapshot()?.let(installer::install)
        } catch (unavailable: MarmotKitException) {
            null
        }

    /**
     * Waits for the next replacement that is newer than everything installed so far. Null ends the stream,
     * which the controller answers by reopening; no window error reaches the caller as a throw.
     */
    override suspend fun nextWindow(): TimelinePageFfi? {
        while (true) {
            val snapshot = window.nextReplacementOrNull() ?: return null
            installer.install(snapshot)?.let { return it }
        }
    }

    /** Extends the window towards older history from the installed revision. */
    override suspend fun paginateBackwards(count: UInt): TimelinePageFfi {
        val page = page(ConversationPageDirectionFfi.OLDER, count)
        return page
    }

    /** Extends the window towards newer history from the installed revision. */
    override suspend fun paginateForwards(count: UInt): TimelinePageFfi {
        val page = page(ConversationPageDirectionFfi.NEWER, count)
        return page
    }

    /** Page and sidecar of the newest installed replacement as one revision. */
    override fun latestInstalledWindow(): InstalledConversationWindow? = installer.installed

    /** Reports the visible row; null when nothing newer was installed. */
    override suspend fun setVisibleAnchor(messageIdHex: String): TimelinePageFfi? =
        installer.command { revision ->
            window.setVisibleAnchor(revision, messageIdHex, CONVERSATION_WINDOW_DEFAULT_DEADLINE)
        }

    /** Recenters the window on a retained message; throws when MDK no longer retains it. */
    override suspend fun jumpToMessage(messageIdHex: String): TimelinePageFfi? =
        installer.command(rethrowMissingTarget = true) { revision ->
            window.jumpToMessage(revision, messageIdHex, CONVERSATION_WINDOW_DEFAULT_DEADLINE)
        }

    /** Resumes following the tail; null when nothing newer was installed. */
    override suspend fun returnToLatest(): TimelinePageFfi? =
        installer.command { revision ->
            window.returnToLatest(revision, CONVERSATION_WINDOW_DEFAULT_DEADLINE)
        }

    /** Wakes pending operations and releases the runtime window; call before [close]. */
    @Suppress("SwallowedException", "TooGenericExceptionCaught")
    override suspend fun cancel() {
        try {
            window.cancel()
        } catch (teardown: MarmotKitException) {
            // The window is being torn down; a runtime that already released it needs nothing more.
        }
    }

    /** Releases the native handle; a handle the runtime already dropped needs no further action. */
    @Suppress("SwallowedException", "TooGenericExceptionCaught")
    override fun close() {
        try {
            release()
        } catch (teardown: MarmotKitException) {
            // Same contract as cancel: releasing twice, or after shutdown, is not an app-visible failure.
        }
    }

    private suspend fun page(
        direction: ConversationPageDirectionFfi,
        count: UInt,
    ): TimelinePageFfi {
        val rows = count.coerceIn(1u, CONVERSATION_WINDOW_MAX_ROWS)
        val result =
            installer.command { revision ->
                window.page(revision, direction, rows, CONVERSATION_WINDOW_DEFAULT_DEADLINE)
            }
        return result ?: installer.page ?: TimelinePageFfi(emptyList(), false, false)
    }
}

/**
 * One replacement from MDK's stream, or null when the stream ended and the window must be reopened.
 *
 * `ConversationWindowNotReady` is retryable by contract: MDK repairs dirty read state and retries accepted
 * work in the background, so the loop waits briefly and keeps receiving instead of failing the screen. Every
 * other window error is terminal for this handle, and the controller's reconnect loop opens a new one. No
 * window error escapes: one reaching the receive pump would cancel its scope and take the process down,
 * which is exactly how a not-ready window crashed the app on device.
 */
@Suppress("SwallowedException", "TooGenericExceptionCaught")
private suspend fun ConversationWindowSubscriptionInterface.nextReplacementOrNull(): ConversationWindowSnapshotFfi? {
    while (true) {
        try {
            return next()
        } catch (notReady: MarmotKitException.ConversationWindowNotReady) {
            delay(CONVERSATION_WINDOW_NOT_READY_RETRY_MS)
        } catch (terminal: MarmotKitException) {
            return null
        }
    }
}

/**
 * Reaction chips from the window's bounded reaction references. Counts are complete and
 * `viewerReacted` is authoritative for the viewer; optimistic changes adjust both until MDK echoes them.
 */
internal fun windowReactionTallies(
    reactions: ConversationReactionsFfi,
    optimisticChanges: Collection<OptimisticReactionChange>,
): List<ReactionTally> {
    val byEmoji = linkedMapOf<String, ReactionTally>()
    reactions.items.forEach { item ->
        byEmoji[item.emoji] = ReactionTally(item.emoji, item.count.toInt(), item.viewerReacted)
    }
    optimisticChanges.forEach { change ->
        val current = byEmoji[change.emoji]
        if (change.add && current?.mine != true) {
            byEmoji[change.emoji] = ReactionTally(change.emoji, (current?.count ?: 0) + 1, mine = true)
        } else if (!change.add && current?.mine == true) {
            if (current.count <= 1) {
                byEmoji.remove(change.emoji)
            } else {
                byEmoji[change.emoji] = current.copy(count = current.count - 1, mine = false)
            }
        }
    }
    return byEmoji.values.sortedWith(
        compareByDescending<ReactionTally> { it.count }
            .thenByDescending { it.mine }
            .thenBy { it.emoji },
    )
}

/**
 * Reaction participants from the window's bounded reactor preview. MDK previews at most a few
 * reactors per emoji, so this list can be shorter than the chip count; timestamps are not carried.
 */
internal fun windowReactionParticipants(
    reactions: ConversationReactionsFfi,
    viewerAccountIdHex: String?,
    optimisticChanges: Collection<OptimisticReactionChange>,
): List<ReactionParticipant> {
    val participants =
        reactions.items
            .flatMap { item -> item.reactors.map { reactor -> ReactionParticipant(reactor, item.emoji, 0uL) } }
            .toMutableList()
    val mine = viewerAccountIdHex ?: return participants
    optimisticChanges.forEach { change ->
        participants.removeAll { it.sender.equals(mine, ignoreCase = true) && it.emoji == change.emoji }
        if (change.add) participants += ReactionParticipant(mine, change.emoji, 0uL)
    }
    return participants
}

/** Merges window tallies for retained messages with sender-based tallies for everything else. */
internal fun reactionTalliesForWindow(
    sendersByTarget: Map<String, Map<String, Set<String>>>,
    references: Map<String, ConversationMessageReferencesFfi>?,
    optimisticChanges: Collection<OptimisticReactionChange>,
    viewerAccountIdHex: String?,
): Map<String, List<ReactionTally>> {
    val targets = sendersByTarget.keys + references?.keys.orEmpty()
    return targets
        .associateWith { target ->
            val windowReferences = references?.get(target)
            if (windowReferences != null) {
                val changes = optimisticChanges.filter { it.targetMessageId == target }
                windowReactionTallies(windowReferences.reactions, changes)
            } else {
                reactionTalliesForSenders(viewerAccountIdHex, sendersByTarget[target].orEmpty(), emptyList())
            }
        }.filterValues { it.isNotEmpty() }
}

/** Installs the sidecar that belongs to the page just applied and warms the previewed reactor identities. */
internal fun ConversationController.installWindowFrame(frame: ConversationWindowFrame?) {
    if (frame == null) return
    window.install(frame)
    val reactors = frame.references.values.flatMap { it.reactions.items.flatMap { item -> item.reactors } }
    if (reactors.isNotEmpty()) appState.requestProfiles(reactors.distinct())
}

/**
 * Reports the message the reader settled on so replacements keep it in view; a no-op without a window.
 * Optimistic rows carry local ids MDK never issued, so only a retained authoritative row is reported.
 */
suspend fun ConversationController.reportVisibleMessage(messageIdHex: String) {
    if (!retainsTimelineRecord(messageIdHex)) return
    val page = timelineSubscription?.setVisibleAnchor(messageIdHex) ?: return
    applyTimelinePage(page, replaceWindow = true, updatePagination = true)
}

/** Resumes following new arrivals after the reader jumped to the newest message. */
suspend fun ConversationController.returnToLatestWindow() {
    val page = timelineSubscription?.returnToLatest() ?: return
    applyTimelinePage(page, replaceWindow = true, updatePagination = true)
}
