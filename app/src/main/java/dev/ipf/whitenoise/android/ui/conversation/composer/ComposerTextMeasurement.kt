package dev.ipf.whitenoise.android.ui.conversation.composer

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Constraints

/** The line count at which the composer adopts its editing layout, mirrored by the pill's hysteresis. */
internal const val COMPOSER_MULTILINE_CONTROL_LINES = 3

/**
 * The draft measured for the composer: the layout its height should target, and the line count that
 * decides which layout it belongs in. The two can disagree, which is the whole point of measuring
 * twice, so they are reported separately — using the destination layout's own count for the decision
 * would let a draft that wraps to three compact lines but two editing lines stay compact while
 * targeting the shorter editing height, clipping the text it had just grown for.
 */
internal data class ComposerDraftMeasurement(
    val layout: TextLayoutResult,
    val crossoverLineCount: Int,
)

/**
 * Whether the draft should be measured at the editing width rather than the compact one: either it is
 * already editing, or its compact wrap has crossed the line count that adopts the editing layout.
 * Kept separate from the measuring so the branch can be asserted without depending on text wrapping.
 */
internal fun composerMeasuresAtEditingWidth(
    startsEditing: Boolean,
    compactLineCount: Int,
    multilineControlsSuppressed: Boolean,
): Boolean =
    startsEditing ||
        (!multilineControlsSuppressed && compactLineCount >= COMPOSER_MULTILINE_CONTROL_LINES)

/**
 * Measures the draft at the width it will settle at, not the one it is leaving.
 *
 * The compact and editing layouts inset the editor differently, and the inset animates between them.
 * Measuring a bulk insert at the compact width produced a tall first target, the extra lines then
 * flipped the layout to editing, the wider inset re-wrapped the text shorter, and the height animation
 * reversed mid-flight — the stepped growth a paste or a dictation commit showed. Measuring once more at
 * the editing width whenever the draft is about to cross into that layout makes the first target final,
 * while [ComposerDraftMeasurement.crossoverLineCount] keeps reporting the compact width's count so the
 * crossover itself is still decided by the layout the draft is actually leaving.
 */

@Suppress("LongParameterList") // One measurement decision; its inputs belong together.
internal fun composerDestinationTextLayout(
    measurer: TextMeasurer,
    text: AnnotatedString,
    style: TextStyle,
    editingWidthPx: Int,
    compactWidthPx: Int,
    startsEditing: Boolean,
    multilineControlsSuppressed: Boolean,
): ComposerDraftMeasurement {
    fun measure(widthPx: Int): TextLayoutResult {
        val constraints = Constraints(maxWidth = widthPx)
        return measurer.measure(text = text, style = style, constraints = constraints)
    }
    if (startsEditing) {
        // The editor's insets are driven by the same editing state, so a draft that starts editing is
        // already drawn at this width — its own count is the rendered one, and a compact count here
        // would describe a row the reader is not looking at.
        val editing = measure(editingWidthPx)
        return ComposerDraftMeasurement(editing, editing.lineCount)
    }
    val compact = measure(compactWidthPx)
    val crossesIntoEditing =
        composerMeasuresAtEditingWidth(
            startsEditing = false,
            compactLineCount = compact.lineCount,
            multilineControlsSuppressed = multilineControlsSuppressed,
        )
    val layout = if (crossesIntoEditing) measure(editingWidthPx) else compact
    return ComposerDraftMeasurement(layout, compact.lineCount)
}
