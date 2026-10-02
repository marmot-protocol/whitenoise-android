package dev.ipf.whitenoise.android.state

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.ipf.marmotkit.MarmotEventFfi
import dev.ipf.marmotkit.PollOptionResultFfi
import dev.ipf.marmotkit.PollVoteFfi
import dev.ipf.marmotkit.PollVotePageFfi
import dev.ipf.marmotkit.TimelineMessageChangeFfi
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive

/** MDK accepts 1 through 100 votes per page, and the largest page keeps most polls to a single read. */
internal const val POLL_VOTES_PAGE_SIZE = 100u

/** Where the per-voter list is in its native paging lifecycle. */
internal enum class PollVotesPhase {
    /** The first page is being read. */
    LOADING,

    /** The loaded votes are current, and [PollVotesPager.hasMore] says whether another page follows. */
    READY,

    /** The first page failed, so there is nothing to show yet. */
    FAILED,

    /** A later page is being read while the loaded votes stay visible. */
    LOADING_MORE,

    /** A later page failed, so the loaded votes stay visible with a retry. */
    MORE_FAILED,
}

/** One native page read, keyed by the previous page's last vote as MDK's cursor. */
internal fun interface PollVotesReader {
    /** Reads one page after the cursor, or the first page when both cursor fields are null. */
    suspend fun read(
        afterVotedAt: ULong?,
        afterVoterAccountIdHex: String?,
        limit: UInt,
    ): PollVotePageFfi
}

/**
 * Pages MDK's authoritative per-voter results for one poll without reconstructing any vote locally.
 * A pager belongs to one account, chat and poll projection: the host recreates it when the poll row is
 * reprojected, and [isCurrent] drops results that arrive after the account or chat changed.
 */
internal class PollVotesPager(
    private val reader: PollVotesReader,
    private val isCurrent: () -> Boolean,
    private val pageSize: UInt = POLL_VOTES_PAGE_SIZE,
) {
    /** Votes loaded so far in MDK's `(votedAt, voterAccountIdHex)` order. */
    var votes: List<PollVoteFfi> by mutableStateOf(emptyList())
        private set

    /** Current paging lifecycle step. */
    var phase: PollVotesPhase by mutableStateOf(PollVotesPhase.LOADING)
        private set

    /** Whether MDK reported more votes after the last loaded one. */
    var hasMore: Boolean by mutableStateOf(false)
        private set

    private var generation = 0

    /**
     * Reads the first page again and supersedes any in-flight read. Votes already shown stay visible until
     * the new page replaces them, so a reprojection does not blank the list or lose the scroll position.
     */
    suspend fun refresh() {
        val mine = ++generation
        val hadVotes = votes.isNotEmpty()
        if (!hadVotes) {
            hasMore = false
            phase = PollVotesPhase.LOADING
        }
        read(mine, cursor = null)?.let { page ->
            votes = page.votes
            hasMore = page.hasMoreAfter && page.votes.isNotEmpty()
            phase = PollVotesPhase.READY
        } ?: failIfCurrent(mine, if (hadVotes) PollVotesPhase.READY else PollVotesPhase.FAILED)
    }

    /** Appends the next page; ignored while a read is in flight or when nothing follows. */
    suspend fun loadMore() {
        val last = votes.lastOrNull()
        val idle = phase == PollVotesPhase.READY || phase == PollVotesPhase.MORE_FAILED
        if (!hasMore || !idle || last == null) return
        val mine = ++generation
        phase = PollVotesPhase.LOADING_MORE
        read(mine, cursor = last)?.let { page ->
            // MDK lists each voter's latest response, so a re-vote between pages can repeat a loaded voter
            // later in the order. The newest entry wins, which also keeps list keys unique.
            val repeated = page.votes.mapTo(hashSetOf()) { it.voterAccountIdHex }
            votes = votes.filterNot { it.voterAccountIdHex in repeated } + page.votes
            hasMore = page.hasMoreAfter && page.votes.isNotEmpty()
            phase = PollVotesPhase.READY
        } ?: failIfCurrent(mine, PollVotesPhase.MORE_FAILED)
    }

    private fun failIfCurrent(
        mine: Int,
        failure: PollVotesPhase,
    ) {
        // A read the owner invalidated mid-flight still leaves the pager retryable, never stuck loading.
        if (mine == generation) phase = failure
    }

    /** Returns the page only while this read is still the newest and its owner is still current. */
    private suspend fun read(
        mine: Int,
        cursor: PollVoteFfi?,
    ): PollVotePageFfi? =
        runCatchingCancellable { reader.read(cursor?.votedAt, cursor?.voterAccountIdHex, pageSize) }
            .onFailure { appStateDebug(it) { "poll votes read failed" } }
            .getOrNull()
            ?.takeIf { mine == generation && isCurrent() }
}

/** One rendered voter, with option labels resolved from the card's own options. */
internal data class PollVoteRow(
    val voterAccountIdHex: String,
    val choices: List<String>,
)

/** Maps native votes to rows, labelling option ids from the card's options and ignoring unknown ids. */
internal fun pollVoteRows(
    votes: List<PollVoteFfi>,
    options: List<PollOptionResultFfi>,
): List<PollVoteRow> {
    val labels = options.associate { it.id to it.label }
    return votes.map { vote -> PollVoteRow(vote.voterAccountIdHex, vote.optionIds.mapNotNull(labels::get)) }
}

/** Forwards each projection event that touched the poll to [onTouched] until the stream ends or is cancelled. */
internal suspend fun observePollProjection(
    nextEvent: suspend () -> MarmotEventFfi?,
    touched: (MarmotEventFfi) -> Boolean,
    onTouched: () -> Unit,
) {
    while (currentCoroutineContext().isActive) {
        val event = nextEvent() ?: return
        if (touched(event)) onTouched()
    }
}

/**
 * Whether [event] reprojected the poll [pollEventId] of [groupIdHex] for [accountIdHex]. MDK says to re-read
 * per-voter results from the start then, and a same-option re-vote changes only `votedAt`, which the
 * poll row in the window never shows.
 */
internal fun pollProjectionTouched(
    event: MarmotEventFfi,
    accountIdHex: String?,
    groupIdHex: String,
    pollEventId: String,
): Boolean {
    val runtime = (event as? MarmotEventFfi.ProjectionUpdated)?.update ?: return false
    val update = runtime.update
    val sameScope = runtime.accountIdHex.equals(accountIdHex, ignoreCase = true) && update.groupIdHex == groupIdHex
    return sameScope &&
        (
            update.messages.any { it.messageIdHex == pollEventId } ||
                update.changes.any { change ->
                    when (change) {
                        is TimelineMessageChangeFfi.Upsert -> change.message.messageIdHex == pollEventId
                        is TimelineMessageChangeFfi.Remove -> change.messageIdHex == pollEventId
                    }
                }
        )
}
