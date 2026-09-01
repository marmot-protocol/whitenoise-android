package dev.ipf.whitenoise.android

internal fun String.functionBody(functionName: String): String {
    val start =
        Regex("""\bfun\s+${Regex.escape(functionName)}\s*\(""")
            .find(this)
            ?.range
            ?.first
            ?: error("Missing function $functionName")
    val parametersStart = indexOf('(', start)
    require(parametersStart >= 0) { "Missing parameters for $functionName" }
    val parameters =
        kotlinBlockFrom(
            openDelimiter = parametersStart,
            description = "parameters for $functionName",
            opening = '(',
            closing = ')',
        )
    val parametersEnd = parametersStart + parameters.length
    return kotlinBlockFrom(
        openDelimiter = parametersEnd,
        description = "function $functionName",
        searchForOpening = true,
    )
}

/** Returns the balanced argument list of a named Kotlin property's initializer call. */
internal fun String.propertyInitializerCall(propertyName: String): String {
    val declaration =
        Regex("""\bval\s+${Regex.escape(propertyName)}\b""")
            .find(this)
            ?.range
            ?: error("Missing property $propertyName")
    val assignment = indexOf('=', declaration.last + 1)
    require(assignment >= 0) { "Missing initializer for property $propertyName" }
    val callStart = indexOf('(', assignment + 1)
    require(callStart >= 0) { "Missing initializer call for property $propertyName" }
    return kotlinBlockFrom(
        openDelimiter = callStart,
        description = "initializer call for property $propertyName",
        opening = '(',
        closing = ')',
    )
}

internal fun String.kotlinBlockFrom(
    openDelimiter: Int,
    description: String,
    opening: Char = '{',
    closing: Char = '}',
    searchForOpening: Boolean = false,
): String {
    require(searchForOpening || getOrNull(openDelimiter) == opening) {
        "Missing opening delimiter for $description"
    }

    var depth = 0
    var index = openDelimiter
    var blockStart = if (searchForOpening) -1 else openDelimiter
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
                    current == opening -> {
                        if (depth == 0) blockStart = index
                        depth += 1
                        index += 1
                    }
                    current == closing && depth > 0 -> {
                        depth -= 1
                        index += 1
                        if (depth == 0) return substring(blockStart, index)
                    }
                    else -> index += 1
                }
            }
        }
    }

    require(blockStart >= 0) { "Missing opening delimiter for $description" }
    error("Unterminated delimiters for $description")
}
