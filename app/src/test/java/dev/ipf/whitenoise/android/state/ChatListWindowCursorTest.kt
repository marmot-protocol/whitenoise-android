package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.ChatListAnchorOutcomeFfi
import dev.ipf.marmotkit.ChatListViewFfi
import dev.ipf.marmotkit.ChatListWindowSnapshotFfi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatListWindowCursorTest {
    /** Only a strictly newer sequence from the same generation is installed. */
    @Test
    fun acceptsOnlyNewerSequencesFromTheSameGeneration() {
        val cursor = ChatListWindowCursor(snapshot(sequence = 3uL))

        assertFalse(cursor.accept(snapshot(sequence = 3uL)))
        assertFalse(cursor.accept(snapshot(sequence = 2uL)))
        assertTrue(cursor.accept(snapshot(sequence = 4uL)))
        assertEquals(4uL, cursor.sequence)
        assertFalse(cursor.accept(snapshot(sequence = 4uL)))
    }

    /** A foreign generation is never installed and tells the owner to reopen. */
    @Test
    fun foreignGenerationRequiresReopen() {
        val cursor = ChatListWindowCursor(snapshot(sequence = 1uL))
        val foreign = snapshot(sequence = 9uL, generation = "other")

        assertTrue(cursor.requiresReopen(foreign))
        assertFalse(cursor.accept(foreign))
        assertEquals(1uL, cursor.sequence)
    }

    private fun snapshot(
        sequence: ULong,
        generation: String = "gen",
    ) = ChatListWindowSnapshotFfi(
        subscriptionGeneration = generation,
        sequence = sequence,
        view = ChatListViewFfi.CHATS,
        rows = emptyList(),
        hasMoreBefore = false,
        hasMoreAfter = false,
        anchor = ChatListAnchorOutcomeFfi.Top,
    )
}
