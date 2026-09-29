package dev.ipf.whitenoise.android.ui.conversation

import java.text.Normalizer
import java.util.Locale

private const val MAX_QUESTION_BYTES = 1_024
private const val MAX_OPTION_BYTES = 256
private const val MAX_OPTIONS = 10

/** Specific draft rule to explain beside the creator's input. */
internal enum class PollDraftIssue {
    MISSING_QUESTION,
    QUESTION_TOO_LONG,
    TOO_FEW_OPTIONS,
    OPTION_TOO_LONG,
    DUPLICATE_OPTION,
}

/** Cleaned question and options when the draft satisfies native poll limits. */
internal data class ValidatedPollDraft(
    val question: String,
    val options: List<String>,
    val issue: PollDraftIssue? = null,
)

/** Normalizes text MDK rejects so pasted line breaks and formatting controls do not spoil a draft. */
internal fun normalizePollText(value: String): String =
    buildString {
        value.forEach { character ->
            when {
                character == '\n' || character == '\r' || character == '\u2028' || character == '\u2029' -> append(' ')
                Character.isISOControl(character) || character.isPollBidiControl() -> Unit
                else -> append(character)
            }
        }
    }.trim()

/** Drops pasted direction overrides that can hide or reorder the visible choice text. */
private fun Char.isPollBidiControl(): Boolean =
    this == '\u061c' || this == '\u200e' || this == '\u200f' || this in '\u202a'..'\u202e' || this in '\u2066'..'\u2069'

/** Validates the question, option count, byte limits and duplicate choices before publication. */
internal fun validatePollDraft(
    question: String,
    options: List<String>,
): ValidatedPollDraft {
    val cleanQuestion = normalizePollText(question)
    val cleanOptions = options.map(::normalizePollText).filter(String::isNotEmpty)
    val issue =
        when {
            cleanQuestion.isEmpty() -> PollDraftIssue.MISSING_QUESTION
            cleanQuestion.toByteArray(Charsets.UTF_8).size > MAX_QUESTION_BYTES -> PollDraftIssue.QUESTION_TOO_LONG
            cleanOptions.size !in 2..MAX_OPTIONS -> PollDraftIssue.TOO_FEW_OPTIONS
            cleanOptions.any { it.toByteArray(Charsets.UTF_8).size > MAX_OPTION_BYTES } ->
                PollDraftIssue.OPTION_TOO_LONG
            cleanOptions.map(::foldPollOption).distinct().size != cleanOptions.size -> PollDraftIssue.DUPLICATE_OPTION
            else -> null
        }
    return ValidatedPollDraft(cleanQuestion, cleanOptions, issue)
}

/** Matches options without differences in case or combining accents. */
private fun foldPollOption(value: String): String {
    val decomposed = Normalizer.normalize(value, Normalizer.Form.NFD)
    val withoutAccents = decomposed.filterNot { Character.getType(it) == Character.NON_SPACING_MARK.toInt() }
    return withoutAccents.lowercase(Locale.ROOT)
}
