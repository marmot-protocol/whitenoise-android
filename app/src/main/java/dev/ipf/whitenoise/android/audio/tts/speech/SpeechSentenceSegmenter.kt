package dev.ipf.whitenoise.android.audio.tts.speech

import dev.ipf.whitenoise.android.audio.tts.TtsTextRange

data class SpeechSentence(
    val id: String,
    val ordinal: Int,
    val source: TtsTextRange,
    val role: SpeechRole,
)

object SpeechSentenceSegmenter {
    fun segment(
        source: String,
        role: SpeechRole,
        context: SpeechContext,
        revisionId: String,
    ): List<SpeechSentence> {
        val ranges = mutableListOf<TtsTextRange>()
        var start = 0

        fun emit(end: Int) {
            var a = start
            var b = end
            while (a < b && source[a].isWhitespace()) a++
            while (b > a && source[b - 1].isWhitespace()) b--
            if (a < b) ranges += TtsTextRange(a, b)
            start = end
        }
        var i = 0
        while (i < source.length) {
            val c = source[i]
            if (c == '\n') {
                emit(i)
                start = i + 1
            } else if (role != SpeechRole.CodeBlock && c in ".!?") {
                val end = punctuationEnd(source, i)
                if (end != null) {
                    val abbreviation = isAbbreviation(source, start, i, end)
                    if (!abbreviation) {
                        emit(end)
                        i = end - 1
                    }
                }
            }
            i++
        }
        emit(source.length)
        return ranges.mapIndexed {
            ordinal,
            range,
            ->
            SpeechSentence("$revisionId:${context.verbalizerVersion}:$ordinal:${range.start}", ordinal, range, role)
        }
    }

    private fun punctuationEnd(
        source: String,
        i: Int,
    ): Int? {
        var end = i + 1
        while (end < source.length && source[end] in "\"'”’") end++
        return end.takeIf { it == source.length || source[it].isWhitespace() }
    }

    private fun isAbbreviation(
        source: String,
        start: Int,
        i: Int,
        end: Int,
    ): Boolean {
        val token =
            source
                .substring(start, i + 1)
                .trimEnd()
                .substringAfterLast(' ')
                .lowercase()
        val next = source.substring(end).trimStart()
        return token in setOf("dr.", "mr.", "mrs.", "ms.", "prof.", "sr.", "jr.", "e.g.", "i.e.") ||
            token == "no." &&
            next.firstOrNull()?.isDigit() == true ||
            token in setOf("a.m.", "p.m.", "u.s.") &&
            next.firstOrNull()?.isLowerCase() == true
    }
}
