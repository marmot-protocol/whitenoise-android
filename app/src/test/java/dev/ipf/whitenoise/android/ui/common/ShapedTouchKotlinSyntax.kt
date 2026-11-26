package dev.ipf.whitenoise.android.ui.common

private val namedArgument = Regex("""^\s*(\w+)\s*=(?!=)""")
private val parameterName = Regex("""(\w+)\s*:""")
private val typeOperator = Regex("""\b(?:as\??|is)\s+[\w.]+$""")
private val ifCondition = Regex("""^\s*if\s*\(""")
private val elseBlock = Regex("""\s*else\s*\{""")
private val elseKeyword = Regex("""\s*else\b""")
private val conditionalModifier = Regex("""^\s*(?:if|when)\b""")
private val sourcePresentCondition = Regex("""^\s*if\s*\(\s*(\w+)\s*!=\s*null\s*\)""")
private val emptyModifierElse = Regex("""\belse\s+Modifier\s*$""")
private val modifierCall = Regex("""\.\s*(\w+)\s*([({])""")
private val branchKeyword = Regex("""\b(?:if|when|else|try|catch)\b""")
private val nullableOrLogicalOperator = Regex("""\?:|\?\.|&&|\|\|""")
private val replacingScopeCall = Regex("""\.\s*(?:let|run|takeIf|takeUnless)\b""")
private val branchConditionOpening = Regex("""\b(?:if|when|while|for|catch)\s*$""")
private val armKeyword = Regex("""(?:\belse|\bwhen|\btry|->|\bfinally)\s*$""")
private val NESTED = LinkScope(conditional = true, nested = true)

/** The name of a link standing for a stored or computed modifier the walk cannot see into. */
internal const val OPAQUE_LINK = "<opaque>"
private val stockRoundedName =
    Regex("""CircleShape|CenteredCircleShape|MaterialTheme\.shapes\.\w+|ShapeDefaults\.\w+|CardDefaults\.\w*[Ss]hape""")
private val stockRoundedFactory = Regex("""(?:RoundedCornerShape|CutCornerShape)\s*\(""")
internal val shapeConstant = Regex("""[A-Z][A-Za-z0-9_]*(?:Shape|SHAPE)""")
private val positiveRadius = Regex("""(\d+(?:\.\d+)?)(?:\s*\.\s*dp|f|F)?""")
private val namedRadius = Regex("""[A-Z]\w*(?:\.\w+)*""")
private val opaqueStep = Regex("""\.\s*\w+(?!\s*[\w({])""")
private val bareName = Regex("""[A-Za-z_]\w*""")
private val strayName = Regex("""(?<![\w.])[A-Za-z_]\w*""")
private val inlineWords =
    setOf("Modifier", "as", "else", "false", "if", "in", "is", "it", "null", "this", "true", "when")

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

/** The top-level arguments' values, after any `name =`, with their offsets in the argument text. */
internal fun topLevelArgumentValues(args: String): List<TouchArgument> =
    topLevelArgumentSpans(args).map { part ->
        val name = namedArgument.find(part.text)
        part.valueAfter(name?.let { it.range.last + 1 } ?: 0)
    }

/** The name of a `name = value` argument, or null for a positional one. */
internal fun argumentName(argument: String): String? = namedArgument.find(argument)?.groupValues?.get(1)

/** The parameter names of a function's parameter list text, in order. */
internal fun parameterNames(parameters: String): List<String?> =
    topLevelArguments(parameters).map { parameter -> parameterName.find(parameter)?.groupValues?.get(1) }

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

/** The nullable source a plain `.then(if (source != null) … else Modifier)` arm is guarded by, and that `.then`. */
internal data class SourceGuard(
    val source: String,
    val arm: Int,
)

/**
 * How the walk reached a call: [conditional] when it may not apply, [nested] when it sits in another call's
 * arguments or a lambda rather than in the applied expression or a branch arm, and the [guard] of the plain
 * nullable-source arm it is in.
 */
internal data class LinkScope(
    val conditional: Boolean = false,
    val nested: Boolean = false,
    val guard: SourceGuard? = null,
)

/** One modifier call found by the walk, and the offset of its arguments within the source. */
internal data class ModifierLink(
    val name: String,
    val args: String,
    val argumentOffset: Int,
    val scope: LinkScope = LinkScope(),
) {
    val conditional: Boolean get() = scope.conditional
    val nested: Boolean get() = scope.nested
    val guardedSource: String? get() = scope.guard?.source

    /** Whether this link always clips to a stock rounded shape or a shape constant, optionally to [matching]. */
    fun clipsToShape(matching: String? = null): Boolean {
        val shape = args.trim()
        val shaped = shape.isStockOrConstantShape() && (matching == null || shape == matching)
        return name == "clip" && !conditional && shaped
    }
}

/** A plain stock rounded shape or capitalized shape constant: never a rectangle, a variable or a branch. */
internal fun String.isStockOrConstantShape(): Boolean {
    val shape = trim()
    val plain = shape.firstTopLevelBranch() == shape.length && "RectangleShape" !in shape
    return plain && (shape.isStockRoundedShape() || shapeConstant.matches(shape))
}

/** Exactly a stock rounded shape: a whole stock name, or a whole rounded-corner factory call, never a prefix. */
internal fun String.isStockRoundedShape(provenRadius: (String) -> Boolean = ::isNamedOrPositiveRadius): Boolean {
    val factory = stockRoundedFactory.matchAt(this, 0)
    val whole = factory != null && closingBracket(factory.range.last) == lastIndex
    val radius = if (whole) substring(factory!!.range.last + 1, lastIndex) else ""
    // Every radius must be proven positive; anything computed stays unproven.
    val rounded =
        whole && radius.isNotBlank() && topLevelArguments(radius).all { provenRadius(it.substringAfter('=').trim()) }
    return stockRoundedName.matches(this) || rounded
}

/** Whether a radius value is a positive literal in any unit: `8.dp`, `50`, `1.5f`. */
internal fun isPositiveRadius(value: String): Boolean {
    val literal =
        positiveRadius
            .matchEntire(value)
            ?.groupValues
            ?.get(1)
            ?.toDoubleOrNull()
    return literal != null && literal > 0
}

/** The click rules' looser radius test: a positive literal or any capitalized name. */
private fun isNamedOrPositiveRadius(value: String): Boolean = isPositiveRadius(value) || namedRadius.matches(value)

/**
 * Visits every modifier call in an applied modifier expression, in order. One rule decides whether a call is
 * applied unconditionally: only a plain chain written inline on `Modifier` or a parameter is. A call after an
 * `if`, `when`, `else`, `try` or `catch`, any call in an expression with `?:`, `?.`, `&&`, `||`, `let`, `run`,
 * `takeIf` or `takeUnless`, a call in a branch arm, and a call reached through a `var` are
 * [ModifierLink.conditional]. A call inside another call's arguments or a lambda is also [ModifierLink.nested]:
 * it is not part of the applied expression at all. Every call is still visited, so a touch target anywhere is
 * checked, but only an unconditional call proves feedback or a clip. Grouping parentheses keep their contents'
 * status. A local modifier value is never expanded: the gate requires targets and feedback to be written inline,
 * because proving what a named value holds at each use needs a Kotlin parser.
 */
internal fun String.modifierLinks(
    offset: Int = 0,
    scope: LinkScope = LinkScope(),
): Sequence<ModifierLink> =
    sequence {
        val text = this@modifierLinks
        val firstBranch = text.firstTopLevelBranch()
        val root = text.indexOfFirst { !it.isWhitespace() }
        var index = 0
        while (index < length) {
            // Past a branch nothing is certain, and a nullable-source guard no longer covers the chain.
            val here = if (index >= firstBranch) scope.copy(conditional = true, guard = null) else scope
            val call = modifierCall.matchAt(text, index)
            // An extension property, an infix call such as `then`, or any other bare name after the root is a link
            // the walk cannot see into.
            val opaque = if (call == null) opaqueStep.matchAt(text, index) ?: text.bareNameAt(index, root) else null
            when {
                call != null -> {
                    yieldAll(text.callLinks(call, offset, here))
                    index = closingBracket(call.range.last)
                }
                opaque != null -> {
                    yield(ModifierLink(OPAQUE_LINK, opaque.value, offset + index, here))
                    index = opaque.range.last
                }
                text[index] in "({[" -> {
                    yieldAll(text.bracketLinks(index, offset, here))
                    index = closingBracket(index)
                }
            }
            index++
        }
    }

/** The link for one modifier call, then the calls inside its own arguments and lambdas, which are nested. */
private fun String.callLinks(
    call: MatchResult,
    offset: Int,
    scope: LinkScope,
): Sequence<ModifierLink> {
    val open = call.range.last
    val argumentOffset = open + 1
    val args = substring(argumentOffset, closingBracket(open))
    val name = call.groupValues[1]
    return if (name == "then" && this[open] == '(') {
        args.thenLinks(offset + argumentOffset, scope)
    } else {
        sequenceOf(ModifierLink(name, args, offset + argumentOffset, scope)) +
            args.modifierLinks(offset + argumentOffset, NESTED)
    }
}

/**
 * Links inside a bracket that is not a modifier call's arguments. A grouping keeps its contents' status, and a
 * branch arm is conditional; any other bracket (a lambda, a condition, a plain call's arguments) is nested.
 */
private fun String.bracketLinks(
    open: Int,
    offset: Int,
    scope: LinkScope,
): Sequence<ModifierLink> {
    val inner = substring(open + 1, closingBracket(open))
    val start = offset + open + 1
    return when {
        groupsAt(open) -> inner.opaqueLinks(start, scope) + inner.modifierLinks(start, scope)
        opensBranchArm(open) -> {
            val arm = scope.copy(conditional = true, guard = null)
            inner.opaqueLinks(start, arm) + inner.modifierLinks(start, arm)
        }
        else -> inner.modifierLinks(start, NESTED)
    }
}

/** Whether the brace at [open] is an `if`, `else`, `when` or `try` body rather than a lambda. */
private fun String.opensBranchArm(open: Int): Boolean {
    var before = open - 1
    while (before >= 0 && this[before].isWhitespace()) before--
    val previous = getOrNull(before)
    val afterCondition = previous == ')' && branchConditionOpening.containsMatchIn(substring(0, openingParen(before)))
    return this[open] == '{' && (afterCondition || armKeyword.containsMatchIn(substring(0, before + 1)))
}

/**
 * The call or function a bracket belongs to: `name {`, `name(…) {`, `fun name(…) {`, or the `name(` whose
 * arguments hold a lambda such as `onClick = { … }`; else null.
 */
internal fun String.scopeName(open: Int): String? {
    var before = lastNonWhitespace(open - 1)
    if (this[open] == '{' && getOrNull(before) == ')') before = lastNonWhitespace(openingParen(before) - 1)
    val end = before + 1
    while (before >= 0 && (this[before].isLetterOrDigit() || this[before] == '_')) before--
    return substring(before + 1, end).takeIf { it.isNotEmpty() && this[open] in "{(" }
}

/** The last index at or before [from] that is not whitespace, or -1. */
private fun String.lastNonWhitespace(from: Int): Int {
    var index = from
    while (index >= 0 && this[index].isWhitespace()) index--
    return index
}

/** A bare name at [index] that is neither the expression's [root], a member, nor an inline keyword. */
private fun String.bareNameAt(
    index: Int,
    root: Int,
): MatchResult? {
    val previous = getOrNull(index - 1)
    val startsName = index != root && (previous == null || !(previous.isLetterOrDigit() || previous in "_."))
    return bareName.matchAt(this, index)?.takeIf { startsName && it.value !in inlineWords }
}

/** The index of the parenthesis that opens the one closing at [close]. */
private fun String.openingParen(close: Int): Int {
    var depth = 0
    for (index in close downTo 0) {
        when (this[index]) {
            ')', '}', ']' -> depth++
            '(', '{', '[' -> if (--depth == 0) return index
        }
    }
    return 0
}

/**
 * The links appended by `.then(…)`. An optional branch is conditional; its nullable-source guard applies only
 * when the guarded arm is itself a plain, branch-free chain.
 */
private fun String.thenLinks(
    offset: Int,
    scope: LinkScope,
): Sequence<ModifierLink> {
    val optional = conditionalModifier.containsMatchIn(this)
    val guarded = if (optional && !scope.conditional) plainGuardedArm(offset) else null
    if (guarded != null) {
        // Only the validated arm is walked under the guard; its else arm appends nothing.
        val arm = LinkScope(conditional = true, nested = scope.nested, guard = guarded.first)
        val (start, text) = guarded.second
        return text.opaqueLinks(start, arm) + text.modifierLinks(start, arm)
    }
    val inner = LinkScope(scope.conditional || optional, scope.nested, if (optional) null else scope.guard)
    val branches = bracedModifierBranches().ifEmpty { listOf(TouchArgument(0, this)) }
    return branches.asSequence().flatMap { branch ->
        val start = offset + branch.offset
        branch.text.opaqueLinks(start, inner) + branch.text.modifierLinks(start, inner)
    }
}

/**
 * A `then` argument or branch arm must be written inline from `Modifier`. Any other name in it, such as a stored
 * modifier or a helper call, could shrink or discard what follows, so it becomes an opaque `then` link.
 */
private fun String.opaqueLinks(
    offset: Int,
    scope: LinkScope,
): Sequence<ModifierLink> =
    strayName
        .findAll(topLevelText())
        .filterNot { it.value in inlineWords }
        .map { ModifierLink(OPAQUE_LINK, it.value, offset + it.range.first, scope) }

/**
 * The guard and guarded arm of `if (source != null) <arm> else Modifier` when that arm, braced or not, is a plain
 * chain with no branch, nullable or logical operator or replacing scope call of its own.
 */
private fun String.plainGuardedArm(offset: Int): Pair<SourceGuard, TouchArgument>? {
    val source = sourcePresentGuard()
    val condition = ifCondition.find(this)?.takeIf { source != null } ?: return null
    val braced = bracedIfArms(condition.range.last).firstOrNull()
    val arm =
        if (braced != null) {
            TouchArgument(braced.first + 1, substring(braced.first + 1, braced.last))
        } else {
            val start = closingBracket(condition.range.last) + 1
            val rest = substring(start)
            val keyword = branchKeyword.find(rest.topLevelText())
            TouchArgument(start, if (keyword?.value == "else") rest.substring(0, keyword.range.first) else "")
        }
    val plain = arm.text.isNotBlank() && arm.text.firstTopLevelBranch() == arm.text.length
    return source?.takeIf { plain }?.let { SourceGuard(it, offset) to TouchArgument(offset + arm.offset, arm.text) }
}

/**
 * Whether the bracket at [open] only groups an expression: a parenthesis that no name, call or condition keyword
 * precedes, so it is neither a call's arguments, a condition nor a lambda.
 */
private fun String.groupsAt(open: Int): Boolean {
    var before = open - 1
    while (before >= 0 && this[before].isWhitespace()) before--
    val previous = getOrNull(before)
    return this[open] == '(' && (previous == null || (!previous.isLetterOrDigit() && previous !in "_)]}>"))
}

/** This text with every bracket's contents blanked, offsets unchanged, so only the top level remains. */
private fun String.topLevelText(): String {
    val topLevel = StringBuilder(this)
    var index = 0
    while (index < length) {
        if (this[index] in "({[") {
            val close = closingBracket(index)
            for (inner in index + 1 until minOf(close, length)) topLevel[inner] = ' '
            index = close
        }
        index++
    }
    return topLevel.toString()
}

/**
 * Where the expression starts to branch outside its brackets: 0 when a nullable or logical operator, or a
 * `let`, `run`, `takeIf` or `takeUnless` that can replace the chain, makes any call uncertain, the first branch
 * keyword otherwise, or past the end when the chain never branches.
 */
private fun String.firstTopLevelBranch(): Int {
    val topLevel = topLevelText()
    // `?:`, `?.`, `&&` and `||` make every call uncertain, and so does a scope call that can replace the chain.
    if (nullableOrLogicalOperator.containsMatchIn(topLevel) || replacingScopeCall.containsMatchIn(topLevel)) return 0
    return branchKeyword.find(topLevel)?.range?.first ?: length
}

/**
 * Whether this modifier expression keeps [root]: it is [root] itself, or [root] extended only by unconditional
 * standard modifier calls. A scope call such as `let` or `run`, `?:`, `?.`, a branch or any other call could replace
 * or drop it, so none of them counts.
 */
internal fun String.retainsRoot(root: String): Boolean {
    val text = trim()
    val links = text.modifierLinks().filterNot { it.nested }.toList()
    val chained =
        text.startsWith("$root.") &&
            text.firstTopLevelBranch() == text.length &&
            links.isNotEmpty() &&
            links.all { it.name in standardModifiers && !it.conditional }
    return text == root || chained
}

/**
 * Unwraps the arms of a modifier built by an if/else whose first arm is braced. An unbraced `else` arm runs to the
 * end of the expression, so it is kept whole rather than dropped.
 */
private fun String.bracedModifierBranches(): List<TouchArgument> {
    val condition = ifCondition.find(this) ?: return emptyList()
    val arms = bracedIfArms(condition.range.last)
    val braced = arms.map { arm -> TouchArgument(arm.first + 1, substring(arm.first + 1, arm.last)) }
    val unbracedElse =
        arms
            .singleOrNull()
            ?.let { elseKeyword.matchAt(this, it.last + 1) }
            ?.range
            ?.last
            ?.plus(1)
    return if (unbracedElse == null) braced else braced + TouchArgument(unbracedElse, substring(unbracedElse))
}

/** Reads the braced arms after a condition, keeping each arm's opening and closing braces. */
internal fun String.bracedIfArms(conditionOpen: Int): List<IntRange> {
    val body = indexOfNonWhitespace(closingBracket(conditionOpen) + 1)
    if (getOrNull(body) != '{') return emptyList()
    val end = closingBracket(body)
    val alternative = elseBlock.matchAt(this, end + 1)?.range?.last
    return listOfNotNull(body..end, alternative?.let { it..closingBracket(it) })
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
