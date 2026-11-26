package dev.ipf.whitenoise.android.ui.common

private val componentCall = Regex("""\b([A-Z]\w*)\s*\(""")
private val componentDeclaration = Regex("""\b(fun|class|interface)\s*(?:$TYPE_PARAMETERS\s*)?$""")
private const val COMPONENT_DECLARATION_LOOKBEHIND = 256

/** One component call, including its trailing content lambda when present. */
internal data class FeedbackOwner(
    val name: String,
    val start: Int,
    val open: Int,
    val close: Int,
    val end: Int,
)

/** A function's parameters and body, used only for a component's explicit named source hand-off. */
internal data class FeedbackFunction(
    val name: String,
    val open: Int,
    val close: Int,
    val body: Int,
    val end: Int,
)

/** Excludes class and function declarations when identifying calls. */
internal fun String.isDeclaration(start: Int): Boolean {
    val before = substring(maxOf(0, start - COMPONENT_DECLARATION_LOOKBEHIND), start)
    return componentDeclaration.containsMatchIn(before)
}

/** Finds component calls while excluding declarations, so a whole function never acts as one control. */
internal fun String.feedbackOwners(): List<FeedbackOwner> =
    componentCall
        .findAll(this)
        .filterNot { isDeclaration(it.range.first) }
        .map { match ->
            val open = match.range.last
            val close = closingBracket(open)
            val next = skipFeedbackWhitespace(close + 1)
            val end = if (getOrNull(next) == '{') closingBracket(next) + 1 else close + 1
            FeedbackOwner(match.groupValues[1], match.groups[1]!!.range.first, open, close, end)
        }.toList()

/** Function ranges let an explicit hand-off inspect its callee without borrowing feedback from a sibling. */
internal fun String.feedbackFunctions(): List<FeedbackFunction> =
    enclosingFunction
        .findAll(this)
        .mapNotNull { match ->
            val open = match.range.last
            val close = closingBracket(open)
            val body = indexOfAny(charArrayOf('{', '='), close + 1)
            if (body < 0) {
                null
            } else {
                val start = skipFeedbackWhitespace(body + 1)
                val end = if (this[body] == '{') closingBracket(body) + 1 else start + initializerFrom(start).length
                FeedbackFunction(match.groupValues[1], open, close, start, end)
            }
        }.toList()

/** The first non-whitespace character after a call or before a function's expression body. */
internal fun String.skipFeedbackWhitespace(from: Int): Int {
    var index = from
    while (index < length && this[index].isWhitespace()) index++
    return index
}
