package dev.ipf.whitenoise.android.ui

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
}
