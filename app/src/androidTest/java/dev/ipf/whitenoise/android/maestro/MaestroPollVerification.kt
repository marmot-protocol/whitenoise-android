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
    val question = maestroPollQuestion(postcondition)
    val labels =
        when (postcondition) {
            "poll-single-vote" -> setOf("Maestro Tea")
            "poll-change-vote" -> setOf("Maestro Coffee")
            "poll-multiple-vote", "poll-copy-multiple" -> setOf("Maestro Tea", "Maestro Coffee")
            else -> emptySet()
        }
    withTimeout(30_000L) {
        while (true) {
            val local = matchingMaestroPoll(native, owner, group, question)
            val remote = matchingMaestroPoll(native, peer, group, question)
            if (
                local != null &&
                remote != null
            ) {
                val tallyMatches = matchesMaestroTally(local, labels) && matchesMaestroTally(remote, labels)
                val selection =
                    local.options
                        .filter { it.id in local.localSelection }
                        .map { it.label }
                        .toSet()
                if (
                    tallyMatches && selection == labels && maestroPollCopyDeadlinePassed(local, remote, postcondition)
                ) {
                    verifyMaestroPollCopyDidNotSend(native, owner, peer, group, postcondition)
                    return@withTimeout
                }
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

/** Keep the Unicode seed's authored question independent of its expected clipboard payload. */
private fun maestroPollQuestion(postcondition: String): String =
    when (postcondition) {
        "poll-published" -> "Maestro published poll"
        "poll-copy-unicode" -> "Maestro ¿Qué comemos? 🍜"
        else -> "Maestro fixture poll"
    }

/** Closed UI is asserted by Maestro; native deadline metadata must agree on both accounts. */
private fun maestroPollCopyDeadlinePassed(
    local: PollProjectionFfi,
    peer: PollProjectionFfi,
    postcondition: String,
): Boolean {
    if (postcondition != "poll-copy-closed") return true
    val nowSeconds = (System.currentTimeMillis() / 1_000L).toULong()
    return listOf(local, peer).all { poll -> poll.endsAt?.let { it < nowSeconds } == true }
}

/** Clipboard actions may not publish a chat on either real native account. */
private fun verifyMaestroPollCopyDidNotSend(
    native: Marmot,
    owner: String,
    peer: String,
    group: String,
    postcondition: String,
) {
    if (!postcondition.startsWith("poll-copy-")) return
    for (account in listOf(owner, peer)) {
        val chats = readMaestroMessages(native, account, group).filter { it.kind == 9uL }
        check(chats.size == 1 && chats.single().plaintext == "Generated fixture message") {
            "Poll Copy unexpectedly published a chat message"
        }
    }
}
