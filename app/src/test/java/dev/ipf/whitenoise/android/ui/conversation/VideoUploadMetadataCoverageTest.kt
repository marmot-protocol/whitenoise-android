package dev.ipf.whitenoise.android.ui.conversation

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import dev.ipf.marmotkit.MessageDraftAttachmentFfi
import dev.ipf.whitenoise.android.functionBody
import dev.ipf.whitenoise.android.media.MediaCacheDirs
import dev.ipf.whitenoise.android.media.VideoMetadataScrubber
import dev.ipf.whitenoise.android.media.aviVideoBytes
import dev.ipf.whitenoise.android.media.containsAscii
import dev.ipf.whitenoise.android.media.videoFixture
import dev.ipf.whitenoise.android.share.PrivateShareFiles
import dev.ipf.whitenoise.android.share.ShareFileImporter
import dev.ipf.whitenoise.android.share.SharePayload
import dev.ipf.whitenoise.android.share.ShareRequest
import dev.ipf.whitenoise.android.share.ShareSourceMetadata
import dev.ipf.whitenoise.android.state.DraftPersistence
import dev.ipf.whitenoise.android.state.DraftStore
import dev.ipf.whitenoise.android.state.PendingAttachment
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.ui.conversation.media.FileBackedSendLimits
import dev.ipf.whitenoise.android.ui.conversation.media.removePreparedVideoMetadata
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.File

/**
 * Invariant gate for #3376: every way a local video becomes an outgoing attachment hands onward only bytes whose
 * private container metadata is gone, and a video that cannot be cleaned is refused instead of sent as-is.
 *
 * The entry points are the visual picker (in memory, as a native draft and staged to a file), the Files picker (the
 * same three forms), incoming Android shares (imported into private files and read through the same readers) and
 * prepared drafts, including a native draft an older app version saved before videos were scrubbed. The in-app
 * camera captures photos only, so recorded videos reach the app through these same paths. Assertions inspect the
 * bytes and files the send queues for upload, not previews. Source checks pin the order inside the paths that a
 * behavioral fixture cannot reach without native bindings.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class VideoUploadMetadataCoverageTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val tagged = videoFixture("mp4-tagged.mp4")
    private val clean = tagged.copyOf().also { VideoMetadataScrubber.scrub(it) }

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

    /** Photo Picker videos read in memory, for a send or a native draft, carry only scrubbed bytes. */
    @Test
    fun visualPickerInMemoryAndDraftBytesAreScrubbed() =
        runBlocking {
            val uri = pick("picked.mp4", "video/mp4", tagged, declaredSize = tagged.size.toLong())
            val reader = reader()

            assertClean(
                reader
                    .readPickedImages(listOf(uri))
                    .attachments
                    .single()
                    .plaintextBytes,
            )
            assertClean(checkNotNull(reader.readVisualDraft(uri)).plaintextBytes)
        }

    /** A Photo Picker video too large for memory is staged to a file that is scrubbed before it is handed on. */
    @Test
    fun visualPickerFileBackedSnapshotIsScrubbed() =
        runBlocking {
            val uri = pick("large.mp4", "video/mp4", tagged, declaredSize = LARGE)
            val reader = reader()

            val outcome = reader.readPickedImages(listOf(uri), checkNotNull(reader.fileBackedBudget()))

            val source = checkNotNull(outcome.attachments.single().sourceFile)
            assertClean(source.file.readBytes())
            source.close()
        }

    /** Files-picker videos are scrubbed in memory, as native drafts and as staged files; other files stay as-is. */
    @Test
    fun filesPickerVideosAreScrubbedAndOtherDocumentsKeepTheirBytes() =
        runBlocking {
            val small = pick("document.mp4", "video/mp4", tagged, declaredSize = tagged.size.toLong())
            val large = pick("document-large.mp4", "video/mp4", tagged, declaredSize = LARGE)
            val generic = pick("movie.bin", "application/octet-stream", tagged, declaredSize = tagged.size.toLong())
            val reader = reader()

            val inMemory = reader.readPickedDocuments(listOf(small)).attachments.single()
            assertEquals("video/mp4", inMemory.mediaType)
            assertClean(inMemory.plaintextBytes)
            assertClean(checkNotNull(reader.readDocumentDraft(small)).plaintextBytes)
            val stagedRead = reader.readPickedDocuments(listOf(large), allowFileBacked = true)
            val staged = checkNotNull(stagedRead.attachments.single().sourceFile)
            assertClean(staged.file.readBytes())
            staged.close()
            assertArrayEquals(
                "generic documents stay byte-for-byte",
                tagged,
                reader
                    .readPickedDocuments(listOf(generic))
                    .attachments
                    .single()
                    .plaintextBytes,
            )
        }

    /** An incoming Android share is imported to a private file and read through the same scrubbing readers. */
    @Test
    fun incomingShareVideosAreScrubbed() =
        runBlocking {
            val files = PrivateShareFiles(context)
            val importer =
                ShareFileImporter(
                    files,
                    { _, _ -> ShareSourceMetadata("shared.mp4", "video/mp4", tagged.size.toLong()) },
                    { _, _ -> ByteArrayInputStream(tagged) },
                )
            val request =
                ShareRequest(
                    SharePayload(null, listOf(Uri.parse("content://external/shared-video")), "video/mp4"),
                    shortcutId = null,
                    requestId = "video-metadata-share",
                )
            try {
                val uri =
                    importer
                        .import(request)
                        .payload.streamUris
                        .single()
                val reader = reader()
                assertClean(checkNotNull(reader.readVisualDraft(uri)).plaintextBytes)
                assertClean(
                    reader
                        .readPickedImages(listOf(uri))
                        .attachments
                        .single()
                        .plaintextBytes,
                )
                assertClean(
                    reader
                        .readPickedDocuments(listOf(uri))
                        .attachments
                        .single()
                        .plaintextBytes,
                )
            } finally {
                files.leases.releaseRequest(request.requestId)
            }
        }

    /**
     * Prepared drafts are re-checked before a send reuses them: a legacy draft saved with private metadata is sent
     * scrubbed without mutating the native draft's array, clean drafts and photos are reused as-is, and a video that
     * cannot be cleaned is refused.
     */
    @Test
    fun preparedDraftsAreScrubbedBeforeTheyAreReused() {
        val legacyNative = draft("legacy.mp4", "video/mp4", tagged.copyOf())
        val legacy = legacyNative.toPendingAttachment()
        val alreadyClean = PendingAttachment(clean.copyOf(), "video/mp4", "clean.mp4")
        val photo = PendingAttachment(byteArrayOf(1, 2, 3), "image/jpeg", "photo.jpg")
        val avi = draft("old.avi", "video/x-msvideo", aviVideoBytes()).toPendingAttachment()

        val checked =
            removePreparedVideoMetadata(
                mapOf("legacy" to legacy, "clean" to alreadyClean, "photo" to photo, "avi" to avi),
            )

        assertClean(checkNotNull(checked.ready["legacy"]).plaintextBytes)
        assertArrayEquals("the native draft's own array is never rewritten", tagged, legacyNative.plaintext)
        assertSame(alreadyClean, checked.ready["clean"])
        assertSame(photo, checked.ready["photo"])
        assertEquals(setOf("avi"), checked.refused)
        assertNull(checked.ready["avi"])
    }

    /** A video whose metadata cannot be removed is refused on every reader path, never sent with its own bytes. */
    @Test
    fun unremovableVideosAreRefusedOnEveryReaderPath() =
        runBlocking {
            val bytes = aviVideoBytes()
            val small = pick("old.mp4", "video/mp4", bytes, declaredSize = bytes.size.toLong())
            val large = pick("old-large.mp4", "video/mp4", bytes, declaredSize = LARGE)
            val reader = reader()

            val visual = reader.readPickedImages(listOf(small))
            assertTrue(visual.attachments.isEmpty() && visual.videoMetadataRefused)
            assertNull(reader.readVisualDraft(small))
            val staged = reader.readPickedImages(listOf(large), checkNotNull(reader.fileBackedBudget()))
            assertTrue(staged.attachments.isEmpty() && staged.videoMetadataRefused)
            val document = reader.readPickedDocuments(listOf(small))
            assertTrue(document.attachments.isEmpty())
            assertEquals(setOf(DocumentReadFailure.VIDEO_METADATA), document.failures)
            assertNull(reader.readDocumentDraft(small))
            val stagedDocument = reader.readPickedDocuments(listOf(large), allowFileBacked = true)
            assertEquals(setOf(DocumentReadFailure.VIDEO_METADATA), stagedDocument.failures)
            assertTrue(File(context.cacheDir, MediaCacheDirs.UPLOAD_SOURCES).walk().none(File::isFile))
        }

    /** Scrubbing precedes the probe and the upload read in memory, and the staged probe in a file-backed read. */
    @Test
    fun scrubbingPrecedesEveryProbeAndRead() {
        val pipeline = source("media/MediaPipeline.kt").functionBody("readVideoForUpload")
        val scrub = pipeline.indexOf("VideoMetadataScrubber.scrub(tmp)")
        assertTrue(scrub in 0 until pipeline.indexOf("readVideoFileMetadata(tmp)"))
        assertTrue(pipeline.indexOf("readVideoFileMetadata(tmp)") < pipeline.indexOf("readFileBytesExact(tmp)"))
        assertTrue(pipeline.contains("return VideoReadResult.MetadataNotRemovable"))

        val reader = source("ui/conversation/ConversationMediaSender.kt")
        val fileBacked = reader.functionBody("readFileBackedVideo")
        assertTrue(fileBacked.indexOf("scrubVideoMetadata()") in 0 until fileBacked.indexOf("readVideoFileMetadata("))
        assertTrue(reader.functionBody("readRawDocument").contains("removeVideoMetadataInPlace(mediaType, read.bytes)"))
        assertTrue(reader.functionBody("readFileBackedDocument").contains("scrubVideoMetadata()"))
        val inMemory = reader.functionBody("readInMemoryVideo")
        assertTrue(inMemory.contains("MetadataNotRemovable ->") && inMemory.contains("videoMetadataRefused = true"))
    }

    /** A send reaches prepared draft attachments only through the metadata re-check, never as saved. */
    @Test
    fun preparedDraftAttachmentsReachTheSendOnlyThroughTheRecheck() {
        val prepare = source("ui/conversation/ConversationMediaSender.kt").functionBody("prepareStagedAttachments")

        assertEquals(1, Regex("preparedImageAttachments").findAll(prepare).count())
        assertEquals(1, Regex("preparedDocumentAttachments").findAll(prepare).count())
        assertTrue(prepare.contains("removePreparedVideoMetadata(preparedImageAttachments)"))
        assertTrue(prepare.contains("removePreparedVideoMetadata(preparedDocumentAttachments)"))
        assertTrue(prepare.contains("readStagedImages(imageSlots, checkedImages, albumFileBudget)"))
    }

    /** Checks that [bytes] are the scrubbed fixture and that none of its private fields survive. */
    private fun assertClean(bytes: ByteArray) {
        assertArrayEquals(clean, bytes)
        PRIVATE.forEach { assertFalse("$it reached the upload", bytes.containsAscii(it)) }
    }

    /** One production source file, relative to the app module. */
    private fun source(relative: String): String = File("src/main/java/dev/ipf/whitenoise/android/$relative").readText()

    /** A native draft attachment as MarmotKit stores it. */
    private fun draft(
        name: String,
        mediaType: String,
        bytes: ByteArray,
    ) = MessageDraftAttachmentFfi(
        id = "media-$name",
        fileName = name,
        mediaType = mediaType,
        plaintext = bytes,
        dim = "48x32",
        thumbhash = null,
        durationSeconds = null,
        waveformSamples = emptyList(),
    )

    /** Builds the production reader with inert draft persistence and scripted native limits. */
    private fun reader(): ConversationAttachmentReader {
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
            fileBackedLimits = { FileBackedSendLimits(perFileBytes = 64L * MIB, batchCiphertextBytes = 900L * MIB) },
        )
    }

    /** Publishes one scripted pick through the provider and returns its content Uri. */
    private fun pick(
        name: String,
        mediaType: String,
        bytes: ByteArray,
        declaredSize: Long,
    ): Uri {
        val file = File(context.cacheDir, "provider-${System.nanoTime()}-$name").apply { writeBytes(bytes) }
        ScriptedPickProvider.picks[name] = ScriptedPick(file, mediaType, declaredSize)
        return Uri.parse("content://$AUTHORITY/$name")
    }

    private companion object {
        const val AUTHORITY = "dev.ipf.whitenoise.test.videometadata"
        const val MIB = 1024L * 1024L
        const val LARGE = 40L * MIB
        val PRIVATE =
            listOf("WNPrivateTitle", "WNPrivateAuthor", "WNPrivateComment", "WNPrivateVideoHandler", "Lavf", "libx264")
    }
}
