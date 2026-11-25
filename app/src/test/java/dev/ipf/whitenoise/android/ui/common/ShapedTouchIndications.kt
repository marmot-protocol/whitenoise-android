package dev.ipf.whitenoise.android.ui.common

private val componentCall = Regex("""\b([A-Z]\w*)\s*\(""")
private val componentDeclaration = Regex("""\b(fun|class|interface)\s*$""")
private const val COMPONENT_DECLARATION_LOOKBEHIND = 32
private val modifierRoot = Regex("""^[A-Za-z_]\w*(?!\w)(?!\s*\()""")
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
private val controlCondition = Regex("""\b(?:if|for|while)\s*\(""")
private val elseBranch = Regex("""\belse$""")
private val sourceReference = Regex("""[A-Za-z_]\w*(?:\.[A-Za-z_]\w*)*""")

/**
 * Replacement feedback belongs to the component receiving the click, rather than every similarly named
 * source in the file. Direct children may draw its feedback; a named parameter may hand the source to
 * that component's unique top-level implementation. Local declarations keep shadowed sources distinct.
 */
internal class ShapedTouchIndications(
    private val code: String,
) {
    private val owners = code.feedbackOwners()
    private val functions = code.feedbackFunctions()
    private val replacements by lazy {
        owners.mapNotNull { owner ->
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

    /** Reports applied targets that suppress their normal indication without drawing matching feedback. */
    fun unmatchedTouchTargets(): List<Int> {
        if ("shapedTouchTarget" !in code) return emptyList()
        return owners
            .flatMap { owner ->
                appliedModifierLinks(owner)
                    .filter { it.name == "shapedTouchTarget" }
                    .filterNot { link ->
                        val source = link.feedbackReference()?.let { binding(it.expression, it.offset) }
                        source != null && hasFeedback(owner, source, requireGuaranteedComposition = true)
                    }.map(ModifierLink::argumentOffset)
                    .toList()
            }.distinct()
    }

    /** Reads the links that actually reach this component, including a local modifier initializer. */
    private fun appliedModifierLinks(owner: FeedbackOwner): Sequence<ModifierLink> {
        val args = code.substring(owner.open + 1, owner.close)
        val modifier = modifierArgument(args, positionalModifiers[owner.name]) ?: return emptySequence()
        val offset = owner.open + 1 + modifier.offset
        val alias = modifierRoot.find(modifier.text)?.value
        val initializer = alias?.let { code.substring(0, offset).localBinding(it) }
        return initializer?.text.orEmpty().modifierLinks(initializer?.offset ?: 0) + modifier.text.modifierLinks(offset)
    }

    /** Collects indications only from a modifier supplied to a component. */
    private fun appliedReplacements(owner: FeedbackOwner): List<FeedbackReference> {
        var clipped = false
        return appliedModifierLinks(owner)
            .mapNotNull { link ->
                clipped = clipped || link.clipsToShape()
                when (link.name) {
                    "indication" -> link.feedbackReference().takeIf { clipped && link.hasIndication() }
                    "pressFeedbackInsideClip", "shapedPressFeedback" -> link.feedbackReference()
                    else -> null
                }
            }.toList()
    }

    /** A raw indication must actually draw, even if its modifier is clipped. */
    private fun ModifierLink.hasIndication(): Boolean {
        val factory = topLevelNamedArguments(args)["indication"] ?: topLevelArguments(args).getOrNull(1)
        return factory != null && factory.trim() != "null"
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
        requireGuaranteedComposition: Boolean = false,
    ): Boolean {
        val drawn = draws(owner, source, requireGuaranteedComposition)
        return drawn || forwards(owner, source, requireGuaranteedComposition)
    }

    /** Only feedback inside the receiving component's call or content can replace its click's indication. */
    private fun draws(
        owner: FeedbackOwner,
        source: FeedbackBinding,
        requireGuaranteedComposition: Boolean,
    ): Boolean =
        replacements.any { (component, references) ->
            component.start in owner.start until owner.end &&
                (!requireGuaranteedComposition || isComposedFrom(owner.open, component)) &&
                references.any { reference ->
                    !reference.conditional && binding(reference.expression, reference.offset) == source
                }
        }

    /** Follows a named source parameter into the called component, never an unrelated function's parameter. */
    private fun forwards(
        owner: FeedbackOwner,
        source: FeedbackBinding,
        requireGuaranteedComposition: Boolean,
    ): Boolean {
        val passed = topLevelNamedArguments(code.substring(owner.open + 1, owner.close))
        val function = componentImplementation(owner) ?: return false
        return passed.any { (parameter, argument) ->
            val declared = parameterBinding(function, parameter)
            binding(argument.trim(), owner.open) == source &&
                declared != null &&
                replacements.any { (component, references) ->
                    component.start in function.body until function.end &&
                        (!requireGuaranteedComposition || isComposedFrom(function.body, component)) &&
                        references.any { reference ->
                            (!reference.conditional || reference.guardedSource == parameter) &&
                                binding(reference.expression, reference.offset) == FeedbackBinding(parameter, declared)
                        }
                }
        }
    }

    /** The complete path to a feedback node must run through immediately composed layout content. */
    private fun isComposedFrom(
        start: Int,
        component: FeedbackOwner,
    ): Boolean {
        if (component.start < start) return true // Feedback on the receiving component itself.
        val scopes = code.openScopesAt(start, component.start)
        val parents = scopes.mapNotNull(composedContentScopes::get)
        val calls = parents.map(ContentCall::start) + component.start
        val branching = calls.any { it >= start && code.followsUnbracedBranch(start, it) }
        return parents.size == scopes.size && !branching && !canReturnBeforeFeedback(start, component.start, parents)
    }

    /** An early return can skip feedback even when both calls are in the same composed block. */
    private fun canReturnBeforeFeedback(
        from: Int,
        feedbackStart: Int,
        enclosingContent: List<ContentCall>,
    ): Boolean =
        returnStatement
            .findAll(code, from)
            .takeWhile { it.range.first < feedbackStart }
            .any { statement ->
                val offset = statement.range.first
                val label = statement.groups[1]?.value
                if (label == null) {
                    functions.none { it.body > from && offset in it.body until it.end }
                } else {
                    val scopes = code.openScopesAt(from, offset)
                    val destination = scopes.mapNotNull(composedContentScopes::get).lastOrNull { it.name == label }
                    destination != null && destination in enclosingContent
                }
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
private fun String.openScopesAt(
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
