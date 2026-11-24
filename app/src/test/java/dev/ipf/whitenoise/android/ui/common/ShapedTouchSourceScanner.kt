package dev.ipf.whitenoise.android.ui.common

/*
 * The source scanner behind ShapedTouchFeedbackCoverageTest. It reads Kotlin as text: comments and string
 * contents are blanked first (keeping offsets and line numbers), then calls are matched by balancing
 * brackets. It is deliberately narrow — the patterns that draw a rectangular or stretched highlight around a
 * round or rounded control — and reports each as "<kind> @<line>".
 */

internal const val SHAPED_SURFACE = "click modifier inside a shaped Surface's modifier"
internal const val CLICK_BEFORE_CLIP = "click modifier before a round clip"
internal const val ROUND_CHILD = "clickable Box around a round or rounded child"
internal const val ROUND_COMPONENT = "click modifier passed to a round component"
internal const val STRETCHED_ICON_BUTTON = "IconButton sized to a box that may not be square"
internal const val CLICK_OVER_SHAPED_FILL = "click modifier over a shaped background or border without a clip"
internal const val BARE_GLYPH = "click modifier on a bare Icon without a shaped clip"

/** The package whose icon buttons sit in slots narrower than they are tall. */
private const val COMPOSER_PACKAGE = "conversation/composer/"

private val clickModifier =
    Regex("""\.(clickable|combinedClickable|toggleable|selectable|triStateToggleable)\s*([({])""")
private val noIndication = Regex("""indication\s*=\s*null""")
private val chainedCall = Regex("""\s*\.\s*(\w+)\s*([({])""")
private val trailingLambda = Regex("""[ \t]*\{""")
private val namedArgument = Regex("""^\s*(\w+)\s*=(?!=)""")
private val roundChild =
    Regex("""CircleShape|PillShape|RoundedCornerShape|shapes\.\w+|(?<![\w.])(Avatar|ProfileAvatar|GroupAvatar)\s*\(""")
private val roundComponentCall = Regex("""(?<![\w.])(\w*Swatch|Avatar|ProfileAvatar|GroupAvatar)\s*\(""")
private val twoArgumentSize = Regex("""\.(size|requiredSize)\(\s*[^,()]+,\s*[^,()]+\)""")
private val oneSideSize = Regex("""\.(width|height|requiredWidth|requiredHeight)\(""")
private val shapedFill = Regex("""\.(background|border)\s*\(""")
private val shapeName = Regex("""CircleShape|PillShape|RoundedCornerShape|CutCornerShape|shapes\.\w+|\w+Shape\b""")
private val clipCall = Regex("""\.clip\s*\(""")
private val declaration = Regex("""fun\s+$""")
private val enclosingFunction = Regex("""\bfun\s+(?:[\w.<>]+\.)?(\w+)\s*\(""")

/** How far before a round component's name the scanner looks for `fun`, to skip its own declaration. */
private const val DECLARATION_LOOKBEHIND = 12

/**
 * Every shape-rule finding in [source], as "<kind> @<line>". [path] is the file's path below `ui/`, with `/`
 * separators, for the rules that depend on where a control lives.
 */
internal fun shapedTouchFindings(
    source: String,
    path: String = "",
): List<String> {
    val code = blankCommentsAndStrings(source)
    return (
        code.clicksBeforeClip() + code.clicksInShapedSurfaces() + code.clickableBoxesAroundRoundChildren() +
            code.clicksOnRoundComponents() + code.stretchedIconButtons(path) + code.clicksOverShapedFills() +
            code.clicksOnBareGlyphs()
    ).sortedBy { it.second }.map { (kind, offset) -> "$kind @${code.lineAt(offset)}" }
}

/**
 * Rule 2: a click modifier with its own highlight, followed in the same chain by a clip that is not a
 * rectangle, directly or inside `.then(…)`.
 */
private fun String.clicksBeforeClip(): List<Pair<String, Int>> =
    clickModifier
        .findAll(this)
        .filter { match ->
            val open = match.groups[2]!!.range.first
            val close = closingBracket(open)
            // `clickable(…) { … }` ends at its trailing lambda, so the chain continues after that.
            val callEnd =
                if (match.groupValues[2] == "(") {
                    trailingLambda.matchAt(this, close + 1)?.let { closingBracket(it.range.last) } ?: close
                } else {
                    close
                }
            !noIndication.containsMatchIn(substring(open + 1, close)) &&
                chainAfter(callEnd).any { (name, args) ->
                    (name == "clip" && "RectangleShape" !in args) || (name == "then" && args.hasShapedClip())
                }
        }.map { CLICK_BEFORE_CLIP to it.range.first }
        .toList()

/**
 * Rule 1: a click modifier in the `modifier` of a non-clickable Surface whose shape is not a rectangle, or
 * of a non-clickable card, whose default shape is rounded.
 */
private fun String.clicksInShapedSurfaces(): List<Pair<String, Int>> =
    calls("Surface").flatMap { shapedContainerClicks(it, defaultShape = null) } +
        listOf("Card", "ElevatedCard", "OutlinedCard").flatMap { calls(it) }.flatMap {
            shapedContainerClicks(it, defaultShape = "rounded")
        }

/** The click modifiers in one shaped container call's `modifier`, unless it is clickable itself or rectangular. */
private fun shapedContainerClicks(
    call: Pair<Int, String>,
    defaultShape: String?,
): List<Pair<String, Int>> {
    val (start, args) = call
    val named = topLevelNamedArguments(args)
    val shape = named["shape"] ?: defaultShape
    val clickableOverload = listOf("onClick", "checked", "selected").any(named::containsKey)
    return if (clickableOverload || shape == null || "RectangleShape" in shape) {
        emptyList()
    } else {
        // A clip before the click only shapes the highlight like the Surface when it uses the Surface's shape.
        args.undecoratedClicks(matching = named["shape"]?.trim()).map { SHAPED_SURFACE to start + it }
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

/**
 * Rule 5: a transparent icon button given a width, a height or two sizes, or taking its size from its caller's
 * modifier in the composer, whose slots are narrower than they are tall. Its one shape clips the highlight
 * and the touch area together, so it draws a capsule or loses taps, and a slot like that takes
 * [SlotIconButton].
 */
private fun String.stretchedIconButtons(path: String): List<Pair<String, Int>> =
    (calls("IconButton") + calls("IconToggleButton"))
        .filter { (_, args) ->
            val modifier = modifierArgument(args)
            val sized = twoArgumentSize.containsMatchIn(args) || oneSideSize.containsMatchIn(args)
            val slotSized = path.startsWith(COMPOSER_PACKAGE) && modifier.startsWith("modifier")
            sized || slotSized
        }.map { (start, _) -> STRETCHED_ICON_BUTTON to start }

/** The `modifier` an icon button call receives, named or as its second positional argument. */
private fun modifierArgument(args: String): String =
    topLevelNamedArguments(args)["modifier"]?.trim()
        ?: topLevelArguments(args)
            .filterNot { namedArgument.containsMatchIn(it) }
            .getOrNull(1)
            ?.trim()
            .orEmpty()

/**
 * The functions in [source] that wrap a transparent icon button and pass their caller's `modifier` straight
 * to it, so the caller decides its size. Each name maps to nothing more: a call of it is checked for a size.
 */
internal fun passThroughIconButtonWrappers(source: String): Set<String> {
    val code = blankCommentsAndStrings(source)
    return (code.calls("IconButton") + code.calls("IconToggleButton"))
        .filter { (_, args) -> modifierArgument(args).startsWith("modifier") }
        .mapNotNull { (start, _) ->
            enclosingFunction
                .findAll(code.substring(0, start))
                .lastOrNull()
                ?.groupValues
                ?.get(1)
        }.toSet()
}

/**
 * Rule 5, across files: a call that hands one of [wrappers] (see [passThroughIconButtonWrappers]) a width, a
 * height or two sizes, so the icon button inside it stretches into a capsule. Reported as "<kind> @<line>".
 */
internal fun stretchedWrapperCalls(
    source: String,
    wrappers: Set<String>,
): List<String> {
    val code = blankCommentsAndStrings(source)
    return wrappers.flatMap { name ->
        code
            .calls(name)
            .filter { (start, args) ->
                val nameStart = start - 1 - name.length
                val before = code.substring(maxOf(0, nameStart - DECLARATION_LOOKBEHIND), maxOf(0, nameStart))
                !declaration.containsMatchIn(before) &&
                    (twoArgumentSize.containsMatchIn(args) || oneSideSize.containsMatchIn(args))
            }.map { (start, _) -> "$STRETCHED_ICON_BUTTON @${code.lineAt(start)}" }
    }
}

/** Rule 6: a click modifier after a shaped background or border in the same chain, with no shaped clip. */
private fun String.clicksOverShapedFills(): List<Pair<String, Int>> =
    clickModifier
        .findAll(this)
        .filter { match ->
            val open = match.groups[2]!!.range.first
            val before = chainBefore(match.range.first)
            !noIndication.containsMatchIn(substring(open + 1, closingBracket(open))) &&
                !before.hasShapedClip() &&
                shapedFill.findAll(before).any { fill ->
                    val fillArgs = before.substring(fill.range.last + 1, before.closingBracket(fill.range.last))
                    shapeName.containsMatchIn(fillArgs) && "RectangleShape" !in fillArgs
                }
        }.map { CLICK_OVER_SHAPED_FILL to it.range.first }
        .toList()

/** Rule 7: a click modifier on a bare Icon, whose highlight is a square around a glyph unless it is clipped. */
private fun String.clicksOnBareGlyphs(): List<Pair<String, Int>> =
    calls("Icon").flatMap { (start, args) -> args.undecoratedClicks().map { BARE_GLYPH to start + it } }

/** The modifier chain that ends at [end]: back to the start of its argument or assignment. */
private fun String.chainBefore(end: Int): String {
    var depth = 0
    var index = end - 1
    var atBoundary = false
    while (index >= 0 && !atBoundary) {
        when (this[index]) {
            ')', '}', ']' -> depth++
            '(', '{', '[' -> if (depth == 0) atBoundary = true else depth--
            ',', '=' -> atBoundary = depth == 0
        }
        if (!atBoundary) index--
    }
    return substring(index + 1, end)
}

/**
 * Offsets, within this argument text, of click modifiers that draw their own highlight before a clip that
 * shapes it — any clip that is not a rectangle, or, given [matching], one to that very shape.
 */
private fun String.undecoratedClicks(matching: String? = null): List<Int> =
    clickModifier
        .findAll(this)
        .filter { match ->
            val open = match.groups[2]!!.range.first
            !noIndication.containsMatchIn(substring(open + 1, closingBracket(open))) &&
                !substring(0, match.range.first).hasShapedClip(matching)
        }.map { it.range.first }
        .toList()

/**
 * Whether this text clips to a shape other than a rectangle, which then shapes a later click's highlight,
 * and, given [matching], to that shape.
 */
private fun String.hasShapedClip(matching: String? = null): Boolean =
    clipCall.findAll(this).any { match ->
        val open = match.range.last
        val clip = substring(open + 1, closingBracket(open)).trim()
        "RectangleShape" !in clip && (matching == null || clip == matching)
    }

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

/** The top-level arguments of a call's argument text, in order. */
private fun topLevelArguments(args: String): List<String> {
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
    return parts.filter { it.isNotBlank() }
}

/** The top-level named arguments of a call's argument text, by name. */
private fun topLevelNamedArguments(args: String): Map<String, String> {
    val parts = topLevelArguments(args)
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
