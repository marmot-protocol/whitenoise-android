package dev.ipf.whitenoise.android.ui.common

private val namedArgument = Regex("""^\s*(\w+)\s*=(?!=)""")
private val typeOperator = Regex("""\b(?:as\??|is)\s+[\w.]+$""")
private val ifCondition = Regex("""^\s*if\s*\(""")
private val elseBlock = Regex("""\s*else\s*\{""")
private val conditionalModifier = Regex("""^\s*(?:if|when)\b""")
private val sourcePresentCondition = Regex("""^\s*if\s*\(\s*(\w+)\s*!=\s*null\s*\)""")
private val emptyModifierElse = Regex("""\belse\s+Modifier\s*$""")
private val modifierCall = Regex("""\.\s*(\w+)\s*([({])""")

/** One argument's expression and offset within the original argument list. */
internal data class TouchArgument(
    val offset: Int,
    val text: String,
) {
    /** Removes leading and trailing whitespace and advances the offset past the leading whitespace. */
    fun valueAfter(prefix: Int): TouchArgument {
        val value = text.substring(prefix)
        return TouchArgument(offset + prefix + value.indexOfFirst { !it.isWhitespace() }.coerceAtLeast(0), value.trim())
    }
}

/** Finds a named modifier or the modifier at its position in the call's signature. */
internal fun modifierArgument(
    args: String,
    position: Int? = 0,
): TouchArgument? {
    val parts = topLevelArgumentSpans(args)
    val namedModifier =
        parts.firstNotNullOfOrNull { part ->
            val name = namedArgument.find(part.text)
            name?.takeIf { it.groupValues[1] == "modifier" }?.let { part.valueAfter(it.range.last + 1) }
        }
    val positional = position?.let(parts::getOrNull)
    return namedModifier ?: positional?.takeUnless { namedArgument.containsMatchIn(it.text) }?.valueAfter(0)
}

/** Returns the source location of a named argument's value. */
internal fun namedArgumentValue(
    args: String,
    parameter: String,
): TouchArgument? =
    topLevelArgumentSpans(args).firstNotNullOfOrNull { part ->
        val name = namedArgument.find(part.text)
        name?.takeIf { it.groupValues[1] == parameter }?.let { part.valueAfter(it.range.last + 1) }
    }

/** The top-level arguments of a call's argument text, in order. */
internal fun topLevelArguments(args: String): List<String> = topLevelArgumentSpans(args).map(TouchArgument::text)

/** The top-level named arguments of a call's argument text, by name. */
internal fun topLevelNamedArguments(args: String): Map<String, String> =
    topLevelArguments(args)
        .mapNotNull { part -> namedArgument.find(part)?.let { it.groupValues[1] to part.substring(it.range.last + 1) } }
        .toMap()

/** Splits commas outside Kotlin brackets and explicit type arguments, retaining source offsets. */
private fun topLevelArgumentSpans(args: String): List<TouchArgument> {
    val parts = mutableListOf<TouchArgument>()
    var start = 0
    var index = 0
    while (index < args.length) {
        when (args[index]) {
            '(', '{', '[' -> index = args.closingBracket(index)
            '<' -> index = args.typeArgumentEnd(index) ?: index
            ',' -> {
                parts += TouchArgument(start, args.substring(start, index))
                start = index + 1
            }
        }
        index++
    }
    parts += TouchArgument(start, args.substring(start))
    return parts.filter { it.text.isNotBlank() }
}

/** Recognizes type arguments in calls and casts without swallowing commas between comparisons. */
private fun String.typeArgumentEnd(open: Int): Int? {
    val previous = getOrNull(open - 1)
    if (previous == null || (!previous.isLetterOrDigit() && previous != '_')) return null
    return closingTypeArgument(open)?.takeIf { close ->
        val next = getOrNull(indexOfNonWhitespace(close + 1))
        next == '(' || next == '{' || typeOperator.containsMatchIn(substring(0, open))
    }
}

/** Finds the closing angle bracket when the intervening characters can form a type argument list. */
private fun String.closingTypeArgument(open: Int): Int? {
    var depth = 1
    for (index in open + 1 until length) {
        val char = this[index]
        when {
            char == '<' -> depth++
            char == '>' -> if (this[index - 1] != '-' && --depth == 0) return index
            !char.isLetterOrDigit() && !char.isWhitespace() && char !in "._?*,()-:" -> break
        }
    }
    return null
}

/** One applied modifier call and the offset of its arguments within the expression. */
internal data class ModifierLink(
    val name: String,
    val args: String,
    val argumentOffset: Int,
    val conditional: Boolean,
    val guardedSource: String?,
) {
    /** Checks whether this link clips to a nonrectangular shape, optionally requiring an exact shape. */
    fun clipsToShape(matching: String? = null): Boolean {
        val shape = args.trim()
        return name == "clip" && !conditional && "RectangleShape" !in shape && (matching == null || shape == matching)
    }
}

/** Visits top-level modifier calls in order, including appended chains in `.then(…)` arguments. */
internal fun String.modifierLinks(
    offset: Int = 0,
    conditional: Boolean = false,
    guardedSource: String? = null,
): Sequence<ModifierLink> =
    sequence {
        var index = 0
        while (index < length) {
            val match = modifierCall.matchAt(this@modifierLinks, index)
            if (match != null) {
                val open = match.range.last
                val close = closingBracket(open)
                val argumentOffset = open + 1
                val args = substring(argumentOffset, close)
                val name = match.groupValues[1]
                if (name == "then" && this@modifierLinks[open] == '(') {
                    val optional = conditionalModifier.containsMatchIn(args)
                    val guard = if (optional) args.sourcePresentGuard().takeUnless { conditional } else guardedSource
                    val branches = args.bracedModifierBranches().ifEmpty { listOf(TouchArgument(0, args)) }
                    for (branch in branches) {
                        val branchOffset = offset + argumentOffset + branch.offset
                        yieldAll(branch.text.modifierLinks(branchOffset, conditional || optional, guard))
                    }
                } else {
                    yield(ModifierLink(name, args, offset + argumentOffset, conditional, guardedSource))
                }
                index = close
            } else if (this@modifierLinks[index] in "({[") {
                index = closingBracket(index)
            }
            index++
        }
    }

/** Unwraps the expression blocks in a modifier built by a braced if/else. */
private fun String.bracedModifierBranches(): List<TouchArgument> {
    val condition = ifCondition.find(this) ?: return emptyList()
    val body = indexOfNonWhitespace(closingBracket(condition.range.last) + 1)
    return if (getOrNull(body) == '{') {
        val end = closingBracket(body)
        val alternative = elseBlock.matchAt(this, end + 1)?.range?.last
        listOfNotNull(modifierBranch(body), alternative?.let(::modifierBranch))
    } else {
        emptyList()
    }
}

/** Reads one branch without its outer braces and retains the original expression offset. */
private fun String.modifierBranch(open: Int): TouchArgument {
    val start = open + 1
    return TouchArgument(start, substring(start, closingBracket(open)))
}

/** Recognizes the simple nullable-source hand-off whose else branch appends no modifier. */
private fun String.sourcePresentGuard(): String? =
    sourcePresentCondition
        .find(this)
        ?.groupValues
        ?.get(1)
        ?.takeIf { emptyModifierElse.containsMatchIn(this) }

/** Finds the next non-whitespace character without changing offsets. */
private fun String.indexOfNonWhitespace(from: Int): Int {
    var index = from
    while (index < length && this[index].isWhitespace()) index++
    return index
}

/** The index of the bracket that closes the one at [open], or the end of the text when it never closes. */
internal fun String.closingBracket(open: Int): Int {
    var depth = 0
    for (index in open until length) {
        when (this[index]) {
            '(', '{', '[' -> depth++
            ')', '}', ']' -> if (--depth == 0) return index
        }
    }
    return length
}

/** [source] with comments and the contents of string and character literals blanked, offsets unchanged. */
internal fun blankCommentsAndStrings(source: String): String {
    val out = StringBuilder(source)
    var index = 0
    while (index < source.length) {
        val span = literalSpan(source, index)
        if (span == null) {
            index++
        } else {
            for (position in span.blank) if (source[position] != '\n') out[position] = ' '
            index = span.next
        }
    }
    return out.toString()
}

/** A comment or literal: the range to blank, and the index where scanning resumes after it. */
private class LiteralSpan(
    val blank: IntRange,
    val next: Int,
)

/** The comment or literal that starts at [index] in [source], or null when none does. */
private fun literalSpan(
    source: String,
    index: Int,
): LiteralSpan? =
    when {
        source.startsWith("//", index) -> comment(index, source.indexOrEnd("\n", index, past = false))
        source.startsWith("/*", index) -> comment(index, source.blockCommentEnd(index))
        source.startsWith(RAW_QUOTE, index) ->
            source.indexOrEnd(RAW_QUOTE, index + RAW_QUOTE.length, past = true).let {
                LiteralSpan(index + RAW_QUOTE.length until it - RAW_QUOTE.length, it)
            }
        source[index] == '"' || source[index] == '\'' ->
            quotedEnd(source, index).let { LiteralSpan(index + 1 until it, it + 1) }
        else -> null
    }

/** Kotlin block comments may contain other block comments. Returns the end of the outer comment. */
private fun String.blockCommentEnd(open: Int): Int {
    var depth = 1
    var index = open + 2
    while (index < length && depth > 0) {
        when {
            startsWith("/*", index) -> {
                depth++
                index += 2
            }
            startsWith("*/", index) -> {
                depth--
                index += 2
            }
            else -> index++
        }
    }
    return index
}

/** A comment from [start] until [end], blanked whole. */
private fun comment(
    start: Int,
    end: Int,
) = LiteralSpan(start until end, end)

/** The index of [marker] from [from], past it when [past], or the end of the text when it is missing. */
private fun String.indexOrEnd(
    marker: String,
    from: Int,
    past: Boolean,
): Int {
    val found = indexOf(marker, from)
    return when {
        found < 0 -> length
        past -> found + marker.length
        else -> found
    }
}

/** The index of the quote that closes the one-line literal opened at [open], skipping escapes. */
private fun quotedEnd(
    source: String,
    open: Int,
): Int {
    var end = open + 1
    while (end < source.length && source[end] != source[open]) end += if (source[end] == '\\') 2 else 1
    return end.coerceAtMost(source.length)
}

private const val RAW_QUOTE = "\"\"\""
