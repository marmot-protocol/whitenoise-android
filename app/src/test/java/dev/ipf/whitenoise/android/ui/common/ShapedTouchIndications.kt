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

    /** Collects indications only from a modifier supplied to a component. */
    private fun appliedReplacements(owner: FeedbackOwner): List<FeedbackReference> {
        val args = code.substring(owner.open + 1, owner.close)
        val modifier = modifierArgument(args, positionalModifiers[owner.name]) ?: return emptyList()
        val offset = owner.open + 1 + modifier.offset
        val alias = modifierRoot.find(modifier.text)?.value
        val initializer = alias?.let { code.substring(0, offset).localBinding(it) }
        val links =
            initializer?.text.orEmpty().modifierLinks(initializer?.offset ?: 0) + modifier.text.modifierLinks(offset)
        var clipped = false
        return links
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
            (draws(owner, source) || forwards(owner, source))
    }

    /** Only feedback inside the receiving component's call or content can replace its click's indication. */
    private fun draws(
        owner: FeedbackOwner,
        source: FeedbackBinding,
    ): Boolean =
        replacements.any { (component, references) ->
            component.start in owner.start until owner.end &&
                references.any { reference ->
                    !reference.conditional && binding(reference.expression, reference.offset) == source
                }
        }

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
                replacements.any { (component, references) ->
                    component.start in function.body until function.end &&
                        references.any { reference ->
                            (!reference.conditional || reference.guardedSource == parameter) &&
                                binding(reference.expression, reference.offset) == FeedbackBinding(parameter, declared)
                        }
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

/** Finds component calls while excluding declarations, so a whole function never acts as one control. */
private fun String.feedbackOwners(): List<FeedbackOwner> =
    componentCall
        .findAll(this)
        .filterNot { match ->
            val before = substring(0, match.range.first).takeLast(COMPONENT_DECLARATION_LOOKBEHIND)
            componentDeclaration.containsMatchIn(before)
        }.map { match ->
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
