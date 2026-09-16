package dev.ipf.whitenoise.android.ui.conversation.composer

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which width the composer measures its draft at. Measuring at the width the draft is leaving made a
 * bulk insert grow in steps: the compact measurement produced a tall target, the extra lines then
 * adopted the editing layout, and its wider inset re-wrapped the text shorter mid-animation.
 *
 * The branch is asserted here rather than through a rendered draft because the unit-test text layout
 * does not wrap — it reports one line at every width — so a line-count fixture could never cross.
 */
class ComposerDestinationMeasurementTest {
    /** A draft whose compact wrap reaches the crossover is measured at the editing width. */
    @Test
    fun draftCrossingTheLineThresholdMeasuresAtTheEditingWidth() {
        assertTrue(
            measuresAtEditingWidth(compactLineCount = COMPOSER_MULTILINE_CONTROL_LINES),
        )
        assertTrue(
            measuresAtEditingWidth(compactLineCount = COMPOSER_MULTILINE_CONTROL_LINES + 4),
        )
    }

    /** A draft still short of the crossover keeps the compact width it is rendered at. */
    @Test
    fun draftBelowTheLineThresholdKeepsTheCompactWidth() {
        for (lines in 0 until COMPOSER_MULTILINE_CONTROL_LINES) {
            assertFalse("a $lines-line draft must stay compact", measuresAtEditingWidth(compactLineCount = lines))
        }
    }

    /** A draft already in the editing layout is measured there whatever its compact wrap would be. */
    @Test
    fun draftAlreadyEditingMeasuresAtTheEditingWidth() {
        assertTrue(measuresAtEditingWidth(compactLineCount = 1, startsEditing = true))
        assertTrue(
            measuresAtEditingWidth(compactLineCount = 1, startsEditing = true, multilineControlsSuppressed = true),
        )
    }

    /** With the multiline controls suppressed the composer never crosses over, however long the draft. */
    @Test
    fun suppressedMultilineControlsNeverCrossOver() {
        assertFalse(
            measuresAtEditingWidth(
                compactLineCount = COMPOSER_MULTILINE_CONTROL_LINES + 10,
                multilineControlsSuppressed = true,
            ),
        )
    }

    private fun measuresAtEditingWidth(
        compactLineCount: Int,
        startsEditing: Boolean = false,
        multilineControlsSuppressed: Boolean = false,
    ) = composerMeasuresAtEditingWidth(
        startsEditing = startsEditing,
        compactLineCount = compactLineCount,
        multilineControlsSuppressed = multilineControlsSuppressed,
    )
}
