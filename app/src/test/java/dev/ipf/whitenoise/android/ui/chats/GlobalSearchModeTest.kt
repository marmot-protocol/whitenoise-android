package dev.ipf.whitenoise.android.ui.chats

import dev.ipf.whitenoise.android.search.GlobalSearchDateFilterSelection
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

/** The one rule that decides when global search shows message results (#1944). */
class GlobalSearchModeTest {
    /** An email-shaped query is classified as a NIP-05 address, yet still lists its matching messages. */
    @Test
    fun identifierShapedQueriesStillShowMessageResults() {
        listOf("bob@example.com", "npub1qqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqq").forEach { query ->
            assertTrue(query, show(query = query))
        }
    }

    /** Any nonblank text shows message results; blank text with only chat-scope filters does not. */
    @Test
    fun textQueriesShowResultsAndScopeOnlyFiltersDoNot() {
        assertTrue(show(query = "gm"))
        assertFalse(show(query = "   "))
        assertTrue(show(query = "", messageFiltersActive = true))
    }

    /** Attachment-only content filters keep the files and media library instead. */
    @Test
    fun attachmentBrowsingWins() {
        assertFalse(show(query = "gm", browsingAttachments = true))
    }

    /** A reversed custom range resolves to no constraint, so it cannot switch on an unfiltered all-messages list. */
    @Test
    fun reversedCustomRangeIsNoMessageFilter() {
        val reversed =
            GlobalSearchDateFilterSelection.Custom(
                from = LocalDate.of(2026, 3, 2),
                to = LocalDate.of(2026, 3, 1),
                zoneId = ZoneOffset.UTC,
            )
        val state = GlobalSearchState(isOpen = true, dateFilterSelection = reversed)

        assertTrue(state.messageFiltersActive)
        assertNull(messageSearchConstraintsFor(state))
        assertFalse(show(query = "", messageFiltersActive = messageSearchConstraintsFor(state) != null))
    }

    /** Evaluates the mode rule for an open search. */
    private fun show(
        query: String,
        browsingAttachments: Boolean = false,
        messageFiltersActive: Boolean = false,
    ): Boolean =
        globalSearchShowsMessageResults(
            searchActive = query.isNotBlank() || messageFiltersActive,
            browsingAttachments = browsingAttachments,
            query = query,
            messageFiltersActive = messageFiltersActive,
        )
}
