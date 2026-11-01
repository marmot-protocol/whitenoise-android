package dev.ipf.whitenoise.android.state

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The collection journal holds bounded metadata only, scoped and expiring as #2576 requires. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AttachmentCollectionJournalTest {
    @get:Rule
    val folder = TemporaryFolder()

    private var now = 1_000L

    /** A journal under a temporary no-backup directory with a test clock. */
    private fun journal() = AttachmentCollectionJournal(folder.root, clock = { now })

    /** An operation of [count] documents whose URIs are [uriLength] characters long. */
    private fun operation(
        id: String = "op",
        account: String = "account",
        group: String = "group",
        count: Int = 3,
        uriLength: Int = 20,
    ): AttachmentCollectionOperation {
        val planned = List(count) { PlannedCollectionItem("item$it", CollectionItemKind.DOCUMENT, 1L) }
        return AttachmentCollectionOperation(
            id = id,
            accountRef = account,
            groupIdHex = group,
            items =
                planned.mapIndexed { index, item ->
                    CollectionItem(item.id, "content://p/$index/" + "x".repeat(uriLength), item.kind)
                },
            chunks = planAttachmentChunks(id, planned),
            createdAtMs = now,
            updatedAtMs = now,
        )
    }

    /** A written operation reads back paused, with ids, tokens and states intact and no attachment bytes. */
    @Test
    fun roundTripKeepsMetadataAndRestoresPaused() {
        val journal = journal()
        val written = operation().withChunkToken(operation().chunks[0].id, "token-0", now)
        assertTrue(journal.write(written))
        val read = journal.read("account", "group").single()
        assertTrue(read.paused)
        assertEquals(written.items, read.items)
        assertEquals(written.chunks, read.chunks)
        val text =
            folder.root
                .walkTopDown()
                .filter { it.isFile }
                .single()
                .readText()
        assertFalse("captions or names never reach the journal", text.contains("caption\":\""))
    }

    /** Records are scoped: another account or conversation sees nothing. */
    @Test
    fun recordsAreScopedToAccountAndConversation() {
        val journal = journal()
        journal.write(operation())
        assertTrue(journal.read("other", "group").isEmpty())
        assertTrue(journal.read("account", "other").isEmpty())
        assertEquals(1, journal.read("account", "group").size)
    }

    /** A record untouched for more than 24 hours is deleted when read or swept. */
    @Test
    fun expiredRecordsAreDeleted() {
        val journal = journal()
        journal.write(operation())
        now += ATTACHMENT_COLLECTION_JOURNAL_TTL_MS + 1
        assertTrue(journal.read("account", "group").isEmpty())
        journal.write(operation(id = "later").copy(updatedAtMs = 0L))
        journal.sweepExpired()
        assertTrue(folder.root.walkTopDown().none { it.isFile })
    }

    /** A record too large with URIs is kept without them, under 256 KiB; more than 100 items is refused. */
    @Test
    fun sizeAndCountCapsAreEnforced() {
        val journal = journal()
        assertTrue(journal.write(operation(count = 100, uriLength = 4_000)))
        val file = folder.root.walkTopDown().single { it.isFile }
        assertTrue(file.length() <= ATTACHMENT_COLLECTION_JOURNAL_MAX_BYTES)
        assertTrue(
            journal
                .read("account", "group")
                .single()
                .items
                .all { it.uri.isEmpty() },
        )
        assertFalse(journal.write(operation(id = "big", count = 101)))
    }

    /** Sign-out and destination invalidation delete exactly the matching records. */
    @Test
    fun accountAndConversationDeletion() {
        val journal = journal()
        journal.write(operation(id = "a"))
        journal.write(operation(id = "b", group = "other"))
        journal.write(operation(id = "c", account = "second"))
        journal.deleteConversation("account", "group")
        assertTrue(journal.read("account", "group").isEmpty())
        assertEquals(1, journal.read("account", "other").size)
        journal.deleteAccount("account")
        assertTrue(journal.read("account", "other").isEmpty())
        assertEquals(1, journal.read("second", "group").size)
    }

    /** The registry deletes a record as soon as its operation finishes or is discarded. */
    @Test
    fun registryDeletesOnTerminalSuccessAndDiscard() {
        val registry = AttachmentCollectionRegistry(journal(), clock = { now })
        val operation = operation(count = 1)
        assertTrue(registry.start(operation))
        registry.update(operation.id) {
            it.withChunkState(it.chunks[0].id, CollectionChunkState.ACCEPTED, CollectionItemState.PUBLISHED, now)
        }
        assertNull(registry.operations.value[operation.id])
        assertTrue(folder.root.walkTopDown().none { it.isFile })
        val second = operation(id = "second")
        registry.start(second)
        registry.discard(second.id)
        assertTrue(folder.root.walkTopDown().none { it.isFile })
    }
}
