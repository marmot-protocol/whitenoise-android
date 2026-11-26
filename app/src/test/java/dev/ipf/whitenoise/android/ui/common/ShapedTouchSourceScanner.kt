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
internal const val MISSING_TOUCH_FEEDBACK = "shapedTouchTarget without matching applied press feedback"

/** The package whose icon buttons sit in slots narrower than they are tall. */
private const val COMPOSER_PACKAGE = "conversation/composer/"

private val clickModifier =
    Regex("""\.(clickable|combinedClickable|toggleable|selectable|triStateToggleable)\s*([({])""")
private val thenOpen = Regex("""\.\s*then\s*$""")
private val chainedCall = Regex("""\s*\.\s*(\w+)\s*([({])""")
private val trailingLambda = Regex("""[ \t]*\{""")
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
private val declaration = Regex("""fun\s+$""")
private val modifierParameter = Regex("""^\s*(@\w+\s+)*modifier\s*:""")
private val leadingName = Regex("""^\s*(\w+)""")
private val privateModifier = Regex("""\bprivate\b""")
private val functionModifiers =
    Regex(
        """(?:\b(?:private|public|internal|protected|inline|suspend|tailrec|operator|infix)\s+|""" +
            """@[\w.]+(?:\([^()]*\))?\s+)+$""",
    )
private val importDirective =
    Regex("""^\s*import\s+([\w.]+(?:\.\*)?)(?:\s+as\s+(\w+))?\s*$""", RegexOption.MULTILINE)
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
    sharedShapes: Map<String, String> = emptyMap(),
): List<String> {
    val code = blankCommentsAndStrings(source)
    val feedback = ShapedTouchIndications(code, sharedShapes)
    return (
        code.clicksBeforeClip(feedback) + code.clicksInShapedSurfaces(feedback) +
            code.clickableBoxesAroundRoundChildren(feedback) + code.clicksOnRoundComponents(feedback) +
            code.stretchedIconButtons(path) + code.clicksOverShapedFills(feedback) + code.clicksOnBareGlyphs(feedback) +
            feedback.unmatchedTouchTargets().map { MISSING_TOUCH_FEEDBACK to it }
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
    val shape = (named["shape"] ?: defaultShape)?.takeUnless { "RectangleShape" in it }
    val clickableOverload = listOf("onClick", "checked", "selected").any(named::containsKey)
    val modifier = modifierArgument(args)
    if (clickableOverload || shape == null || modifier == null) return emptyList()
    val offset = start + modifier.offset
    return modifier.text
        .undecoratedClicks(feedback, offset, matching = shape.trim())
        .map { SHAPED_SURFACE to offset + it }
}

/**
 * Rule 3: a clickable Box whose content, a trailing lambda or one passed as an argument, draws a round or rounded
 * shape without a shaped highlight. Only the Box's own modifier is checked for clicks.
 */
private fun String.clickableBoxesAroundRoundChildren(feedback: ShapedTouchIndications): List<Pair<String, Int>> =
    calls("Box").flatMap { (start, args) ->
        val modifier = modifierArgument(args) ?: return@flatMap emptyList()
        val lambda = trailingLambda.matchAt(this, start + args.length + 1)
        val trailing = lambda?.let { substring(it.range.last + 1, closingBracket(it.range.last)) }
        // Besides its modifier, only Box's content can draw, so every other argument is read as content: named or
        // positional, annotated such as `@Composable { … }` or parenthesized.
        val passed = topLevelArgumentValues(args).filterNot { it.offset == modifier.offset }.map { it.text }
        if ((listOfNotNull(trailing) + passed).any(roundChild::containsMatchIn)) {
            val offset = start + modifier.offset
            modifier.text.undecoratedClicks(feedback, offset).map { ROUND_CHILD to offset + it }
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
    calls("IconButton").map { (start, args) -> start to modifierArgument(args, position = 1)?.text.orEmpty() } +
        calls("IconToggleButton").map { (start, args) -> start to modifierArgument(args, position = 2)?.text.orEmpty() }

/**
 * The functions in [source] that wrap a transparent icon button and pass their caller's `modifier` straight
 * to it, so the caller decides its size, each with the position of its `modifier` parameter.
 */
internal fun passThroughIconButtonWrappers(
    source: String,
    visibleAcrossFilesOnly: Boolean = false,
): Map<String, Set<Int>> {
    val code = blankCommentsAndStrings(source)
    return code
        .iconButtonCalls()
        .filter { (_, modifier) -> modifier.startsWith("modifier") }
        .mapNotNull { (start, _) -> code.functionContaining(start, visibleAcrossFilesOnly) }
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
 * button inside stretches into a capsule. Reported as "<kind> @<line>". When the wrappers come [fromOtherFiles], a
 * call that a local function of the same name shadows reaches that function instead, so it is skipped.
 */
internal fun stretchedWrapperCalls(
    source: String,
    wrappers: Map<String, Set<Int>>,
    fromOtherFiles: Boolean = false,
): List<String> {
    val code = blankCommentsAndStrings(source)
    return wrappers.flatMap { (name, modifierIndices) ->
        code
            .calls(name)
            .filter { (start, args) ->
                val nameStart = start - 1 - name.length
                val before = code.substring(maxOf(0, nameStart - DECLARATION_LOOKBEHIND), maxOf(0, nameStart))
                // A local function declared earlier in an enclosing block is what a bare call reaches instead.
                val shadowed = fromOtherFiles && code.substring(0, nameStart).declaresLocalFunction(name)
                !declaration.containsMatchIn(before) &&
                    !shadowed &&
                    modifierIndices.any { index ->
                        // Only bindings declared before this call are in scope for it.
                        val expression = modifierArgument(args, index)?.text.orEmpty()
                        val modifier = code.substring(0, start).withLocalBinding(expression)
                        twoArgumentSize.containsMatchIn(modifier) || oneSideSize.containsMatchIn(modifier)
                    }
            }.map { (start, _) -> "$STRETCHED_ICON_BUTTON @${code.lineAt(start)}" }
    }
}

/**
 * Checks private wrappers in their own file and shares visible wrappers, by fully qualified name, only with the
 * files whose imports or package make a call reach that very function.
 */
internal fun stretchedWrapperFindings(sources: Map<String, String>): Map<String, List<String>> {
    val visible =
        sources.values
            .flatMap { source ->
                val pkg = blankCommentsAndStrings(source).packageName()
                passThroughIconButtonWrappers(source, visibleAcrossFilesOnly = true).map { (name, positions) ->
                    pkg.qualify(name) to positions
                }
            }.groupBy({ it.first }, { it.second })
            .mapValues { (_, positions) -> positions.flatten().toSet() }
    return sources.mapValues { (_, source) ->
        val code = blankCommentsAndStrings(source)
        // Only this file's own top-level functions take a name from its package and star imports; a local one
        // shadows a wrapper only inside its own function, so it cannot hide every call in the file.
        val ownTopLevel =
            enclosingFunction
                .findAll(code)
                .filter { code.isTopLevel(it) }
                .map { it.groupValues[1] }
                .toSet()
        val reached =
            visible.flatMap { (qualified, positions) ->
                code.callNamesOf(qualified, visible.keys, ownTopLevel).map { it to positions }
            }
        val imported = reached.groupBy({ it.first }, { it.second }).mapValues { (_, it) -> it.flatten().toSet() }
        val local = passThroughIconButtonWrappers(source)
        (stretchedWrapperCalls(source, imported, fromOtherFiles = true) + stretchedWrapperCalls(source, local))
            .distinct()
    }
}

/** Whether a local `fun [name]` is declared in a block of this text that is still open at its end. */
private fun String.declaresLocalFunction(name: String): Boolean =
    Regex("""\bfun\s+${Regex.escape(name)}\s*\(""")
        .findAll(this)
        .any { !isTopLevel(it) && blockStaysOpen(from = it.range.first) }

/**
 * The names this file calls the top-level function [qualified] by, in Kotlin's order: an aliased or explicit
 * import of it; else, unless an explicit import or one of this file's own top-level functions ([ownTopLevel])
 * takes the simple name, this file's package and then a star import of its package, where a [known] function of
 * the same name in this file's package wins over the star import. Overloads are not told apart: any own top-level
 * function of that name takes it, even one whose parameters a call could not match, so such a call goes unchecked.
 * Local functions are resolved at each call (see [stretchedWrapperCalls]).
 */
private fun String.callNamesOf(
    qualified: String,
    known: Set<String>,
    ownTopLevel: Set<String>,
): List<String> {
    val simple = qualified.substringAfterLast('.')
    val owner = qualified.substringBeforeLast('.', "")
    val imports = importDirective.findAll(this).map { it.groupValues[1] to it.groupValues[2] }.toList()
    val aliases = imports.filter { (path, alias) -> path == qualified && alias.isNotEmpty() }.map { it.second }
    val explicit = imports.any { (path, alias) -> path == qualified && alias.isEmpty() }
    val taken =
        simple in ownTopLevel ||
            imports.any { (path, alias) -> alias == simple || (alias.isEmpty() && path.endsWith(".$simple")) }
    val pkg = packageName()
    val viaPackage = owner == pkg
    val viaStar = imports.any { it.first == "$owner.*" } && pkg.qualify(simple) !in known
    val bySimpleName = explicit || (!taken && (viaPackage || viaStar))
    return aliases + listOfNotNull(simple.takeIf { bySimpleName })
}

/** [expression] with the initializer of the local `val` it starts from, if any, put in front of it. */
private fun String.withLocalBinding(expression: String): String {
    val head = leadingName.find(expression)?.groupValues?.get(1)
    val local = head?.takeUnless { it == "Modifier" || it == "modifier" }
    val binding = local?.let { localBinding(it)?.text }
    return if (binding == null) expression else "$binding $expression"
}

/** The name and parameter text of the function whose body contains [offset], or null. */
private fun String.functionContaining(
    offset: Int,
    visibleAcrossFilesOnly: Boolean,
): Pair<String, String>? =
    enclosingFunction
        .findAll(substring(0, offset))
        .mapNotNull { match ->
            val parametersOpen = match.range.last
            val parametersClose = closingBracket(parametersOpen)
            val bodyOpen = indexOfAny(charArrayOf('{', '='), parametersClose)
            val bodyEnd = if (bodyOpen >= 0 && this[bodyOpen] == '{') closingBracket(bodyOpen) else length
            if (bodyOpen in 0 until offset && offset < bodyEnd) {
                match to substring(parametersOpen + 1, parametersClose)
            } else {
                null
            }
        }.lastOrNull()
        ?.takeUnless { (match, _) -> visibleAcrossFilesOnly && !isFileVisible(match.range.first) }
        ?.let { (match, parameters) -> match.groupValues[1] to parameters }

/** Cross-file wrappers must be top-level functions without a private modifier. */
private fun String.isFileVisible(start: Int): Boolean {
    val prefix = substring(0, start)
    val topLevel = prefix.count { it == '{' } == prefix.count { it == '}' }
    val modifierList = functionModifiers.find(prefix)?.value.orEmpty()
    return topLevel && !privateModifier.containsMatchIn(modifierList)
}

/** Rule 6: a click modifier after a shaped background or border in the same chain, with no shaped clip. */
private fun String.clicksOverShapedFills(feedback: ShapedTouchIndications): List<Pair<String, Int>> =
    clickModifier
        .findAll(this)
        .filter { match ->
            val open = match.groups[2]!!.range.first
            val before = ownChainBefore(match.range.first)
            !feedback.replaces(match.range.first, substring(open + 1, closingBracket(open))) &&
                !before.hasShapedClip { shape -> feedback.isKnownRounded(shape, match.range.first) } &&
                shapedFill.findAll(before).any { fill ->
                    val fillArgs = before.substring(fill.range.last + 1, before.closingBracket(fill.range.last))
                    shapeName.containsMatchIn(fillArgs) && "RectangleShape" !in fillArgs
                }
        }.map { CLICK_OVER_SHAPED_FILL to it.range.first }
        .toList()

/** Rule 7: a click modifier on a bare Icon, whose highlight is a square around a glyph unless it is clipped. */
private fun String.clicksOnBareGlyphs(feedback: ShapedTouchIndications): List<Pair<String, Int>> =
    calls("Icon").flatMap { (start, args) ->
        val clicks = args.undecoratedClicks(feedback, offset = start)
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
            ',', '=', ';' -> atBoundary = depth == 0
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
                !ownChainBefore(match.range.first).hasShapedClip(matching) { shape ->
                    feedback.isKnownRounded(shape, offset + match.range.first)
                }
        }.map { it.range.first }
        .toList()

/**
 * Whether this text clips to a shape other than a rectangle, which then shapes a later click's highlight,
 * and, given [matching], to that shape. Without [matching], a named shape constant counts only once [isRounded]
 * resolves it, since a name such as `TileShape` can hold a rectangle.
 */
private fun String.hasShapedClip(
    matching: String? = null,
    isRounded: ((String) -> Boolean)? = null,
): Boolean =
    modifierLinks().any { link ->
        val shape = link.args.trim()
        val resolved = isRounded == null || matching != null || !shapeConstant.matches(shape) || isRounded(shape)
        link.clipsToShape(matching) && resolved
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

/** The 1-based line of [offset]. */
private fun String.lineAt(offset: Int): Int = 1 + (0 until offset.coerceAtMost(length)).count { this[it] == '\n' }

/** Identifies an audited control by its test tag, enclosing function and click signature. */
internal fun roundChildClickSite(
    source: String,
    line: Int,
): String? {
    val code = blankCommentsAndStrings(source)
    val click = code.clickOnLine(line) ?: return null
    val function = code.functionContaining(click.range.first, visibleAcrossFilesOnly = false)?.first
    val tag = code.roundChildTag(click.range.first)
    return if (function == null || tag == null) {
        null
    } else {
        val open = click.range.last
        val args = code.substring(open + 1, code.closingBracket(open)).normalizedArguments()
        "$function: testTag($tag): ${click.groupValues[1]}($args)"
    }
}

/** Finds the click starting on this line when it is the only candidate for the finding. */
private fun String.clickOnLine(line: Int): MatchResult? {
    val start = lineSequence().take(line - 1).sumOf { it.length + 1 }
    val end = indexOf('\n', start).takeIf { it >= 0 } ?: length
    return clickModifier.findAll(this, start).takeWhile { it.range.first < end }.singleOrNull()
}

/** Reads the test tag from the Box modifier that receives this click. */
private fun String.roundChildTag(click: Int): String? =
    calls("Box")
        .mapNotNull { (start, args) ->
            val modifier = modifierArgument(args) ?: return@mapNotNull null
            val offset = start + modifier.offset
            modifier.text.takeIf { click in offset until offset + it.length }
        }.lastOrNull()
        ?.modifierLinks()
        ?.singleOrNull { it.name == "testTag" && !it.conditional }
        ?.args
        ?.normalizedArguments()

/** Ignores argument formatting and Kotlin's optional trailing comma in audit signatures. */
private fun String.normalizedArguments(): String = filterNot(Char::isWhitespace).trimEnd(',')
