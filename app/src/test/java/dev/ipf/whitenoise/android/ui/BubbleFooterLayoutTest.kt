package dev.ipf.whitenoise.android.ui

import dev.ipf.whitenoise.android.ui.conversation.messages.bubbleCollapsedFooterWidth
import dev.ipf.whitenoise.android.ui.conversation.messages.bubbleFooterInlineWidth
import org.junit.Assert.assertEquals
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
}
