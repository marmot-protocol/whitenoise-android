package dev.ipf.whitenoise.android.ui

import androidx.compose.ui.layout.AlignmentLine
import dev.ipf.whitenoise.android.ui.conversation.messages.bubbleCollapsedFooterWidth
import dev.ipf.whitenoise.android.ui.conversation.messages.bubbleFooterInlineWidth
import dev.ipf.whitenoise.android.ui.conversation.messages.bubbleInlineFooterGeometry
import dev.ipf.whitenoise.android.ui.conversation.messages.collapsedFooterFitsOnOneRow
import dev.ipf.whitenoise.android.ui.conversation.messages.collapsedFooterRowMetrics
import dev.ipf.whitenoise.android.ui.conversation.messages.footerItemRows
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BubbleFooterLayoutTest {
    @Test
    fun inlineFooterRespectsParentMinimumWidth() {
        assertEquals(
            220,
            bubbleFooterInlineWidth(
                contentWidth = 24,
                lastLineRight = 24,
                footerWidth = 58,
                minWidth = 220,
                maxWidth = 320,
                gap = 8,
            ),
        )
    }

    @Test
    fun inlineFooterStillWrapsToNaturalWidthWithoutMinimumWidth() {
        assertEquals(
            90,
            bubbleFooterInlineWidth(
                contentWidth = 24,
                lastLineRight = 24,
                footerWidth = 58,
                minWidth = 0,
                maxWidth = 320,
                gap = 8,
            ),
        )
    }

    @Test
    fun translatedEditedLabelWrapsBeforeTheTerminalStatusAndTime() {
        assertEquals(listOf(listOf(0), listOf(1, 2)), footerItemRows(listOf(120, 14, 32), maxWidth = 130, gap = 3))
        assertEquals(listOf(listOf(0, 1, 2)), footerItemRows(listOf(36, 14, 32), maxWidth = 130, gap = 3))
        assertEquals(emptyList<List<Int>>(), footerItemRows(emptyList(), maxWidth = 130, gap = 3))
    }

    /** A footer forced onto its own row reserves no unused Read More row width. */
    @Test
    fun forcedCollapsedRowsKeepOnlyTheirIndependentWidths() {
        assertEquals(151, bubbleCollapsedFooterWidth(110, 76, 151, 0, 280, 8, allowInline = false))
        assertEquals(235, bubbleCollapsedFooterWidth(110, 76, 151, 0, 280, 8))
        assertEquals(280, bubbleCollapsedFooterWidth(280, 76, 151, 0, 280, 8, allowInline = false))
        assertEquals(280, bubbleCollapsedFooterWidth(280, 76, 151, 0, 280, 8))
    }

    /** Warning/recovery footers stay below the body even when its last line is very short. */
    @Test
    fun terminalFooterCannotRiseIntoEarlierBodyLines() {
        val geometry =
            bubbleInlineFooterGeometry(
                textWidth = 220,
                textHeight = 120,
                lastLineRight = 8,
                lastBaseline = 115,
                footerWidth = 140,
                footerHeight = 60,
                footerBaseline = 55,
                maxWidth = 280,
                minWidth = 0,
                gap = 8,
                allowInline = false,
            )
        assertEquals(124, geometry.y)
        assertEquals(184, geometry.height)
        assertEquals(220 - 140, geometry.x)
    }

    /** The timestamp and its glyph share the last line's baseline rather than hanging off the block. */
    @Test
    fun inlineFooterSitsOnTheLastLineBaseline() {
        val geometry =
            bubbleInlineFooterGeometry(
                textWidth = 120,
                textHeight = 24,
                lastLineRight = 120,
                lastBaseline = 19,
                footerWidth = 58,
                footerHeight = 16,
                footerBaseline = 12,
                maxWidth = 320,
                minWidth = 0,
                gap = 8,
            )

        assertEquals(7, geometry.y)
        assertEquals(geometry.y + 12, 19)
        assertEquals(186, geometry.width)
        assertEquals(geometry.width - 58, geometry.x)
        assertEquals(24, geometry.height)
    }

    /** A footer that would overflow the line's width drops below the text with half the gap above it. */
    @Test
    fun inlineFooterDropsBelowWhenTheLineHasNoRoom() {
        val geometry =
            bubbleInlineFooterGeometry(
                textWidth = 300,
                textHeight = 48,
                lastLineRight = 300,
                lastBaseline = 43,
                footerWidth = 58,
                footerHeight = 16,
                footerBaseline = 12,
                maxWidth = 320,
                minWidth = 0,
                gap = 8,
            )

        assertEquals(52, geometry.y)
        assertEquals(300, geometry.width)
        assertEquals(68, geometry.height)
    }

    /** A line whose baseline sits above the footer's own cannot host it without clipping the glyph. */
    @Test
    fun inlineFooterDropsBelowWhenTheLineBaselineIsTooHigh() {
        val geometry =
            bubbleInlineFooterGeometry(
                textWidth = 40,
                textHeight = 12,
                lastLineRight = 40,
                lastBaseline = 9,
                footerWidth = 58,
                footerHeight = 16,
                footerBaseline = 12,
                maxWidth = 320,
                minWidth = 0,
                gap = 8,
            )

        assertEquals(16, geometry.y)
        assertEquals(32, geometry.height)
    }

    @Test
    fun collapsedFooterKeepsBodyWidthWhenBodyIsWiderThanBottomRow() {
        assertEquals(
            180,
            bubbleCollapsedFooterWidth(
                contentWidth = 180,
                readMoreWidth = 64,
                footerWidth = 50,
                minWidth = 0,
                maxWidth = 320,
                gap = 8,
            ),
        )
    }

    @Test
    fun collapsedFooterWidensToFitReadMoreAndTimestampRow() {
        assertEquals(
            122,
            bubbleCollapsedFooterWidth(
                contentWidth = 24,
                readMoreWidth = 64,
                footerWidth = 50,
                minWidth = 0,
                maxWidth = 320,
                gap = 8,
            ),
        )
    }

    @Test
    fun collapsedFooterRespectsParentMinimumAndMaximumWidths() {
        assertEquals(
            220,
            bubbleCollapsedFooterWidth(
                contentWidth = 24,
                readMoreWidth = 64,
                footerWidth = 50,
                minWidth = 220,
                maxWidth = 320,
                gap = 8,
            ),
        )
        assertEquals(
            96,
            bubbleCollapsedFooterWidth(
                contentWidth = 24,
                readMoreWidth = 64,
                footerWidth = 50,
                minWidth = 0,
                maxWidth = 96,
                gap = 8,
            ),
        )
    }

    @Test
    fun collapsedFooterReportsWhenBottomRowFits() {
        assertTrue(
            collapsedFooterFitsOnOneRow(
                containerWidth = 122,
                readMoreWidth = 64,
                footerWidth = 50,
                gap = 8,
            ),
        )
        assertFalse(
            collapsedFooterFitsOnOneRow(
                containerWidth = 96,
                readMoreWidth = 64,
                footerWidth = 50,
                gap = 8,
            ),
        )
    }

    @Test
    fun collapsedFooterMetricsAlignAvailableBaselines() {
        val metrics =
            collapsedFooterRowMetrics(
                readMoreHeight = 20,
                readMoreBaseline = 15,
                footerHeight = 12,
                footerBaseline = 9,
            )

        assertEquals(20, metrics.height)
        assertEquals(0, metrics.readMoreY)
        assertEquals(6, metrics.footerY)
        assertEquals(metrics.readMoreY + 15, metrics.footerY + 9)
    }

    @Test
    fun collapsedFooterMetricsCenterWhenBaselineUnavailable() {
        val metrics =
            collapsedFooterRowMetrics(
                readMoreHeight = 20,
                readMoreBaseline = AlignmentLine.Unspecified,
                footerHeight = 12,
                footerBaseline = 9,
            )

        assertEquals(20, metrics.height)
        assertEquals(0, metrics.readMoreY)
        assertEquals(4, metrics.footerY)
    }
}
