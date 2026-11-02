package dev.ipf.whitenoise.android.ui.conversation

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import androidx.test.core.app.ApplicationProvider
import dev.ipf.whitenoise.android.media.MediaCacheDirs
import dev.ipf.whitenoise.android.media.VideoMetadataScrubber
import dev.ipf.whitenoise.android.media.aviVideoBytes
import dev.ipf.whitenoise.android.media.containsAscii
import dev.ipf.whitenoise.android.media.minimalMp4
import dev.ipf.whitenoise.android.media.videoFixture
import dev.ipf.whitenoise.android.state.DraftPersistence
import dev.ipf.whitenoise.android.state.DraftStore
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.ui.conversation.media.FileBackedPickBudget
import dev.ipf.whitenoise.android.ui.conversation.media.FileBackedSendLimits
import dev.ipf.whitenoise.android.ui.conversation.media.FileBackedSendStaging
import dev.ipf.whitenoise.android.ui.conversation.media.StagedUploadSource
import dev.ipf.whitenoise.android.ui.conversation.media.uploadSourcesDirectory
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ConversationAttachmentReaderFileBackedTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val uploadSources = uploadSourcesDirectory(context.cacheDir)

    /** Registers the scripted provider and starts from an empty staging directory. */
    @Before
    fun setUp() {
        Robolectric.setupContentProvider(ScriptedPickProvider::class.java, AUTHORITY)
        File(context.cacheDir, MediaCacheDirs.UPLOAD_SOURCES).deleteRecursively()
    }

    /** Releases the scripted picks and anything a test left staged. */
    @After
    fun tearDown() {
        ScriptedPickProvider.picks.clear()
        File(context.cacheDir, MediaCacheDirs.UPLOAD_SOURCES).deleteRecursively()
    }

    /** A document declared above the in-memory cap is staged to a private file instead of being refused. */
    @Test
    fun declaredLargeDocumentIsStagedToAFileWhenFileBackedSendsAreAllowed() =
        runBlocking {
            val bytes = ByteArray(4096) { (it % 251).toByte() }
            val uri = pick("report.pdf", "application/pdf", bytes, declaredSize = 40L * MIB)

            val reported = mutableListOf<StagedUploadSource>()
            val outcome =
                reader().readPickedDocuments(
                    listOf(uri),
                    allowFileBacked = true,
                    staging = FileBackedSendStaging(reported::add),
                )

            val attachment = outcome.attachments.single()
            val source = checkNotNull(attachment.sourceFile)
            assertEquals("the caller hears about the snapshot before it is handed back", listOf(source), reported)
            assertTrue(outcome.failures.isEmpty())
            assertEquals(0L, outcome.totalBytes)
            assertNull(attachment.inMemoryBytes)
            assertEquals("report.pdf", attachment.fileName)
            assertEquals("application/pdf", attachment.mediaType)
            assertArrayEquals(bytes, source.file.readBytes())
            assertEquals(uploadSources.canonicalFile, source.file.parentFile?.canonicalFile)
            source.close()
        }

    /** Without file-backed sends (replies and native draft staging) the same pick keeps today's refusal. */
    @Test
    fun declaredLargeDocumentIsStillRefusedWhenFileBackedSendsAreNotAllowed() =
        runBlocking {
            val uri = pick("report.pdf", "application/pdf", ByteArray(16), declaredSize = 40L * MIB)

            val outcome = reader().readPickedDocuments(listOf(uri))

            assertTrue(outcome.attachments.isEmpty())
            assertEquals(setOf(DocumentReadFailure.TOO_LARGE), outcome.failures)
            assertNull("native draft staging never receives a file-backed pick", reader().readDocumentDraft(uri))
            assertFalse(uploadSources.exists() && uploadSources.list()!!.isNotEmpty())
        }

    /** A provider that hides its size is read in memory first and moved to a file once it overflows the cap. */
    @Test
    fun undeclaredDocumentThatOverflowsMemoryIsStagedToAFile() =
        runBlocking {
            val bytes = ByteArray(2048) { 7 }
            val uri = pick("notes.bin", "application/octet-stream", bytes, declaredSize = null)

            val outcome = reader().readPickedDocuments(listOf(uri), bytesBudget = 1024, allowFileBacked = true)

            val source = checkNotNull(outcome.attachments.single().sourceFile)
            assertEquals(2048L, source.byteCount)
            source.close()
        }

    /** A document above the native per-file ceiling is refused without leaving a partial snapshot. */
    @Test
    fun documentAboveThePerFileCeilingIsRefusedWithoutAPartialSnapshot() =
        runBlocking {
            val uri = pick("huge.iso", "application/octet-stream", ByteArray(4096), declaredSize = null)

            val outcome =
                reader(perFileBytes = 1024).readPickedDocuments(listOf(uri), bytesBudget = 0, allowFileBacked = true)

            assertTrue(outcome.attachments.isEmpty())
            assertEquals(setOf(DocumentReadFailure.TOO_LARGE), outcome.failures)
            assertTrue(uploadSources.list().orEmpty().isEmpty())
        }

    /** A large video becomes a file-backed album item with its own name, type and poster metadata slot. */
    @Test
    fun largeVideoIsStagedToAFileForTheAlbum() =
        runBlocking {
            val uri = pick("clip.mp4", "video/mp4", minimalMp4(4096), declaredSize = 40L * MIB)
            val reader = reader()

            val outcome = reader.readPickedImages(listOf(uri), checkNotNull(reader.fileBackedBudget()))

            val attachment = outcome.attachments.single()
            assertFalse(outcome.albumOverflowed)
            assertEquals("video/mp4", attachment.mediaType)
            assertEquals("clip.mp4", attachment.fileName)
            assertEquals("the poster metadata was read from the snapshot", "0x0", attachment.dim)
            assertEquals(4096L, attachment.byteCount)
            assertNull(attachment.inMemoryBytes)
            checkNotNull(attachment.sourceFile).close()
        }

    /** Once an album has spent its native ciphertext bound, the next large video overflows instead of joining. */
    @Test
    fun albumStopsAddingVideosOnceItsCiphertextBoundIsSpent() =
        runBlocking {
            val first = pick("first.mp4", "video/mp4", minimalMp4(1024), declaredSize = 1024)
            val second = pick("second.mp4", "video/mp4", minimalMp4(1024), declaredSize = 1024)
            val albumLimits = FileBackedSendLimits(perFileBytes = 1024, batchCiphertextBytes = 1536)
            val budget = FileBackedPickBudget(uploadSources, albumLimits)

            val outcome = reader().readPickedImages(listOf(first, second), budget, inMemoryBytesBudget = 0)

            assertEquals(listOf("first.mp4"), outcome.attachments.map { it.fileName })
            assertTrue(outcome.albumOverflowed)
            outcome.attachments.forEach { checkNotNull(it.sourceFile).close() }
            assertTrue("the refused video leaves no snapshot", uploadSources.list().orEmpty().isEmpty())
        }

    /** A named pick is one picked file: reading two URIs under one pick fails before any worker decodes them. */
    @Test
    fun aNamedPickReadsAtMostOneFile() {
        val first = pick("first.mp4", "video/mp4", minimalMp4(1024), declaredSize = 1024)
        val second = pick("second.mp4", "video/mp4", minimalMp4(1024), declaredSize = 1024)
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { reader().readPickedImages(listOf(first, second), pick = "slot-a") }
        }
    }

    /**
     * A video small enough for memory that follows a staged one still uploads from a file, so its two
     * disk copies must fit beside the staged copy owed to the first, or it is left out for storage.
     */
    @Test
    fun anInMemoryVideoAfterAStagedOneIsLeftOutWhenItsCopiesDoNotFit() =
        runBlocking {
            val large = pick("large.mp4", "video/mp4", minimalMp4(4096), declaredSize = 4096)
            val small = pick("small.mp4", "video/mp4", minimalMp4(512), declaredSize = 512)
            val albumLimits = FileBackedSendLimits(perFileBytes = 8192, batchCiphertextBytes = 16_384)
            // Room for the large video's two copies with 100 bytes to spare, shrinking as snapshots land.
            val free = { APP_RESERVE_BYTES + 2 * 4096 + 100 - stagedBytes(uploadSources) }
            val budget = FileBackedPickBudget(uploadSources, albumLimits, usableBytes = free)

            val outcome = reader().readPickedImages(listOf(large, small), budget, inMemoryBytesBudget = 1024)

            assertEquals(listOf("large.mp4"), outcome.attachments.map { it.fileName })
            assertTrue("the small video's 1024 bytes do not fit beside the 4096 still owed", outcome.storageUnavailable)
            assertFalse(outcome.albumOverflowed)
            outcome.attachments.forEach { checkNotNull(it.sourceFile).close() }
        }

    /**
     * A large tagged video's snapshot loses its private metadata in place before it is handed on: the staged file is
     * exactly the scrubbed clip, keeps the reserved size, and is read-only again (#3376).
     */
    @Test
    fun largeVideoSnapshotIsScrubbedBeforeItIsHandedOn() =
        runBlocking {
            val tagged = videoFixture("mov-quicktime-keys.mov")
            val uri = pick("tagged.mov", "video/quicktime", tagged, declaredSize = 40L * MIB)
            val reader = reader()

            val outcome = reader.readPickedImages(listOf(uri), checkNotNull(reader.fileBackedBudget()))

            val source = checkNotNull(outcome.attachments.single().sourceFile)
            val staged = source.file.readBytes()
            assertArrayEquals(scrubbed(tagged), staged)
            assertFalse(staged.containsAscii("WNPrivateMake"))
            assertEquals(tagged.size.toLong(), source.byteCount)
            assertFalse("the snapshot is read-only again", source.file.canWrite())
            source.close()
        }

    /**
     * A large video whose metadata cannot be removed is refused with the distinct failure: its snapshot is deleted,
     * nothing is reported to the send, and the budget keeps every byte it had (#3376).
     */
    @Test
    fun largeVideoWhoseMetadataCannotBeRemovedLeavesNoSnapshotOrReservation() =
        runBlocking {
            val uri = pick("clip.avi", "video/x-msvideo", aviVideoBytes(4096), declaredSize = 40L * MIB)
            val reported = mutableListOf<StagedUploadSource>()
            val staging = FileBackedSendStaging(reported::add)
            val reader = reader()
            val budget = checkNotNull(reader.fileBackedBudget(staging))
            val room = budget.nextPickMaxBytes()

            val outcome = reader.readPickedImages(listOf(uri), budget)

            assertTrue(outcome.attachments.isEmpty())
            assertTrue(outcome.videoMetadataRefused)
            assertFalse(outcome.albumOverflowed || outcome.storageUnavailable)
            assertTrue(reported.isEmpty())
            assertEquals(room, budget.nextPickMaxBytes())
            assertEquals(0L, staging.largestUploadWriteBytes)
            assertTrue(uploadSources.list().orEmpty().isEmpty())
        }

    /** A large video picked through Files is scrubbed in its snapshot, and one that cannot be is refused (#3376). */
    @Test
    fun largeVideoDocumentIsScrubbedOrRefused() =
        runBlocking {
            val tagged = videoFixture("mkv-tagged.mkv")
            val good = pick("tagged.mkv", "video/x-matroska", tagged, declaredSize = 40L * MIB)
            val bad = pick("clip.avi", "video/avi", aviVideoBytes(2048), declaredSize = 40L * MIB)

            val outcome = reader().readPickedDocuments(listOf(good, bad), allowFileBacked = true)

            val source = checkNotNull(outcome.attachments.single().sourceFile)
            assertArrayEquals(scrubbed(tagged), source.file.readBytes())
            assertEquals(setOf(DocumentReadFailure.VIDEO_METADATA), outcome.failures)
            source.close()
            assertTrue(uploadSources.list().orEmpty().isEmpty())
        }

    /** [bytes] as the scrubber leaves them. */
    private fun scrubbed(bytes: ByteArray): ByteArray = bytes.copyOf().also { VideoMetadataScrubber.scrub(it) }

    /** Bytes of every snapshot currently written under [directory]. */
    private fun stagedBytes(directory: File): Long = directory.walk().filter(File::isFile).sumOf(File::length)

    /** Builds the production reader with inert draft persistence and scripted native limits. */
    private fun reader(perFileBytes: Long = 64L * MIB): ConversationAttachmentReader {
        val persistence =
            object : DraftPersistence {
                /** Starts every reader with no saved drafts. */
                override fun read(): Map<String, String> = emptyMap()

                /** Drops draft writes; these tests read picks only. */
                override fun write(
                    key: String,
                    value: String?,
                ) = Unit
            }
        val state =
            WhiteNoiseAppState(
                context,
                DraftStore(persistence),
                { null },
                emptyList(),
                "fixture",
                inboundShareTextStager = { _, _, _ -> },
            )
        return ConversationAttachmentReader(
            state,
            context,
            fileBackedLimits = { FileBackedSendLimits(perFileBytes = perFileBytes, batchCiphertextBytes = 900L * MIB) },
        )
    }

    /** Publishes one scripted pick through the provider and returns its content Uri. */
    private fun pick(
        name: String,
        mediaType: String,
        bytes: ByteArray,
        declaredSize: Long?,
    ): Uri {
        val file = File(context.cacheDir, "provider-${System.nanoTime()}-$name").apply { writeBytes(bytes) }
        ScriptedPickProvider.picks[name] = ScriptedPick(file, mediaType, declaredSize)
        return Uri.parse("content://$AUTHORITY/$name")
    }
}

/** One provider entry: the bytes it streams, its MIME type and the size it declares, if any. */
data class ScriptedPick(
    val file: File,
    val mediaType: String,
    val declaredSize: Long?,
)

/** A document provider whose declared sizes need not match the bytes it streams. */
class ScriptedPickProvider : ContentProvider() {
    /** Needs no setup; picks are scripted through the companion map. */
    override fun onCreate(): Boolean = true

    /** Reports the scripted MIME type, as a document provider would. */
    override fun getType(uri: Uri): String? = picks[uri.lastPathSegment]?.mediaType

    /** Answers name and size queries with the scripted values; a null size reads as unknown. */
    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? {
        val pick = picks[uri.lastPathSegment] ?: return null
        val columns = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        return MatrixCursor(columns).apply {
            addRow(
                columns.map { column ->
                    when (column) {
                        OpenableColumns.DISPLAY_NAME -> uri.lastPathSegment
                        OpenableColumns.SIZE -> pick.declaredSize
                        else -> null
                    }
                },
            )
        }
    }

    /** Streams the scripted bytes, which need not match the declared size. */
    override fun openFile(
        uri: Uri,
        mode: String,
    ): ParcelFileDescriptor? {
        val pick = picks[uri.lastPathSegment] ?: return null
        return ParcelFileDescriptor.open(pick.file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    /** Read-only provider. */
    override fun insert(
        uri: Uri,
        values: ContentValues?,
    ): Uri? = null

    /** Read-only provider. */
    override fun delete(
        uri: Uri,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0

    /** Read-only provider. */
    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0

    companion object {
        val picks = mutableMapOf<String, ScriptedPick>()
    }
}

private const val AUTHORITY = "dev.ipf.whitenoise.test.largepicks"
private const val MIB = 1024L * 1024L

// The free space every file-backed staging check leaves to the rest of the app.
private const val APP_RESERVE_BYTES = 64L * 1024L * 1024L
