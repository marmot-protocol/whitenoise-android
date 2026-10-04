package dev.ipf.whitenoise.android.state

import android.os.SystemClock
import android.util.Log
import dev.ipf.marmotkit.PollTypeFfi
import dev.ipf.marmotkit.PollVotePageFfi
import dev.ipf.marmotkit.SendAcceptDispositionFfi
import dev.ipf.whitenoise.android.R

internal const val MAX_POLL_OPTIONS = 10
internal const val POLL_FIVE_MINUTES_SECONDS = 5L * 60L
internal const val POLL_HOUR_SECONDS = 60L * 60L
internal const val POLL_DAY_SECONDS = 24L * POLL_HOUR_SECONDS
internal const val POLL_WEEK_SECONDS = 7L * POLL_DAY_SECONDS
internal const val MAX_POLL_DEADLINE_SECONDS = 30L * POLL_DAY_SECONDS
private const val POLL_MILLIS_PER_SECOND = 1_000L

/** Computes MDK's Unix-second deadline at send time, so an open draft does not shorten the poll. */
internal fun pollDeadlineEpochSeconds(
    durationSeconds: Long?,
    nowMillis: Long,
): ULong? {
    if (durationSeconds == null) return null
    require(durationSeconds in 1..MAX_POLL_DEADLINE_SECONDS)
    return (nowMillis / POLL_MILLIS_PER_SECOND + durationSeconds).toULong()
}

/** Publish a group poll through MDK; native failures vary, while cancellation must propagate. */
@Suppress("TooGenericExceptionCaught")
internal suspend fun ConversationController.createPoll(
    question: String,
    options: List<String>,
    pollType: PollTypeFfi,
    deadlineDurationSeconds: Long?,
): Boolean {
    val account = boundAccountRef ?: return false
    val cleanQuestion = question.trim()
    val cleanOptions = options.map(String::trim)
    val canPublish = canSendMessages && !isDirectConversation
    val validDraft =
        cleanQuestion.isNotEmpty() &&
            cleanOptions.size in 2..MAX_POLL_OPTIONS &&
            cleanOptions.none(String::isEmpty) &&
            (deadlineDurationSeconds == null || deadlineDurationSeconds in 1..MAX_POLL_DEADLINE_SECONDS)
    return if (!canPublish || !validDraft) {
        false
    } else {
        try {
            appState.withGroupCommitLock(account, group.groupIdHex) {
                check(canSendMessages && !isDirectConversation)
                appState.marmotIo {
                    createPoll(
                        account,
                        group.groupIdHex,
                        cleanQuestion,
                        cleanOptions,
                        pollType,
                        pollDeadlineEpochSeconds(deadlineDurationSeconds, System.currentTimeMillis()),
                    )
                }
            }
            true
        } catch (throwable: Throwable) {
            rethrowIfCancellation(throwable)
            appState.presentFailure(R.string.poll_create_failed, "POLL_CREATE", throwable)
            false
        }
    }
}

/** Replace the account's selection; surface native failures while preserving cancellation. */
@Suppress("TooGenericExceptionCaught")
internal suspend fun ConversationController.castPollVote(
    pollEventId: String,
    optionIds: List<String>,
): SendAcceptDispositionFfi? {
    val account = boundAccountRef ?: return null
    val canVote = canSendMessages && pollEventId.isNotBlank() && optionIds.isNotEmpty()
    return if (!canVote) {
        null
    } else {
        val startedAt = SystemClock.elapsedRealtime()
        try {
            val summary =
                appState.withGroupCommitLock(account, group.groupIdHex) {
                    check(canSendMessages)
                    appState.marmotIo {
                        castPollVote(account, group.groupIdHex, pollEventId, optionIds)
                    }
                }
            Log.i(
                "WNPolls",
                "event=vote_result outcome=${summary.acceptDisposition.name} " +
                    "elapsed_ms=${SystemClock.elapsedRealtime() - startedAt} option_count=${optionIds.size}",
            )
            summary.acceptDisposition
        } catch (throwable: Throwable) {
            rethrowIfCancellation(throwable)
            Log.i("WNPolls", "event=vote_result outcome=failed elapsed_ms=${SystemClock.elapsedRealtime() - startedAt}")
            appState.presentFailure(R.string.poll_vote_failed, "POLL_VOTE", throwable.withPollVoteFailureAttribution())
            null
        }
    }
}

/**
 * Reads one page of MDK's per-voter results for [pollEventId] off the main thread. It throws when no
 * account is bound, so the pager shows its failure state instead of an empty poll.
 */
internal suspend fun ConversationController.pollVotesPage(
    pollEventId: String,
    afterVotedAt: ULong?,
    afterVoterAccountIdHex: String?,
    limit: UInt,
): PollVotePageFfi {
    val account = checkNotNull(boundAccountRef) { "no account bound" }
    return appState.marmotIo {
        pollVotes(account, group.groupIdHex, pollEventId, afterVotedAt, afterVoterAccountIdHex, limit)
    }
}
