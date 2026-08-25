package dev.ipf.whitenoise.android.fuzz

/**
 * Linear pre-parse structural bounds for JSON-like text before handing it to org.json.
 * Does not allocate proportional to attacker input beyond a fixed-depth stack.
 *
 * [withinBounds] returns false only when input-size, depth, or collection-size limits are exceeded.
 * Malformed, lenient, or unrecognized input is treated as within bounds.
 */
object FuzzJsonStructure {
    fun withinBounds(text: String): Boolean = scan(text).withinBounds

    internal fun scan(text: String): ScanResult {
        if (text.isEmpty()) {
            return ScanResult(withinBounds = true, recognized = true, maxDepthSeen = 0, maxMembersSeen = 0)
        }
        val looseLimits = scanLooseLimits(text)
        if (!looseLimits.withinBounds) {
            return ScanResult(
                withinBounds = false,
                recognized = false,
                maxDepthSeen = looseLimits.maxDepthSeen,
                maxMembersSeen = looseLimits.maxMembersSeen,
            )
        }
        val scanner = Scanner(text)
        val strict = scanner.scanValue(depth = 1)
        return strict.copy(
            maxDepthSeen = maxOf(strict.maxDepthSeen, looseLimits.maxDepthSeen),
            maxMembersSeen = maxOf(strict.maxMembersSeen, looseLimits.maxMembersSeen),
        )
    }

    data class ScanResult(
        val withinBounds: Boolean,
        val recognized: Boolean,
        val maxDepthSeen: Int,
        val maxMembersSeen: Int,
    )

    private data class LooseLimitResult(
        val withinBounds: Boolean,
        val maxDepthSeen: Int,
        val maxMembersSeen: Int,
    )

    /**
     * Keeps lenient or malformed input eligible while still enforcing resource limits
     * across the complete input, even after the strict scanner stops recognizing it.
     */
    private fun scanLooseLimits(text: String): LooseLimitResult {
        if (utf8LengthExceedsLimit(text, FuzzBounds.MAX_STRING_BYTES)) {
            return LooseLimitResult(withinBounds = false, maxDepthSeen = 0, maxMembersSeen = 0)
        }

        val membersByDepth = IntArray(FuzzBounds.MAX_DEPTH + 1)
        var depth = 0
        var maxDepthSeen = 0
        var maxMembersSeen = 0
        var quote: Char? = null
        var escaped = false

        for (char in text) {
            val activeQuote = quote
            if (activeQuote != null) {
                if (escaped) {
                    escaped = false
                } else if (char == '\\') {
                    escaped = true
                } else if (char == activeQuote) {
                    quote = null
                }
                continue
            }

            when (char) {
                '\'', '"' -> quote = char
                '{', '[' -> {
                    depth++
                    maxDepthSeen = maxOf(maxDepthSeen, depth)
                    if (depth > FuzzBounds.MAX_DEPTH) {
                        return LooseLimitResult(false, maxDepthSeen, maxMembersSeen)
                    }
                    membersByDepth[depth] = 1
                    maxMembersSeen = maxOf(maxMembersSeen, 1)
                }
                '}', ']' -> {
                    if (depth > 0) {
                        membersByDepth[depth] = 0
                        depth--
                    }
                }
                ',', ';' -> {
                    if (depth > 0) {
                        membersByDepth[depth]++
                        maxMembersSeen = maxOf(maxMembersSeen, membersByDepth[depth])
                        if (membersByDepth[depth] > FuzzBounds.MAX_COLLECTION_ELEMENTS) {
                            return LooseLimitResult(false, maxDepthSeen, maxMembersSeen)
                        }
                    }
                }
            }
        }
        return LooseLimitResult(true, maxDepthSeen, maxMembersSeen)
    }

    private fun utf8LengthExceedsLimit(
        text: String,
        limit: Int,
    ): Boolean {
        var bytes = 0
        var index = 0
        while (index < text.length) {
            val char = text[index]
            bytes +=
                when {
                    char.code <= 0x7F -> 1
                    char.code <= 0x7FF -> 2
                    char.isHighSurrogate() && index + 1 < text.length && text[index + 1].isLowSurrogate() -> {
                        index++
                        4
                    }
                    else -> 3
                }
            if (bytes > limit) {
                return true
            }
            index++
        }
        return false
    }

    private class Scanner(
        private val text: String,
    ) {
        private var index = 0
        var maxDepthSeen = 0
        var maxMembersSeen = 0

        fun scanValue(depth: Int): ScanResult {
            skipWhitespace()
            if (index >= text.length) {
                return withinBoundsUnrecognized(depth)
            }
            val startsContainer = text[index] == '{' || text[index] == '['
            if (startsContainer && depth > FuzzBounds.MAX_DEPTH) {
                return overLimit(depth)
            }
            maxDepthSeen = maxOf(maxDepthSeen, depth.coerceAtMost(FuzzBounds.MAX_DEPTH))
            return when (text[index]) {
                '{' -> scanObject(depth)
                '[' -> scanArray(depth)
                '"' -> {
                    if (!skipString()) {
                        withinBoundsUnrecognized(depth)
                    } else {
                        withinBoundsRecognized(depth)
                    }
                }
                't', 'f', 'n', '-', in '0'..'9' -> {
                    if (!skipPrimitive()) {
                        withinBoundsUnrecognized(depth)
                    } else {
                        withinBoundsRecognized(depth)
                    }
                }
                else -> withinBoundsUnrecognized(depth)
            }
        }

        private fun scanObject(depth: Int): ScanResult {
            index++
            skipWhitespace()
            if (index < text.length && text[index] == '}') {
                index++
                return withinBoundsRecognized(depth)
            }
            var members = 0
            while (index < text.length) {
                skipWhitespace()
                if (index >= text.length || text[index] != '"') {
                    return withinBoundsUnrecognized(depth)
                }
                if (!skipString()) {
                    return withinBoundsUnrecognized(depth)
                }
                skipWhitespace()
                if (index >= text.length || text[index] != ':') {
                    return withinBoundsUnrecognized(depth)
                }
                index++
                val valueResult = scanValue(depth + 1)
                if (!valueResult.withinBounds) {
                    return valueResult
                }
                members++
                maxMembersSeen = maxOf(maxMembersSeen, members)
                if (members > FuzzBounds.MAX_COLLECTION_ELEMENTS) {
                    return overLimit(depth)
                }
                skipWhitespace()
                if (index >= text.length) {
                    return withinBoundsUnrecognized(depth)
                }
                when (text[index]) {
                    ',' -> index++
                    '}' -> {
                        index++
                        return withinBoundsRecognized(depth)
                    }
                    else -> return withinBoundsUnrecognized(depth)
                }
            }
            return withinBoundsUnrecognized(depth)
        }

        private fun scanArray(depth: Int): ScanResult {
            index++
            skipWhitespace()
            if (index < text.length && text[index] == ']') {
                index++
                return withinBoundsRecognized(depth)
            }
            var elements = 0
            while (index < text.length) {
                val elementResult = scanValue(depth + 1)
                if (!elementResult.withinBounds) {
                    return elementResult
                }
                elements++
                maxMembersSeen = maxOf(maxMembersSeen, elements)
                if (elements > FuzzBounds.MAX_COLLECTION_ELEMENTS) {
                    return overLimit(depth)
                }
                skipWhitespace()
                if (index >= text.length) {
                    return withinBoundsUnrecognized(depth)
                }
                when (text[index]) {
                    ',' -> index++
                    ']' -> {
                        index++
                        return withinBoundsRecognized(depth)
                    }
                    else -> return withinBoundsUnrecognized(depth)
                }
            }
            return withinBoundsUnrecognized(depth)
        }

        private fun skipString(): Boolean {
            if (index >= text.length || text[index] != '"') {
                return false
            }
            index++
            while (index < text.length) {
                when (text[index]) {
                    '"' -> {
                        index++
                        return true
                    }
                    '\\' -> {
                        index++
                        if (index >= text.length) {
                            return false
                        }
                        index++
                    }
                    else -> index++
                }
            }
            return false
        }

        private fun skipPrimitive(): Boolean {
            if (index >= text.length) {
                return false
            }
            when (text[index]) {
                't' -> {
                    if (!text.regionMatches(index, "true", 0, 4)) return false
                    index += 4
                }
                'f' -> {
                    if (!text.regionMatches(index, "false", 0, 5)) return false
                    index += 5
                }
                'n' -> {
                    if (!text.regionMatches(index, "null", 0, 4)) return false
                    index += 4
                }
                else -> {
                    if (text[index] == '-') {
                        index++
                        if (index >= text.length) return false
                    }
                    if (!text[index].isDigit()) {
                        return false
                    }
                    while (index < text.length &&
                        (text[index].isDigit() || text[index] == '.' || text[index] == 'e' || text[index] == 'E' || text[index] == '+' || text[index] == '-')
                    ) {
                        index++
                    }
                }
            }
            return true
        }

        private fun skipWhitespace() {
            while (index < text.length && text[index].isWhitespace()) {
                index++
            }
        }

        private fun withinBoundsRecognized(depth: Int): ScanResult {
            maxDepthSeen = maxOf(maxDepthSeen, depth)
            return ScanResult(withinBounds = true, recognized = true, maxDepthSeen, maxMembersSeen)
        }

        private fun withinBoundsUnrecognized(depth: Int): ScanResult {
            maxDepthSeen = maxOf(maxDepthSeen, depth)
            return ScanResult(withinBounds = true, recognized = false, maxDepthSeen, maxMembersSeen)
        }

        private fun overLimit(depth: Int): ScanResult {
            maxDepthSeen = maxOf(maxDepthSeen, depth)
            return ScanResult(withinBounds = false, recognized = true, maxDepthSeen, maxMembersSeen)
        }
    }
}
