package dev.ipf.whitenoise.android.audio.tts.speech

object CodeSpeech {
    private val symbols =
        linkedMapOf(
            "!=" to "not equal",
            "==" to "equal to",
            "<=" to "less than or equal",
            ">=" to "greater than or equal",
            "++" to "increment",
            "--" to "decrement",
            "&&" to "and",
            "||" to "or",
            "->" to "arrow",
            "=>" to "arrow",
            "=" to "equals",
            "+" to "plus",
            "-" to "minus",
            "*" to "asterisk",
            "/" to "slash",
            "." to "dot",
            "," to "comma",
            ";" to "semicolon",
            ":" to "colon",
            "(" to "open parenthesis",
            ")" to "close parenthesis",
            "[" to "open bracket",
            "]" to "close bracket",
            "{" to "open brace",
            "}" to "close brace",
            "\"" to "quote",
            "'" to "single quote",
            "$" to "dollar",
            "\\" to "backslash",
            "<" to "less than",
            ">" to "greater than",
            "!" to "exclamation mark",
            "#" to "hash",
            "_" to "underscore",
            "|" to "vertical bar",
            "&" to "ampersand",
            "%" to "percent",
            "?" to "question mark",
            "`" to "backtick",
            "~" to "tilde",
            "^" to "caret",
            "@" to "at",
        )
    private val tokens =
        Regex(
            "0x[0-9A-Fa-f]+|0b[01]+|[0-9]+(?:\\.[0-9]+)?|[\\p{L}_][\\p{L}\\p{N}_]*|" +
                symbols.keys.sortedByDescending(String::length).joinToString("|", transform = Regex::escape) +
                "|\\S",
        )

    fun narrate(
        source: String,
        leafId: String,
        // This generic symbol grammar is shared across languages and code roles.
        @Suppress("UnusedParameter") languageTag: String?,
        @Suppress("UnusedParameter") role: SpeechRole,
        context: SpeechContext,
    ): VerbalizedText {
        if (context.voiceLocale.language != "en") return literalText(source, leafId, SpeechLocaleSupport.Literal)
        val builder = NarrationBuilder()
        var offset = 0
        var indentation = 0
        for ((lineIndex, line) in source.split('\n').withIndex()) {
            if (lineIndex > 0) builder.add(". New line. ")
            val indent = line.takeWhile { it == ' ' || it == '\t' }.length
            if (indent > indentation) builder.add("Indent. ")
            if (indent < indentation) builder.add("Dedent. ")
            indentation = indent
            val matches = if (context.mode == SpeechMode.LiteralCode) Regex(".").findAll(line) else tokens.findAll(line)
            for (match in matches) {
                val token = match.value
                val text = tokenWords(token, context)
                builder.add(
                    text,
                    listOf(
                        SpeechSourceSpan(
                            leafId,
                            offset + match.range.first,
                            offset + match.range.last + 1,
                        ),
                    ),
                    SpeechMappingKind.Replacement,
                )
                builder.add(" ")
            }
            offset += line.length + 1
        }
        return builder.build()
    }

    private fun tokenWords(
        token: String,
        context: SpeechContext,
    ): String =
        when {
            token.startsWith("0x") -> "hex ${EnglishNumbers.digits(token.drop(2))}"
            token.startsWith("0b") -> "binary ${EnglishNumbers.digits(token.drop(2))}"
            token.first().isDigit() ->
                EnglishNumbers.number(token, context.copy(sourceFormatLocale = java.util.Locale.US))
                    ?: token
            token == " " -> "space"
            token == "\t" -> "tab"
            token in symbols -> symbols.getValue(token)
            else ->
                token
                    .replace(
                        Regex("([a-z])([A-Z])"),
                        "$1 $2",
                    ).replace(Regex("([A-Z])([A-Z][a-z])"), "$1 $2")
                    .replace('_', ' ')
        }
}
