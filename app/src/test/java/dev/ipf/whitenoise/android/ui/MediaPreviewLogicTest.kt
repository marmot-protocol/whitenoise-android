package dev.ipf.whitenoise.android.ui

import android.net.Uri
import dev.ipf.whitenoise.android.ui.conversation.media.PendingMediaSlot
import dev.ipf.whitenoise.android.ui.conversation.media.StagedPreviewItem
import dev.ipf.whitenoise.android.ui.conversation.media.previewIndexAfterRemoval
import dev.ipf.whitenoise.android.ui.conversation.media.stagedPreviewItems
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Pins the preview-screen ordering model: send order is media first then
 * documents, badge numbers are list positions, and removing an item renumbers
 * the rest by shifting them left.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class MediaPreviewLogicTest {
    private fun uri(n: Int): Uri = Uri.parse("content://test/$n")

    private fun slot(n: Int): PendingMediaSlot = PendingMediaSlot("slot-$n", uri(n))

    @Test
    fun sendOrderIsMediaThenDocumentsInSelectionOrder() {
        val items =
            stagedPreviewItems(
                mediaSlots = listOf(slot(1), slot(2)),
                documentUris = listOf(uri(3)),
            )
        assertEquals(
            listOf<StagedPreviewItem>(
                StagedPreviewItem.Media(slot(1)),
                StagedPreviewItem.Media(slot(2)),
                StagedPreviewItem.Document(uri(3)),
            ),
            items,
        )
    }

    @Test
    fun deselectingAnItemRenumbersTheRemainingOnes() {
        val before = stagedPreviewItems(listOf(slot(1), slot(2), slot(3)), emptyList())
        assertEquals(uri(2), before[1].uri)
        val after = stagedPreviewItems(listOf(slot(1), slot(3)), emptyList())
        // uri(3) held badge 3, and moves up to badge 2 once uri(2) is removed.
        assertEquals(uri(3), after[1].uri)
        assertEquals(2, after.size)
    }

    @Test
    fun removingBeforeTheCursorShiftsItLeft() {
        assertEquals(1, previewIndexAfterRemoval(removedIndex = 0, currentIndex = 2, remainingCount = 3))
    }

    @Test
    fun removingTheCursorItemKeepsItsSlot() {
        assertEquals(1, previewIndexAfterRemoval(removedIndex = 1, currentIndex = 1, remainingCount = 3))
    }

    @Test
    fun removingTheLastItemClampsToTheNewEnd() {
        assertEquals(1, previewIndexAfterRemoval(removedIndex = 2, currentIndex = 2, remainingCount = 2))
    }

    @Test
    fun removingAfterTheCursorLeavesItInPlace() {
        assertEquals(0, previewIndexAfterRemoval(removedIndex = 2, currentIndex = 0, remainingCount = 2))
    }

    @Test
    fun emptyingTheListResetsTheCursor() {
        assertEquals(0, previewIndexAfterRemoval(removedIndex = 0, currentIndex = 0, remainingCount = 0))
    }
}
