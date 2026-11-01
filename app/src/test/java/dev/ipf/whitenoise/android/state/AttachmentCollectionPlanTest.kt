package dev.ipf.whitenoise.android.state

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure coverage for the collection chunk planner and the operation's state transitions (#2576). */
class AttachmentCollectionPlanTest {
    private val mib = 1024L * 1024L

    /** Builds [count] visual items of [bytes] each, named in order. */
    private fun media(
        count: Int,
        bytes: Long?,
        prefix: String = "m",
    ) = List(count) { PlannedCollectionItem("$prefix$it", CollectionItemKind.MEDIA, bytes) }

    /** One hundred small items split into ten albums of ten, in source order, with the caption on chunk 1 only. */
    @Test
    fun hundredSmallItemsSplitIntoOrderedAlbumsOfTen() {
        val chunks = planAttachmentChunks("op", media(100, 1_000L))
        assertEquals(10, chunks.size)
        assertEquals(List(100) { "m$it" }, chunks.flatMap { it.itemIds })
        assertTrue(chunks.all { it.itemIds.size == ATTACHMENT_CHUNK_MAX_ITEMS })
        assertEquals(listOf(true) + List(9) { false }, chunks.map { it.carriesCaption })
        assertEquals((0 until 10).toList(), chunks.map { it.index })
    }

    /** No album passes the 32 MiB retained-plaintext ceiling; an exact fit is allowed. */
    @Test
    fun albumsStopAtTheRetainedPlaintextCeiling() {
        val chunks = planAttachmentChunks("op", media(12, 4 * mib))
        assertEquals(listOf(8, 4), chunks.map { it.itemIds.size })
        val sixes = planAttachmentChunks("op", media(6, 6 * mib))
        assertEquals(listOf(5, 1), sixes.map { it.itemIds.size })
    }

    /** Documents, items above the ceiling and items of unknown size each travel alone without reordering. */
    @Test
    fun documentsOversizedAndUnknownItemsTravelAlone() {
        val items =
            listOf(
                PlannedCollectionItem("a", CollectionItemKind.MEDIA, 10L),
                PlannedCollectionItem("b", CollectionItemKind.MEDIA, 40 * mib),
                PlannedCollectionItem("c", CollectionItemKind.MEDIA, 10L),
                PlannedCollectionItem("d", CollectionItemKind.MEDIA, null),
                PlannedCollectionItem("e", CollectionItemKind.MEDIA, 10L),
                PlannedCollectionItem("f", CollectionItemKind.DOCUMENT, 10L),
                PlannedCollectionItem("g", CollectionItemKind.DOCUMENT, 10L),
            )
        val chunks = planAttachmentChunks("op", items)
        assertEquals(
            listOf(listOf("a"), listOf("b"), listOf("c"), listOf("d"), listOf("e"), listOf("f"), listOf("g")),
            chunks.map { it.itemIds },
        )
    }

    /** The same ordered ids always give the same chunk ids; another operation or order gives others. */
    @Test
    fun chunkIdsAreDeterministicFromOrderedItemIds() {
        val first = planAttachmentChunks("op", media(25, 10L))
        val again = planAttachmentChunks("op", media(25, 10L))
        assertEquals(first.map { it.id }, again.map { it.id })
        assertNotEquals(first.map { it.id }, planAttachmentChunks("other", media(25, 10L)).map { it.id })
        assertNotEquals(first.first().id, planAttachmentChunks("op", media(25, 10L).reversed()).first().id)
        assertEquals(first.size, first.map { it.id }.toSet().size)
    }

    /** Duplicate document URIs keep distinct, stable occurrence ids. */
    @Test
    fun repeatedDocumentUrisGetDistinctStableIds() {
        val ids = collectionDocumentIds(listOf("content://a", "content://b", "content://a"))
        assertEquals(3, ids.toSet().size)
        assertEquals(ids, collectionDocumentIds(listOf("content://a", "content://b", "content://a")))
        assertEquals(ids[0], collectionDocumentIds(listOf("content://a"))[0])
    }

    /** Builds a three-chunk operation of documents for transition tests. */
    private fun operation(): AttachmentCollectionOperation {
        val planned = List(3) { PlannedCollectionItem("d$it", CollectionItemKind.DOCUMENT, 1L) }
        return AttachmentCollectionOperation(
            id = "op",
            accountRef = "account",
            groupIdHex = "group",
            items = planned.map { CollectionItem(it.id, "content://doc/${it.id}", it.kind) },
            chunks = planAttachmentChunks("op", planned),
            createdAtMs = 0L,
            updatedAtMs = 0L,
        )
    }

    /** Published items never move again and the settled count never falls. */
    @Test
    fun progressIsMonotonicAndPublishedIsAbsorbing() {
        var operation = operation()
        val first = operation.chunks[0].id
        operation = operation.withChunkState(first, CollectionChunkState.ACCEPTED, CollectionItemState.PUBLISHED, 1L)
        val settled = operation.settled
        operation = operation.withChunkState(first, CollectionChunkState.FAILED, CollectionItemState.FAILED, 2L)
        assertEquals(CollectionItemState.PUBLISHED, operation.items[0].state)
        assertEquals(settled, operation.settled)
        assertEquals(3, operation.total)
    }

    /** A failed chunk blocks the operation; skipping it lets the next chunk run. */
    @Test
    fun failedChunkBlocksUntilSkipped() {
        var operation = operation()
        val second = operation.chunks[1]
        val first = operation.chunks[0].id
        operation = operation.withChunkState(first, CollectionChunkState.ACCEPTED, CollectionItemState.PUBLISHED, 1L)
        operation = operation.withChunkState(second.id, CollectionChunkState.FAILED, CollectionItemState.FAILED, 2L)
        assertEquals(CollectionOperationPhase.BLOCKED, operation.phase)
        operation = operation.skipItems(second.itemIds.toSet(), 3L)
        assertEquals(CollectionOperationPhase.RUNNING, operation.phase)
        assertEquals(operation.chunks[2].id, operation.nextChunk?.id)
    }

    /** Cancellation stops unaccepted chunks, but a chunk MDK accepts afterwards settles as sent. */
    @Test
    fun lateAcceptanceAfterCancelReconcilesAsSent() {
        var operation = operation()
        val first = operation.chunks[0].id
        operation = operation.withChunkState(first, CollectionChunkState.UPLOADING, CollectionItemState.UPLOADING, 1L)
        operation = operation.cancelUnaccepted(2L)
        assertEquals(CollectionChunkState.UPLOADING, operation.chunks[0].state)
        assertEquals(CollectionChunkState.CANCELLED, operation.chunks[2].state)
        operation = operation.withChunkState(first, CollectionChunkState.CANCELLED, CollectionItemState.CANCELLED, 3L)
        operation = operation.withChunkState(first, CollectionChunkState.ACCEPTED, CollectionItemState.PUBLISHED, 4L)
        assertEquals(CollectionItemState.PUBLISHED, operation.items[0].state)
        assertEquals(CollectionOperationPhase.FINISHED, operation.phase)
    }

    /** Resuming without an unreadable item replans only its chunk and keeps intact chunk ids and tokens. */
    @Test
    fun resumeReplansOnlyChunksThatLostItems() {
        val planned = media(4, 10L)
        val base =
            AttachmentCollectionOperation(
                id = "op",
                accountRef = "a",
                groupIdHex = "g",
                items = planned.map { CollectionItem(it.id, "content://m/${it.id}", it.kind) },
                chunks =
                    planAttachmentChunks(
                        "op",
                        planned,
                        CollectionPlanLimits(maxItemsPerChunk = 2),
                    ),
                createdAtMs = 0L,
                updatedAtMs = 0L,
                paused = true,
            )
        val tokened = base.withChunkToken(base.chunks[0].id, "t0", 1L)
        val resumed = tokened.resumedWithout(setOf("m3"), generation = 1, nowMs = 2L)
        assertFalse(resumed.paused)
        assertEquals(tokened.chunks[0].id, resumed.chunks[0].id)
        assertEquals("t0", resumed.chunks[0].clientToken)
        assertNotEquals(tokened.chunks[1].id, resumed.chunks[1].id)
        assertEquals(listOf("m2"), resumed.chunks[1].itemIds)
        assertEquals(CollectionItemState.SKIPPED, resumed.items.single { it.id == "m3" }.state)
        assertTrue(resumed.chunks[0].carriesCaption)
    }
}
