package dev.ipf.whitenoise.android.ui.common

/**
 * The touch gate's one name resolver. It reads Kotlin with comments and string contents blanked (see
 * [blankCommentsAndStrings]) and says what a bare name can mean at one place, in Kotlin's order:
 *
 * 1. a local binding in scope ([localAt], [localFunctionsAt]): a parameter of an enclosing function or lambda, or a
 *    `val`, `var`, destructured entry, loop variable or local function declared earlier in a block still open there;
 * 2. a top-level declaration ([valueCandidates], [functionCandidates]): an explicit or aliased import, then this
 *    package, then each star import.
 *
 * What text alone cannot place, such as a class member used before its declaration or a constructor property, is
 * caught by [isBoundAnywhere], so a caller that must prove a value can fail closed.
 */
internal class ShapedTouchNames(
    private val code: String,
) {
    private val packageName = code.packageName()
    private val imports =
        importDirective
            .findAll(code)
            .map { ImportDirective(it.groupValues[1], it.groupValues[2].ifEmpty { null }) }
            .toList()
    private val functions =
        enclosingFunction
            .findAll(code)
            .mapNotNull { match ->
                val open = match.range.last
                val close = code.closingBracket(open)
                val body = code.indexOfAny(charArrayOf('{', '='), close + 1)
                if (body < 0) {
                    null
                } else {
                    val expressionEnd = body + 1 + code.initializerFrom(body + 1).length
                    val end = if (code[body] == '{') code.closingBracket(body) else expressionEnd
                    ScopedParameters(open, close, body..end)
                }
            }.toList()

    /**
     * Where the nearest binding of [name] visible at [offset] is declared, as the offset of the name in its
     * declaration, or null when the name is not local there. Top-level declarations are never local.
     */
    fun localAt(
        name: String,
        offset: Int,
    ): Int? {
        val prefix = code.substring(0, offset.coerceIn(0, code.length))
        val escaped = Regex.escape(name)
        val declared =
            localPatterns(escaped)
                .flatMap { it.findAll(prefix) }
                .mapNotNull { it.groups[1]?.range?.first }
                .filter { !code.isTopLevelAt(it) && prefix.blockStaysOpen(from = it) }
        val parameters = functions.filter { offset in it.body }.mapNotNull { parameterIn(it.open, it.close, name) }
        return (declared + parameters).maxOrNull()
    }

    /** Where each local `fun [name]` in scope at [offset] starts, innermost first. */
    fun localFunctionsAt(
        name: String,
        offset: Int,
    ): List<Int> {
        val prefix = code.substring(0, offset.coerceIn(0, code.length))
        return functionDeclaration(name)
            .findAll(prefix)
            .filter { !code.isTopLevelAt(it.range.first) && prefix.blockStaysOpen(from = it.range.first) }
            .map { it.range.first }
            .toList()
            .asReversed()
    }

    /** The offset of [name] in the parameter list between [open] and [close], or null when it declares none. */
    fun parameterIn(
        open: Int,
        close: Int,
        name: String,
    ): Int? =
        Regex("""\b${Regex.escape(name)}\s*:""")
            .find(code.substring(open + 1, close))
            ?.range
            ?.first
            ?.plus(open + 1)

    /**
     * The fully qualified names a top-level value or object [name] can mean here. An explicit or aliased import
     * decides alone, since what it names may lie outside the scanned tree; otherwise this package, then each star
     * import, and the first that exists wins.
     */
    fun valueCandidates(name: String): List<String> = explicitImport(name)?.let(::listOf) ?: implicitCandidates(name)

    /**
     * Every fully qualified name a call of [name] can reach here, in the order Kotlin tries them: an explicit or
     * aliased import, this package, then each star import. A call skips a level whose functions it cannot apply to,
     * so a caller must look past a declaration it cannot prove the call applies to.
     */
    fun functionCandidates(name: String): List<String> = listOfNotNull(explicitImport(name)) + implicitCandidates(name)

    /** The names this file's aliased imports introduce. */
    fun aliases(): List<String> = imports.mapNotNull { it.alias }

    /** Whether this file imports a declaration as [name], directly or under an alias. */
    fun isImported(name: String): Boolean = explicitImport(name) != null

    /** This file's package-qualified form of [name]. */
    fun inThisPackage(name: String): String = packageName.qualify(name)

    /**
     * Whether [name] is bound anywhere in this file other than at [except]: a `val` or `var` (members, delegated,
     * getter or `lateinit` ones included), a loop variable, a destructured name, a parameter of any function, lambda
     * (destructured ones included) or constructor, a type or function declaration, an extension property or an enum
     * entry.
     */
    fun isBoundAnywhere(
        name: String,
        except: Set<Int> = emptySet(),
    ): Boolean {
        val escaped = Regex.escape(name)
        val binding =
            Regex(
                """\b(?:val|var)\s+$escaped\b|\bfor\s*\(\s*$escaped\b|""" +
                    """\b(?:val|var|for)\s*\(\s*\(?[^)]*\b$escaped\b|""" +
                    """[(,]\s*$escaped\s*:|[{,]\s*$escaped\s*(?::[^,{}]*)?(?:,[^{}]*?)?->|""" +
                    // A destructured lambda parameter, such as `{ (name, _) -> … }`, binds the name too.
                    """[{,]\s*\([^)]*\b$escaped\b[^)]*\)\s*(?::[^{}]*?)?(?:,[^{}]*?)?->""",
            )
        val typeDeclaration = Regex("""\b(?:object|class|interface|typealias|fun)\s+$escaped\b""")
        // Any `.` before the name inside a declaration makes it an extension, whatever the receiver's spelling.
        val extensionProperty = Regex("""\b(?:val|var)\s+[^=\n]*?\.\s*$escaped\b""")
        return binding.findAll(code).any { it.range.first !in except } ||
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

    /** The target of an explicit import of [name], or of the import aliased to it, or null. */
    private fun explicitImport(name: String): String? =
        imports
            .firstOrNull { (path, alias) -> alias == name || (alias == null && path.endsWith(".$name")) }
            ?.path

    /** [name] in this package, then in each star-imported package. */
    private fun implicitCandidates(name: String): List<String> =
        listOf(inThisPackage(name)) +
            imports.filter { it.path.endsWith(".*") && it.alias == null }.map { "${it.path.removeSuffix(".*")}.$name" }
}

/** One import directive: its path, ending in `.*` for a star import, and its alias. */
private data class ImportDirective(
    val path: String,
    val alias: String?,
)

/** A function's parameter list bounds and the body range those parameters are visible in. */
private data class ScopedParameters(
    val open: Int,
    val close: Int,
    val body: IntRange,
)

private val importDirective =
    Regex("""^\s*import\s+([\w.]+(?:\.\*)?)(?:\s+as\s+(\w+))?\s*$""", RegexOption.MULTILINE)

/**
 * The local declaration forms of the escaped name, each capturing the name itself: `val`/`var`, a destructured
 * entry, a loop variable, a local function and a lambda parameter, plain or destructured.
 */
private fun localPatterns(escaped: String): List<Regex> =
    listOf(
        Regex("""\b(?:val|var)\s+($escaped)\b"""),
        Regex("""\b(?:val|var)\s*\([^)]*?\b($escaped)\b[^)]*\)"""),
        Regex("""\bfor\s*\(\s*($escaped)\b"""),
        Regex("""\bfor\s*\(\s*\([^)]*?\b($escaped)\b[^)]*\)"""),
        Regex("""\bfun\s+(?:[\w.<>]+\.)?($escaped)\s*\("""),
        Regex("""[{,]\s*($escaped)\s*(?::\s*[\w.<>?]+\s*)?(?:,[^{}]*?)?->"""),
        Regex("""[{,]\s*\([^)]*?\b($escaped)\b[^)]*\)\s*(?::[^{}]*?)?(?:,[^{}]*?)?->"""),
    )

/** A `fun [name](` declaration, with or without a receiver, matched from `fun` as [enclosingFunction] is. */
internal fun functionDeclaration(name: String) = Regex("""\bfun\s+(?:[\w.<>]+\.)?${Regex.escape(name)}\s*\(""")

/** Whether [index] sits outside every brace, at the top level of the file. */
internal fun String.isTopLevelAt(index: Int): Boolean {
    val before = substring(0, index)
    return before.count { it == '{' } == before.count { it == '}' }
}

/**
 * One declared parameter: its name without backticks, its type without whitespace, whether it has a default value
 * and whether it is a `vararg`. See [declaredParameter].
 */
internal data class DeclaredParameter(
    val name: String?,
    val type: String,
    val hasDefault: Boolean,
    val isVararg: Boolean,
)

/**
 * Reads this parameter text from its type's colon instead of parsing what comes before it: the default is the first
 * `=` outside every bracket, the type colon the last `:` outside every bracket before it, and the name the plain or
 * backticked identifier just before that colon. Annotations in any form, such as `@pkg .Hint`, `@[A B]`, `@param:Hint`
 * or `@Hint(x = 1)`, and modifiers such as `vararg` can therefore never hide the name or pass for a default. Without
 * such a colon, the name is null and the type empty.
 */
internal fun String.declaredParameter(): DeclaredParameter {
    val defaultAt = topLevelAssignment()
    val colon = lastTopLevelColon(defaultAt)
    val head = if (colon < 0) "" else substring(0, colon)
    val type = if (colon < 0) "" else substring(colon + 1, defaultAt).filterNot(Char::isWhitespace)
    val vararg = Regex("""\bvararg\b""").containsMatchIn(head)
    return DeclaredParameter(head.trailingName(), type, hasDefault = defaultAt < length, isVararg = vararg)
}

/** The index of the first `=` outside every bracket and type argument, or the length when there is none. */
internal fun String.topLevelAssignment(): Int =
    firstOrLastTopLevel(length, first = true) { index ->
        this[index] == '=' && getOrNull(index + 1) != '=' && getOrNull(index - 1)?.let { it in "=!<>" } != true
    } ?: length

/** The index of the last `:` outside every bracket before [end], or -1. */
private fun String.lastTopLevelColon(end: Int): Int =
    firstOrLastTopLevel(end, first = false) { index -> this[index] == ':' && getOrNull(index + 1) != ':' } ?: -1

/**
 * The first, or last, index before [end] outside every bracket and type argument where [matches] holds. Angle
 * brackets count only outside `(`, `[` and `{`, so a comparison inside an annotation's arguments, such as
 * `@Hint(a > b)`, cannot unbalance the type-argument depth; a `>` closes one unless it ends an arrow, `->`.
 */
private inline fun String.firstOrLastTopLevel(
    end: Int,
    first: Boolean,
    matches: (Int) -> Boolean,
): Int? {
    var brackets = 0
    var angles = 0
    var found: Int? = null
    for (index in 0 until end) {
        val char = this[index]
        when {
            char in "([{" -> brackets++
            char in ")]}" -> brackets--
            brackets == 0 && char == '<' -> angles++
            brackets == 0 && char == '>' && getOrNull(index - 1) != '-' -> angles--
            brackets == 0 && angles == 0 && matches(index) -> {
                found = index
                if (first) break
            }
        }
    }
    return found
}

/** The plain or backticked identifier this text ends with, ignoring trailing whitespace, without backticks; or null. */
private fun String.trailingName(): String? {
    val text = trimEnd()
    val start =
        if (text.endsWith('`')) {
            text.lastIndexOf('`', text.length - 2)
        } else {
            text.indexOfLast { !(it.isLetterOrDigit() || it == '_') } + 1
        }
    return text.substring(start.coerceAtLeast(0)).removeSurrounding("`").takeIf { it.isNotEmpty() }
}

/**
 * A function's parameter list split at top-level commas. A `<` opens a type argument list only where one can stand:
 * after a name in a parameter's type, or before a call or cast in its default value (see [typeArgumentEnd]). That
 * whole list is skipped, so its commas never split, while a comparison such as `width > 600` in a default, or `1 < 2`
 * inside an annotation's arguments, never changes the depth.
 */
internal fun parameterList(parameters: String): List<String> {
    val parts = mutableListOf<String>()
    var depth = 0
    var start = 0
    var inDefault = false
    var index = 0
    while (index < parameters.length) {
        val char = parameters[index]
        when {
            char in "({[" -> depth++
            char in ")}]" -> depth--
            depth == 0 && char == '<' -> index = parameters.typeArgumentsAt(index, inDefault) ?: index
            depth == 0 && char == '=' && parameters.isAssignmentAt(index) -> inDefault = true
            depth == 0 && char == ',' -> {
                parts += parameters.substring(start, index)
                start = index + 1
                inDefault = false
            }
        }
        index++
    }
    parts += parameters.substring(start)
    return parts.filter { it.isNotBlank() }
}

/** The closing `>` of the type argument list opened at [open], or null when that `<` is a comparison. */
private fun String.typeArgumentsAt(
    open: Int,
    inDefault: Boolean,
): Int? {
    val previous = getOrNull(open - 1)
    val afterName = previous != null && (previous.isLetterOrDigit() || previous == '_')
    return if (inDefault) typeArgumentEnd(open) else closingTypeArgument(open)?.takeIf { afterName }
}

/** Whether the `=` at [index] assigns, rather than belonging to `==`, `!=`, `<=` or `>=`. */
private fun String.isAssignmentAt(index: Int): Boolean {
    val partOfComparison = getOrNull(index - 1)?.let { it in "=!<>" } == true
    return getOrNull(index + 1) != '=' && !partOfComparison
}
