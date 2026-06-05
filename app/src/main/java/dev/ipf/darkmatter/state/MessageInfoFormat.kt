package dev.ipf.darkmatter.state

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/**
 * Pure formatters for the message-info sheet. Extracted from the Compose
 * surface so the boundary behavior (epoch overflow, hex bounds, status
 * mapping) can be unit-tested directly without an Android runtime.
 *
 * @see firstUnreadReceivedIndex for the testability pattern used here.
 */

/**
 * Render a record's `recordedAt` (seconds since epoch, as stored by the FFI)
 * as an absolute, locale-aware long timestamp — e.g. `"June 4, 2026 at
 * 3:14:22 PM GMT-04:00"`. The zone is passed in so tests can pin a value;
 * the call site supplies `ZoneId.systemDefault()`.
 *
 * Returns an empty string when `recordedAtSeconds == 0` (the FFI's "unset"
 * sentinel) — callers should fall back to "—" or hide the row entirely
 * rather than display a 1970 timestamp.
 */
fun formatExactTimestamp(
    recordedAtSeconds: ULong,
    zone: ZoneId,
    locale: Locale,
): String {
    if (recordedAtSeconds == 0uL) return ""
    val instant = Instant.ofEpochSecond(recordedAtSeconds.toLong())
    val formatter = DateTimeFormatter
        .ofLocalizedDateTime(FormatStyle.LONG, FormatStyle.LONG)
        .withLocale(locale)
        .withZone(zone)
    return formatter.format(instant)
}

/**
 * Truncate a hex string to the first/last few characters for display, joined
 * with an ellipsis. Used for both pubkeys and message ids in the info sheet.
 * Returns the input unchanged when it's already short enough (≤ `head+tail`).
 * Defensive: blank input yields blank output rather than throwing.
 */
fun shortHex(hex: String, head: Int = 8, tail: Int = 4): String {
    if (hex.isBlank()) return ""
    if (hex.length <= head + tail) return hex
    return "${hex.take(head)}…${hex.takeLast(tail)}"
}

/**
 * A short, user-facing label for a message's local status. Strings come from
 * the caller (each is a `stringResource` lookup) so the function stays pure
 * — no Compose, no Android resources.
 */
data class MessageStatusLabels(
    val pending: String,
    val sent: String,
    val received: String,
    val failed: String,
    val streaming: String,
)

fun labelFor(status: MessageStatus, labels: MessageStatusLabels): String = when (status) {
    MessageStatus.Pending -> labels.pending
    MessageStatus.Sent -> labels.sent
    MessageStatus.Received -> labels.received
    MessageStatus.Failed -> labels.failed
    MessageStatus.Streaming -> labels.streaming
}
