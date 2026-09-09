package dev.ipf.whitenoise.android.audio.tts.speech

import dev.ipf.whitenoise.android.audio.tts.TtsTextRange

data class SpeechPreparationCursor(
    val sourceRevisionId: String,
    val sourceOffset: Int,
    val nextSentenceOrdinal: Int,
    val leafPath: String,
    val provenance: SpeechProvenance? = null,
    val runIndex: Int = 0,
    val unitIndex: Int = 0,
    val traversalOffset: Int = 0,
    val pendingSentence: PreparedSentence? = null,
    val pendingSpokenOffset: Int = 0,
)

data class SpeechPreparationRequest(
    val messageIdHex: String,
    val sourceRevisionId: String,
    val runs: List<SpeechSourceRun>,
    val context: SpeechContext,
    val senderAnnouncement: String? = null,
    val maxOutputChars: Int = SPEECH_PREPARATION_MAX_OUTPUT_CHARS,
    val cursor: SpeechPreparationCursor? = null,
)

enum class SpeechUnavailableReason { BlankSource, Cancelled, IncompatibleCursor, UnsupportedInput }

sealed interface SpeechPreparationResult {
    data class Complete(
        val message: PreparedSpeechMessage,
    ) : SpeechPreparationResult

    data class Continuation(
        val message: PreparedSpeechMessage,
        val cursor: SpeechPreparationCursor,
    ) : SpeechPreparationResult

    data class Unavailable(
        val reason: SpeechUnavailableReason,
    ) : SpeechPreparationResult
}

object SpeechPreparation {
    fun prepare(
        request: SpeechPreparationRequest,
        isCancelled: () -> Boolean = { false },
    ): SpeechPreparationResult {
        val provenance = request.context.provenance(request.sourceRevisionId)
        val reason =
            when {
                isCancelled() -> SpeechUnavailableReason.Cancelled
                request.cursor?.isCompatible(request.sourceRevisionId, provenance) == false ->
                    SpeechUnavailableReason.IncompatibleCursor
                request.runs.all { it.text.isBlank() } -> SpeechUnavailableReason.BlankSource
                request.maxOutputChars <= 0 || request.runs.size > SPEECH_PREPARATION_MAX_NODES ->
                    SpeechUnavailableReason.UnsupportedInput
                else -> null
            }
        return if (reason != null) unavailable(reason) else PreparationTraversal(request, isCancelled).prepare()
    }
}

private fun SpeechPreparationCursor.isCompatible(
    revision: String,
    expected: SpeechProvenance,
): Boolean = sourceRevisionId == revision && (provenance == null || provenance == expected)

private fun unavailable(reason: SpeechUnavailableReason) = SpeechPreparationResult.Unavailable(reason)

private class PreparationTraversal(
    private val request: SpeechPreparationRequest,
    private val isCancelled: () -> Boolean,
) {
    private val provenance = request.context.provenance(request.sourceRevisionId)
    private val cursor = request.cursor
    private val budget = minOf(request.maxOutputChars, SPEECH_PREPARATION_MAX_OUTPUT_CHARS)
    private val output = mutableListOf<PreparedSentence>()
    private var used = 0
    private var ordinal = cursor?.nextSentenceOrdinal ?: 0
    private var runIndex = cursor?.runIndex ?: 0
    private var absoluteOffset = cursor?.traversalOffset ?: 0
    private var unitIndex = 0

    private val resumeUnit: Int get() = if (runIndex == cursor?.runIndex) cursor.unitIndex else 0

    private fun message() =
        PreparedSpeechMessage(
            request.messageIdHex,
            output.toList(),
            provenance,
            request.runs.joinToString("") {
                it.text
            },
            request.runs.toList(),
        )

    private fun accept(
        value: VerbalizedText,
        role: SpeechRole,
        leaf: String,
        offset: Int,
    ): SpeechPreparationResult? {
        if (isCancelled()) return unavailable(SpeechUnavailableReason.Cancelled)
        val current = ordinal++
        return if (value.text.isBlank()) null else acceptSentence(value, role, leaf, offset, current)
    }

    private fun acceptSentence(
        value: VerbalizedText,
        role: SpeechRole,
        leaf: String,
        offset: Int,
        current: Int,
    ): SpeechPreparationResult? {
        val preparedSentence = prepareSentence(request, provenance, value, role, current)
        val spoken = preparedSentence.utterance.engineText
        if (used + spoken.length > budget &&
            output.isNotEmpty()
        ) {
            return SpeechPreparationResult.Continuation(
                message(),
                SpeechPreparationCursor(
                    request.sourceRevisionId,
                    offset,
                    current,
                    leaf,
                    provenance,
                    runIndex,
                    unitIndex,
                    absoluteOffset,
                ),
            )
        }
        return if (spoken.length > budget) {
            splitSentence(preparedSentence, offset, current, leaf)
        } else {
            used += spoken.length
            output += preparedSentence
            null
        }
    }

    private fun splitSentence(
        preparedSentence: PreparedSentence,
        offset: Int,
        current: Int,
        leaf: String,
    ): SpeechPreparationResult {
        val spoken = preparedSentence.utterance.engineText
        val boundary =
            graphemeBoundaries(spoken).lastOrNull { it in 1..budget }
                ?: return unavailable(SpeechUnavailableReason.UnsupportedInput)
        output += preparedSentence.slice(0, boundary)
        return SpeechPreparationResult.Continuation(
            message(),
            SpeechPreparationCursor(
                request.sourceRevisionId,
                offset,
                current + 1,
                leaf,
                provenance,
                runIndex,
                unitIndex + 1,
                absoluteOffset,
                preparedSentence.slice(boundary, spoken.length),
                boundary,
            ),
        )
    }

    fun prepare(): SpeechPreparationResult {
        var result = resumePending()
        while (result == null && runIndex < request.runs.size) {
            val run = request.runs[runIndex]
            result =
                when {
                    isCancelled() -> unavailable(SpeechUnavailableReason.Cancelled)
                    run.structure != null -> prepareTable()
                    request.context.mode == SpeechMode.LiteralCode &&
                        run.role != SpeechRole.Prose &&
                        run.role != SpeechRole.Diagram -> prepareLiteral(run)
                    run.role == SpeechRole.Diagram -> prepareDiagram(run)
                    else -> prepareSentences(run)
                }
        }
        return result ?: if (isCancelled()) {
            unavailable(SpeechUnavailableReason.Cancelled)
        } else {
            SpeechPreparationResult.Complete(message())
        }
    }

    private fun resumePending(): SpeechPreparationResult? {
        val pending = cursor?.pendingSentence ?: return null
        val length = pending.utterance.engineText.length
        return if (length > budget) {
            splitPending(pending, length)
        } else {
            output += pending
            used += length
            null
        }
    }

    private fun splitPending(
        pending: PreparedSentence,
        length: Int,
    ): SpeechPreparationResult {
        val boundary =
            graphemeBoundaries(pending.utterance.engineText).lastOrNull { it in 1..budget }
                ?: return unavailable(SpeechUnavailableReason.UnsupportedInput)
        output += pending.slice(0, boundary)
        val resume = requireNotNull(cursor)
        return SpeechPreparationResult.Continuation(
            message(),
            resume.copy(
                pendingSentence = pending.slice(boundary, length),
                pendingSpokenOffset = resume.pendingSpokenOffset + boundary,
            ),
        )
    }

    private fun prepareTable(): SpeechPreparationResult? {
        val table = request.runs.drop(runIndex).takeWhile { it.structure != null }
        val headers =
            table
                .filter {
                    it.structure is SpeechStructure.TableHeader
                }.associateBy { (it.structure as SpeechStructure.TableHeader).column }
        val rows =
            table
                .filter {
                    it.structure is SpeechStructure.TableCell
                }.groupBy { (it.structure as SpeechStructure.TableCell).row }
                .toSortedMap()
        for ((rowIndex, row) in rows.entries.withIndex()) {
            if (rowIndex < resumeUnit) continue
            unitIndex = rowIndex
            val cells = row.value
            val builder = NarrationBuilder()
            for (cell in cells.sortedBy { (it.structure as SpeechStructure.TableCell).column }) {
                val header = headers[(cell.structure as SpeechStructure.TableCell).column]
                if (header != null) {
                    builder.append(SpokenForms.verbalize(header.text, header.leafId, request.context))
                    builder.add(": ")
                }
                builder.append(SpokenForms.verbalize(cell.text, cell.leafId, request.context))
                builder.add(". ")
            }
            accept(builder.build(), SpeechRole.Prose, cells.first().leafId, absoluteOffset)?.let { return it }
        }
        absoluteOffset += table.sumOf { it.text.length }
        runIndex += table.size
        return null
    }

    private fun prepareLiteral(run: SpeechSourceRun): SpeechPreparationResult? {
        if (resumeUnit == 0) {
            unitIndex = 0
            accept(
                CodeSpeech.narrate(run.text, run.leafId, run.languageTag, run.role, request.context),
                run.role,
                run.leafId,
                absoluteOffset,
            )?.let { return it }
        }
        absoluteOffset += run.text.length
        runIndex++
        return null
    }

    private fun prepareDiagram(run: SpeechSourceRun): SpeechPreparationResult? {
        val narration = DiagramSpeech.narrate(run.text, run.leafId, run.languageTag, request.context)
        val ranges =
            narration.relations.map { it.spoken }.ifEmpty {
                listOf(
                    TtsTextRange(0, narration.verbalized.text.length),
                )
            }
        for ((relationIndex, range) in ranges.withIndex()) {
            if (relationIndex < resumeUnit) continue
            unitIndex = relationIndex
            val value = narration.verbalized
            accept(
                VerbalizedText(
                    value.text.substring(range.start, range.end),
                    value.runs.mapNotNull {
                        it.slice(range, range.start)
                    },
                ),
                run.role,
                run.leafId,
                absoluteOffset,
            )?.let { return it }
        }
        absoluteOffset += run.text.length
        runIndex++
        return null
    }

    private fun prepareSentences(run: SpeechSourceRun): SpeechPreparationResult? {
        val group =
            if (run.role ==
                SpeechRole.Prose
            ) {
                request.runs.drop(runIndex).takeWhile {
                    it.role == SpeechRole.Prose &&
                        it.structure == null &&
                        it.leafId.substringBefore('/') == run.leafId.substringBefore('/')
                }
            } else {
                listOf(run)
            }
        val narration = SpeechRunGroup(group, request.context)
        val source = narration.source
        var previousIndent = 0
        var result: SpeechPreparationResult? = null
        for ((sentenceIndex, sentence) in SpeechSentenceSegmenter
            .segment(
                source,
                run.role,
                request.context,
                request.sourceRevisionId,
            ).withIndex()) {
            if (isCancelled()) return unavailable(SpeechUnavailableReason.Cancelled)
            if (sentenceIndex < resumeUnit) {
                val lineStart = source.lastIndexOf('\n', sentence.source.start - 1) + 1
                previousIndent = sentence.source.start - lineStart
            } else {
                unitIndex = sentenceIndex
                val (narrated, indent) = narration.narrate(sentence, previousIndent)
                previousIndent = indent
                result = accept(narrated, run.role, run.leafId, absoluteOffset + sentence.source.start)
                if (result != null) break
            }
        }
        if (result == null) {
            absoluteOffset += source.length
            runIndex += group.size
        }
        return result
    }
}

private fun PreparedSentence.slice(
    start: Int,
    end: Int,
): PreparedSentence {
    val range = TtsTextRange(start, end)
    return copy(
        utterance =
            utterance.copy(
                engineText = utterance.engineText.substring(start, end),
                originRuns = utterance.originRuns.mapNotNull { it.slice(range, start) },
                senderPrefix =
                    utterance.senderPrefix?.let {
                        if (start >= it.end) null else TtsTextRange(0, minOf(end, it.end) - start)
                    },
            ),
    )
}

private fun prepareSentence(
    request: SpeechPreparationRequest,
    provenance: SpeechProvenance,
    value: VerbalizedText,
    role: SpeechRole,
    current: Int,
): PreparedSentence {
    val builder = NarrationBuilder()
    val sender = if (current == 0) request.senderAnnouncement?.takeIf { it.isNotBlank() }?.let { "$it: " } else null
    if (sender != null) builder.add(sender)
    builder.append(value)
    val spoken = builder.build()
    return PreparedSentence(
        "${request.sourceRevisionId}:${provenance.effectiveLanguageTag}:${provenance.mode}:$current",
        current,
        PreparedUtterance(
            spoken.text,
            spoken.runs,
            provenance,
            sender
                ?.let {
                    TtsTextRange(0, it.length)
                },
        ),
        role,
    )
}
