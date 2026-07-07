package dev.ipf.whitenoise.android

internal fun String.functionBody(functionName: String): String {
    val start =
        Regex("""\bfun\s+${Regex.escape(functionName)}\s*\(""")
            .find(this)
            ?.range
            ?.first
            ?: error("Missing function $functionName")
    val braceStart = indexOf('{', start)
    require(braceStart >= 0) { "Missing body for $functionName" }
    return kotlinBlockFrom(braceStart, "function $functionName")
}

internal fun String.kotlinBlockFrom(
    openBrace: Int,
    description: String,
): String {
    require(getOrNull(openBrace) == '{') { "Missing opening brace for $description" }

    var depth = 0
    var index = openBrace
    var inLineComment = false
    var blockCommentDepth = 0
    var inString = false
    var inTripleString = false
    var inChar = false

    while (index < length) {
        val current = this[index]
        val next = getOrNull(index + 1)
        when {
            inLineComment -> {
                if (current == '\n' || current == '\r') inLineComment = false
                index += 1
            }
            blockCommentDepth > 0 -> {
                when {
                    current == '/' && next == '*' -> {
                        blockCommentDepth += 1
                        index += 2
                    }
                    current == '*' && next == '/' -> {
                        blockCommentDepth -= 1
                        index += 2
                    }
                    else -> index += 1
                }
            }
            inTripleString -> {
                if (startsWith("\"\"\"", index)) {
                    inTripleString = false
                    index += 3
                } else {
                    index += 1
                }
            }
            inString -> {
                when (current) {
                    '\\' -> index += 2
                    '"' -> {
                        inString = false
                        index += 1
                    }
                    else -> index += 1
                }
            }
            inChar -> {
                when (current) {
                    '\\' -> index += 2
                    '\'' -> {
                        inChar = false
                        index += 1
                    }
                    else -> index += 1
                }
            }
            else -> {
                when {
                    current == '/' && next == '/' -> {
                        inLineComment = true
                        index += 2
                    }
                    current == '/' && next == '*' -> {
                        blockCommentDepth = 1
                        index += 2
                    }
                    startsWith("\"\"\"", index) -> {
                        inTripleString = true
                        index += 3
                    }
                    current == '"' -> {
                        inString = true
                        index += 1
                    }
                    current == '\'' -> {
                        inChar = true
                        index += 1
                    }
                    current == '{' -> {
                        depth += 1
                        index += 1
                    }
                    current == '}' -> {
                        depth -= 1
                        index += 1
                        if (depth == 0) return substring(openBrace, index)
                    }
                    else -> index += 1
                }
            }
        }
    }

    error("Unterminated block for $description")
}
