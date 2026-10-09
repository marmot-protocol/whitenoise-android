package dev.ipf.whitenoise.android.state

private const val END_PREFIX = "attachment_transfer end "
private const val BEGIN_PREFIX = "attachment_transfer begin "

/** The attempt label of a begin line, which is the last field and so ends at the line's end or a space. */
private fun String.attemptLabel() = substringAfter("attempt=").substringBefore(" ")

/** The outcome labels of the transfers that ended, in order, read from the ledger's diagnostics lines. */
internal fun List<String>.endOutcomes() = filter { it.startsWith(END_PREFIX) }.map { it.substringAfter("outcome=") }

/** The attempt labels of the transfers that began, in order, read from the ledger's diagnostics lines. */
internal fun List<String>.beginAttempts() = filter { it.startsWith(BEGIN_PREFIX) }.map { it.attemptLabel() }
