package dev.ipf.whitenoise.android.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class MarkdownElisionLeafIdTest {
    @Test
    fun tableHeaderCellAndRowElisionUseDistinctLeafIds() {
        val tablePath = "b0"

        assertEquals(
            "b0/h/elided",
            markdownTableHeaderCellElisionLeafId(tablePath),
        )
        assertEquals(
            "b0/elided",
            markdownElisionLeafId(tablePath, sourceIndexOffset = null),
        )
        assertNotEquals(
            markdownTableHeaderCellElisionLeafId(tablePath),
            markdownElisionLeafId(tablePath, sourceIndexOffset = null),
        )
    }
}
