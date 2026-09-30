package dev.ipf.whitenoise.android.ui.conversation

import dev.ipf.marmotkit.PollProjectionFfi
import dev.ipf.marmotkit.PollTypeFfi

private const val POLL_MILLIS_PER_SECOND = 1_000L

/** Applies an in-flight replacement vote to MDK's last projection for immediate feedback. */
internal fun optimisticPollProjection(
    poll: PollProjectionFfi,
    selection: List<String>,
): PollProjectionFfi {
    val previous = poll.localSelection.toSet()
    val next = selection.toSet()
    if (previous == next) return poll
    return poll.copy(
        options =
            poll.options.map { option ->
                option.copy(
                    votes =
                        when {
                            option.id in previous && option.id !in next ->
                                if (option.votes > 0uL) option.votes - 1uL else 0uL
                            option.id !in previous && option.id in next -> option.votes + 1uL
                            else -> option.votes
                        },
                )
            },
        participants = if (previous.isEmpty() && next.isNotEmpty()) poll.participants + 1uL else poll.participants,
        localSelection = selection,
    )
}

/** Fraction of participants who selected an option, bounded for stale projections. */
internal fun pollResultFraction(
    votes: ULong,
    participants: ULong,
): Float = if (participants == 0uL) 0f else (votes.toDouble() / participants.toDouble()).toFloat().coerceIn(0f, 1f)

/** Checks expiry before the first frame and again when a vote is tapped. */
internal fun pollDeadlineReached(
    endsAt: ULong?,
    nowMillis: Long,
): Boolean = endsAt != null && endsAt < (nowMillis / POLL_MILLIS_PER_SECOND).toULong()

/** Rejects a tap against an expired projection even before Compose's deadline timer fires. */
internal fun pollVoteAllowed(
    poll: PollProjectionFfi,
    nowMillis: Long,
): Boolean = poll.open && !pollDeadlineReached(poll.endsAt, nowMillis)

/** Computes the complete replacement vote from native option ids, never an empty selection. */
internal fun replacementPollSelection(
    poll: PollProjectionFfi,
    tappedId: String,
): List<String>? {
    val selection = poll.localSelection.filter { id -> poll.options.any { it.id == id } }
    return if (poll.options.none { it.id == tappedId }) {
        null
    } else if (poll.pollType == PollTypeFfi.SINGLE_CHOICE) {
        listOf(tappedId).takeUnless { it == selection }
    } else {
        val next = if (tappedId in selection) selection - tappedId else selection + tappedId
        next.takeIf { it.isNotEmpty() && it != selection }
    }
}
