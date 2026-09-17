package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.TimelineEditHistoryPageFfi
import dev.ipf.marmotkit.TimelineEditVersionFfi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * MarmotKit pages accepted edits latest-first with the page's first version as the cursor for older ones.
 * These pin that every page is read and the result comes back oldest first, and that a runaway cursor stops.
 */
class EditHistoryPagingTest {
    /** Three latest-first pages are walked with the documented cursor and returned oldest first. */
    @Test
    fun walksEveryPageAndReturnsOldestFirst() =
        runTest {
            val cursors = mutableListOf<String?>()
            val pages =
                mapOf(
                    null to page(listOf("v5", "v6"), hasMoreBefore = true),
                    "v5" to page(listOf("v3", "v4"), hasMoreBefore = true),
                    "v3" to page(listOf("v1", "v2"), hasMoreBefore = false),
                )

            val history =
                collectEditHistory { before ->
                    cursors += before?.messageIdHex
                    pages.getValue(before?.messageIdHex)
                }

            assertEquals(listOf(null, "v5", "v3"), cursors)
            assertEquals(listOf("v1", "v2", "v3", "v4", "v5", "v6"), history.map { it.messageIdHex })
        }

    /** A single page without more history is returned as-is with one read. */
    @Test
    fun singlePageNeedsOneRead() =
        runTest {
            var reads = 0
            val history =
                collectEditHistory {
                    reads += 1
                    page(listOf("v1"), hasMoreBefore = false)
                }

            assertEquals(1, reads)
            assertEquals(listOf("v1"), history.map { it.messageIdHex })
        }

    /** An engine that keeps claiming more history is cut off at the page cap instead of looping forever. */
    @Test
    fun runawayCursorStopsAtThePageCap() =
        runTest {
            var reads = 0
            val history =
                collectEditHistory {
                    reads += 1
                    page(listOf("v$reads"), hasMoreBefore = true)
                }

            assertEquals(EDIT_HISTORY_MAX_PAGES, reads)
            assertEquals(EDIT_HISTORY_MAX_PAGES, history.size)
        }

    private fun page(
        ids: List<String>,
        hasMoreBefore: Boolean,
    ) = TimelineEditHistoryPageFfi(
        versions = ids.map { TimelineEditVersionFfi(messageIdHex = it, editedAt = 1uL, plaintext = "text-$it") },
        hasMoreBefore = hasMoreBefore,
    )
}
