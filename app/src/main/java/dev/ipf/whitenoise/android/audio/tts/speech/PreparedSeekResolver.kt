package dev.ipf.whitenoise.android.audio.tts.speech

data class PreparedRenderedHit(
    val leafId: String,
    val renderedText: String,
    val renderedOffset: Int,
)

sealed interface PreparedSeekTarget {
    data class Sentence(
        val sentenceId: String,
        val ordinal: Int,
    ) : PreparedSeekTarget

    data object NotSeekable : PreparedSeekTarget
}

object PreparedSeekResolver {
    fun resolve(
        message: PreparedSpeechMessage,
        hit: PreparedRenderedHit,
    ): PreparedSeekTarget {
        val leaf = message.sourceRuns.singleOrNull { it.leafId == hit.leafId }
        return if (leaf == null || !isSeekable(leaf, hit)) {
            PreparedSeekTarget.NotSeekable
        } else {
            resolveSentence(message, hit)
        }
    }

    private fun isSeekable(
        leaf: SpeechSourceRun,
        hit: PreparedRenderedHit,
    ): Boolean {
        val validHit = leaf.text == hit.renderedText && hit.renderedOffset in 0..leaf.text.length
        val omittedLink =
            leaf.role == SpeechRole.Prose &&
                Regex("https?://\\S+").findAll(leaf.text).any { hit.renderedOffset in it.range }
        return validHit && !omittedLink
    }

    private fun resolveSentence(
        message: PreparedSpeechMessage,
        hit: PreparedRenderedHit,
    ): PreparedSeekTarget {
        var id = message.canonicalSentenceIdForSource(hit.leafId, hit.renderedOffset)
        if (id == null) {
            val spans =
                message.sentences
                    .flatMap { it.utterance.originRuns }
                    .flatMap { it.sources }
                    .filter { it.leafId == hit.leafId }
                    .sortedBy { it.start }
            val next = spans.firstOrNull { it.start >= hit.renderedOffset } ?: spans.lastOrNull()
            id = next?.let { message.canonicalSentenceIdForSource(it.leafId, it.start) }
        }
        val sentence = message.sentences.firstOrNull { it.sentenceId == id } ?: return PreparedSeekTarget.NotSeekable
        return PreparedSeekTarget.Sentence(sentence.sentenceId, sentence.ordinal)
    }
}
