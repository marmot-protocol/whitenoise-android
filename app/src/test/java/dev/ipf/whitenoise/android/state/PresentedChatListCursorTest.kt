package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.PresentationVersionFfi
import dev.ipf.marmotkit.PresentedChatListSnapshotFfi
import dev.ipf.marmotkit.PresentedChatListUpdateFfi
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PresentedChatListCursorTest {
    /** Monotonic updates from the opened handle are accepted exactly once. */
    @Test
    fun acceptsOnlyIncreasingSequenceFromCurrentGenerationAndStore() {
        val cursor = PresentedChatListCursor(update(sequence = 4uL))

        assertFalse(cursor.accept(update(sequence = 4uL)))
        assertFalse(cursor.accept(update(sequence = 3uL)))
        assertTrue(cursor.accept(update(sequence = 5uL)))
        assertFalse(cursor.accept(update(sequence = 5uL)))
    }

    /** A native generation change requires ownership of a newly opened handle. */
    @Test
    fun generationChangeRequiresReopen() {
        val cursor = PresentedChatListCursor(update(generation = "first"))
        val replacement = update(generation = "second", sequence = 1uL)

        assertTrue(cursor.requiresReopen(replacement))
        assertFalse(cursor.accept(replacement))
    }

    /** Account-store replacement invalidates presentation cache identity. */
    @Test
    fun accountStoreEpochChangeRequiresReopen() {
        val cursor = PresentedChatListCursor(update(storeEpoch = byteArrayOf(1)))
        val replacement = update(sequence = 1uL, storeEpoch = byteArrayOf(2))

        assertTrue(cursor.requiresReopen(replacement))
        assertFalse(cursor.accept(replacement))
    }

    private fun update(
        generation: String = "generation",
        sequence: ULong = 0uL,
        storeEpoch: ByteArray = byteArrayOf(1),
    ) = PresentedChatListUpdateFfi(
        subscriptionGeneration = generation,
        sequence = sequence,
        snapshot =
            PresentedChatListSnapshotFfi(
                rows = emptyList(),
                presentationVersion = PresentationVersionFfi(accountStoreEpoch = storeEpoch, revision = 0uL),
            ),
    )
}
