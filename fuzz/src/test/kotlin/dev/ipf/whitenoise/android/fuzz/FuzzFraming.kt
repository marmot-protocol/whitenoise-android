package dev.ipf.whitenoise.android.fuzz

import com.code_intelligence.jazzer.api.FuzzedDataProvider

/** Result of [consumeFramedString]; [consumedAllRemaining] is true for legacy seed payloads. */
data class FramedString(
    val value: String,
    val consumedAllRemaining: Boolean,
)

/**
 * Length-prefixed string field for multi-property fuzz inputs.
 *
 * - `0x00`: empty
 * - `0x01..0xFE`: next N bytes (capped by remaining input and [maxBytes])
 * - `0xFF`: all remaining bytes (explicit direct payload)
 *
 * Legacy checked-in seeds omit an explicit `0xFF` prefix: when the length byte is
 * greater than the bytes still available after it, the tag byte is treated as the
 * first payload byte (for example JSON starting with `{`).
 */
fun FuzzedDataProvider.consumeFramedString(maxBytes: Int = FuzzBounds.MAX_STRING_BYTES): FramedString {
    if (remainingBytes() == 0) {
        return FramedString("", consumedAllRemaining = false)
    }
    val tag = consumeByte()
    val available = remainingBytes()
    return when (val tagValue = tag.toInt() and 0xFF) {
        0 -> FramedString("", consumedAllRemaining = false)
        0xFF -> {
            val bytes = consumeRemainingAsBytes().bounded(maxBytes)
            FramedString(bytes.decodeToString(throwOnInvalidSequence = false), consumedAllRemaining = true)
        }
        else ->
            if (tagValue > available) {
                val bytes = byteArrayOf(tag) + consumeRemainingAsBytes()
                val bounded = bytes.bounded(maxBytes)
                FramedString(
                    value = bounded.decodeToString(throwOnInvalidSequence = false),
                    consumedAllRemaining = true,
                )
            } else {
                val len = minOf(tagValue, available, maxBytes)
                val bytes = consumeBytes(len)
                FramedString(
                    value = bytes.decodeToString(throwOnInvalidSequence = false),
                    consumedAllRemaining = remainingBytes() == 0,
                )
            }
    }
}

private fun ByteArray.bounded(maxBytes: Int): ByteArray =
    if (size <= maxBytes) {
        this
    } else {
        copyOf(maxBytes)
    }
