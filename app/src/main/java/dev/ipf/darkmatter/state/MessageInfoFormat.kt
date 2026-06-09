package dev.ipf.darkmatter.state

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/*
 * Pure formatters for the message-info sheet. Extracted from the Compose
 * surface so the boundary behavior (epoch overflow, hex bounds, status
 * mapping) can be unit-tested directly without an Android runtime.
 *
 * See firstUnreadReceivedIndex for the testability pattern used here.
 */

/**
 * Render a record's `recordedAt` (seconds since epoch, as stored by the FFI)
 * as an absolute, zone-qualified long timestamp — e.g. with `ZoneId.of("UTC")`
 * and `Locale.US`, epoch `1780787662` renders as
 * `"June 6, 2026, 11:14:22 PM Coordinated Universal Time"` (the exact JDK
 * locale string drifts between releases — see the test for stable
 * substrings). The zone is passed in so tests can pin a value; call sites
 * supply `ZoneId.systemDefault()`.
 *
 * Returns an empty string for:
 *  - `recordedAtSeconds == 0` — the FFI's "unset" sentinel.
 *  - `recordedAtSeconds` past `Instant.MAX.epochSecond` — would either wrap on
 *    `toLong()` or throw `DateTimeException` from `Instant.ofEpochSecond`.
 *    Won't happen for plausible timestamps (`Instant.MAX` is ~1 billion years
 *    from now), but pinning the boundary stops the cast from being a footgun.
 *
 * Callers should fall back to "—" or hide the row when this returns empty.
 */
fun formatExactTimestamp(
    recordedAtSeconds: ULong,
    zone: ZoneId,
    locale: Locale,
): String {
    if (recordedAtSeconds == 0uL) return ""
    if (recordedAtSeconds > Instant.MAX.epochSecond.toULong()) return ""
    val instant = Instant.ofEpochSecond(recordedAtSeconds.toLong())
    val formatter =
        DateTimeFormatter
            .ofLocalizedDateTime(FormatStyle.LONG, FormatStyle.LONG)
            .withLocale(locale)
            .withZone(zone)
    return formatter.format(instant)
}

/**
 * Absolute difference between two `ULong` seconds, expressed as a `ULong`
 * (never goes negative). Used to compare a record's claimed `recordedAt`
 * against the locally observed `receivedAt`.
 */
fun absDelta(
    a: ULong,
    b: ULong,
): ULong = if (a > b) a - b else b - a

/** Seconds of clock skew below which sender's claimed and locally observed
 *  timestamps are considered "the same". 5s absorbs typical NTP drift and
 *  network latency without being lax enough to mask a meaningful disagreement. */
const val ORIGINAL_TIMESTAMP_SKEW_TOLERANCE_SECONDS: ULong = 5uL

/**
 * Whether the "Original timestamp" row should be shown alongside the local
 * "Received" timestamp on an incoming message. True only when:
 *  - both timestamps are populated (non-sentinel), and
 *  - the sender's claimed `recordedAt` diverges from the locally observed
 *    `receivedAt` by more than [thresholdSeconds] (default 5s).
 *
 * The "mine" guard belongs at the call site — for own messages the two
 * fields collapse to the same value and the row is redundant regardless.
 */
fun shouldShowOriginalTimestamp(
    recordedAtSeconds: ULong,
    receivedAtSeconds: ULong,
    thresholdSeconds: ULong = ORIGINAL_TIMESTAMP_SKEW_TOLERANCE_SECONDS,
): Boolean {
    if (recordedAtSeconds == 0uL || receivedAtSeconds == 0uL) return false
    return absDelta(recordedAtSeconds, receivedAtSeconds) > thresholdSeconds
}

/**
 * Truncate a hex string to the first/last few characters for display, joined
 * with an ellipsis. Used for both pubkeys and message ids in the info sheet.
 * Returns the input unchanged when it's already short enough (≤ `head+tail`).
 * Defensive: blank input yields blank output rather than throwing.
 */
fun shortHex(
    hex: String,
    head: Int = 8,
    tail: Int = 4,
): String {
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

fun labelFor(
    status: MessageStatus,
    labels: MessageStatusLabels,
): String =
    when (status) {
        MessageStatus.Pending -> labels.pending
        MessageStatus.Sent -> labels.sent
        MessageStatus.Received -> labels.received
        MessageStatus.Failed -> labels.failed
        MessageStatus.Streaming -> labels.streaming
    }
