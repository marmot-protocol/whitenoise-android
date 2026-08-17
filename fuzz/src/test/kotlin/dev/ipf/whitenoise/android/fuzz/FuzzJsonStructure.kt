package dev.ipf.whitenoise.android.fuzz

/**
 * Linear pre-parse structural bounds for JSON-like text before handing it to org.json.
 * Does not allocate proportional to attacker input beyond a fixed-depth stack.
 */
object FuzzJsonStructure {
    fun withinBounds(text: String): Boolean = scan(text).withinBounds

    internal fun scan(text: String): ScanResult {
        if (text.isEmpty()) {
            return ScanResult(withinBounds = true, maxDepthSeen = 0, maxMembersSeen = 0)
        }
        val scanner = Scanner(text)
        return scanner.scanValue(depth = 1)
    }

    data class ScanResult(
        val withinBounds: Boolean,
        val maxDepthSeen: Int,
        val maxMembersSeen: Int,
    )

    private class Scanner(
        private val text: String,
    ) {
        private var index = 0
        var maxDepthSeen = 0
        var maxMembersSeen = 0

        fun scanValue(depth: Int): ScanResult {
            if (depth > FuzzBounds.MAX_DEPTH) {
                return overLimit(depth)
            }
            maxDepthSeen = maxOf(maxDepthSeen, depth)
            skipWhitespace()
            if (index >= text.length) {
                return ScanResult(withinBounds = false, maxDepthSeen, maxMembersSeen)
            }
            return when (text[index]) {
                '{' -> scanObject(depth)
                '[' -> scanArray(depth)
                '"' -> {
                    if (!skipString()) {
                        ScanResult(withinBounds = false, maxDepthSeen, maxMembersSeen)
                    } else {
                        ScanResult(withinBounds = true, maxDepthSeen, maxMembersSeen)
                    }
                }
                't', 'f', 'n', '-', in '0'..'9' -> {
                    if (!skipPrimitive()) {
                        ScanResult(withinBounds = false, maxDepthSeen, maxMembersSeen)
                    } else {
                        ScanResult(withinBounds = true, maxDepthSeen, maxMembersSeen)
                    }
                }
                else -> ScanResult(withinBounds = false, maxDepthSeen, maxMembersSeen)
            }
        }

        private fun scanObject(depth: Int): ScanResult {
            index++
            skipWhitespace()
            if (index < text.length && text[index] == '}') {
                index++
                return ScanResult(withinBounds = true, maxDepthSeen, maxMembersSeen)
            }
            var members = 0
            while (index < text.length) {
                skipWhitespace()
                if (index >= text.length || text[index] != '"') {
                    return ScanResult(withinBounds = false, maxDepthSeen, maxMembersSeen)
                }
                if (!skipString()) {
                    return ScanResult(withinBounds = false, maxDepthSeen, maxMembersSeen)
                }
                skipWhitespace()
                if (index >= text.length || text[index] != ':') {
                    return ScanResult(withinBounds = false, maxDepthSeen, maxMembersSeen)
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
                    return ScanResult(withinBounds = false, maxDepthSeen, maxMembersSeen)
                }
                when (text[index]) {
                    ',' -> index++
                    '}' -> {
                        index++
                        return ScanResult(withinBounds = true, maxDepthSeen, maxMembersSeen)
                    }
                    else -> return ScanResult(withinBounds = false, maxDepthSeen, maxMembersSeen)
                }
            }
            return ScanResult(withinBounds = false, maxDepthSeen, maxMembersSeen)
        }

        private fun scanArray(depth: Int): ScanResult {
            index++
            skipWhitespace()
            if (index < text.length && text[index] == ']') {
                index++
                return ScanResult(withinBounds = true, maxDepthSeen, maxMembersSeen)
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
                    return ScanResult(withinBounds = false, maxDepthSeen, maxMembersSeen)
                }
                when (text[index]) {
                    ',' -> index++
                    ']' -> {
                        index++
                        return ScanResult(withinBounds = true, maxDepthSeen, maxMembersSeen)
                    }
                    else -> return ScanResult(withinBounds = false, maxDepthSeen, maxMembersSeen)
                }
            }
            return ScanResult(withinBounds = false, maxDepthSeen, maxMembersSeen)
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

        private fun overLimit(depth: Int): ScanResult {
            maxDepthSeen = maxOf(maxDepthSeen, depth)
            return ScanResult(withinBounds = false, maxDepthSeen, maxMembersSeen)
        }
    }
}
