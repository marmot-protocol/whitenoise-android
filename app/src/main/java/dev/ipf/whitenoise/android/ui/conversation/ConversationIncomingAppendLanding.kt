package dev.ipf.whitenoise.android.ui.conversation

import androidx.compose.runtime.withFrameNanos
import dev.ipf.whitenoise.android.state.TimelineMessage

/** Frames an appended incoming row has to be measured before the append keeps ordinary tail following. */
private const val INCOMING_APPEND_MEASURE_FRAMES = 6

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

    /** An oversized incoming row's beginning meets the top of the clear viewport and is the durable reading anchor. */
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
 * measured. Keeping the earlier rows here means the next append decides over the whole burst, so its first oversized
 * row, not the latest arrival, gets the reading start. The rows belong to the scroll intent they were admitted under:
 * a drag, a navigation or any other settled intent change since then discards them. An own row in the burst is the
 * reader's accepted send, which keeps the established tail reveal, so it retires the incoming landing as well.
 */
internal class ConversationIncomingAppendLandingQueue {
    private var pending: List<ConversationIncomingAppendCandidate> = emptyList()
    private var intentRevision: Long? = null

    /** Adds [appended] to the burst still pending under [intent] and returns every incoming row to decide over. */
    fun admit(
        appended: List<ConversationAppendedRow>,
        intent: ConversationScrollIntentToken,
    ): List<ConversationIncomingAppendCandidate> {
        if (intent.revision != intentRevision) pending = emptyList()
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

    /** Forgets the burst once an append has reached a decision, completed or superseded. */
    fun retire() {
        pending = emptyList()
        intentRevision = null
    }
}

/**
 * Follows a live append exactly as tail following always has, unless an incoming row in it is taller than the clear
 * reading viewport. Physical-tail alignment would then show only that row's end, so its beginning is placed at the
 * top of the viewport instead and the reader's intent becomes reading that message.
 *
 * The decision runs inside the same latest-wins command as the tail write. The rows are measured after the tail write
 * in the occlusion-aware reading layout, waiting a bounded number of frames for the newest of them to be laid out,
 * and the existing reading-start settle places and validates the beginning. A drag, a navigation, a send or any
 * newer command supersedes it before a stale correction can be written. Only a reader who was following the tail is
 * moved, and a history reader is left exactly where they are. [probeFor] resolves each row by message identity on
 * every read, so paging or structural rows that move its index cannot misplace the landing.
 */
internal suspend fun ConversationScrollCoordinator.followAppendOrRevealIncomingStart(
    candidates: List<ConversationIncomingAppendCandidate>,
    probeFor: (ConversationIncomingAppendCandidate) -> ConversationReadingStartProbe,
    resolveTailIndex: () -> Int,
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
            val target = awaitOversizedIncomingRow(candidates, probeFor, maxMeasureFrames, awaitFrame)
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
 * Waits at most [maxFrames] frames for the newest appended row still in the timeline to be measured, then returns the
 * oldest appended row whose measured height exceeds the clear reading viewport, or null when every one fits.
 *
 * The newest row sits at the tail that was just written, so once it is measured the layout pass is complete. An older
 * row of the burst that newer rows already pushed past the top is not laid out and is passed over. Heights come from
 * the rendered rows, never from text length, and the reading layout already excludes the composer's overlap, so the
 * keyboard is not subtracted a second time.
 */
private suspend fun awaitOversizedIncomingRow(
    candidates: List<ConversationIncomingAppendCandidate>,
    probeFor: (ConversationIncomingAppendCandidate) -> ConversationReadingStartProbe,
    maxFrames: Int,
    awaitFrame: suspend () -> Unit,
): ConversationIncomingAppendCandidate? {
    var decided = candidates.isEmpty()
    var target: ConversationIncomingAppendCandidate? = null
    var frame = 0
    while (!decided && frame < maxFrames.coerceAtLeast(1)) {
        awaitFrame()
        frame++
        // A row that has left the timeline reads as null, an unlaid-out row as an unmeasured layout.
        val layouts = candidates.map { probeFor(it).let { probe -> probe.resolveTargetIndex()?.let(probe.readLayout) } }
        val newest = layouts.lastOrNull { it != null }
        if (newest == null || newest.isMeasured) {
            decided = true
            target =
                candidates
                    .zip(layouts)
                    .firstOrNull { (_, layout) -> layout?.exceedsReadingViewport == true }
                    ?.first
        }
    }
    return target
}

/**
 * Whether physical-tail alignment would clip this row's beginning: its rendered height is larger than the reading
 * viewport between the tail and the viewport's far edge. A row exactly as tall as that space fits.
 */
private val ConversationMentionJumpLayout.exceedsReadingViewport: Boolean
    get() = isMeasured && readingStartOffset > 0
