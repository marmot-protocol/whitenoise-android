package dev.ipf.whitenoise.android.core

import dev.ipf.marmotkit.AppMessageRecordFfi
import dev.ipf.marmotkit.MarkdownDocumentFfi
import dev.ipf.marmotkit.MessageTagFfi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class MessageSearchTest {
    @Test
    fun matchIndicesIsCaseInsensitiveSubstring() {
        val bodies = listOf("Hello world", "goodBYE", "Worldly things", "")
        assertEquals(listOf(0, 2), MessageSearch.matchIndices(bodies, "world"))
        assertEquals(listOf(0, 2), MessageSearch.matchIndices(bodies, "WORLD"))
        assertEquals(listOf(1), MessageSearch.matchIndices(bodies, "bye"))
    }

    @Test
    fun matchIndicesUsesLocaleRootCaseFolding() =
        withDefaultLocale(Locale.forLanguageTag("tr")) {
            assertEquals(listOf(0), MessageSearch.matchIndices(listOf("INDIGO"), "i"))
        }

    @Test
    fun matchIndicesTrimsTheQuery() {
        val bodies = listOf("the quick brown fox")
        assertEquals(listOf(0), MessageSearch.matchIndices(bodies, "  quick "))
    }

    @Test
    fun blankQueryYieldsNoMatches() {
        val bodies = listOf("a", "b", "c")
        assertEquals(emptyList<Int>(), MessageSearch.matchIndices(bodies, ""))
        assertEquals(emptyList<Int>(), MessageSearch.matchIndices(bodies, "   "))
    }

    @Test
    fun isSearchableExcludesNonTextKindsAndBlankBodies() {
        // Plain chat with text is searchable.
        assertTrue(MessageSearch.isSearchable(message(plaintext = "hi"), "hi"))
        // Reaction (kind 7) is never searchable, even with non-blank text.
        assertFalse(
            MessageSearch.isSearchable(
                message(kind = 7uL, plaintext = "👍"),
                "👍",
            ),
        )
        // Delete (kind 5) is never searchable.
        assertFalse(MessageSearch.isSearchable(message(kind = 5uL), ""))
        // Group-system (kind 1210) is never searchable.
        assertFalse(MessageSearch.isSearchable(message(kind = 1210uL, plaintext = "{}"), "Alice joined"))
        // Agent-stream-start (kind 1200) is never searchable.
        assertFalse(MessageSearch.isSearchable(message(kind = 1200uL, plaintext = "x"), "x"))
        // A caption-less media row resolves to a blank displayed body and so
        // is not searchable — filenames are out of scope for v1.
        assertFalse(MessageSearch.isSearchable(message(kind = 9uL, plaintext = ""), ""))
    }

    @Test
    fun stepWrapsAroundInBothDirections() {
        // Forward from the last match wraps to the first.
        assertEquals(0, MessageSearch.step(current = 2, matchCount = 3, forward = true))
        assertEquals(1, MessageSearch.step(current = 0, matchCount = 3, forward = true))
        // Backward from the first match wraps to the last.
        assertEquals(2, MessageSearch.step(current = 0, matchCount = 3, forward = false))
        assertEquals(1, MessageSearch.step(current = 2, matchCount = 3, forward = false))
    }

    @Test
    fun stepClampsAnOutOfRangeCursor() {
        // A stale cursor (e.g. left over from a larger match set) is clamped
        // before stepping, so navigation never lands on a phantom ordinal.
        assertEquals(0, MessageSearch.step(current = 9, matchCount = 3, forward = true))
        assertEquals(-1, MessageSearch.step(current = 0, matchCount = 0, forward = true))
    }

    @Test
    fun resolveCursorKeepsThePinnedMatchWhenItStillExists() {
        // The focused match (m3) survives a pagination that prepends older
        // matches above it — its ordinal shifts but the cursor follows it.
        assertEquals(2, MessageSearch.resolveCursor(listOf("m1", "m2", "m3"), pinnedMatchId = "m3"))
    }

    @Test
    fun resolveCursorFallsBackToFirstWhenPinDisappearsOrIsAbsent() {
        assertEquals(0, MessageSearch.resolveCursor(listOf("m1", "m2"), pinnedMatchId = "gone"))
        assertEquals(0, MessageSearch.resolveCursor(listOf("m1", "m2"), pinnedMatchId = null))
        assertEquals(-1, MessageSearch.resolveCursor(emptyList(), pinnedMatchId = "m1"))
    }

    private fun message(
        id: String = "m",
        direction: String = "received",
        sender: String = "alice",
        plaintext: String = "hello",
        kind: ULong = 9uL,
        tags: List<MessageTagFfi> = emptyList(),
        at: UInt = 1u,
    ) = AppMessageRecordFfi(
        messageIdHex = id,
        direction = direction,
        groupIdHex = "group",
        sender = sender,
        plaintext = plaintext,
        contentTokens = MarkdownDocumentFfi(truncated = false, blocks = emptyList(), blankLinesBefore = ByteArray(0)),
        kind = kind,
        tags = tags,
        sourceEpoch = null,
        retentionSeconds = null,
        retentionExpiresAt = null,
        recordedAt = at.toULong(),
        receivedAt = at.toULong(),
    )

    private fun withDefaultLocale(
        locale: Locale,
        block: () -> Unit,
    ) {
        val old = Locale.getDefault()
        Locale.setDefault(locale)
        try {
            block()
        } finally {
            Locale.setDefault(old)
        }
    }

    @Test
    fun mergeUsesTheScanAsSpineOutsideTheLoadedWindow() {
        val merged =
            MessageSearch.mergeWithHistoryScan(
                windowMatches = listOf(match("e", 5u), match("f", 6u)),
                loadedWindowIds = setOf("d", "e", "f"),
                scanMatchesOldestFirst = listOf(match("a", 1u), match("b", 2u), match("e", 5u), match("f", 6u)),
            )
        assertEquals(listOf("a", "b", "e", "f"), merged.map { it.messageIdHex })
    }

    @Test
    fun mergeDropsScanHitsTheWindowRejected() {
        // "d" matched the raw stored body but the loaded window's edit-resolved
        // text no longer matches — the window verdict wins inside the window.
        val merged =
            MessageSearch.mergeWithHistoryScan(
                windowMatches = listOf(match("e", 5u)),
                loadedWindowIds = setOf("d", "e"),
                scanMatchesOldestFirst = listOf(match("a", 1u), match("d", 4u), match("e", 5u)),
            )
        assertEquals(listOf("a", "e"), merged.map { it.messageIdHex })
    }

    @Test
    fun mergeOrdersWindowOnlyHitsByTheirTimelinePosition() {
        // "e" only matches after an edit and has no loaded-window scan
        // neighbor. Its durable timeline position still places it between the
        // older and newer history hits instead of appending it at the end.
        val merged =
            MessageSearch.mergeWithHistoryScan(
                windowMatches = listOf(match("e", 5u)),
                loadedWindowIds = setOf("d", "e", "f"),
                scanMatchesOldestFirst = listOf(match("a", 1u), match("z", 10u)),
            )
        assertEquals(listOf("a", "e", "z"), merged.map { it.messageIdHex })
    }

    @Test
    fun mergeWithEmptyScanKeepsWindowMatches() {
        val merged =
            MessageSearch.mergeWithHistoryScan(
                windowMatches = listOf(match("a", 1u), match("b", 2u)),
                loadedWindowIds = setOf("a", "b", "c"),
                scanMatchesOldestFirst = emptyList(),
            )
        assertEquals(listOf("a", "b"), merged.map { it.messageIdHex })
    }

    private fun match(
        id: String,
        at: UInt,
    ) = ConversationSearchMatch(messageIdHex = id, timelineAt = at.toULong())
}
