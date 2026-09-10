package dev.ipf.whitenoise.android.audio.tts.speech

/** Source offsets and narration for one contiguous group of projected leaves. */
internal class SpeechRunGroup(
    private val group: List<SpeechSourceRun>,
    private val context: SpeechContext,
) {
    val source = group.joinToString("") { it.text }
    private val starts = group.runningFold(0) { offset, run -> offset + run.text.length }.dropLast(1)

    fun narrate(
        sentence: SpeechSentence,
        previousIndent: Int,
    ): Pair<VerbalizedText, Int> {
        val run = group.first()
        var nextIndent = previousIndent
        val a = sentence.source.start
        val b = sentence.source.end
        val text = source.substring(a, b)
        val value =
            if (run.role ==
                SpeechRole.Prose
            ) {
                SpokenForms.verbalize(text, run.leafId, context)
            } else {
                CodeSpeech.narrate(text, run.leafId, run.languageTag, run.role, context)
            }
        val mapped = mapSources(value, a)
        val narrated =
            if (run.role == SpeechRole.CodeBlock) {
                val lineStart = source.lastIndexOf('\n', a - 1) + 1
                val indent = a - lineStart
                val builder = NarrationBuilder()
                if (a > 0) builder.add("New line. ")
                if (indent > previousIndent) builder.add("Indent. ")
                if (indent < previousIndent) builder.add("Dedent. ")
                nextIndent = indent
                builder.append(mapped)
                builder.build()
            } else {
                mapped
            }
        return narrated to nextIndent
    }

    private fun mapSources(
        value: VerbalizedText,
        a: Int,
    ): VerbalizedText =

        value.copy(
            runs =
                value.runs.map { origin ->
                    val spans =
                        origin.sources.flatMap { span ->
                            val first = span.start + a
                            val last = span.end + a
                            group.indices.mapNotNull { index ->
                                var left = maxOf(first, starts[index])
                                var right = minOf(last, starts[index] + group[index].text.length)
                                if (origin.kind == SpeechMappingKind.Replacement && group.size > 1) {
                                    while (left < right && source[left].isWhitespace()) left++
                                    while (right > left && source[right - 1].isWhitespace()) right--
                                }
                                if (left <
                                    right
                                ) {
                                    SpeechSourceSpan(
                                        group[index].leafId,
                                        left - starts[index],
                                        right - starts[index],
                                    )
                                } else {
                                    null
                                }
                            }
                        }
                    origin.copy(sources = spans)
                },
        )
}
