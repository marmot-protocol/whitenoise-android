package dev.ipf.whitenoise.android.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class MarkdownRenderedBlockPathTest {
    @Test
    fun rootDetailsContentUsesDocumentSiblingIndices() {
        assertEquals(
            "b3",
            markdownRenderedBlockPath(
                pathPrefix = "",
                sourceIndexOffset = 3,
                relativeSourceIndex = 0,
            ),
        )
    }

    @Test
    fun nestedDetailsContentCombinesOffsetAndRelativeIndex() {
        assertEquals(
            "b2/q/b5",
            markdownRenderedBlockPath(
                pathPrefix = "b2/q",
                sourceIndexOffset = 3,
                relativeSourceIndex = 2,
            ),
        )
    }

    @Test
    fun rootBlockPathCombinesSourceOffsetAndRelativeIndex() {
        assertEquals(
            "b7",
            markdownRenderedBlockPath(
                pathPrefix = "",
                sourceIndexOffset = 5,
                relativeSourceIndex = 2,
            ),
        )
    }

    @Test
    fun nestedDetailsContentRetainsContainerPath() {
        assertEquals(
            "b2/q/b3",
            markdownRenderedBlockPath(
                pathPrefix = "b2/q",
                sourceIndexOffset = 3,
                relativeSourceIndex = 0,
            ),
        )
    }

    @Test
    fun nestedContainersKeepPathPrefixWhenNotUsingSourceOffset() {
        assertEquals(
            "b2/q/b1",
            markdownRenderedBlockPath(
                pathPrefix = "b2/q",
                sourceIndexOffset = null,
                relativeSourceIndex = 1,
            ),
        )
    }
}
