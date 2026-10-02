package dev.ipf.whitenoise.android.state

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.ipf.marmotkit.PollOptionResultFfi
import dev.ipf.marmotkit.PollVoteFfi
import dev.ipf.marmotkit.PollVotePageFfi

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

    /** Discards loaded votes and in-flight reads, then reads the first page again. */
    suspend fun refresh() {
        val mine = ++generation
        votes = emptyList()
        hasMore = false
        phase = PollVotesPhase.LOADING
        read(mine, cursor = null)?.let { page ->
            votes = page.votes
            hasMore = page.hasMoreAfter && page.votes.isNotEmpty()
            phase = PollVotesPhase.READY
        } ?: failIfCurrent(mine, PollVotesPhase.FAILED)
    }

    /** Appends the next page; ignored while a read is in flight or when nothing follows. */
    suspend fun loadMore() {
        val last = votes.lastOrNull()
        val idle = phase == PollVotesPhase.READY || phase == PollVotesPhase.MORE_FAILED
        if (!hasMore || !idle || last == null) return
        val mine = ++generation
        phase = PollVotesPhase.LOADING_MORE
        read(mine, cursor = last)?.let { page ->
            votes = votes + page.votes
            // A page whose last vote repeats the cursor cannot advance, so stop instead of looping.
            hasMore = page.hasMoreAfter && page.votes.isNotEmpty() && page.votes.last() != last
            phase = PollVotesPhase.READY
        } ?: failIfCurrent(mine, PollVotesPhase.MORE_FAILED)
    }

    private fun failIfCurrent(
        mine: Int,
        failure: PollVotesPhase,
    ) {
        if (mine == generation && isCurrent()) phase = failure
    }

    /** Returns the page only while this read is still the newest and its owner is still current. */
    private suspend fun read(
        mine: Int,
        cursor: PollVoteFfi?,
    ): PollVotePageFfi? =
        runCatchingCancellable { reader.read(cursor?.votedAt, cursor?.voterAccountIdHex, pageSize) }
            .getOrNull()
            ?.takeIf { mine == generation && isCurrent() }
}

/** One rendered voter, resolved from MDK's vote and the host's profile and block-list lookups. */
internal data class PollVoteRow(
    val voterAccountIdHex: String,
    val displayName: String,
    val avatarUrl: String?,
    val choices: List<String>,
    val blocked: Boolean,
)

/** Maps native votes to rows, labelling option ids from the card's own options and keeping blocked voters. */
internal fun pollVoteRows(
    votes: List<PollVoteFfi>,
    options: List<PollOptionResultFfi>,
    displayName: (String) -> String,
    avatarUrl: (String) -> String?,
    isBlocked: (String) -> Boolean,
): List<PollVoteRow> {
    val labels = options.associate { it.id to it.label }
    return votes.map { vote ->
        PollVoteRow(
            voterAccountIdHex = vote.voterAccountIdHex,
            displayName = displayName(vote.voterAccountIdHex),
            avatarUrl = avatarUrl(vote.voterAccountIdHex),
            choices = vote.optionIds.mapNotNull(labels::get),
            blocked = isBlocked(vote.voterAccountIdHex),
        )
    }
}
