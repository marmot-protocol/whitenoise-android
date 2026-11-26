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

    /** Discard stops remaining uploads while leaving an accepted stored-draft cleanup recoverable after expiry. */
    @Test
    fun discardingAcceptedCollectionPreservesOnlyCleanup() {
        val journal = journal()
        val registry = AttachmentCollectionRegistry(journal) { now }
        val planned = operation()
        val original = CollectionStoredDraft("native", 42L, "application/example")
        val items = planned.items.map { item -> item.copy(storedDraft = original.copy(attachmentId = item.id)) }
        val accepted =
            planned
                .copy(items = items)
                .withChunkState(
                    planned.chunks.first().id,
                    CollectionChunkState.ACCEPTED,
                    CollectionItemState.PUBLISHED,
                    now,
                )
        assertTrue(registry.start(accepted))
        registry.discard(accepted.id)
        awaitAttachmentCollectionJournal()
        now += ATTACHMENT_COLLECTION_JOURNAL_TTL_MS + 1
        val coldRegistry = AttachmentCollectionRegistry(journal) { now }
        coldRegistry.sweepExpired()

        val restored = coldRegistry.journaledFor(accepted.accountRef, accepted.groupIdHex).single()
        assertEquals(CollectionOperationPhase.FINISHED, restored.phase)
        assertTrue(restored.needsStoredDraftCleanup)
        assertEquals(original.copy(attachmentId = planned.items.first().id), restored.items.first().storedDraft)
        assertTrue(restored.items.all { it.uri.isEmpty() })
    }

    /** A later unresolved token survives Discard even when an earlier accepted ID awaits native cleanup. */
    @Test
    fun discardWaitsForLaterDraftToken() {
        val journal = journal()
        val registry = AttachmentCollectionRegistry(journal) { now }
        val plan = operation(count = 2)
        val items = plan.items.map { it.copy(storedDraft = CollectionStoredDraft(it.id, 3L, "application/example")) }
        val paused =
            plan
                .copy(items = items, paused = true)
                .withChunkState(plan.chunks[0].id, CollectionChunkState.ACCEPTED, CollectionItemState.PUBLISHED, now)
                .withChunkToken(plan.chunks[1].id, "later", now)
        assertTrue(paused.needsStoredDraftCleanup)
        assertFalse(paused.canDiscard)
        assertTrue(registry.start(paused))
        registry.discard(paused.id)
        awaitAttachmentCollectionJournal()

        val restored = journal.read(paused.accountRef, paused.groupIdHex).single()
        assertEquals("later", restored.nextChunk?.clientToken)
        assertFalse(restored.canDiscard)
    }

    /** A failed same-token retry must leave both the earlier live state and its durable record intact. */
    @Test
    fun failedRewritePreservesEarlierJournal() {
        val journal = journal()
        val registry = AttachmentCollectionRegistry(journal) { now }
        val planned = operation()
        val original = planned.withChunkToken(planned.chunks.first().id, "accepted-before-retry", now)
        assertTrue(registry.start(original))
        val file = folder.root.walkTopDown().single { it.isFile }
        assertTrue(java.io.File(file.parentFile, file.name + ".tmp").mkdir())

        assertFalse(registry.start(original.copy(updatedAtMs = now + 1)))

        assertEquals(original, registry.operations.value[original.id])
        val saved = journal.read(original.accountRef, original.groupIdHex).single()
        assertEquals("accepted-before-retry", saved.nextChunk?.clientToken)
    }

    /** An expiry cleanup that cannot rewrite the journal still returns adoptable durable metadata. */
    @Test
    fun failedExpiryRewriteKeepsRecoverableUris() {
        val journal = journal()
        val planned = operation(count = 1)
        val source = CollectionStoredDraft("native", 3L, "application/example")
        val owned = planned.items.single().copy(storedDraft = source)
        val recorded = planned.copy(items = listOf(owned)).withChunkToken(planned.chunks.single().id, "pending", now)
        assertTrue(journal.write(recorded))
        val file = folder.root.walkTopDown().single { it.isFile }
        assertTrue(java.io.File(file.parentFile, file.name + ".tmp").mkdir())
        now += ATTACHMENT_COLLECTION_JOURNAL_TTL_MS + 1

        val reopened = journal.read(recorded.accountRef, recorded.groupIdHex).single()

        assertEquals(owned.uri, reopened.items.single().uri)
        assertTrue(journal.contains(reopened))
    }

    /** A skipped pick still owns a shared native ID even when a separate occurrence was accepted. */
    @Test
    fun skippedAliasPreservesSharedOriginal() {
        val planned = operation()
        val shared = CollectionStoredDraft("shared", 10L, "application/example")
        val first = planned.items[0].copy(state = CollectionItemState.PUBLISHED, storedDraft = shared)
        val skipped = planned.items[1].copy(state = CollectionItemState.SKIPPED, storedDraft = shared)
        val sent =
            planned.items[2].copy(
                state = CollectionItemState.PUBLISHED,
                storedDraft =
                    shared.copy(
                        attachmentId = "sent",
                    ),
            )
        val restored =
            decodeCollectionOperation(
                encodeCollectionOperation(
                    planned.copy(items = listOf(first, skipped, sent)),
                    withUris = true,
                ),
            )
        assertEquals(listOf(sent.storedDraft), restored.pendingDraftFiles)
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
        awaitAttachmentCollectionJournal()
        assertTrue(folder.root.walkTopDown().none { it.isFile })
        val second = operation(id = "second")
        registry.start(second)
        registry.discard(second.id)
        awaitAttachmentCollectionJournal()
        assertTrue(folder.root.walkTopDown().none { it.isFile })
    }

    /** Account or conversation removal during copying cannot recreate its journal after cleanup. */
    @Test
    fun destinationRemovalInvalidatesWaitingAdmissionsOnlyForThatDestination() {
        val registry = AttachmentCollectionRegistry(journal(), clock = { now })
        val leaving = registry.admissions.begin("account", "group")
        val deleting = registry.admissions.begin("second", "deleted")
        val retained = registry.admissions.begin("second", "retained")
        registry.forgetAccount("account")
        registry.invalidateConversation("second", "deleted")

        assertFalse(registry.start(operation(), leaving))
        assertFalse(registry.start(operation(id = "deleted", account = "second", group = "deleted"), deleting))
        assertTrue(registry.start(operation(id = "retained", account = "second", group = "retained"), retained))

        assertEquals(setOf("retained"), registry.operations.value.keys)
        assertTrue(journal().read("account", "group").isEmpty())
        assertTrue(journal().read("second", "deleted").isEmpty())
    }

    /** A second Resume of the same operation is refused until the first one ends its claim. */
    @Test
    fun resumeClaimAdmitsOneResumeAtATime() {
        assertTrue(claimCollectionResume("claim-op"))
        assertFalse(claimCollectionResume("claim-op"))
        assertTrue(claimCollectionResume("claim-other"))
        releaseCollectionResume("claim-op")
        assertTrue(claimCollectionResume("claim-op"))
        releaseCollectionResume("claim-op")
        releaseCollectionResume("claim-other")
    }

    /** A write queued before a discard can never land after it, so a discarded record stays deleted. */
    @Test
    fun discardAfterAnUpdateLeavesNoRecord() {
        val registry = AttachmentCollectionRegistry(journal(), clock = { now })
        val operation = operation(count = 3)
        assertTrue(registry.start(operation))
        repeat(20) { round ->
            registry.update(operation.id) { it.copy(updatedAtMs = round.toLong()) }
        }
        registry.discard(operation.id)
        awaitAttachmentCollectionJournal()
        assertTrue(folder.root.walkTopDown().none { it.isFile })
    }
}
