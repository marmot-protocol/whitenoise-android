package dev.ipf.whitenoise.android.ui.conversation

import androidx.compose.runtime.withFrameNanos
import dev.ipf.whitenoise.android.state.TimelineMessage

/** Frames an appended incoming row has to be measured before the append keeps ordinary tail following. */
private const val INCOMING_APPEND_MEASURE_FRAMES = 6

/** Rows the tail layout did not reach that one append premeasures, oldest first, to bound a long catch-up's cost. */
internal const val INCOMING_APPEND_MAX_PREMEASURED_ROWS = 8

/** One row a live append added after the newest row the reader was following. */
internal data class ConversationAppendedRow(
    val messageId: String,
    val itemId: String,
    val mine: Boolean,
)

/** An appended row someone else sent, the only kind of append whose reading start may replace the tail. */
internal data class ConversationIncomingAppendCandidate(
    val messageId: String,
    val itemId: String,
)

/** What one live append did to the transcript and to its durable reading intent. */
internal sealed interface ConversationIncomingAppendOutcome {
    /** The reader was not following the live tail, so the append moved nothing. */
    data object NotFollowing : ConversationIncomingAppendOutcome

    /** The newest edge was followed, as for any append whose incoming rows fit the clear reading viewport. */
    data object FollowedTail : ConversationIncomingAppendOutcome

    /** A gesture, navigation or newer command took the list before the append finished. */
    data object Superseded : ConversationIncomingAppendOutcome

    /** A hidden incoming beginning meets the top of the clear viewport and is the durable reading anchor. */
    data class RevealedReadingStart(
        val anchor: ConversationScrollAnchor,
        val probe: ConversationReadingStartProbe,
        val placement: ConversationReadingStartPlacement,
    ) : ConversationIncomingAppendOutcome
}

/**
 * The rows a genuine append added after [previousLatestId], oldest first. An older-page trim never reaches here,
 * because the caller only treats a publication as an append while the row it followed is still present.
 */
internal fun conversationAppendedRows(
    renderedTimeline: List<TimelineMessage>,
    previousLatestId: String,
    isMine: (TimelineMessage) -> Boolean,
): List<ConversationAppendedRow> {
    val previousIndex = renderedTimeline.indexOfFirst { it.presentationId == previousLatestId }
    return renderedTimeline.drop(previousIndex + 1).map { row ->
        ConversationAppendedRow(row.record.messageIdHex, row.presentationId, isMine(row))
    }
}

/**
 * The incoming rows still waiting for a landing decision in one burst of appends.
 *
 * Each append restarts the screen's follow effect, which cancels a landing that was still waiting for its row to be
 * measured. Keeping the earlier rows here means the next append decides over the whole burst, so its first hidden
 * beginning, not the latest arrival, gets the reading start. The rows belong to the scroll intent they were admitted
 * under, so a drag, a navigation or any other settled intent change since then discards them. An own row in the burst
 * is the reader's accepted send, which keeps the established tail reveal, so it retires the incoming landing as well.
 *
 * A cancelled landing may already have seen an oversized row at the tail. Later short arrivals can push that row out
 * of the layout, where it can no longer be measured, so the burst keeps [oversizedSeen] alongside its rows.
 */
internal class ConversationIncomingAppendLandingQueue {
    private var pending: List<ConversationIncomingAppendCandidate> = emptyList()
    private var intentRevision: Long? = null

    /** True once a decision over this burst measured an oversized row at the tail, even if it was later cancelled. */
    var oversizedSeen: Boolean = false
        private set

    /** Adds [appended] to the burst still pending under [intent] and returns every incoming row to decide over. */
    fun admit(
        appended: List<ConversationAppendedRow>,
        intent: ConversationScrollIntentToken,
    ): List<ConversationIncomingAppendCandidate> {
        if (intent.revision != intentRevision || appended.any { it.mine }) clear()
        intentRevision = intent.revision
        pending =
            if (appended.any { it.mine }) {
                emptyList()
            } else {
                (pending + appended.map { ConversationIncomingAppendCandidate(it.messageId, it.itemId) })
                    .distinctBy { it.messageId }
            }
        return pending
    }

    /** Records that a decision over the pending burst measured an oversized row at the tail. */
    fun markOversized() {
        if (pending.isNotEmpty()) oversizedSeen = true
    }

    /** Forgets the burst once an append has reached a decision, completed or superseded. */
    fun retire() {
        clear()
        intentRevision = null
    }

    /** Drops the pending rows and what was seen of them. */
    private fun clear() {
        pending = emptyList()
        oversizedSeen = false
    }
}

/**
 * Follows a live append exactly as tail following always has, unless an incoming row laid out at the tail is taller
 * than the clear reading viewport. Physical-tail alignment would then hide a beginning the reader has not seen, so the
 * oldest incoming row of the append whose beginning the tail hides — the oversized row itself, or an earlier row of
 * the burst that later arrivals pushed above the viewport — is placed at the top instead, and the reader's intent
 * becomes reading that message. The same happens when a pushed-up row is itself oversized, which [premeasurer] finds
 * out without moving the list. Appends whose incoming rows each fit keep tail following, even when together they
 * overflow the viewport.
 *
 * The decision runs inside the same latest-wins command as the tail write. The rows are measured after the tail write
 * in the occlusion-aware reading layout, waiting a bounded number of frames for the newest of them to be laid out,
 * and the existing reading-start settle places and validates the beginning. A drag, a navigation, a send or any
 * newer command supersedes it before a stale correction can be written. Only a reader who was following the tail is
 * moved, and a history reader is left exactly where they are. [probeFor] resolves each row by message identity on
 * every read, so paging or structural rows that move its index cannot misplace the landing.
 *
 * [oversizedSeen] carries an oversized row an earlier, cancelled decision over the same burst measured, and
 * [onOversized] reports one this decision measures before it starts placing, so a cancellation cannot lose it.
 */
@Suppress("LongParameterList") // The burst, its probes and tail, plus the frame source and the burst's observation.
internal suspend fun ConversationScrollCoordinator.followAppendOrRevealIncomingStart(
    candidates: List<ConversationIncomingAppendCandidate>,
    probeFor: (ConversationIncomingAppendCandidate) -> ConversationReadingStartProbe,
    resolveTailIndex: () -> Int,
    oversizedSeen: Boolean = false,
    onOversized: () -> Unit = {},
    premeasurer: ConversationRowPremeasurer = ConversationRowPremeasurer.None,
    maxMeasureFrames: Int = INCOMING_APPEND_MEASURE_FRAMES,
    awaitFrame: suspend () -> Unit = { withFrameNanos { } },
): ConversationIncomingAppendOutcome {
    if (!isFollowingTail || mode !is ConversationScrollMode.FollowingTail) {
        return ConversationIncomingAppendOutcome.NotFollowing
    }
    var landed: Pair<ConversationIncomingAppendCandidate, ConversationReadingStartPlacement>? = null
    val completed =
        programmaticJump(
            targetMessageId = null,
            reason = ConversationScrollReason.NewMessage,
            resultingMode = ConversationScrollMode.FollowingTail,
        ) {
            awaitFrame()
            scrollToTail(resolveTailIndex())
            val target =
                awaitHiddenIncomingStart(
                    candidates,
                    probeFor,
                    maxMeasureFrames,
                    awaitFrame,
                    oversizedSeen,
                    onOversized,
                    premeasurer,
                )
            if (target != null) {
                val placement =
                    placeReadingStart(
                        probeFor(target).copy(awaitLayout = awaitFrame),
                        ConversationReadingStartApproach.Instant,
                    )
                // A beginning that never validated must not leave a tail follower parked partway up the row.
                if (placement == null) scrollToTail(resolveTailIndex()) else landed = target to placement
            }
        }
    val revealed = landed
    return when {
        !completed -> ConversationIncomingAppendOutcome.Superseded
        revealed == null -> ConversationIncomingAppendOutcome.FollowedTail
        else -> settleRevealedReadingStart(revealed.first, revealed.second, probeFor(revealed.first))
    }
}

/** Commits the landed beginning as a logical reading anchor, so later tail corrections leave it in place. */
private fun ConversationScrollCoordinator.settleRevealedReadingStart(
    target: ConversationIncomingAppendCandidate,
    placement: ConversationReadingStartPlacement,
    probe: ConversationReadingStartProbe,
): ConversationIncomingAppendOutcome.RevealedReadingStart {
    val anchor =
        ConversationScrollAnchor(
            listIndex = placement.index,
            pixelOffset = placement.offsetPx,
            itemId = target.itemId,
            messageId = target.messageId,
        )
    settleReadingAt(anchor)
    return ConversationIncomingAppendOutcome.RevealedReadingStart(anchor, probe, placement)
}

/**
 * Waits at most [maxFrames] frames for the newest appended row still in the timeline to be measured after the tail
 * write, then returns the oldest appended row whose beginning that write left above the clear reading viewport — but
 * only when a row laid out in that same tail layout is oversized, [oversizedSeen] says an earlier, cancelled decision
 * over the burst measured one, or a row the tail layout did not reach turns out to be oversized, and [onOversized]
 * records what this decision measured. A burst of rows that each fit keeps tail following, however far it overflows the
 * viewport in total, so a catch-up of short messages ends exactly where it always has.
 *
 * The newest row sits at the tail that was just written, so once it is measured the layout pass is complete. An older
 * row of the burst that is not laid out by then was pushed past the far edge by newer rows, so its beginning is hidden
 * even though its height is unknown, and the reading-start settle reaches and measures it if it becomes the target. A
 * laid-out row is hidden when its measured top passes the far edge, which covers a single row taller than the
 * viewport. Heights come from the rendered rows, never from text length, and the reading layout already excludes the
 * composer's overlap, so the keyboard is not subtracted a second time.
 *
 * Newer rows that each fit can together fill the viewport and push older rows of the burst out of the tail layout,
 * where they cannot be seen to be oversized. When nothing else decided the landing, [premeasurer] measures those rows
 * off screen within the same frame budget, so the list never moves for them.
 */
@Suppress("LongParameterList") // The burst, its probes, the frame source, the burst's observation and the premeasurer.
private suspend fun awaitHiddenIncomingStart(
    candidates: List<ConversationIncomingAppendCandidate>,
    probeFor: (ConversationIncomingAppendCandidate) -> ConversationReadingStartProbe,
    maxFrames: Int,
    awaitFrame: suspend () -> Unit,
    oversizedSeen: Boolean,
    onOversized: () -> Unit,
    premeasurer: ConversationRowPremeasurer,
): ConversationIncomingAppendCandidate? {
    val layouts = awaitTailLayouts(candidates, probeFor, maxFrames, awaitFrame).orEmpty()
    // Rows below the newest message (a pending membership row, a load error) are not part of the burst, but they
    // take space from it, so an arrival that fits the viewport alone can still have its beginning clipped.
    val trailingPx = layouts.lastOrNull { it?.isMeasured == true }?.itemOffsetPx?.coerceAtLeast(0) ?: 0
    val hidden = candidates.indices.firstOrNull { layouts.getOrNull(it)?.startHiddenAtTail == true } ?: return null
    val offTail = candidates.filterIndexed { index, _ -> layouts.getOrNull(index)?.isMeasured == false }
    val oversized =
        layouts.any { it?.isOversized(trailingPx) == true } ||
            (
                !oversizedSeen &&
                    anyOversizedOffTail(
                        offTail.map(probeFor),
                        viewportEndPx = layouts.firstNotNullOfOrNull { it?.viewportEndOffsetPx } ?: 0,
                        premeasurer,
                        maxFrames,
                        awaitFrame,
                    )
            )
    if (oversized) onOversized()
    return candidates[hidden].takeIf { oversized || oversizedSeen }
}

/**
 * Whether any of [probes], rows the tail layout did not reach with the oldest first, is taller than the clear reading
 * viewport ending at [viewportEndPx]. At most [INCOMING_APPEND_MAX_PREMEASURED_ROWS] of them are premeasured, and the
 * wait ends at the first oversized size, once every size is in or after [maxFrames] frames. An off-tail row is not
 * placed above the trailing rows under the newest message, so only the viewport counts against it. The premeasured
 * rows are released afterwards. Known limit: an oversized row beyond that many of the oldest keeps the tail.
 */
private suspend fun anyOversizedOffTail(
    probes: List<ConversationReadingStartProbe>,
    viewportEndPx: Int,
    premeasurer: ConversationRowPremeasurer,
    maxFrames: Int,
    awaitFrame: suspend () -> Unit,
): Boolean {
    if (viewportEndPx <= 0) return false
    val sizes = mutableListOf<Int>()
    val releases =
        probes.take(INCOMING_APPEND_MAX_PREMEASURED_ROWS).mapNotNull { probe ->
            probe.resolveTargetIndex()?.let { index -> premeasurer.premeasure(index) { sizes += it } }
        }
    try {
        var frame = 0
        while (sizes.none { it > viewportEndPx } && sizes.size < releases.size && frame < maxFrames.coerceAtLeast(1)) {
            awaitFrame()
            frame++
        }
    } finally {
        releases.forEach { it() }
    }
    return sizes.any { it > viewportEndPx }
}

/**
 * Reads every candidate's layout once the newest present one is measured, or null when that never happens within
 * [maxFrames] frames. A row that has left the timeline reads as null, a row that is not laid out as unmeasured.
 */
private suspend fun awaitTailLayouts(
    candidates: List<ConversationIncomingAppendCandidate>,
    probeFor: (ConversationIncomingAppendCandidate) -> ConversationReadingStartProbe,
    maxFrames: Int,
    awaitFrame: suspend () -> Unit,
): List<ConversationMentionJumpLayout?>? {
    var layouts: List<ConversationMentionJumpLayout?>? = null
    var frame = 0
    while (candidates.isNotEmpty() && layouts == null && frame < maxFrames.coerceAtLeast(1)) {
        awaitFrame()
        frame++
        val current = candidates.map { probeFor(it).currentLayout() }
        val newest = current.lastOrNull { it != null }
        if (newest == null || newest.isMeasured) layouts = current
    }
    return layouts
}

/** The row's current layout, or null once the row has left the timeline. */
private fun ConversationReadingStartProbe.currentLayout() = resolveTargetIndex()?.let(readLayout)

/**
 * Whether physical-tail alignment left this present row's beginning outside the reading viewport: it is not laid out
 * at all, or its measured top lies beyond the viewport's far edge. A newest row exactly as tall as the space between
 * the tail and that edge fits.
 */
private val ConversationMentionJumpLayout.startHiddenAtTail: Boolean
    get() = !isMeasured || requireNotNull(itemOffsetPx) + requireNotNull(itemHeightPx) > viewportEndOffsetPx

/**
 * A measured row taller than the space between the [trailingPx] rows under the newest message and the viewport's far
 * edge. A row exactly that tall fits.
 */
private fun ConversationMentionJumpLayout.isOversized(trailingPx: Int): Boolean =
    isMeasured &&
        requireNotNull(itemHeightPx) + trailingPx > viewportEndOffsetPx
