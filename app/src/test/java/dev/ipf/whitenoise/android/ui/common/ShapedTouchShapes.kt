package dev.ipf.whitenoise.android.ui.common

/**
 * The one shape rule for every clip route of the touch gate. [code] is the file being scanned, [sharedShapes] the
 * top-level shape constants of the whole scanned tree by fully qualified name, and [parameterNamed] whether a
 * function enclosing an offset declares a parameter with a given name.
 */
internal class ShapedTouchShapes(
    private val code: String,
    private val sharedShapes: Map<String, String>,
    private val parameterNamed: (name: String, useOffset: Int) -> Boolean,
) {
    /**
     * Whether [shape] is established as rounded: a plain stock rounded shape whose radii are proven positive, or a
     * capitalized shape constant that no enclosing parameter at [useOffset] shadows and that [isEstablishedConstant]
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
            constant == null || constant in seen || isShadowed(constant, useOffset) -> false
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
        val shared = sharedShapes[qualifiedName(constant)]?.trim()
        return when {
            hasOtherBinding(constant, definitions) || definitions.size > 1 -> false
            // An explicit or aliased import wins over this file's own top-level declaration.
            definitions.isNotEmpty() && importsName(constant) -> false
            definitions.isEmpty() -> shared?.isStockRoundedShape { isProvenRadius(it, inFile = false) } == true
            else -> isKnownRounded(code.initializerFrom(definitions.single().range.last + 1), 0, seen)
        }
    }

    /**
     * Whether a corner radius is proven positive: a positive literal, a member of an object another file defines
     * (a design token such as `Radii.md`, never `Dp.Hairline`), or, [inFile], a simple name whose single plain
     * top-level `val` here is a positive literal.
     */
    private fun isProvenRadius(
        value: String,
        inFile: Boolean,
    ): Boolean {
        val owner = qualifiedRadius.matchEntire(value)?.groupValues?.get(1)
        val simple = if (inFile && simpleRadius.matches(value)) plainTopLevelDefinitions(value).singleOrNull() else null
        return when {
            isPositiveRadius(value) -> true
            // A receiver declared in this file, in any form, could hold anything, so only another file's counts.
            owner != null -> owner != "Dp" && !inFileDeclaration(owner).containsMatchIn(code)
            simple != null ->
                !hasOtherBinding(value, listOf(simple)) &&
                    !importsName(value) &&
                    isPositiveRadius(code.initializerFrom(simple.range.last + 1).trim())
            else -> false
        }
    }

    /** The plain top-level `val Name = …` declarations of [name] in this file. */
    private fun plainTopLevelDefinitions(name: String): List<MatchResult> =
        Regex("""\bval\s+${Regex.escape(name)}\s*(?::\s*[\w.<>?]+\s*)?=(?!=)\s*""")
            .findAll(code)
            .filter { code.isTopLevel(it) }
            .toList()

    /**
     * Whether [name] is bound anywhere in this file other than by [definitions]: another `val` or `var` (members,
     * delegated, getter or `lateinit` ones included), a loop variable, a destructured name or a parameter.
     */
    private fun hasOtherBinding(
        name: String,
        definitions: List<MatchResult>,
    ): Boolean {
        val escaped = Regex.escape(name)
        val binding =
            Regex(
                """\b(?:val|var)\s+$escaped\b|\bfor\s*\(\s*$escaped\b|""" +
                    """\b(?:val|var|for)\s*\(\s*\(?[^)]*\b$escaped\b|""" +
                    """[(,]\s*$escaped\s*:|[{,]\s*$escaped\s*(?::[^,{}]*)?(?:,[^{}]*?)?->""",
            )
        val declared = definitions.map { it.range.first }.toSet()
        val typeDeclaration = Regex("""\b(?:object|class|interface|typealias|fun)\s+$escaped\b""")
        // Any `.` before the name inside a declaration makes it an extension, whatever the receiver's spelling.
        val extensionProperty = Regex("""\b(?:val|var)\s+[^=\n]*?\.\s*$escaped\b""")
        return binding.findAll(code).any { it.range.first !in declared } ||
            typeDeclaration.containsMatchIn(code) ||
            extensionProperty.containsMatchIn(code) ||
            isEnumEntry(name)
    }

    /** Whether any enum class in this file has an entry named [name], however the entries before it are written. */
    private fun isEnumEntry(name: String): Boolean {
        val entry = Regex("""(?<![\w.])${Regex.escape(name)}(?![\w])""")
        return Regex("""\benum\s+class\b""").findAll(code).any { match ->
            val open = code.indexOf('{', match.range.last)
            open >= 0 && entry.containsMatchIn(code.topLevelOf(open))
        }
    }

    /** Whether this file imports a declaration as [name], directly or under an alias. */
    private fun importsName(name: String): Boolean {
        val escaped = Regex.escape(name)
        return Regex("""^\s*import\s+(?:[\w.]+\.$escaped|[\w.]+\s+as\s+$escaped)\s*$""", RegexOption.MULTILINE)
            .containsMatchIn(code)
    }

    /** The fully qualified name [name] refers to here: its import, an aliased import's target, or this package. */
    private fun qualifiedName(name: String): String {
        val escaped = Regex.escape(name)
        val imported = Regex("""^\s*import\s+([\w.]+\.$escaped)\s*$""", RegexOption.MULTILINE).find(code)
        val aliased = Regex("""^\s*import\s+([\w.]+)\s+as\s+$escaped\s*$""", RegexOption.MULTILINE).find(code)
        return imported?.groupValues?.get(1) ?: aliased?.groupValues?.get(1) ?: code.packageName().qualify(name)
    }

    /** Whether a function or lambda parameter enclosing [useOffset] reuses the constant's [name]. */
    private fun isShadowed(
        name: String,
        useOffset: Int,
    ): Boolean = parameterNamed(name, useOffset) || lambdaParameterNamed(name, useOffset)

    /** Whether a lambda still open at [useOffset] names a parameter [name]. */
    private fun lambdaParameterNamed(
        name: String,
        useOffset: Int,
    ): Boolean {
        val prefix = code.substring(0, useOffset)
        val parameter = Regex("""[{,]\s*${Regex.escape(name)}\s*(?::\s*[\w.<>?]+\s*)?(?:,[^{}]*?)?->""")
        return parameter.findAll(prefix).any { prefix.blockStaysOpen(it.range.first + 1) }
    }
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
private fun String.topLevelOf(open: Int): String {
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
private fun String.packageName(): String =
    packageDeclaration
        .find(this)
        ?.groupValues
        ?.get(1)
        .orEmpty()

/** [name] inside this package, or [name] alone in the default package. */
private fun String.qualify(name: String): String = if (isEmpty()) name else "$this.$name"

private val packageDeclaration = Regex("""^\s*package\s+([\w.]+)""", RegexOption.MULTILINE)

/**
 * Non-private top-level shape constants across [sources] by fully qualified name, so a shape defined in another
 * file is resolved from its definition rather than trusted by name. A name defined twice is left out.
 */
internal fun topLevelShapeConstants(sources: Collection<String>): Map<String, String> {
    val definition = Regex("""\bval\s+(${shapeConstant.pattern})\s*(?::\s*[\w.<>?]+\s*)?=\s*""")
    val found =
        sources.flatMap { source ->
            val code = blankCommentsAndStrings(source)
            definition
                .findAll(code)
                .filter { code.isTopLevel(it) && !code.isPrivateDeclaration(it) }
                .map { match ->
                    code.packageName().qualify(match.groupValues[1]) to code.initializerFrom(match.range.last + 1)
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
