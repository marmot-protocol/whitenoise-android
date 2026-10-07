package dev.ipf.whitenoise.android.maestro

import dev.ipf.marmotkit.Marmot
import dev.ipf.marmotkit.PollProjectionFfi
import dev.ipf.marmotkit.TimelineMessageQueryFfi
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout

/** Check native publication, local selection and peer tally rather than inferring a vote from UI text. */
internal suspend fun verifyMaestroPoll(
    native: Marmot,
    owner: String,
    peer: String,
    group: String,
    postcondition: String,
) {
    val question = if (postcondition == "poll-published") "Maestro published poll" else "Maestro fixture poll"
    val labels =
        when (postcondition) {
            "poll-single-vote" -> setOf("Maestro Tea")
            "poll-change-vote" -> setOf("Maestro Coffee")
            "poll-multiple-vote" -> setOf("Maestro Tea", "Maestro Coffee")
            else -> emptySet()
        }
    withTimeout(30_000L) {
        while (true) {
            val local = matchingMaestroPoll(native, owner, group, question)
            val remote = matchingMaestroPoll(native, peer, group, question)
            if (
                local != null &&
                remote != null &&
                matchesMaestroTally(local, labels) &&
                matchesMaestroTally(remote, labels)
            ) {
                val selection = local.options.filter { it.id in local.localSelection }.map { it.label }.toSet()
                if (selection == labels) return@withTimeout
            }
            delay(100L)
        }
    }
}

/** Reject duplicate publications; a missing peer projection remains pending until the bounded deadline. */
private fun matchingMaestroPoll(
    native: Marmot,
    account: String,
    group: String,
    question: String,
): PollProjectionFfi? {
    val polls =
        native
            .timelineMessages(account, TimelineMessageQueryFfi(group, null, null, null, null, null, 100u))
            .messages
            .mapNotNull { it.poll }
            .filter { it.question == question }
    check(polls.size <= 1) { "Duplicate poll publication" }
    return polls.singleOrNull()
}

/** One synthetic voter contributes one vote to each selected option and none to any other option. */
private fun matchesMaestroTally(
    poll: PollProjectionFfi,
    labels: Set<String>,
): Boolean =
    poll.participants == (if (labels.isEmpty()) 0uL else 1uL) &&
        labels.all { label -> poll.options.any { it.label == label } } &&
        poll.options.all { it.votes == (if (it.label in labels) 1uL else 0uL) }
