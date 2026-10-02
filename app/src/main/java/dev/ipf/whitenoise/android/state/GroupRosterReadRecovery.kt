package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.MarmotKitException
import dev.ipf.whitenoise.android.R

/** Why one account-owned roster read did not produce a roster to verify (#2861). */
internal enum class GroupRosterReadFailureKind {
    /** MDK has not finished hydrating the group locally yet. */
    HYDRATION_PENDING,

    /** A busy or not-yet-ready local runtime that a later read can clear. */
    TRANSIENT,

    /** A newer canonical group observation invalidated the captured read. */
    SUPERSEDED,

    /** A failure a repeated read would not fix, such as an unknown account or group. */
    TERMINAL,
}

/**
 * Why a notification-opened transcript stays hidden behind the roster gate, so
 * the error and its report name the branch instead of one unqualified sentence.
 */
internal sealed interface GroupRosterBlockReason {
    /** The read itself failed; [attempts] counts every read in the exhausted chain. */
    data class ReadFailed(
        val kind: GroupRosterReadFailureKind,
        val attempts: Int,
    ) : GroupRosterBlockReason

    /** MDK answered, but the answer failed an account/group membership invariant. */
    data class Inconsistent(
        val invariant: GroupRosterInvariant,
    ) : GroupRosterBlockReason
}

/**
 * Backoff between automatic roster re-reads while a transcript is blocked on the
 * roster. Its length bounds the retries; manual Retry starts a fresh chain.
 */
internal val GROUP_ROSTER_READ_RETRY_DELAYS_MS = listOf(750L, 1_500L, 3_000L)

/** Classifies a roster read failure without inspecting any identifier it carries. */
internal fun classifyGroupRosterReadFailure(failure: Throwable): GroupRosterReadFailureKind =
    when (failure) {
        is SupersededGroupRosterRead -> GroupRosterReadFailureKind.SUPERSEDED
        is MarmotKitException.GroupHydrationPending -> GroupRosterReadFailureKind.HYDRATION_PENDING
        is MarmotKitException.RuntimeBusy,
        is MarmotKitException.AccountSessionBusy,
        is MarmotKitException.AccountWorkerBusy,
        is MarmotKitException.AccountWorkerResponseTimedOut,
        is MarmotKitException.AccountCatchUp,
        is MarmotKitException.StorageBusy,
        -> GroupRosterReadFailureKind.TRANSIENT
        else -> GroupRosterReadFailureKind.TERMINAL
    }

/**
 * Returns the delay before automatic re-read number [attempt] (zero-based), or
 * null when the failure should settle and expose manual Retry. A blocked
 * transcript backs off through [delays]; a transcript that already has trusted
 * chrome keeps the single hydration/supersession re-read it always had.
 */
internal fun groupRosterReadRetryDelayMs(
    kind: GroupRosterReadFailureKind,
    attempt: Int,
    transcriptBlocked: Boolean,
    delays: List<Long> = GROUP_ROSTER_READ_RETRY_DELAYS_MS,
): Long? =
    when (kind) {
        GroupRosterReadFailureKind.SUPERSEDED -> 0L.takeIf { attempt == 0 }
        GroupRosterReadFailureKind.HYDRATION_PENDING ->
            if (transcriptBlocked) delays.getOrNull(attempt) else delays.first().takeIf { attempt == 0 }
        GroupRosterReadFailureKind.TRANSIENT -> if (transcriptBlocked) delays.getOrNull(attempt) else null
        GroupRosterReadFailureKind.TERMINAL -> null
    }

/** Privacy-safe roster diagnostic: branch, attempt and gate facts, never account, group or member IDs. */
internal fun groupRosterReadDiagnostic(
    event: String,
    kind: GroupRosterReadFailureKind?,
    attempt: Int,
    transcriptBlocked: Boolean,
    initialSnapshot: Boolean,
    targetAccountActive: Boolean,
): String =
    "roster read $event" +
        (kind?.let { " kind=${it.name.lowercase()}" } ?: "") +
        " attempt=$attempt" +
        " transcript_blocked=$transcriptBlocked" +
        " initial_snapshot=$initialSnapshot" +
        " target_account_active=$targetAccountActive"

/** The support report for a blocked transcript, naming the branch without any identifier. */
internal fun GroupRosterBlockReason?.transcriptRosterReport(): String =
    "Operation: CONVERSATION_TRANSCRIPT_ROSTER\n" +
        when (this) {
            is GroupRosterBlockReason.ReadFailed ->
                "Branch: read_failed\nFailure: ${kind.name.lowercase()}\nReads: $attempts\n"
            is GroupRosterBlockReason.Inconsistent -> "Branch: inconsistent\nInvariant: ${invariant.name.lowercase()}\n"
            null -> ""
        } +
        "Account-owned membership could not be verified."

/** The blocked-transcript error, keeping a wrong roster's explanation distinct from a failed read's Retry copy. */
internal fun GroupRosterBlockReason?.transcriptRosterError(): ErrorPresentation =
    ErrorPresentation(
        message =
            AppText.Resource(
                if (this is GroupRosterBlockReason.Inconsistent) {
                    R.string.error_conversation_membership_inconsistent
                } else {
                    R.string.error_conversation_membership_unavailable
                },
            ),
        report = transcriptRosterReport(),
    )
