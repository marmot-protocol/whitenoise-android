package dev.ipf.whitenoise.android.ui.conversation.messages

import dev.ipf.whitenoise.android.core.EditVersion
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Under MarmotKit 0.10.1 the engine owns edit history: pages are the newest part of a longer history and the
 * record's plaintext is already the edited body. These pin the numbering and that no edited body is ever
 * labelled as the original.
 */
class EditHistoryRowsTest {
    /** A complete history numbers from 1 and lists the newest revision first, the original last. */
    @Test
    fun localHistoryKeepsTheOriginalLast() {
        val rows = editHistoryRows("first", 1uL, listOf(version("v1", 2uL), version("v2", 3uL)), editCount = 2)

        assertEquals(listOf(2, 1, 0), rows.map { it.versionNumber })
        assertEquals(listOf("text-v2", "text-v1", "first"), rows.map { it.text })
    }

    /** With the engine's summary there is no original row, and the newest revision is the current body. */
    @Test
    fun engineHistoryHasNoOriginalRow() {
        val rows = editHistoryRows(null, 1uL, listOf(version("v1", 2uL), version("v2", 3uL)), editCount = 2)

        assertEquals(listOf(2, 1), rows.map { it.versionNumber })
        assertEquals("text-v2", rows.first().text)
    }

    /** A page holding only the newest revisions of a longer history keeps their true numbers. */
    @Test
    fun partialPageNumbersFromTheTotalEditCount() {
        val rows = editHistoryRows(null, 1uL, listOf(version("v4", 5uL), version("v5", 6uL)), editCount = 5)

        assertEquals(listOf(5, 4), rows.map { it.versionNumber })
    }

    /** An undercounted summary never produces a revision numbered below one. */
    @Test
    fun numberingNeverDropsBelowOne() {
        val rows = editHistoryRows(null, 1uL, listOf(version("v1", 2uL), version("v2", 3uL)), editCount = 1)

        assertEquals(listOf(2, 1), rows.map { it.versionNumber })
    }

    private fun version(
        id: String,
        at: ULong,
    ) = EditVersion(messageIdHex = id, text = "text-$id", recordedAt = at)
}
