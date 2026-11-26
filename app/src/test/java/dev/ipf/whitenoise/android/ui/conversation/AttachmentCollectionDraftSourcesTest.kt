package dev.ipf.whitenoise.android.ui.conversation

import android.net.Uri
import dev.ipf.whitenoise.android.FileProviderStrategyCacheRule
import dev.ipf.whitenoise.android.media.AttachmentPlaintextCache
import dev.ipf.whitenoise.android.media.MediaCacheDirs
import dev.ipf.whitenoise.android.state.ATTACHMENT_COLLECTION_JOURNAL_TTL_MS
import dev.ipf.whitenoise.android.state.AttachmentCollectionJournal
import dev.ipf.whitenoise.android.state.AttachmentCollectionRegistry
import dev.ipf.whitenoise.android.state.CollectionChunkState
import dev.ipf.whitenoise.android.state.CollectionDraftSourceFiles
import dev.ipf.whitenoise.android.state.CollectionItemState
import dev.ipf.whitenoise.android.state.DraftStoredFile
import dev.ipf.whitenoise.android.state.PendingAttachment
import dev.ipf.whitenoise.android.state.awaitAttachmentCollectionJournal
import dev.ipf.whitenoise.android.state.mediaSendReconciliationAppState
import dev.ipf.whitenoise.android.state.withChunkState
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/** Exercises the operation's sources at the same seam where the composer hands them off. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AttachmentCollectionDraftSourcesTest {
    @get:Rule
    val strategyCache = FileProviderStrategyCacheRule()

    /** Both stored bodies stay readable from the journal after the composer releases its presentation files. */
    @Test
    fun laterDocumentRemainsReadableAfterComposerLeavesAndJournalReopens() =
        runTest {
            val context = RuntimeEnvironment.getApplication()
            val composer = File(context.cacheDir, "composer_paste/native_drafts")
            assertTrue(composer.mkdirs() || composer.isDirectory)
            val originals = listOf("first.zip", "second.zip").map { File(composer, it) }
            val bodies = listOf(byteArrayOf(1, 2, 3), byteArrayOf(4, 5, 6))
            originals.zip(bodies).forEach { (file, bytes) -> file.writeBytes(bytes) }
            val shelf = originals.map(Uri::fromFile)
            val prepared =
                shelf
                    .mapIndexed { index, uri ->
                        uri to
                            PendingAttachment(
                                byteArrayOf(),
                                "application/zip",
                                originals[index].name,
                                draftFile = DraftStoredFile("document-$index", bodies[index].size.toLong()),
                            )
                    }.toMap()
            val request = CollectionSendRequest(emptyList(), shelf, null, preparedDocuments = prepared)
            val now = System.currentTimeMillis()
            val plan = planCollectionOperation(context, "account", "group", request, now)
            val sources = CollectionDraftSourceFiles(context.cacheDir)
            val journal = AttachmentCollectionJournal(context.noBackupFilesDir, draftSources = sources)
            val copied = mutableListOf<File>()
            try {
                val owned =
                    ownCollectionDraftSources(context, plan, request) { pending, target ->
                        val index = checkNotNull(pending.draftFile).attachmentId.substringAfterLast('-').toInt()
                        target.writeBytes(bodies[index])
                        copied += target
                        true
                    }
                assertTrue(journal.write(owned))
                assertEquals(2, owned.chunks.size)
                assertTrue(composer.deleteRecursively())
                val shared = File(context.cacheDir, MediaCacheDirs.SHARED)
                val ordinaryShare = File(shared, "ordinary.zip").apply { writeBytes(byteArrayOf(1, 2)) }
                AttachmentPlaintextCache.trimDirectoryToByteCap(shared, maxBytes = 0)
                assertFalse(ordinaryShare.exists())
                assertTrue(copied.all(File::exists))

                val reopened = journal.read("account", "group").single()
                val laterUri = Uri.parse(reopened.items[1].uri)
                val laterBytes = context.contentResolver.openInputStream(laterUri)?.use { it.readBytes() }

                assertArrayEquals(bodies[1], laterBytes)
                journal.delete("account", "group", plan.id)
                assertTrue(copied.none(File::exists))
            } finally {
                journal.delete("account", "group", plan.id)
                composer.deleteRecursively()
            }
        }

    /** A blocked collection keeps its private sources across both expiry entry points until discard. */
    @Test
    fun expiryKeepsBlockedSourcesUntilDiscard() {
        val context = RuntimeEnvironment.getApplication()
        var now = System.currentTimeMillis()
        val request = CollectionSendRequest(emptyList(), listOf(Uri.parse("content://fixture/file")), null)
        val operation = planCollectionOperation(context, "account", "group", request, now)
        val blocked =
            operation.withChunkState(
                operation.chunks.single().id,
                CollectionChunkState.FAILED,
                CollectionItemState.FAILED,
                now,
            )
        val sources = CollectionDraftSourceFiles(context.cacheDir)
        val journal = AttachmentCollectionJournal(context.noBackupFilesDir, { now }, draftSources = sources)
        val registry = AttachmentCollectionRegistry(journal) { now }
        val file = sources.fileFor(context, blocked, 0, ".bin")
        checkNotNull(file.parentFile).mkdirs()
        file.writeBytes(byteArrayOf(1))
        try {
            assertTrue(registry.start(blocked))
            assertTrue(journal.write(blocked.copy(id = "unclaimed")))
            now += ATTACHMENT_COLLECTION_JOURNAL_TTL_MS + 1
            registry.sweepExpired()
            assertTrue(registry.journaledFor("account", "group").isEmpty())
            assertTrue(registry.journaledFor("account", "other").isEmpty())
            assertTrue(file.exists())
            val records = File(context.noBackupFilesDir, "attachment_collections")
            assertEquals(1, records.walkTopDown().count(File::isFile))
            registry.discard(blocked.id)
            awaitAttachmentCollectionJournal()
            assertFalse(file.exists())
        } finally {
            journal.delete("account", "group", blocked.id)
        }
    }

    /** A custom native MIME survives an image-like display name and uses the ordinary raw-document reader. */
    @Test
    fun restoredStoredDocumentKeepsItsMimeAndBytes() =
        runTest {
            val app = mediaSendReconciliationAppState()
            val context = app.appContext
            val mime = "application/x-whitenoise-fixture"
            val body = byteArrayOf(4, 5, 6)
            val picked = Uri.parse("content://fixture/fake.png")
            val pending =
                PendingAttachment(
                    byteArrayOf(),
                    mime,
                    "fake.png",
                    draftFile = DraftStoredFile("native-id", body.size.toLong()),
                )
            val request =
                CollectionSendRequest(emptyList(), listOf(picked), null, preparedDocuments = mapOf(picked to pending))
            val operation = planCollectionOperation(context, "account", "group", request, System.currentTimeMillis())
            val journal =
                AttachmentCollectionJournal(
                    context.noBackupFilesDir,
                    draftSources = CollectionDraftSourceFiles(context.cacheDir),
                )
            try {
                val owned =
                    ownCollectionDraftSources(context, operation, request) { _, target ->
                        target.writeBytes(body)
                        true
                    }
                assertTrue(journal.write(owned))
                val restored =
                    journal
                        .read("account", "group")
                        .single()
                        .items
                        .single()
                val uri = Uri.parse(restored.uri)
                val stored = checkNotNull(restored.storedDraft)
                assertEquals("native-id", stored.attachmentId)
                val read =
                    ConversationAttachmentReader(app, context)
                        .readPickedDocuments(listOf(uri), ownedMime = mapOf(uri to stored.mediaType))
                        .attachments
                        .single()
                assertEquals("fake.png", read.fileName)
                assertEquals(mime, read.mediaType)
                assertArrayEquals(body, read.plaintextBytes)
            } finally {
                journal.delete("account", "group", operation.id)
                app.mutationsScope.cancel()
            }
        }
}
