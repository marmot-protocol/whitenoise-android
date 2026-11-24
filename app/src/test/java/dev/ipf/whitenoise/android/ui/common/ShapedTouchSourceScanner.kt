package dev.ipf.whitenoise.android.ui.common

/*
 * The source scanner behind ShapedTouchFeedbackCoverageTest. It reads Kotlin as text: comments and string
 * contents are blanked first (keeping offsets and line numbers), then calls are matched by balancing
 * brackets. It is deliberately narrow — five patterns that draw a rectangular highlight around a round
 * or rounded control — and reports each as "<kind> @<line>".
 */

internal const val SHAPED_SURFACE = "click modifier inside a shaped Surface's modifier"
internal const val CLICK_BEFORE_CLIP = "click modifier before a round clip"
internal const val ROUND_CHILD = "clickable Box around a round or rounded child"
internal const val ROUND_COMPONENT = "click modifier passed to a round component"
internal const val STRETCHED_ICON_BUTTON = "IconButton sized to a box that may not be square"

private val clickModifier =
    Regex("""\.(clickable|combinedClickable|toggleable|selectable|triStateToggleable)\s*([({])""")
private val noIndication = Regex("""indication\s*=\s*null""")
private val chainedCall = Regex("""\s*\.\s*(\w+)\s*([({])""")
private val trailingLambda = Regex("""[ \t]*\{""")
private val namedArgument = Regex("""^\s*(\w+)\s*=(?!=)""")
private val roundChild =
    Regex("""CircleShape|PillShape|RoundedCornerShape|shapes\.\w+|(?<![\w.])(Avatar|ProfileAvatar|GroupAvatar)\s*\(""")
private val roundComponentCall = Regex("""(?<![\w.])(\w*Swatch|Avatar|ProfileAvatar|GroupAvatar)\s*\(""")
private val twoArgumentSize = Regex("""\.size\(\s*[^,()]+,\s*[^,()]+\)""")
private val declaration = Regex("""fun\s+$""")

/** How far before a round component's name the scanner looks for `fun`, to skip its own declaration. */
private const val DECLARATION_LOOKBEHIND = 12

/** Every shape-rule finding in [source], as "<kind> @<line>". */
internal fun shapedTouchFindings(source: String): List<String> {
    val code = blankCommentsAndStrings(source)
    return (
        code.clicksBeforeClip() + code.clicksInShapedSurfaces() + code.clickableBoxesAroundRoundChildren() +
            code.clicksOnRoundComponents() + code.stretchedIconButtons()
    ).sortedBy { it.second }.map { (kind, offset) -> "$kind @${code.lineAt(offset)}" }
}

/** Rule 2: a click modifier with its own highlight, followed in the same chain by a clip that is not a rectangle. */
private fun String.clicksBeforeClip(): List<Pair<String, Int>> =
    clickModifier
        .findAll(this)
        .filter { match ->
            val open = match.groups[2]!!.range.first
            val close = closingBracket(open)
            !noIndication.containsMatchIn(substring(open + 1, close)) &&
                chainAfter(close).any { (name, args) -> name == "clip" && "RectangleShape" !in args }
        }.map { CLICK_BEFORE_CLIP to it.range.first }
        .toList()

/** Rule 1: a click modifier in the `modifier` of a non-clickable Surface whose shape is not a rectangle. */
private fun String.clicksInShapedSurfaces(): List<Pair<String, Int>> =
    calls("Surface").flatMap { (start, args) ->
        val named = topLevelNamedArguments(args)
        val shape = named["shape"]
        val clickableOverload = listOf("onClick", "checked", "selected").any(named::containsKey)
        if (clickableOverload || shape == null || "RectangleShape" in shape) {
            emptyList()
        } else {
            args.undecoratedClicks().map { SHAPED_SURFACE to start + it }
        }
    }

/** Rule 3: a clickable Box whose content draws a round or rounded shape without a shaped highlight. */
private fun String.clickableBoxesAroundRoundChildren(): List<Pair<String, Int>> =
    calls("Box").flatMap { (start, args) ->
        val lambda = trailingLambda.matchAt(this, start + args.length + 1)
        val body = lambda?.let { substring(it.range.last + 1, closingBracket(it.range.last)) }
        if (body != null && roundChild.containsMatchIn(body)) {
            args.undecoratedClicks().map { ROUND_CHILD to start + it }
        } else {
            emptyList()
        }
    }

/** Rule 4: a click modifier passed to an avatar or swatch, which clips itself only inside. */
private fun String.clicksOnRoundComponents(): List<Pair<String, Int>> =
    roundComponentCall
        .findAll(this)
        .filterNot { match ->
            val name = match.range.first
            declaration.containsMatchIn(substring(maxOf(0, name - DECLARATION_LOOKBEHIND), name))
        }.flatMap { match ->
            val open = match.range.last
            substring(open + 1, closingBracket(open)).undecoratedClicks().map { ROUND_COMPONENT to open + 1 + it }
        }.toList()

/** Rule 5: an IconButton given a width, a height or two sizes, without the centred circle shape. */
private fun String.stretchedIconButtons(): List<Pair<String, Int>> =
    calls("IconButton")
        .filter { (_, args) ->
            "CenteredCircleShape" !in args &&
                (twoArgumentSize.containsMatchIn(args) || ".width(" in args || ".height(" in args)
        }.map { (start, _) -> STRETCHED_ICON_BUTTON to start }

/** Offsets, within this argument text, of click modifiers that draw their own highlight before any clip. */
private fun String.undecoratedClicks(): List<Int> =
    clickModifier
        .findAll(this)
        .filter { match ->
            val open = match.groups[2]!!.range.first
            !noIndication.containsMatchIn(substring(open + 1, closingBracket(open))) &&
                ".clip(" !in substring(0, match.range.first)
        }.map { it.range.first }
        .toList()

/** Each call of [name] as (offset of its argument text, argument text), not counting longer names. */
private fun String.calls(name: String): List<Pair<Int, String>> =
    Regex("""(?<![\w.])$name\s*\(""")
        .findAll(this)
        .map { match ->
            val open = match.range.last
            open + 1 to substring(open + 1, closingBracket(open))
        }.toList()

/** The calls chained after the call that closes at [close], as (name, argument text). */
private fun String.chainAfter(close: Int): List<Pair<String, String>> {
    val chain = mutableListOf<Pair<String, String>>()
    var next = close + 1
    while (true) {
        val call = chainedCall.matchAt(this, next) ?: return chain
        val open = call.groups[2]!!.range.first
        val end = closingBracket(open)
        chain += call.groupValues[1] to substring(open + 1, end)
        next = end + 1
        if (call.groupValues[2] == "(") {
            trailingLambda.matchAt(this, next)?.let { next = closingBracket(it.range.last) + 1 }
        }
    }
}

/** The top-level named arguments of a call's argument text, by name. */
private fun topLevelNamedArguments(args: String): Map<String, String> {
    val parts = mutableListOf<String>()
    var depth = 0
    var start = 0
    args.forEachIndexed { index, char ->
        when (char) {
            '(', '{', '[' -> depth++
            ')', '}', ']' -> depth--
            ',' -> if (depth == 0) parts += args.substring(start, index).also { start = index + 1 }
        }
    }
    parts += args.substring(start)
    return parts
        .mapNotNull { part -> namedArgument.find(part)?.let { it.groupValues[1] to part.substring(it.range.last + 1) } }
        .toMap()
}

/** The index of the bracket that closes the one at [open], or the end of the text when it never closes. */
private fun String.closingBracket(open: Int): Int {
    var depth = 0
    for (index in open until length) {
        when (this[index]) {
            '(', '{', '[' -> depth++
            ')', '}', ']' -> if (--depth == 0) return index
        }
    }
    return length
}

/** The 1-based line of [offset]. */
private fun String.lineAt(offset: Int): Int = 1 + (0 until offset.coerceAtMost(length)).count { this[it] == '\n' }

/** [source] with comments and the contents of string and character literals blanked, offsets unchanged. */
private fun blankCommentsAndStrings(source: String): String {
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
        source.startsWith("/*", index) -> comment(index, source.indexOrEnd("*/", index + 2, past = true))
        source.startsWith(RAW_QUOTE, index) ->
            source.indexOrEnd(RAW_QUOTE, index + RAW_QUOTE.length, past = true).let {
                LiteralSpan(index + RAW_QUOTE.length until it - RAW_QUOTE.length, it)
            }
        source[index] == '"' || source[index] == '\'' ->
            quotedEnd(source, index).let { LiteralSpan(index + 1 until it, it + 1) }
        else -> null
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
