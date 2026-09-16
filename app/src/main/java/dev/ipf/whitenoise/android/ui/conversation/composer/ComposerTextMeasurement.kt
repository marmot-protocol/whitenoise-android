package dev.ipf.whitenoise.android.ui.conversation.composer

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Constraints

/** The line count at which the composer adopts its editing layout, mirrored by the pill's hysteresis. */
internal const val COMPOSER_MULTILINE_CONTROL_LINES = 3

/**
 * Measures the draft at the width it will settle at, not the one it is leaving.
 *
 * The compact and editing layouts inset the editor differently, and the inset animates between them.
 * Measuring a bulk insert at the compact width produced a tall first target, the extra lines then
 * flipped the layout to editing, the wider inset re-wrapped the text shorter, and the height animation
 * reversed mid-flight — the stepped growth a paste or a dictation commit showed. Measuring once more at
 * the editing width whenever the draft is about to cross into that layout makes the first target final.
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
): TextLayoutResult {
    fun measure(widthPx: Int): TextLayoutResult {
        val constraints = Constraints(maxWidth = widthPx)
        return measurer.measure(text = text, style = style, constraints = constraints)
    }
    if (startsEditing) return measure(editingWidthPx)
    val compact = measure(compactWidthPx)
    val crossesIntoEditing =
        !multilineControlsSuppressed && compact.lineCount >= COMPOSER_MULTILINE_CONTROL_LINES
    return if (crossesIntoEditing) measure(editingWidthPx) else compact
}
