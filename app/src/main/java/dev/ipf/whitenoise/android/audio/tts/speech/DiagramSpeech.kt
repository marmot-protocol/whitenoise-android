package dev.ipf.whitenoise.android.audio.tts.speech

import dev.ipf.whitenoise.android.audio.tts.TtsTextRange

data class DiagramRelation(
    val spoken: TtsTextRange,
    val sources: List<SpeechSourceSpan>,
)

data class DiagramNarration(
    val recognized: Boolean,
    val verbalized: VerbalizedText,
    val relations: List<DiagramRelation>,
)

object DiagramSpeech {
    private const val LABEL = "[\\p{L}\\p{N}_]+(?: +[\\p{L}\\p{N}_]+)*"
    private val arrowGrammar = Regex(" *$LABEL(?: *(?:->|→) *$LABEL)+ *")
    private val treeRootGrammar = Regex(" *$LABEL *")
    private val treeBranchGrammar = Regex("[ │]*(?:├──|└──) $LABEL *")
    private val boxBorderGrammar = Regex("\\+[-─]{3,}\\+")
    private val boxBodyGrammar = Regex("[|│].*[|│]")

    fun recognizes(source: String): Boolean {
        val lines = source.lines()
        return arrowGrammar.matches(source) || isTree(lines) || isBox(lines)
    }

    fun narrate(
        source: String,
        leafId: String,
        languageTag: String?,
        context: SpeechContext,
    ): DiagramNarration =
        when {
            context.voiceLocale.language != "en" ->
                DiagramNarration(false, literalText(source, leafId, SpeechLocaleSupport.Literal), emptyList())
            context.mode == SpeechMode.LiteralCode ->
                DiagramNarration(
                    false,
                    CodeSpeech.narrate(source, leafId, languageTag, SpeechRole.CodeBlock, context),
                    emptyList(),
                )
            languageTag.isNullOrBlank() &&
                arrowGrammar.matches(source) ->
                arrows(source, leafId)
            languageTag.isNullOrBlank() && isTree(source.lines()) -> tree(source.lines(), leafId)
            else -> fallback(source, leafId, languageTag, context)
        }

    private fun arrows(
        source: String,
        leafId: String,
    ): DiagramNarration {
        val builder = NarrationBuilder()
        val relations = mutableListOf<DiagramRelation>()
        val labels =
            Regex("[^>→-]+")
                .findAll(source)
                .mapNotNull { match ->
                    val leading = match.value.length - match.value.trimStart().length
                    val label = match.value.trim()
                    label.takeIf(String::isNotEmpty)?.let {
                        label to
                            SpeechSourceSpan(
                                leafId,
                                match.range.first + leading,
                                match.range.first + leading + label.length,
                            )
                    }
                }.toList()
        for (index in 0 until labels.lastIndex) {
            if (index > 0) builder.add(" ")
            val start = builder.length
            val (from, fromSpan) = labels[index]
            val (to, toSpan) = labels[index + 1]
            builder.add(from, listOf(fromSpan), SpeechMappingKind.Replacement)
            builder.add(" points to ")
            builder.add(to, listOf(toSpan), SpeechMappingKind.Replacement)
            builder.add(".")
            relations += DiagramRelation(TtsTextRange(start, builder.length), listOf(fromSpan, toSpan))
        }
        return DiagramNarration(true, builder.build(), relations)
    }

    private fun isTree(lines: List<String>): Boolean =
        lines.size > 1 &&
            treeRootGrammar.matches(lines[0]) &&
            lines.drop(1).all(treeBranchGrammar::matches)

    private fun isBox(lines: List<String>): Boolean =
        lines.size >= 3 &&
            boxBorderGrammar.matches(lines.first()) &&
            boxBorderGrammar.matches(lines.last()) &&
            lines.subList(1, lines.lastIndex).all(boxBodyGrammar::matches)

    private fun tree(
        lines: List<String>,
        leafId: String,
    ): DiagramNarration {
        val builder = NarrationBuilder()
        var offset = 0
        lines.forEachIndexed { index, line ->
            val label = if (index == 0) line.trim() else line.substringAfter("──").trim()
            val local = line.indexOf(label)
            if (index > 0) builder.add(" Child: ")
            builder.add(
                label,
                listOf(SpeechSourceSpan(leafId, offset + local, offset + local + label.length)),
                SpeechMappingKind.Replacement,
            )
            builder.add(".")
            offset += line.length + 1
        }
        return DiagramNarration(true, builder.build(), emptyList())
    }

    private fun fallback(
        source: String,
        leafId: String,
        languageTag: String?,
        context: SpeechContext,
    ): DiagramNarration {
        val builder = NarrationBuilder()
        builder.add("Diagram, reading line by line. ")
        var cursor = 0
        val border = Regex("[-─]{3,}")
        for (match in border.findAll(source)) {
            if (cursor <
                match.range.first
            ) {
                builder.append(
                    CodeSpeech
                        .narrate(
                            source.substring(cursor, match.range.first),
                            leafId,
                            languageTag,
                            SpeechRole.CodeBlock,
                            context,
                        ).shiftSources(cursor),
                )
            }
            builder.add(
                "${EnglishNumbers.cardinal(match.value.length.toLong())} dashes ",
                listOf(
                    SpeechSourceSpan(
                        leafId,
                        match.range.first,
                        match.range.last + 1,
                    ),
                ),
                SpeechMappingKind.Replacement,
            )
            cursor = match.range.last + 1
        }
        if (cursor <
            source.length
        ) {
            builder.append(
                CodeSpeech
                    .narrate(
                        source.substring(cursor),
                        leafId,
                        languageTag,
                        SpeechRole.CodeBlock,
                        context,
                    ).shiftSources(cursor),
            )
        }
        return DiagramNarration(false, builder.build(), emptyList())
    }
}

internal fun VerbalizedText.shiftSources(offset: Int): VerbalizedText =
    copy(
        runs =
            runs.map { run ->
                run.copy(
                    sources =
                        run.sources.map {
                            it.copy(
                                start =
                                    it.start + offset,
                                end = it.end + offset,
                            )
                        },
                )
            },
    )
