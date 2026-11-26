package dev.ipf.whitenoise.android.ui.common

/**
 * Resolves rule 5's wrapper calls in one file through [ShapedTouchNames]. [code] is the file with comments and strings
 * blanked, and [shared] every function other files make visible (see [sharedFunctions]), by fully qualified name.
 */
internal class WrapperResolver(
    private val code: String,
    private val shared: Map<String, List<FunctionCandidate>>,
) {
    private val names = ShapedTouchNames(code)
    private val wrappers = code.wrapperDeclarations(visibleAcrossFilesOnly = false).associateBy { it.start }
    private val functions = code.declaredFunctions()

    /** Every name a call could use to reach a wrapper: shared wrappers' simple names, import aliases and own ones. */
    fun callNames(): Set<String> {
        val sharedWrappers = shared.filterValues { found -> found.any { it.modifierPositions != null } }.keys
        val ownWrappers = wrappers.values.map { it.name }
        return sharedWrappers.map { it.substringAfterLast('.') }.toSet() + names.aliases() + ownWrappers
    }

    /**
     * The `modifier` positions of the wrapper that a call of [name] at [nameStart] reaches, or null when it reaches
     * none. Kotlin tries each local function in scope, innermost first, then each
     * [function candidate][ShapedTouchNames.functionCandidates] in turn, this file's own declarations before another
     * file's, and skips a level whose functions the call cannot apply to. So a level holding a wrapper decides,
     * failing closed even when the call might not apply to it, and a level without one ends the search only when
     * [endsSearch] proves Kotlin would stop there.
     */
    fun reached(
        name: String,
        nameStart: Int,
        call: WrapperCall,
    ): Set<Int>? {
        val locals = names.localFunctionsAt(name, nameStart).map { start -> listOf(own(start)) }
        // A function the call surely cannot apply to, wrapper or not, is never what Kotlin picks, so it is dropped.
        val levels =
            (locals + names.functionCandidates(name).flatMap { levelsFor(it).toList() })
                .map { level -> level.filterNot(call::cannotReach) }
                .filter { it.isNotEmpty() }
        val decisive =
            levels.indices.firstOrNull { index ->
                val level = levels[index]
                level.any { it.modifierPositions != null } || endsSearch(level, levels.drop(index + 1).flatten(), call)
            }
        return decisive
            ?.let { levels[it] }
            ?.mapNotNull { it.modifierPositions }
            ?.flatten()
            ?.toSet()
            ?.takeIf { it.isNotEmpty() }
    }

    /**
     * Whether a level without a wrapper ends the search. One of its functions, declared without a receiver, must
     * [surely take][WrapperCall.surelyAppliesTo] the call, and Kotlin must prefer it to every wrapper [further] on:
     * the call [cannot apply to][WrapperCall.cannotApplyTo] that wrapper, or the function declares the wrapper's very
     * parameter types. Argument types the text cannot read never end the search, so an overload taking a `String`
     * where the wrapper takes a `Modifier` leaves the wrapper reachable.
     */
    private fun endsSearch(
        level: List<FunctionCandidate>,
        further: List<FunctionCandidate>,
        call: WrapperCall,
    ): Boolean {
        val wrappers = further.filter { it.modifierPositions != null }
        return wrappers.isEmpty() ||
            level.any { function ->
                val parameters = function.parameters
                // A receiver function applies only inside its receiver's scope, which the text cannot place.
                parameters != null &&
                    !function.hasReceiver &&
                    call.surelyAppliesTo(parameters) &&
                    wrappers.all { wrapper ->
                        val wrapped = wrapper.parameters
                        wrapped != null && (call.cannotReach(wrapper) || sameParameterTypes(parameters, wrapped))
                    }
            }
    }

    /** The levels one fully qualified [candidate] names: this file's top-level functions, then other files' ones. */
    private fun levelsFor(candidate: String): Sequence<List<FunctionCandidate>> {
        val simple = candidate.substringAfterLast('.')
        val own =
            if (candidate == names.inThisPackage(simple)) {
                functions.filter { it.name == simple && code.isTopLevelAt(it.start) }.map { own(it.start) }
            } else {
                emptyList()
            }
        return sequenceOf(own, shared[candidate].orEmpty()).filter { it.isNotEmpty() }
    }

    /** This file's function declared at [start], with its wrapper positions when it is a wrapper. */
    private fun own(start: Int): FunctionCandidate {
        val function = functions.firstOrNull { it.start == start }
        return FunctionCandidate(
            wrappers[start]?.let { setOf(it.modifierIndex) },
            function?.parameters,
            hasReceiver = function?.hasReceiver ?: true,
            typeParameters = function?.typeParameters,
        )
    }
}

/**
 * Every top-level function in [source] that other files can call (not `private`), by fully qualified name, with its
 * parameter list and, for a pass-through wrapper, its `modifier` position.
 */
internal fun sharedFunctions(source: String): List<Pair<String, FunctionCandidate>> {
    val code = blankCommentsAndStrings(source)
    val pkg = code.packageName()
    val wrappers = code.wrapperDeclarations(visibleAcrossFilesOnly = true).associateBy { it.start }
    return code
        .declaredFunctions()
        .filter { code.isFileVisible(it.start) }
        .map { function ->
            val positions = wrappers[function.start]?.let { setOf(it.modifierIndex) }
            val candidate =
                FunctionCandidate(positions, function.parameters, function.hasReceiver, function.typeParameters)
            pkg.qualify(function.name) to candidate
        }
}

/**
 * A function a call may reach: its wrapper `modifier` positions, if it is one, its parameter text, if known,
 * whether it is declared on a receiver, and how many type parameters it declares, if known.
 */
internal data class FunctionCandidate(
    val modifierPositions: Set<Int>?,
    val parameters: String?,
    val hasReceiver: Boolean,
    val typeParameters: Int? = null,
)

/**
 * A function declared in this code: where its `fun` starts, its name, its parameter list text, its receiver and
 * how many type parameters it declares.
 */
private data class DeclaredFunction(
    val start: Int,
    val name: String,
    val parameters: String,
    val hasReceiver: Boolean,
    val typeParameters: Int,
)

/** Every function declared in this code, top-level or local. */
private fun String.declaredFunctions(): List<DeclaredFunction> =
    enclosingFunction
        .findAll(this)
        .map { match ->
            val open = match.range.last
            val typeParameters = leadingTypeParameters.find(match.value)?.groupValues?.get(1)
            // Dots in type parameters, such as `<T : kotlin.Any>`, are not a receiver.
            val receiver = '.' in match.value.replace(leadingTypeParameters, "fun ")
            val parameters = substring(open + 1, closingBracket(open))
            val typeCount = typeParameters?.typeArgumentCount() ?: 0
            DeclaredFunction(match.range.first, match.groupValues[1], parameters, receiver, typeCount)
        }.toList()

/**
 * One call's arguments as text, and whether a trailing lambda follows them, enough to tell when the call surely
 * applies to a declaration, or surely cannot, by arity, names and the trailing lambda's slot.
 */
internal class WrapperCall(
    args: String,
    private val trailingLambda: Boolean,
    private val typeArguments: Int? = null,
) {
    private val arguments = topLevelArguments(args).filter { it.isNotBlank() }
    private val named = arguments.mapNotNull(::argumentName).toSet()
    private val positional = arguments.count { argumentName(it) == null }

    // A positional argument after a named one maps by position again, which counting alone cannot follow.
    private val mixed = arguments.dropWhile { argumentName(it) == null }.any { argumentName(it) == null }

    /** Whether this call surely applies to a function with [parameters] (its parameter list text); see [fit]. */
    fun surelyAppliesTo(parameters: String): Boolean = fit(parameters) == Fit.SURE

    /** Whether this call surely cannot apply to a function with [parameters]; see [fit]. */
    fun cannotApplyTo(parameters: String): Boolean = fit(parameters) == Fit.NEVER

    /**
     * Whether this call surely cannot reach [candidate]: explicit type arguments such as `Chip<Int>(…)` name exactly
     * as many type parameters as the function declares, and the arguments must [fit] its parameters.
     */
    fun cannotReach(candidate: FunctionCandidate): Boolean {
        val typeParameters = candidate.typeParameters
        val parameters = candidate.parameters
        val wrongTypeCount = typeArguments != null && typeParameters != null && typeArguments != typeParameters
        return wrongTypeCount || (parameters != null && cannotApplyTo(parameters))
    }

    /**
     * How surely this call fits a parameter list. It never fits when [fitsArity] fails. A `vararg`, a positional
     * argument after a named one, or a trailing lambda whose slot's type the text does not show as a function type,
     * leaves it unsure. Argument types are not read.
     */
    private fun fit(parameters: String): Fit {
        val declared = parameterList(parameters).map { it.declaredParameter() }
        val lambdaTyped = !trailingLambda || "->" in declared.lastOrNull()?.type.orEmpty()
        return when {
            mixed || declared.any { it.isVararg } -> Fit.UNSURE
            !fitsArity(declared) -> Fit.NEVER
            lambdaTyped -> Fit.SURE
            else -> Fit.UNSURE
        }
    }

    /**
     * Whether this call's arguments can fill [declared]: every named argument names a parameter that no positional
     * argument or trailing lambda already fills, the positional ones fit, and every parameter without a default gets
     * a value.
     */
    private fun fitsArity(declared: List<DeclaredParameter>): Boolean {
        val parameterNames = declared.map { it.name }
        val lambdaSlot = if (trailingLambda) declared.lastIndex else -1
        val slots = declared.size - if (trailingLambda) 1 else 0
        val filled = { index: Int -> index < positional || parameterNames[index] in named || index == lambdaSlot }
        // A named argument for a slot a positional argument or the trailing lambda already fills rules it out.
        val duplicated =
            parameterNames.take(positional).any { it in named } || parameterNames.getOrNull(lambdaSlot) in named
        return positional <= slots &&
            !duplicated &&
            named.all { it in parameterNames } &&
            declared.indices.all { index -> filled(index) || declared[index].hasDefault }
    }
}

/** The `<…>` type-parameter list right after `fun`, brackets included, in group 1. */
private val leadingTypeParameters = Regex("""^fun\s+($TYPE_PARAMETERS)""")

/**
 * How many entries the `<…>` type-argument or type-parameter list starting this text holds, counting commas outside
 * nested `<…>` lists and brackets; an arrow's `>` closes nothing. Null when the text does not start with a list,
 * such as a call without type arguments.
 */
internal fun String.typeArgumentCount(): Int? {
    var depth = 0
    var entries = 1
    var closed = false
    var index = 0
    while (startsWith('<') && !closed && index < length) {
        val char = this[index]
        when {
            char in "([{" -> index = closingBracket(index)
            char == '<' -> depth++
            char == '>' && getOrNull(index - 1) != '-' -> closed = --depth == 0
            char == ',' && depth == 1 -> entries++
        }
        index++
    }
    return entries.takeIf { closed }
}

/** How surely a call fits a parameter list: it surely does, surely does not, or the text cannot tell. */
private enum class Fit { SURE, NEVER, UNSURE }

/** Whether two parameter lists declare the same types in the same order, whatever their names and defaults. */
private fun sameParameterTypes(
    first: String,
    second: String,
): Boolean {
    val types = { parameters: String -> parameterList(parameters).map { it.declaredParameter().type } }
    return types(first) == types(second)
}
