package dev.ipf.whitenoise.android.ui.common

private val componentCall = Regex("""\b([A-Z]\w*)\s*\(""")
private val componentDeclaration = Regex("""\b(fun|class|interface)\s*$""")
private const val COMPONENT_DECLARATION_LOOKBEHIND = 32
private val touchTargetCall = Regex("""\bshapedTouchTarget\s*\(""")
private val extensionDeclaration = Regex("""\bfun\s+(?:[\w.<>]+\.)?$""")
private const val EXTENSION_DECLARATION_LOOKBEHIND = 48
internal val drawingComponents =
    setOf("Box", "BoxWithConstraints", "Column", "Icon", "Image", "Row", "Spacer", "Surface", "Text")
private val targetReference = Regex("""::\s*shapedTouchTarget\b""")

// Standard modifiers that keep what comes before them; a stored or helper modifier is not among them.
internal val standardModifiers =
    setOf(
        "align",
        "alpha",
        "aspectRatio",
        "background",
        "border",
        "clearAndSetSemantics",
        "clickable",
        "clip",
        "combinedClickable",
        "defaultMinSize",
        "drawBehind",
        "drawWithContent",
        "fillMaxHeight",
        "fillMaxSize",
        "fillMaxWidth",
        "focusable",
        "graphicsLayer",
        "height",
        "heightIn",
        "indication",
        "matchParentSize",
        "minimumInteractiveComponentSize",
        "offset",
        "onGloballyPositioned",
        "onSizeChanged",
        "padding",
        "pointerInput",
        "pressFeedbackInsideClip",
        "requiredHeight",
        "requiredSize",
        "requiredWidth",
        "selectable",
        "semantics",
        "shadow",
        "shapedPressFeedback",
        "shapedTouchTarget",
        "size",
        "sizeIn",
        "testTag",
        "toggleable",
        "weight",
        "width",
        "widthIn",
        "wrapContentHeight",
        "wrapContentSize",
        "wrapContentWidth",
        "zIndex",
    )
private val renamedTargetImport = Regex("""\bimport\s+[\w.]*\bshapedTouchTarget\s+as\b""")
private val uncertainFactory = Regex("""\bnull\b|\b(?:if|when)\b|\?:|\?\.""")
private val positionalModifiers =
    mapOf(
        "Box" to 0,
        "BoxWithConstraints" to 0,
        "Card" to 0,
        "Column" to 0,
        "ElevatedCard" to 0,
        "Icon" to 2,
        "IconButton" to 1,
        "IconToggleButton" to 2,
        "Image" to 2,
        "OutlinedCard" to 0,
        "Row" to 0,
        "Spacer" to 0,
        "Surface" to 0,
        "Text" to 1,
    )
private val composedContentOwners =
    setOf("Box", "BoxWithConstraints", "Row", "Column", "Surface", "CompositionLocalProvider")
private val bareContentCall = Regex("""\b(${composedContentOwners.joinToString("|")})\s*\{""")
private val qualifiedReceiver = Regex("""(?:[A-Za-z_]\w*\.)+$""")
private val returnStatement = Regex("""\breturn(?:@(\w+))?\b""")
private val branchCondition = Regex("""\bif\s*\(""")
private val controlCondition = Regex("""\b(?:if|for|while)\s*\(""")

// Modifiers that draw or describe without changing size, so a ripple after them still fills the clip.
private val drawOnlyModifiers =
    setOf("alpha", "background", "border", "clearAndSetSemantics", "drawBehind", "semantics", "testTag")
private val freshModifierChain = Regex("""^\s*Modifier\s*\.""")
private val elseBranch = Regex("""(?:\belse|\?:|&&|\|\||\?\.)$""")
private val sourceReference = Regex("""[A-Za-z_]\w*(?:\.[A-Za-z_]\w*)*""")

/**
 * Replacement feedback belongs to the component receiving the click, rather than every similarly named
 * source in the file. Direct children may draw its feedback; a named parameter may hand the source to
 * that component's unique top-level implementation. Local declarations keep shadowed sources distinct.
 */
internal class ShapedTouchIndications(
    private val code: String,
    private val sharedShapes: Map<String, String> = emptyMap(),
) {
    private val owners = code.feedbackOwners()
    private val shapes = ShapedTouchShapes(code, sharedShapes, ::parameterNamed)
    private val functions = code.feedbackFunctions()
    private val replacements by lazy {
        // Only a known layout component draws its modifier; any other uppercase call could be a stored value.
        owners.filter { it.name in drawingComponents }.mapNotNull { owner ->
            appliedReplacements(owner).takeIf { it.isNotEmpty() }?.let { owner to it }
        }
    }

    private val composedContentScopes by lazy {
        val arguments = owners.filter { it.name in composedContentOwners }.flatMap { it.contentScopeOpenings() }
        val bare =
            bareContentCall.findAll(code).filterNot { code.isDeclaration(it.range.first) }.map { match ->
                match.range.last to ContentCall(match.groupValues[1], match.range.first)
            }
        (arguments.asSequence() + bare).toMap()
    }

    private val exhaustiveBranches by lazy {
        branchCondition
            .findAll(code)
            .mapNotNull { condition ->
                val arms = code.bracedIfArms(condition.range.last)
                if (arms.size == 2) ExhaustiveBranch(condition.range.first, arms) else null
            }.toList()
    }

    /**
     * Reports every touch target that suppresses its normal indication without matching feedback. Every
     * `shapedTouchTarget(…)` call in the file counts, so one that no component's modifier can be shown to
     * receive, such as in an extension function, a parameter default, a reassigned `var` or an unused value,
     * is reported rather than skipped.
     */
    fun unmatchedTouchTargets(): List<Int> {
        if ("shapedTouchTarget" !in code) return emptyList()
        val applied =
            owners.flatMap { owner ->
                // A target nested in another call's arguments or a lambda may be discarded, so it reaches nothing.
                (appliedModifierLinks(owner) + otherArgumentLinks(owner))
                    .filter { it.name == "shapedTouchTarget" && !it.nested }
                    .map { owner to it }
                    .toList()
            }
        val unmatched =
            applied
                .filterNot { (owner, link) ->
                    val source = link.feedbackReference()?.let { binding(it.expression, it.offset) }
                    source != null && hasFeedback(owner, source)
                }.map { (_, link) -> link.argumentOffset }
        val reached = applied.map { (_, link) -> link.argumentOffset }.toSet()
        val unreached =
            touchTargetCall
                .findAll(code)
                .filterNot(::declaresTarget)
                .map { it.range.last + 1 }
                .filterNot { it in reached }
        val renamed = (renamedTargetImport.findAll(code) + targetReference.findAll(code)).map { it.range.first }
        return (unmatched + unreached + renamed).distinct()
    }

    /**
     * Links reached through the component's other arguments, such as a modifier passed by position to a component
     * this scanner does not know. They never prove feedback, but a target among them must still find it.
     */
    private fun otherArgumentLinks(owner: FeedbackOwner): Sequence<ModifierLink> {
        val args = code.substring(owner.open + 1, owner.close)
        val modifier = modifierArgument(args, positionalModifiers[owner.name])
        return topLevelArgumentValues(args)
            .asSequence()
            .filterNot { it.offset == modifier?.offset || it.text.startsWith("{") }
            .flatMap { argument ->
                val start = owner.open + 1 + argument.offset
                argument.text.modifierLinks(start, LinkScope(conditional = true))
            }
    }

    /** Whether this match is the `fun Modifier.shapedTouchTarget(` declaration itself rather than a call. */
    private fun declaresTarget(call: MatchResult): Boolean {
        val window = maxOf(0, call.range.first - EXTENSION_DECLARATION_LOOKBEHIND)
        return extensionDeclaration.containsMatchIn(code.substring(window, call.range.first))
    }

    /** Reads every link in the modifier written inline for this component; a named modifier value is not expanded. */
    private fun appliedModifierLinks(owner: FeedbackOwner): Sequence<ModifierLink> {
        val args = code.substring(owner.open + 1, owner.close)
        val modifier = modifierArgument(args, positionalModifiers[owner.name]) ?: return emptySequence()
        return modifier.text.modifierLinks(owner.open + 1 + modifier.offset)
    }

    /**
     * Collects replacement feedback only from a modifier written for a component. A ripple that does not clip
     * itself (`indication`, `pressFeedbackInsideClip`) counts only after a shaped clip that always applies, or that
     * sits in the same plain guarded arm, with only draw-only links between them, so nothing can shrink the ripple;
     * or as the first link of a chain rooted at `Modifier` on a direct child of a rounded Surface.
     */
    private fun appliedReplacements(owner: FeedbackOwner): List<FeedbackReference> {
        val chain = appliedModifierLinks(owner).filterNot { it.nested }.toList()
        // Only a ripple that starts a fresh chain has the Surface child's full, clipped size.
        val fillsSurface by lazy { sitsInShapedSurface(owner) && appliedModifierText(owner).startsWithModifier() }
        return chain.mapIndexedNotNull { index, link ->
            val clipped = link.clipBefore(chain, index)
            val surface = index == 0 && fillsSurface
            // A stored or helper modifier after the feedback could discard it, so only known modifiers may follow.
            val kept = chain.drop(index + 1).all { it.name in standardModifiers }
            if (!kept) return@mapIndexedNotNull null
            when (link.name) {
                "indication" -> link.feedbackReference().takeIf { (clipped || surface) && link.hasIndication() }
                "pressFeedbackInsideClip" -> link.feedbackReference().takeIf { clipped || surface }
                "shapedPressFeedback" -> link.feedbackReference().takeIf { link.hasShapedArgument() }
                else -> null
            }
        }
    }

    /** Whether [clip] is a rounded clip that is certain wherever this link applies. */
    private fun ModifierLink.followsClip(clip: ModifierLink): Boolean =
        clip.name == "clip" &&
            shapes.isKnownRounded(clip.args, clip.argumentOffset) &&
            (!clip.conditional || (clip.scope.guard != null && clip.scope.guard == scope.guard))

    /** The shaped clip this ripple fills: the nearest earlier one with only draw-only links between them. */
    private fun ModifierLink.clipBefore(
        chain: List<ModifierLink>,
        index: Int,
    ): Boolean {
        val earlier = chain.subList(0, index).asReversed()
        val clip =
            earlier.indexOfFirst { it.name != "clip" && it.name !in drawOnlyModifiers }.let { stop ->
                earlier.take(if (stop < 0) earlier.size else stop).firstOrNull { it.name == "clip" }
            }
        return clip != null && followsClip(clip)
    }

    /** The modifier argument text written for this component, or empty when it has none. */
    private fun appliedModifierText(owner: FeedbackOwner): String =
        modifierArgument(code.substring(owner.open + 1, owner.close), positionalModifiers[owner.name])?.text.orEmpty()

    /** Whether a modifier expression starts a fresh chain on `Modifier` rather than extending a parameter. */
    private fun String.startsWithModifier(): Boolean = freshModifierChain.containsMatchIn(this)

    /** `shapedPressFeedback` clips to its own shape argument, which must be established as rounded. */
    private fun ModifierLink.hasShapedArgument(): Boolean {
        val shape = topLevelNamedArguments(args)["shape"] ?: topLevelArguments(args).getOrNull(1)
        return shape != null && shapes.isKnownRounded(shape, argumentOffset)
    }

    /**
     * Whether the component is a direct child of a Surface whose shape is established as rounded. Material's
     * Surface passes its minimum size to its content, so that child fills the clipped shape; a card's content
     * wraps, so cards do not count.
     */
    private fun sitsInShapedSurface(owner: FeedbackOwner): Boolean {
        val parent = code.openScopesAt(0, owner.start).lastOrNull()?.let(composedContentScopes::get)
        val surface = owners.firstOrNull { it.start == parent?.start && it.name == "Surface" } ?: return false
        val shape = topLevelNamedArguments(code.substring(surface.open + 1, surface.close))["shape"]?.trim()
        return shape != null && shapes.isKnownRounded(shape, surface.open)
    }

    /** Whether a function enclosing [useOffset] declares a parameter named [name]. */
    private fun parameterNamed(
        name: String,
        useOffset: Int,
    ): Boolean = functions.any { useOffset in it.body until it.end && parameterBinding(it, name) != null }

    /** A raw indication must always draw, even if its modifier is clipped: no null or conditional factory. */
    private fun ModifierLink.hasIndication(): Boolean {
        val factory = topLevelNamedArguments(args)["indication"] ?: topLevelArguments(args).getOrNull(1)
        return factory != null && !uncertainFactory.containsMatchIn(factory)
    }

    /** Reads the source observed by a replacement at the modifier's declaration location. */
    private fun ModifierLink.feedbackReference(): FeedbackReference? {
        val source = topLevelNamedArguments(args)["interactionSource"] ?: topLevelArguments(args).firstOrNull()
        return source?.trim()?.let { FeedbackReference(it, argumentOffset, conditional, guardedSource) }
    }

    /** A null indication passes only when this control draws feedback for this click's source binding. */
    fun replaces(
        clickOffset: Int,
        arguments: String,
    ): Boolean {
        val named = topLevelNamedArguments(arguments)
        val source = named["interactionSource"]?.trim()?.let { binding(it, clickOffset) }
        val owner = owners.filter { clickOffset in (it.open + 1) until it.close }.maxByOrNull { it.open }
        return named["indication"]?.trim() == "null" &&
            source != null &&
            owner != null &&
            hasFeedback(owner, source)
    }

    /** A control can draw its own feedback or pass the source to its implementation by name. */
    private fun hasFeedback(
        owner: FeedbackOwner,
        source: FeedbackBinding,
    ): Boolean = hasDrawnFeedback(owner.start until owner.end, owner.open, source) || forwards(owner, source)

    /** Follows a named source parameter into the called component, never an unrelated function's parameter. */
    private fun forwards(
        owner: FeedbackOwner,
        source: FeedbackBinding,
    ): Boolean {
        val passed = topLevelNamedArguments(code.substring(owner.open + 1, owner.close))
        val function = componentImplementation(owner) ?: return false
        return passed.any { (parameter, argument) ->
            val declared = parameterBinding(function, parameter)
            binding(argument.trim(), owner.open) == source &&
                declared != null &&
                hasDrawnFeedback(
                    function.body until function.end,
                    function.body,
                    FeedbackBinding(parameter, declared),
                    guardedParameter = parameter,
                )
        }
    }

    /** Finds feedback that observes the exact binding, then proves the matching nodes are actually composed. */
    private fun hasDrawnFeedback(
        scope: IntRange,
        compositionStart: Int,
        source: FeedbackBinding,
        guardedParameter: String? = null,
    ): Boolean {
        val matching =
            replacements.mapNotNull { (component, references) ->
                component.start.takeIf { offset ->
                    offset in scope &&
                        references.any { reference ->
                            val presentGuard = guardedParameter != null && reference.guardedSource == guardedParameter
                            (!reference.conditional || presentGuard) &&
                                binding(reference.expression, reference.offset) == source
                        }
                }
            }
        return hasFeedbackOnAllPaths(compositionStart, matching)
    }

    /** Accepts an exhaustive branch only when each of its arms independently draws matching feedback. */
    private fun hasFeedbackOnAllPaths(
        start: Int,
        matching: List<Int>,
        activeArms: List<IntRange> = emptyList(),
    ): Boolean {
        if (matching.isEmpty()) return false
        return matching.any { isComposedFrom(start, it, activeArms) } ||
            exhaustiveBranches.any { branch ->
                val byArm = branch.arms.map { arm -> matching.filter { it in arm } }
                byArm.all { it.isNotEmpty() } &&
                    isComposedFrom(start, branch.start, activeArms) &&
                    branch.arms.zip(byArm).all { (arm, candidates) ->
                        hasFeedbackOnAllPaths(start, candidates, activeArms.plusElement(arm))
                    }
            }
    }

    /** The path must run through immediately composed layout content and arms already proved exhaustive. */
    private fun isComposedFrom(
        start: Int,
        offset: Int,
        activeArms: List<IntRange>,
    ): Boolean {
        if (offset < start) return true // Feedback on the receiving component itself.
        val scopes = code.openScopesAt(start, offset).filterNot { open -> activeArms.any { it.first == open } }
        val parents = scopes.mapNotNull(composedContentScopes::get)
        val calls = parents.map(ContentCall::start) + offset
        val branching = calls.any { it >= start && code.followsUnbracedBranch(start, it) }
        return parents.size == scopes.size && !branching && !canReturnBeforeFeedback(start, offset, activeArms)
    }

    /**
     * An early return can skip feedback even when both calls are in the same composed block. A plain `return`
     * leaves the whole function. A labeled one leaves the lambda or function its label names, so it skips the
     * feedback whenever that scope still encloses it: `return@Pill` or `return@forEach` around the feedback counts,
     * inside any `if` or `run` braces, while `return@clickable` in a click handler that closed before it does not.
     */
    private fun canReturnBeforeFeedback(
        from: Int,
        feedbackStart: Int,
        activeArms: List<IntRange>,
    ): Boolean {
        val inactiveArms =
            exhaustiveBranches
                .filter { branch -> branch.arms.any { it in activeArms } }
                .flatMap { branch -> branch.arms.filterNot { it in activeArms } }
        return returnStatement
            .findAll(code, from)
            .takeWhile { it.range.first < feedbackStart }
            .filterNot { statement -> inactiveArms.any { statement.range.first in it } }
            .any { statement ->
                val offset = statement.range.first
                val label = statement.groups[1]?.value
                if (label == null) {
                    functions.none { it.body > from && offset in it.body until it.end }
                } else {
                    labeledScopeEncloses(label, offset, feedbackStart)
                }
            }
    }

    /**
     * Whether the innermost scope open at [returnOffset] that [label] names, a lambda passed to that call or the
     * body of that function, still encloses [feedbackStart]. An unresolved label counts, so the rule fails closed.
     */
    private fun labeledScopeEncloses(
        label: String,
        returnOffset: Int,
        feedbackStart: Int,
    ): Boolean {
        val named = code.openScopesAt(0, returnOffset).lastOrNull { code.scopeName(it) == label }
        return named == null || code.closingBracket(named) >= feedbackStart
    }

    /** Knows the trailing and named content blocks that these layout functions compose immediately. */
    private fun FeedbackOwner.contentScopeOpenings(): List<Pair<Int, ContentCall>> {
        val trailing = code.skipFeedbackWhitespace(close + 1)
        val args = code.substring(open + 1, close)
        val named = namedArgumentValue(args, "content")
        val call = ContentCall(name, start)
        return buildList {
            if (trailing < end) add(trailing to call)
            if (named != null && named.text.startsWith("{")) {
                add(open to call)
                add(open + 1 + named.offset to call)
            }
        }
    }

    /** Ambiguous overloads, receivers and nested declarations cannot prove a named component hand-off. */
    private fun componentImplementation(owner: FeedbackOwner): FeedbackFunction? =
        functions.singleOrNull { it.name == owner.name }?.takeIf { function ->
            val before = code.substring(0, function.open)
            val topLevel = before.count { it == '{' } == before.count { it == '}' }
            val plain = Regex("""\bfun\s+${Regex.escape(owner.name)}\s*$""").containsMatchIn(before)
            topLevel && plain && code.getOrNull(owner.start - 1) != '.'
        }

    /** Resolves ordinary source references to their nearest still-open local or function parameter declaration. */
    private fun binding(
        expression: String,
        offset: Int,
    ): FeedbackBinding? {
        if (!sourceReference.matches(expression)) return null
        val name = expression.substringBefore('.')
        val prefix = code.substring(0, offset)
        val local =
            Regex("""\b(?:val|var)\s+${Regex.escape(name)}\b""")
                .findAll(prefix)
                .lastOrNull { prefix.blockStaysOpen(it.range.last + 1) }
                ?.range
                ?.first ?: -1
        val parameter =
            functions
                .filter { offset in it.body until it.end }
                .mapNotNull { parameterBinding(it, name) }
                .maxOrNull() ?: -1
        return FeedbackBinding(expression, maxOf(local, parameter))
    }

    /** The declaration offset distinguishes parameters with the same name in different component functions. */
    private fun parameterBinding(
        function: FeedbackFunction,
        name: String,
    ): Int? =
        Regex("""\b${Regex.escape(name)}\s*:""")
            .find(code.substring(function.open + 1, function.close))
            ?.range
            ?.first
            ?.plus(function.open + 1)
}

/** Collects the enclosing syntax scopes between the start of a composition and a candidate call. */
internal fun String.openScopesAt(
    from: Int,
    offset: Int,
): List<Int> {
    val openings = mutableListOf<Int>()
    for (index in from until offset) {
        when (this[index]) {
            '(', '{', '[' -> openings.add(index)
            ')', '}', ']' -> if (openings.isNotEmpty()) openings.removeAt(openings.lastIndex)
        }
    }
    return openings
}

/** A single-expression control-flow branch can skip a component without enclosing it in braces. */
private fun String.followsUnbracedBranch(
    from: Int,
    call: Int,
): Boolean {
    val before = substring(from, call).replace(qualifiedReceiver, "").trimEnd()
    val end = before.lastIndex
    val closesCondition =
        before.endsWith(')') &&
            controlCondition.findAll(before).any {
                before.closingBracket(it.range.last) == end
            }
    return elseBranch.containsMatchIn(before) || closesCondition
}

/** An if/else whose two explicit arms can be proved separately. */
private data class ExhaustiveBranch(
    val start: Int,
    val arms: List<IntRange>,
)

/** Identifies the layout call that owns a known composed content block. */
private data class ContentCall(
    val name: String,
    val start: Int,
)

/** One component call, including its trailing content lambda when present. */
private data class FeedbackOwner(
    val name: String,
    val start: Int,
    val open: Int,
    val close: Int,
    val end: Int,
)

/** A function's parameters and body, used only for a component's explicit named source hand-off. */
private data class FeedbackFunction(
    val name: String,
    val open: Int,
    val close: Int,
    val body: Int,
    val end: Int,
)

/** One replacement indication and the reference it observes at that source location. */
private data class FeedbackReference(
    val expression: String,
    val offset: Int,
    val conditional: Boolean,
    val guardedSource: String?,
)

/** The reference text and lexical declaration identify the source, instead of its name alone. */
private data class FeedbackBinding(
    val expression: String,
    val declaration: Int,
)

/** Excludes class and function declarations when identifying calls. */
private fun String.isDeclaration(start: Int): Boolean {
    val before = substring(maxOf(0, start - COMPONENT_DECLARATION_LOOKBEHIND), start)
    return componentDeclaration.containsMatchIn(before)
}

/** Finds component calls while excluding declarations, so a whole function never acts as one control. */
private fun String.feedbackOwners(): List<FeedbackOwner> =
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
private fun String.feedbackFunctions(): List<FeedbackFunction> =
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
private fun String.skipFeedbackWhitespace(from: Int): Int {
    var index = from
    while (index < length && this[index].isWhitespace()) index++
    return index
}
