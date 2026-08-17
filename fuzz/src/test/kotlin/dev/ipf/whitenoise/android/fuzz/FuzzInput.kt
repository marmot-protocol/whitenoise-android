package dev.ipf.whitenoise.android.fuzz

import com.code_intelligence.jazzer.api.FuzzedDataProvider

fun FuzzedDataProvider.consumeBoundedString(maxBytes: Int = FuzzBounds.MAX_STRING_BYTES): String {
    val bytes = consumeBytes(maxBytes.coerceAtMost(FuzzBounds.MAX_STRING_BYTES))
    return bytes.decodeToString(throwOnInvalidSequence = false)
}

fun FuzzedDataProvider.consumeBoundedUtf8(maxBytes: Int = FuzzBounds.MAX_STRING_BYTES): String = consumeBoundedString(maxBytes)

fun FuzzedDataProvider.pickFrom(dictionary: List<String>): String = if (dictionary.isEmpty()) "" else dictionary[consumeInt(0, dictionary.size - 1)]

fun FuzzedDataProvider.consumeBoundedElementCount(): Int = consumeInt(0, FuzzBounds.MAX_COLLECTION_ELEMENTS)

fun FuzzedDataProvider.consumeBoundedDepth(): Int = consumeInt(1, FuzzBounds.MAX_DEPTH)

fun FuzzedDataProvider.consumeBoundedFrameCount(): Int = consumeInt(1, FuzzBounds.MAX_FRAMES)

/** Remaining fuzz bytes as UTF-8 text, bounded for parser-boundary replay and mutation. */
fun FuzzedDataProvider.consumeParserInput(maxBytes: Int = FuzzBounds.MAX_STRING_BYTES): String {
    val bytes = consumeRemainingAsBytes()
    if (bytes.isEmpty()) return ""
    val bounded = if (bytes.size <= maxBytes) bytes else bytes.copyOf(maxBytes)
    return bounded.decodeToString(throwOnInvalidSequence = false)
}
