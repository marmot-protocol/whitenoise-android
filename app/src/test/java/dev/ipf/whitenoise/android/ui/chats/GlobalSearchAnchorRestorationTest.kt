package dev.ipf.whitenoise.android.ui.chats

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The staged loading fence keeps fresh local names usable and holds back only a restored deep anchor. */
class GlobalSearchAnchorRestorationTest {
    /** A fresh query never waits on an old dataset's deep position. */
    @Test
    fun freshQueryDoesNotInheritOldDatasetDeepPosition() {
        assertFalse(preserveSearchBodyAnchor(sameDataset = false, firstVisibleIndex = 45, firstVisibleOffset = 7))
        assertFalse(blockLocalSearchRows(loading = true, hasLocalRows = true, preservePendingAnchor = false))
    }

    /** A same-dataset deep anchor waits for its load, then unblocks. */
    @Test
    fun sameDatasetDeepRestorationWaitsWithoutClampingButCompletionUnblocks() {
        val preserve = preserveSearchBodyAnchor(sameDataset = true, firstVisibleIndex = 45, firstVisibleOffset = 7)
        assertTrue(preserve)
        assertTrue(blockLocalSearchRows(loading = true, hasLocalRows = true, preservePendingAnchor = preserve))
        assertFalse(blockLocalSearchRows(loading = false, hasLocalRows = true, preservePendingAnchor = preserve))
        assertTrue(preserveSearchBodyAnchor(sameDataset = true, firstVisibleIndex = 0, firstVisibleOffset = 7))
        assertFalse(preserveSearchBodyAnchor(sameDataset = true, firstVisibleIndex = 0, firstVisibleOffset = 0))
    }

    /** With no local rows the list waits for the first load. */
    @Test
    fun messageOnlySearchWithNoEligibleLocalRowsWaitsForItsRealMatches() {
        assertTrue(blockLocalSearchRows(loading = true, hasLocalRows = false, preservePendingAnchor = false))
        assertFalse(blockLocalSearchRows(loading = false, hasLocalRows = false, preservePendingAnchor = false))
    }
}
