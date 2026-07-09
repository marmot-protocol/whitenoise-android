package dev.ipf.whitenoise.android.ui

import androidx.compose.ui.layout.AlignmentLine
import dev.ipf.whitenoise.android.ui.conversation.messages.bubbleCollapsedFooterWidth
import dev.ipf.whitenoise.android.ui.conversation.messages.bubbleFooterInlineWidth
import dev.ipf.whitenoise.android.ui.conversation.messages.collapsedFooterFitsOnOneRow
import dev.ipf.whitenoise.android.ui.conversation.messages.collapsedFooterRowMetrics
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
