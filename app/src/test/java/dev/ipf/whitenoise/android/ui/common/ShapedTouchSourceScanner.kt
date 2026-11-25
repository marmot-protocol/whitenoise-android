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
private val thenOpen = Regex("""\.\s*then\s*$""")
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

/** Each Material card and the shape it takes when none is named. */
private val cardDefaultShapes =
    mapOf(
        "Card" to "CardDefaults.shape",
        "ElevatedCard" to "CardDefaults.elevatedShape",
        "OutlinedCard" to "CardDefaults.outlinedShape",
    )
private val clipCall = Regex("""\.clip\s*\(""")
private val declaration = Regex("""fun\s+$""")
private val modifierParameter = Regex("""^\s*(@\w+\s+)*modifier\s*:""")
private val leadingName = Regex("""^\s*(\w+)""")
internal val enclosingFunction = Regex("""\bfun\s+(?:[\w.<>]+\.)?(\w+)\s*\(""")

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
    val feedback = ShapedTouchIndications(code)
    return (
        code.clicksBeforeClip(feedback) + code.clicksInShapedSurfaces(feedback) +
            code.clickableBoxesAroundRoundChildren(feedback) + code.clicksOnRoundComponents(feedback) +
            code.stretchedIconButtons(path) + code.clicksOverShapedFills(feedback) + code.clicksOnBareGlyphs(feedback)
    ).sortedBy { it.second }.map { (kind, offset) -> "$kind @${code.lineAt(offset)}" }
}

/**
 * Rule 2: a click modifier with its own highlight, followed in the same chain by a clip that is not a
 * rectangle, directly or inside `.then(…)`.
 */
private fun String.clicksBeforeClip(feedback: ShapedTouchIndications): List<Pair<String, Int>> =
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
            !feedback.replaces(match.range.first, substring(open + 1, close)) &&
                chainAfter(callEnd).any { (name, args) ->
                    (name == "clip" && "RectangleShape" !in args) || (name == "then" && args.hasShapedClip())
                }
        }.map { CLICK_BEFORE_CLIP to it.range.first }
        .toList()

/**
 * Rule 1: a click modifier in the `modifier` of a non-clickable Surface whose shape is not a rectangle, or
 * of a non-clickable card, whose default shape is rounded.
 */
private fun String.clicksInShapedSurfaces(feedback: ShapedTouchIndications): List<Pair<String, Int>> =
    calls("Surface").flatMap { shapedContainerClicks(it, defaultShape = null, feedback) } +
        cardDefaultShapes.flatMap { (card, defaultShape) ->
            calls(card).flatMap { shapedContainerClicks(it, defaultShape, feedback) }
        }

/** The click modifiers in one shaped container call's `modifier`, unless it is clickable itself or rectangular. */
private fun shapedContainerClicks(
    call: Pair<Int, String>,
    defaultShape: String?,
    feedback: ShapedTouchIndications,
): List<Pair<String, Int>> {
    val (start, args) = call
    val named = topLevelNamedArguments(args)
    val shape = named["shape"] ?: defaultShape
    val clickableOverload = listOf("onClick", "checked", "selected").any(named::containsKey)
    return if (clickableOverload || shape == null || "RectangleShape" in shape) {
        emptyList()
    } else {
        // A clip before the click only shapes the highlight like the container when it uses the container's own
        // shape, which for a card with none named is its default.
        val clicks = args.undecoratedClicks(feedback, offset = start + 1, matching = shape.trim())
        clicks.map { SHAPED_SURFACE to start + it }
    }
}

/** Rule 3: a clickable Box whose content draws a round or rounded shape without a shaped highlight. */
private fun String.clickableBoxesAroundRoundChildren(feedback: ShapedTouchIndications): List<Pair<String, Int>> =
    calls("Box").flatMap { (start, args) ->
        val lambda = trailingLambda.matchAt(this, start + args.length + 1)
        val body = lambda?.let { substring(it.range.last + 1, closingBracket(it.range.last)) }
        if (body != null && roundChild.containsMatchIn(body)) {
            args.undecoratedClicks(feedback, offset = start + 1).map { ROUND_CHILD to start + it }
        } else {
            emptyList()
        }
    }

/** Rule 4: a click modifier passed to an avatar or swatch, which clips itself only inside. */
private fun String.clicksOnRoundComponents(feedback: ShapedTouchIndications): List<Pair<String, Int>> =
    roundComponentCall
        .findAll(this)
        .filterNot { match ->
            val name = match.range.first
            declaration.containsMatchIn(substring(maxOf(0, name - DECLARATION_LOOKBEHIND), name))
        }.flatMap { match ->
            val open = match.range.last
            val args = substring(open + 1, closingBracket(open))
            args.undecoratedClicks(feedback, offset = open + 1).map { ROUND_COMPONENT to open + 1 + it }
        }.toList()

/**
 * Rule 5: a transparent icon button given a width, a height or two sizes, or taking its size from its caller's
 * modifier in the composer, whose slots are narrower than they are tall. Its one shape clips the highlight
 * and the touch area together, so it draws a capsule or loses taps, and a slot like that takes
 * [SlotIconButton].
 */
private fun String.stretchedIconButtons(path: String): List<Pair<String, Int>> =
    iconButtonCalls()
        .filter { (_, modifier) ->
            // Only the button's own modifier sizes it, never a sized child in its `content`.
            val sized = twoArgumentSize.containsMatchIn(modifier) || oneSideSize.containsMatchIn(modifier)
            val slotSized = path.startsWith(COMPOSER_PACKAGE) && modifier.startsWith("modifier")
            sized || slotSized
        }.map { (start, _) -> STRETCHED_ICON_BUTTON to start }

/**
 * Each `IconButton` and `IconToggleButton` call in this text, with the `modifier` it receives. The modifier is
 * the second positional argument of `IconButton(onClick, modifier)` and the third of
 * `IconToggleButton(checked, onCheckedChange, modifier)`.
 */
private fun String.iconButtonCalls(): List<Pair<Int, String>> =
    calls("IconButton").map { (start, args) -> start to modifierArgument(args, position = 1) } +
        calls("IconToggleButton").map { (start, args) -> start to modifierArgument(args, position = 2) }

/**
 * The `modifier` a call receives, named or as its positional argument at [position]. A call may name its
 * leading arguments and still pass the modifier by position, so [position] counts every argument, and a
 * named argument there is not the modifier.
 */
private fun modifierArgument(
    args: String,
    position: Int,
): String =
    topLevelNamedArguments(args)["modifier"]?.trim()
        ?: topLevelArguments(args)
            .getOrNull(position)
            ?.takeUnless { namedArgument.containsMatchIn(it) }
            ?.trim()
            .orEmpty()

/**
 * The functions in [source] that wrap a transparent icon button and pass their caller's `modifier` straight
 * to it, so the caller decides its size, each with the position of its `modifier` parameter.
 */
internal fun passThroughIconButtonWrappers(source: String): Map<String, Set<Int>> {
    val code = blankCommentsAndStrings(source)
    return code
        .iconButtonCalls()
        .filter { (_, modifier) -> modifier.startsWith("modifier") }
        .mapNotNull { (start, _) -> code.functionContaining(start) }
        .mapNotNull { (name, parameters) ->
            parameterList(parameters)
                .indexOfFirst { modifierParameter.containsMatchIn(it) }
                .takeIf { it >= 0 }
                ?.let { name to it }
        }.groupBy({ it.first }, { it.second })
        .mapValues { (_, indices) -> indices.toSet() }
}

/**
 * Rule 5, across files: a call that hands one of [wrappers] (see [passThroughIconButtonWrappers]) a modifier
 * with a width, a height or two sizes, written in the call or in a local `val` it starts from, so the icon
 * button inside stretches into a capsule. Reported as "<kind> @<line>".
 */
internal fun stretchedWrapperCalls(
    source: String,
    wrappers: Map<String, Set<Int>>,
): List<String> {
    val code = blankCommentsAndStrings(source)
    return wrappers.flatMap { (name, modifierIndices) ->
        code
            .calls(name)
            .filter { (start, args) ->
                val nameStart = start - 1 - name.length
                val before = code.substring(maxOf(0, nameStart - DECLARATION_LOOKBEHIND), maxOf(0, nameStart))
                !declaration.containsMatchIn(before) &&
                    modifierIndices.any { index ->
                        // Only bindings declared before this call are in scope for it.
                        val modifier = code.substring(0, start).withLocalBinding(modifierArgument(args, index))
                        twoArgumentSize.containsMatchIn(modifier) || oneSideSize.containsMatchIn(modifier)
                    }
            }.map { (start, _) -> "$STRETCHED_ICON_BUTTON @${code.lineAt(start)}" }
    }
}

/** [expression] with the initializer of the local `val` it starts from, if any, put in front of it. */
private fun String.withLocalBinding(expression: String): String {
    val head = leadingName.find(expression)?.groupValues?.get(1)
    val local = head?.takeUnless { it == "Modifier" || it == "modifier" }
    val binding = local?.let { localBinding(it) }
    return if (binding == null) expression else "$binding $expression"
}

/**
 * The initializer of the `val [name] = …` in scope at the end of this text — the nearest one whose block is
 * still open there, not one in a block that has already closed — read over the lines a wrapped modifier chain
 * continues onto, or null.
 */
private fun String.localBinding(name: String): String? {
    val declaration =
        Regex("""\bval\s+${Regex.escape(name)}\s*=\s*""")
            .findAll(this)
            .lastOrNull { blockStaysOpen(from = it.range.last + 1) }
    return declaration?.let { initializerFrom(it.range.last + 1) }
}

/** Whether no brace closes the block open at [from] before the end of this text. */
internal fun String.blockStaysOpen(from: Int): Boolean {
    var depth = 0
    for (index in from until length) {
        when (this[index]) {
            '{' -> depth++
            '}' -> if (--depth < 0) return false
        }
    }
    return true
}

/** The expression starting at [start]: up to a line break outside brackets that no `.` call continues. */
internal fun String.initializerFrom(start: Int): String {
    var depth = 0
    var index = start
    while (index < length && !endsStatementAt(index, depth)) {
        when (this[index]) {
            '(', '{', '[' -> depth++
            ')', '}', ']' -> depth--
        }
        index++
    }
    return substring(start, index)
}

/** Whether a line break at [index], outside brackets at [depth], ends the statement it is in. */
private fun String.endsStatementAt(
    index: Int,
    depth: Int,
): Boolean = this[index] == '\n' && depth <= 0 && !continuesOnNextLine(index + 1)

/** Whether the line starting at [from] continues a call chain with a leading `.`. */
private fun String.continuesOnNextLine(from: Int): Boolean {
    var index = from
    while (index < length && (this[index] == ' ' || this[index] == '\t')) index++
    return index < length && this[index] == '.'
}

/** A function's parameter list split at top-level commas, counting `<…>` type arguments as brackets. */
private fun parameterList(parameters: String): List<String> {
    val parts = mutableListOf<String>()
    var depth = 0
    var start = 0
    parameters.forEachIndexed { index, char ->
        when {
            char in "({[<" -> depth++
            char in ")}]" || (char == '>' && parameters.getOrNull(index - 1) != '-') -> depth--
            char == ',' && depth == 0 -> parts += parameters.substring(start, index).also { start = index + 1 }
        }
    }
    parts += parameters.substring(start)
    return parts.filter { it.isNotBlank() }
}

/** The name and parameter text of the function whose body contains [offset], or null. */
private fun String.functionContaining(offset: Int): Pair<String, String>? =
    enclosingFunction
        .findAll(substring(0, offset))
        .mapNotNull { match ->
            val parametersOpen = match.range.last
            val parametersClose = closingBracket(parametersOpen)
            val bodyOpen = indexOfAny(charArrayOf('{', '='), parametersClose)
            val bodyEnd = if (bodyOpen >= 0 && this[bodyOpen] == '{') closingBracket(bodyOpen) else length
            if (bodyOpen in 0 until offset && offset < bodyEnd) {
                match.groupValues[1] to substring(parametersOpen + 1, parametersClose)
            } else {
                null
            }
        }.lastOrNull()

/** Rule 6: a click modifier after a shaped background or border in the same chain, with no shaped clip. */
private fun String.clicksOverShapedFills(feedback: ShapedTouchIndications): List<Pair<String, Int>> =
    clickModifier
        .findAll(this)
        .filter { match ->
            val open = match.groups[2]!!.range.first
            val before = ownChainBefore(match.range.first)
            !feedback.replaces(match.range.first, substring(open + 1, closingBracket(open))) &&
                !before.hasShapedClip() &&
                shapedFill.findAll(before).any { fill ->
                    val fillArgs = before.substring(fill.range.last + 1, before.closingBracket(fill.range.last))
                    shapeName.containsMatchIn(fillArgs) && "RectangleShape" !in fillArgs
                }
        }.map { CLICK_OVER_SHAPED_FILL to it.range.first }
        .toList()

/** Rule 7: a click modifier on a bare Icon, whose highlight is a square around a glyph unless it is clipped. */
private fun String.clicksOnBareGlyphs(feedback: ShapedTouchIndications): List<Pair<String, Int>> =
    calls("Icon").flatMap { (start, args) ->
        val clicks = args.undecoratedClicks(feedback, offset = start + 1)
        clicks.map { BARE_GLYPH to start + it }
    }

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
 * The modifier chain that ends at [end], as [chainBefore], continued out of each `.then(…)` it sits in, so a
 * clip earlier in the outer chain counts and one in a sibling argument or a content lambda does not.
 */
private fun String.ownChainBefore(end: Int): String {
    val start = end - chainBefore(end).length
    val then = if (start > 0 && this[start - 1] == '(') thenOpen.find(substring(0, start - 1)) else null
    return if (then == null) substring(start, end) else ownChainBefore(then.range.first) + substring(start, end)
}

/**
 * Offsets, within this argument text, of click modifiers whose highlight no shaped clip earlier in their own
 * chain shapes — any clip that is not a rectangle, or, given [matching], one to that very shape — and that no
 * modifier watching their interaction source replaces (see [ShapedTouchIndications]).
 */
private fun String.undecoratedClicks(
    feedback: ShapedTouchIndications,
    offset: Int,
    matching: String? = null,
): List<Int> =
    clickModifier
        .findAll(this)
        .filter { match ->
            val open = match.groups[2]!!.range.first
            !feedback.replaces(offset + match.range.first, substring(open + 1, closingBracket(open))) &&
                !ownChainBefore(match.range.first).hasShapedClip(matching)
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
internal fun topLevelArguments(args: String): List<String> {
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
internal fun topLevelNamedArguments(args: String): Map<String, String> {
    val parts = topLevelArguments(args)
    return parts
        .mapNotNull { part -> namedArgument.find(part)?.let { it.groupValues[1] to part.substring(it.range.last + 1) } }
        .toMap()
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
