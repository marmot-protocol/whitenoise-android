package dev.ipf.whitenoise.android.ui.common

/**
 * The one shape rule for every clip route of the touch gate. [code] is the file being scanned, [sharedShapes] the
 * top-level shape constants and object members of the whole scanned tree by fully qualified name (see
 * [topLevelShapeConstants] and [objectMemberValues]). Every name is resolved through the gate's one [ShapedTouchNames].
 */
internal class ShapedTouchShapes(
    private val code: String,
    private val sharedShapes: Map<String, String>,
) {
    private val names = ShapedTouchNames(code)

    /**
     * Whether [shape] is established as rounded: a plain stock rounded shape whose radii are proven positive, or a
     * capitalized shape constant that no local binding at [useOffset] shadows and that [isEstablishedConstant]
     * accepts. A variable, a branch, a rectangle, an unresolved name or a cycle stays unproven.
     */
    fun isKnownRounded(
        shape: String,
        useOffset: Int,
        seen: Set<String> = emptySet(),
    ): Boolean {
        val plain = shape.trim()
        val constant = shapeConstant.matchEntire(plain)?.value
        return when {
            !plain.isStockOrConstantShape() -> false
            plain.isStockRoundedShape { isProvenRadius(it, inFile = true) } -> true
            constant == null || constant in seen || names.localAt(constant, useOffset) != null -> false
            else -> isEstablishedConstant(constant, seen + constant)
        }
    }

    /**
     * Whether a shape constant is established through its single plain top-level `val` here, or, with none in this
     * file, through a whole stock rounded literal that Kotlin's import or package rules reach in another scanned
     * file. Any other binding of the name, anywhere in this file, could be what a use reads, so it stays unproven.
     */
    private fun isEstablishedConstant(
        constant: String,
        seen: Set<String>,
    ): Boolean {
        val definitions = plainTopLevelDefinitions(constant)
        // Kotlin reads an unimported name from this file's package and an imported one from the import's.
        val shared = names.valueCandidates(constant).firstNotNullOfOrNull { sharedShapes[it] }?.trim()
        val own = definitions.map { it.range.first }.toSet()
        return when {
            names.isBoundAnywhere(constant, except = own) || definitions.size > 1 -> false
            // An explicit or aliased import wins over this file's own top-level declaration.
            definitions.isNotEmpty() && names.isImported(constant) -> false
            definitions.isEmpty() -> shared?.isStockRoundedShape { isProvenRadius(it, inFile = false) } == true
            else -> isKnownRounded(code.initializerFrom(definitions.single().range.last + 1), 0, seen)
        }
    }

    /**
     * Whether a corner radius is proven positive: a positive literal; [inFile], a design token such as `Radii.md`
     * that [isProvenToken] accepts (never `Dp.Hairline`), or a simple name whose single plain top-level `val` here is
     * a positive literal; or, in a shared initializer, a token that its own file resolved to such a member (see
     * [topLevelShapeConstants]).
     */
    private fun isProvenRadius(
        value: String,
        inFile: Boolean,
    ): Boolean {
        val owner = qualifiedRadius.matchEntire(value)?.groupValues?.get(1)
        val simple = if (inFile && simpleRadius.matches(value)) plainTopLevelDefinitions(value).singleOrNull() else null
        return when {
            isPositiveRadius(value) -> true
            // A shared initializer arrives with each token its own file resolved marked as a full key; an unmarked
            // one stays unproven, even when a default-package object happens to share its spelling.
            !inFile -> value.startsWith(RESOLVED_MEMBER) && isPositiveRadius(sharedMemberValue(value).trim())
            owner != null -> inFile && isProvenToken(owner, value)
            simple != null ->
                !names.isBoundAnywhere(value, except = setOf(simple.range.first)) &&
                    !names.isImported(value) &&
                    isPositiveRadius(code.initializerFrom(simple.range.last + 1).trim())
            else -> false
        }
    }

    /**
     * Whether the `Owner.member` [value] is a positive member of the scanned object this file reaches as [owner]
     * (see [objectMemberKey]), with nothing in this file, such as a parameter, that could shadow the owner's name.
     */
    private fun isProvenToken(
        owner: String,
        value: String,
    ): Boolean {
        val shadowed = inFileDeclaration(owner).containsMatchIn(code) || names.isBoundAnywhere(owner)
        val member = names.objectMemberKey(value, sharedShapes)?.let(sharedShapes::get)
        return owner != "Dp" && !shadowed && isPositiveRadius(member.orEmpty().trim())
    }

    /** The value [members][sharedShapes] holds for a token that [topLevelShapeConstants] marked as resolved. */
    private fun sharedMemberValue(token: String): String = sharedShapes[token.removePrefix(RESOLVED_MEMBER)].orEmpty()

    /** The plain top-level `val Name = …` declarations of [name] in this file. */
    private fun plainTopLevelDefinitions(name: String): List<MatchResult> =
        Regex("""\bval\s+${Regex.escape(name)}\s*(?::\s*[\w.<>?]+\s*)?=(?!=)\s*""")
            .findAll(code)
            .filter { code.isTopLevel(it) }
            .toList()
}

/**
 * Whether `private` is among the declaration's modifiers, read backwards across spaces, line breaks and
 * annotations such as `@Stable`.
 */
private fun String.isPrivateDeclaration(declaration: MatchResult): Boolean {
    var end = declaration.range.first
    var word: String
    var annotation: Boolean
    do {
        while (end > 0 && this[end - 1].isWhitespace()) end--
        // A parameterized annotation such as `@Suppress("unused")`: step over its argument list to its name.
        if (getOrNull(end - 1) == ')') end = openingParenOf(end - 1)
        var start = end
        while (start > 0 && (this[start - 1].isLetterOrDigit() || this[start - 1] in "_.")) start--
        word = substring(start, end)
        annotation = getOrNull(start - 1) == '@'
        end = if (annotation) start - 1 else start
    } while (annotation || word in declarationModifiers)
    return word == "private"
}

/** The index of the parenthesis that opens the one closing at [close], or 0 when it never opens. */
private fun String.openingParenOf(close: Int): Int {
    var depth = 0
    for (index in close downTo 0) {
        when (this[index]) {
            ')' -> depth++
            '(' -> if (--depth == 0) return index
        }
    }
    return 0
}

/** The text directly inside the brace at [open], with every nested bracket's contents blanked. */
internal fun String.topLevelOf(open: Int): String {
    val close = closingBracket(open)
    val inner = StringBuilder(substring(open + 1, close.coerceAtMost(length)))
    var index = 0
    while (index < inner.length) {
        if (inner[index] in "({[") {
            val end = inner.toString().closingBracket(index)
            for (blank in index + 1 until minOf(end, inner.length)) inner[blank] = ' '
            index = end
        }
        index++
    }
    return inner.toString()
}

/** Any in-file declaration of [name]: an object, class, interface, typealias, property or function. */
private fun inFileDeclaration(name: String): Regex {
    val escaped = Regex.escape(name)
    return Regex("""\b(?:object|class|interface|typealias|val|var|fun)\s+$escaped\b""")
}

private val declarationModifiers = setOf("const", "internal", "public", "protected", "override", "open", "final")
private val qualifiedRadius = Regex("""([A-Z]\w*)\.\w+""")
private val simpleRadius = Regex("""[A-Z]\w*""")

/** Whether a declaration sits outside every brace, so the whole file can read it. */
internal fun String.isTopLevel(declaration: MatchResult): Boolean {
    val before = substring(0, declaration.range.first)
    return before.count { it == '{' } == before.count { it == '}' }
}

/** This file's package, or empty for a file without one. */
internal fun String.packageName(): String =
    packageDeclaration
        .find(this)
        ?.groupValues
        ?.get(1)
        .orEmpty()

/** [name] inside this package, or [name] alone in the default package. */
internal fun String.qualify(name: String): String = if (isEmpty()) name else "$this.$name"

private val packageDeclaration = Regex("""^\s*package\s+([\w.]+)""", RegexOption.MULTILINE)

/**
 * The plain `val` members of the non-private top-level objects across [sources], keyed `package.Object.member`, so a
 * design token such as `Radii.md` is resolved from its value rather than trusted by name. A key defined twice is
 * left out. Nested brackets in each initializer are blanked, since only a plain literal can prove a radius.
 */
internal fun objectMemberValues(sources: Collection<String>): Map<String, String> {
    val objectDeclaration = Regex("""\bobject\s+(\w+)\s*\{""")
    val member = Regex("""\bval\s+(\w+)\s*(?::\s*[\w.<>?]+\s*)?=(?!=)\s*""")
    val found =
        sources.flatMap { source ->
            val code = blankCommentsAndStrings(source)
            objectDeclaration
                .findAll(code)
                .filter { code.isTopLevel(it) && !code.isPrivateDeclaration(it) }
                .flatMap { match ->
                    val body = code.topLevelOf(match.range.last)
                    val owner = code.packageName().qualify(match.groupValues[1])
                    member.findAll(body).map { value ->
                        "$owner.${value.groupValues[1]}" to body.initializerFrom(value.range.last + 1)
                    }
                }.toList()
        }
    return found.groupBy({ it.first }, { it.second }).filterValues { it.size == 1 }.mapValues { it.value.single() }
}

/**
 * The key in [members] of the object member [value] (`Owner.member`) as this file reaches it: the owner's
 * [value candidates][ShapedTouchNames.valueCandidates], so an explicit or aliased import decides alone. Null when
 * none is a scanned member, so an owner imported from outside the scanned tree stays unproven.
 */
internal fun ShapedTouchNames.objectMemberKey(
    value: String,
    members: Map<String, String>,
): String? {
    val member = value.substringAfter('.')
    return valueCandidates(value.substringBefore('.')).map { "$it.$member" }.firstOrNull { it in members }
}

/**
 * [initializer] with each `Owner.member` token that this file reaches in [members] replaced by its full key, marked
 * with [RESOLVED_MEMBER] so a reader in another file can tell it from a token that resolved to nothing.
 */
private fun String.withMemberKeys(
    initializer: String,
    members: Map<String, String>,
): String =
    memberToken.replace(initializer) { token ->
        ShapedTouchNames(this).objectMemberKey(token.value, members)?.let { RESOLVED_MEMBER + it } ?: token.value
    }

/** Marks a shared initializer's radius token that its own file resolved to a scanned object member's full key. */
private const val RESOLVED_MEMBER = "@"

private val memberToken = Regex("""(?<![\w.])[A-Z]\w*\.\w+(?![\w.(])""")

/**
 * Non-private top-level shape constants across [sources] by fully qualified name, so a shape defined in another
 * file is resolved from its definition rather than trusted by name. A name defined twice is left out. Each
 * `Owner.member` radius its file reaches in [members] (see [objectMemberValues]) is written as that full key.
 */
internal fun topLevelShapeConstants(
    sources: Collection<String>,
    members: Map<String, String> = emptyMap(),
): Map<String, String> {
    val definition = Regex("""\bval\s+(${shapeConstant.pattern})\s*(?::\s*[\w.<>?]+\s*)?=\s*""")
    val found =
        sources.flatMap { source ->
            val code = blankCommentsAndStrings(source)
            definition
                .findAll(code)
                .filter { code.isTopLevel(it) && !code.isPrivateDeclaration(it) }
                .map { match ->
                    val initializer = code.initializerFrom(match.range.last + 1)
                    code.packageName().qualify(match.groupValues[1]) to code.withMemberKeys(initializer, members)
                }.toList()
        }
    // A name some scanned file also declares as an extension property may resolve to that instead.
    val extensions =
        Regex("""\b(?:val|var)\s+[^=\n]*?\.\s*(${shapeConstant.pattern})\b""")
            .findAll(sources.joinToString("\n") { blankCommentsAndStrings(it) })
            .map { it.groupValues[1] }
            .toSet()
    return found
        .groupBy({ it.first }, { it.second })
        .filterValues { it.size == 1 }
        .filterKeys { it.substringAfterLast('.') !in extensions }
        .mapValues { it.value.single() }
}
