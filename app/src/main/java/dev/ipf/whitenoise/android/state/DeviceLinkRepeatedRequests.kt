package dev.ipf.whitenoise.android.state

import android.content.Context
import dev.ipf.marmotkit.DeviceLinkEndReasonFfi
import dev.ipf.marmotkit.DeviceLinkPromptStateFfi
import dev.ipf.marmotkit.DeviceLinkSnapshotFfi

/** How long an unanswered link request counts towards a profile's repeated requests. */
internal const val REPEATED_LINK_REQUEST_WINDOW_MS = 60 * 60 * 1000L

/**
 * How many unanswered link requests within [REPEATED_LINK_REQUEST_WINDOW_MS] make a profile's requests repeated. The
 * second one is enough: someone linking their own device answers the first, and anyone who keeps asking without an
 * answer is the pattern that wears people into approving.
 */
internal const val REPEATED_LINK_REQUESTS = 2

/**
 * Whether a request in this state went, or is still going, without this user linking it: it still waits for an
 * answer, or it ended without being linked here or on another of the account's devices. A request this user approved
 * ([DeviceLinkPromptStateFfi.Linking] onwards, or a link that failed after both users confirmed) is answered.
 */
internal fun DeviceLinkPromptStateFfi.isUnanswered(): Boolean =
    when (this) {
        DeviceLinkPromptStateFfi.Preparing,
        is DeviceLinkPromptStateFfi.Comparing,
        DeviceLinkPromptStateFfi.CompetingRequests,
        is DeviceLinkPromptStateFfi.RateLimited,
        is DeviceLinkPromptStateFfi.CannotLink,
        DeviceLinkPromptStateFfi.TooManyDevices,
        -> true
        DeviceLinkPromptStateFfi.Linking,
        DeviceLinkPromptStateFfi.Linked,
        is DeviceLinkPromptStateFfi.AddedElsewhere,
        -> false
        is DeviceLinkPromptStateFfi.Ended ->
            reason != DeviceLinkEndReasonFfi.LINK_FAILED && reason != DeviceLinkEndReasonFfi.ANSWERED_ELSEWHERE
    }

/**
 * Per profile in [snapshots], when each unanswered request was first seen ([nowMs]), from [before] and MDK's latest
 * prompts. MDK removes a declined prompt at once and a settled one some minutes after it settles, so a request it no
 * longer lists keeps the place it had; a listed one is kept only while it is still unanswered, so a request the user
 * linked stops counting. Entries older than [REPEATED_LINK_REQUEST_WINDOW_MS], and profiles no longer watched, drop.
 * This holds only when requests were seen, in memory: it is not a second store of MDK's prompts.
 */
internal fun unansweredLinkRequests(
    before: Map<String, Map<String, Long>>,
    snapshots: Map<String, DeviceLinkSnapshotFfi>,
    nowMs: Long,
): Map<String, Map<String, Long>> =
    snapshots
        .mapValues { (account, snapshot) ->
            val seen = before[account].orEmpty()
            val listed = snapshot.prompts.associateBy { it.promptId }
            val kept = seen.filterKeys { id -> listed[id]?.state?.isUnanswered() ?: true }
            val added =
                snapshot.prompts
                    .filter { it.state.isUnanswered() && it.promptId !in seen }
                    .associate { it.promptId to nowMs }
            (kept + added).filterValues { nowMs - it in 0 until REPEATED_LINK_REQUEST_WINDOW_MS }
        }.filterValues { it.isNotEmpty() }

/** The profiles whose [unanswered] requests are repeated, so Link is no longer offered for any of their requests. */
internal fun repeatedLinkRequestAccounts(unanswered: Map<String, Map<String, Long>>): Set<String> =
    unanswered.filterValues { it.size >= REPEATED_LINK_REQUESTS }.keys

/** Local notice history only. MDK remains the owner of link requests and their answers. */
internal class DeviceLinkRequestHistory(
    context: Context,
) {
    private val preferences = context.getSharedPreferences("device_link_request_history", Context.MODE_PRIVATE)

    fun read(): Map<String, Map<String, Long>> =
        preferences.all
            .mapValues { (_, stored) ->
                (stored as? Set<*>)
                    .orEmpty()
                    .mapNotNull { item ->
                        val value = item as? String ?: return@mapNotNull null
                        val seen = value.substringBefore(':').toLongOrNull() ?: return@mapNotNull null
                        val prompt = value.substringAfter(':', "")
                        prompt.takeIf(String::isNotEmpty)?.let { it to seen }
                    }.toMap()
            }.filterValues { it.isNotEmpty() }

    fun write(history: Map<String, Map<String, Long>>) {
        val edit = preferences.edit().clear()
        history.forEach { (account, requests) ->
            edit.putStringSet(account, requests.mapTo(mutableSetOf()) { (prompt, seen) -> "$seen:$prompt" })
        }
        edit.apply()
    }
}
