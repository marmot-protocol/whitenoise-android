package dev.ipf.whitenoise.android.ui.conversation

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import dev.ipf.marmotkit.MessageDraftAttachmentFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.functionBody
import dev.ipf.whitenoise.android.media.AsfGuids
import dev.ipf.whitenoise.android.media.MediaCacheDirs
import dev.ipf.whitenoise.android.media.OggPackets
import dev.ipf.whitenoise.android.media.SyntheticTrack
import dev.ipf.whitenoise.android.media.VideoMetadataScrubber
import dev.ipf.whitenoise.android.media.ascii
import dev.ipf.whitenoise.android.media.asfFile
import dev.ipf.whitenoise.android.media.asfStream
import dev.ipf.whitenoise.android.media.audioOnlyWebm
import dev.ipf.whitenoise.android.media.audioOnlyWebmWithJunk
import dev.ipf.whitenoise.android.media.aviVideoBytes
import dev.ipf.whitenoise.android.media.avifSequenceShaped
import dev.ipf.whitenoise.android.media.concat
import dev.ipf.whitenoise.android.media.containsAscii
import dev.ipf.whitenoise.android.media.cr3ShapedPhoto
import dev.ipf.whitenoise.android.media.dvShapedVideo
import dev.ipf.whitenoise.android.media.heicShapedImage
import dev.ipf.whitenoise.android.media.isoBox
import dev.ipf.whitenoise.android.media.m4aWithId3v1Tag
import dev.ipf.whitenoise.android.media.matroskaRefusedBeforeTracks
import dev.ipf.whitenoise.android.media.oggFile
import dev.ipf.whitenoise.android.media.samples
import dev.ipf.whitenoise.android.media.syntheticMovie
import dev.ipf.whitenoise.android.media.videoFixture
import dev.ipf.whitenoise.android.share.PrivateShareFiles
import dev.ipf.whitenoise.android.share.ShareFileImporter
import dev.ipf.whitenoise.android.share.SharePayload
import dev.ipf.whitenoise.android.share.ShareRequest
import dev.ipf.whitenoise.android.share.ShareSourceMetadata
import dev.ipf.whitenoise.android.state.AppText
import dev.ipf.whitenoise.android.state.ConversationController
import dev.ipf.whitenoise.android.state.DraftPersistence
import dev.ipf.whitenoise.android.state.DraftStore
import dev.ipf.whitenoise.android.state.PendingAttachment
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.state.acceptedUploadResult
import dev.ipf.whitenoise.android.state.finishMediaFixture
import dev.ipf.whitenoise.android.state.mediaSendReconciliationAppState
import dev.ipf.whitenoise.android.state.uploadCapturingController
import dev.ipf.whitenoise.android.ui.conversation.media.FileBackedSendLimits
import dev.ipf.whitenoise.android.ui.conversation.media.PendingMediaSlot
import dev.ipf.whitenoise.android.ui.conversation.media.StagedUploadSource
import dev.ipf.whitenoise.android.ui.conversation.media.removePreparedDocumentVideoMetadata
import dev.ipf.whitenoise.android.ui.conversation.media.removePreparedVideoMetadata
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
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
 * Invariant gate for #3376: every way a local video becomes an outgoing attachment hands MDK only bytes whose
 * private container metadata is gone, and a video that cannot be cleaned is refused instead of sent as-is.
 *
 * Three cases drive the real `ConversationMediaSender.sendStagedAttachments` against a controller that captures the
 * native upload requests. Two read the bytes of every request handed to MDK: the in-memory plaintext, and each
 * `sourcePath` while the upload holds it, which covers a staged large pick and legacy native visual and Files drafts.
 * The third proves that an unreadable container (a tagged MP4 with a trailer, reported as a generic file) produces no
 * upload request at all, fresh or as a legacy draft. The other cases check the reader and draft re-check that feed
 * that send:
 * - the visual picker in memory, as a native draft and staged to a file
 * - incoming shares imported into private files
 * - the Files picker, routed by content, in memory and staged
 * - prepared visual and Files drafts
 *
 * The in-app camera captures photos only, so recorded videos reach the app through these same paths. One source
 * check pins that the staged visual probe runs after the scrub, an order no JVM fixture can observe.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class VideoUploadMetadataCoverageTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val tagged = videoFixture("mp4-tagged.mp4")
    private val clean = tagged.copyOf().also { VideoMetadataScrubber.scrub(it) }

    // The tagged fixture with one non-box trailing byte: a recognised movie whose scrub is refused as malformed.
    private val taggedWithTrailer = concat(tagged, byteArrayOf(1))

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

    /**
     * Through the real send, the files MDK uploads from carry only scrubbed bytes: a large tagged pick staged to a
     * file, and a tagged legacy native draft in the same album that the upload snapshots beside it. The native
     * draft's own array is left untouched.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun fileBackedSendHandsMdkOnlyScrubbedFiles() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val state = mediaSendReconciliationAppState()
            val large = pick("send-large.mp4", "video/mp4", tagged, declaredSize = LARGE)
            val draftSlot = pick("send-draft.mp4", "video/mp4", tagged, declaredSize = tagged.size.toLong())
            val legacyNative = draft("legacy.mp4", "video/mp4", tagged.copyOf())
            val uploaded = CompletableDeferred<List<ByteArray>>()
            val controller =
                uploadCapturingController(
                    state,
                    inMemory = { error("a message with a staged pick uploads from files") },
                    fromFiles = { request ->
                        uploaded.complete(request.attachments.map { File(it.sourcePath).readBytes() })
                        acceptedUploadResult(request.attachments.size)
                    },
                )
            try {
                controller.retryMembers()
                sender(state, controller).sendStagedAttachments(
                    imageSlots = listOf(PendingMediaSlot("large", large), PendingMediaSlot("draft", draftSlot)),
                    documentUris = emptyList(),
                    caption = "",
                    preparedImageAttachments = mapOf("draft" to legacyNative.toPendingAttachment()),
                )

                val files = uploaded.await()
                assertEquals(2, files.size)
                files.forEach(::assertClean)
                assertArrayEquals("the native draft's own array is never rewritten", tagged, legacyNative.plaintext)
            } finally {
                finishMediaFixture(controller, state)
                Dispatchers.resetMain()
            }
        }

    /**
     * Through the real send, the in-memory plaintext MDK uploads is scrubbed: a Photo Picker video, a Files pick whose
     * MP4 content its provider reports as `application/octet-stream`, and a legacy native Files draft saved with its
     * tags. The native draft's own array is left untouched.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun inMemorySendHandsMdkOnlyScrubbedBytes() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val state = mediaSendReconciliationAppState()
            val visual = pick("send-small.mp4", "video/mp4", tagged, declaredSize = tagged.size.toLong())
            val document = pick("send-movie.bin", "application/octet-stream", tagged, tagged.size.toLong())
            val draftDocument = pick("send-draft.bin", "application/octet-stream", tagged, tagged.size.toLong())
            val legacyNative = draft("legacy.bin", "application/octet-stream", tagged.copyOf())
            val requests = java.util.Collections.synchronizedList(mutableListOf<ByteArray>())
            val uploaded = CompletableDeferred<Unit>()
            val controller =
                uploadCapturingController(
                    state,
                    inMemory = { request ->
                        requests += request.attachments.map { it.plaintext }
                        if (requests.size == EXPECTED_IN_MEMORY_UPLOADS) uploaded.complete(Unit)
                        acceptedUploadResult(request.attachments.size)
                    },
                    fromFiles = { error("an all-in-memory message uploads from memory") },
                )
            try {
                controller.retryMembers()
                sender(state, controller).sendStagedAttachments(
                    imageSlots = listOf(PendingMediaSlot("small", visual)),
                    documentUris = listOf(document, draftDocument),
                    caption = "",
                    preparedDocumentAttachments = mapOf(draftDocument to legacyNative.toPendingAttachment()),
                )

                uploaded.await()
                assertEquals(EXPECTED_IN_MEMORY_UPLOADS, requests.size)
                requests.forEach(::assertClean)
                assertArrayEquals("the native draft's own array is never rewritten", tagged, legacyNative.plaintext)
            } finally {
                finishMediaFixture(controller, state)
                Dispatchers.resetMain()
            }
        }

    /**
     * Through the real send, a tagged MP4 with a trailer reported as `application/octet-stream` never reaches MDK: as
     * a fresh Files pick in memory, as a fresh Files pick staged to a file, and as a legacy native Files draft. The
     * metadata refusal notice is shown, no snapshot is left behind, and the native draft's own array is untouched.
     */
    @Test
    fun unreadableFilesPicksNeverReachMdk() = assertRefusedFilesPicksNeverReachMdk("trailer", taggedWithTrailer)

    /**
     * Through the real send, a recognised AVI and a recognised DV video reported as `application/octet-stream` never
     * reach MDK, in memory, staged and as a legacy native Files draft, because their content proves video whatever
     * type the provider reports.
     */
    @Test
    fun unsupportedVideoFilesPicksNeverReachMdk() {
        assertRefusedFilesPicksNeverReachMdk("avi", aviVideoBytes())
        assertRefusedFilesPicksNeverReachMdk("dv", dvShapedVideo())
    }

    /**
     * Sends [refused], reported as `application/octet-stream`, as a small Files pick, a staged Files pick and a legacy
     * native Files draft in one real send. The send is rejected with the metadata refusal notice, MDK receives no
     * upload request, no staged snapshot or temporary copy is left, and the native draft's own array is unchanged.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private fun assertRefusedFilesPicksNeverReachMdk(
        label: String,
        refused: ByteArray,
    ) = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val state = mediaSendReconciliationAppState()
        val size = refused.size.toLong()
        val small = pick("refused-$label-small.bin", "application/octet-stream", refused, size)
        val large = pick("refused-$label-large.bin", "application/octet-stream", refused, LARGE)
        val draftUri = pick("refused-$label-draft.bin", "application/octet-stream", refused, size)
        val legacyNative = draft("refused-$label-draft.bin", "application/octet-stream", refused.copyOf())
        val uploads = java.util.Collections.synchronizedList(mutableListOf<String>())
        val rejected = CompletableDeferred<Boolean>()
        val controller =
            uploadCapturingController(
                state,
                inMemory = { request ->
                    uploads += request.attachments.map { it.fileName }
                    acceptedUploadResult(request.attachments.size)
                },
                fromFiles = { request ->
                    uploads += request.attachments.map { it.fileName }
                    acceptedUploadResult(request.attachments.size)
                },
            )
        try {
            controller.retryMembers()
            sender(state, controller).sendStagedAttachments(
                imageSlots = emptyList(),
                documentUris = listOf(small, large, draftUri),
                caption = "",
                preparedDocumentAttachments = mapOf(draftUri to legacyNative.toPendingAttachment()),
                onAccepted = { rejected.complete(false) },
                onRejected = { rejected.complete(true) },
            )

            assertTrue("the $label send is rejected", rejected.await())
            assertTrue("no refused $label attachment reaches MDK: $uploads", uploads.isEmpty())
            assertEquals(AppText.Resource(R.string.media_file_video_metadata_not_removable), state.toast?.title)
            assertTrue("$label leaves no snapshot", stagedFiles().isEmpty())
            assertTrue("$label leaves no temporary copy", videoTempFiles().isEmpty())
            val untouched = legacyNative.plaintext
            assertArrayEquals("the native $label draft's own array is never rewritten", refused, untouched)
        } finally {
            finishMediaFixture(controller, state)
            Dispatchers.resetMain()
        }
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

    /**
     * Files picks are routed by content, in memory and staged alike. A video-branded video container is scrubbed
     * whatever its type (even `application/octet-stream`), and refused when it cannot be (a tagged MP4 with a
     * trailer), and a video-handler movie of unknown brand with a trailer is refused too. A container the scrubber
     * cannot clean is refused whatever its type when its content proves video (AVI and DV, even reported as generic
     * files, or an Ogg Theora reported as `audio/ogg`) or its streams cannot be read (a truncated WMA). TypeScript
     * reported as `video/mp2t`, Ogg Vorbis and Opus and an audio-only WMA reported as audio or as generic files, an
     * audio-only MP4, and a Canon CR3 raw photo, an AVIF sequence and a HEIC image reported as generic files all
     * arrive byte-for-byte. So do audio-only files with trailing bytes after a complete track list (an `.m4a` with a
     * trailer or an ID3v1 tag, an audio WebM with stray bytes), which are refused only when reported as video.
     */
    @Test
    fun filesPickerRoutesByContent() =
        runBlocking {
            val reader = reader()
            filesCases().forEachIndexed { index, case ->
                listOf(false, true).forEach { staged ->
                    val declared = if (staged) LARGE else case.bytes.size.toLong()
                    val uri = pick("files-$index-$staged-${case.name}", case.mime, case.bytes, declared)
                    val outcome = reader.readPickedDocuments(listOf(uri), allowFileBacked = staged)
                    val label = "${case.name} reported ${case.mime}, staged=$staged"
                    val attachment = outcome.attachments.singleOrNull()
                    val bytes = attachment?.sourceFile?.file?.readBytes() ?: attachment?.plaintextBytes
                    if (case.expected == null) {
                        assertNull(label, attachment)
                        assertEquals(label, setOf(DocumentReadFailure.VIDEO_METADATA), outcome.failures)
                        assertTrue("$label leaves no snapshot", stagedFiles().isEmpty())
                    } else {
                        assertArrayEquals(label, case.expected, bytes)
                        assertEquals(label, case.mime, attachment?.mediaType)
                    }
                    attachment?.sourceFile?.close()
                }
            }
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
     * Prepared drafts are re-checked before a send reuses them, without mutating the native draft's array: visual
     * drafts by reported type (a legacy tagged video is scrubbed, a clean one and a photo are reused as-is, AVI and a
     * video draft carrying a staged file are refused), and Files drafts by content like a fresh Files pick.
     */
    @Test
    fun preparedDraftsAreScrubbedBeforeTheyAreReused() {
        val legacyNative = draft("legacy.mp4", "video/mp4", tagged.copyOf())
        val alreadyClean = PendingAttachment(clean.copyOf(), "video/mp4", "clean.mp4")
        val photo = PendingAttachment(byteArrayOf(1, 2, 3), "image/jpeg", "photo.jpg")
        val avi = draft("old.avi", "video/x-msvideo", aviVideoBytes()).toPendingAttachment()
        val stagedSource = stagedVideoSnapshot()
        val staged = PendingAttachment(ByteArray(0), "video/mp4", "staged.mp4", sourceFile = stagedSource)
        val stagedBefore = stagedSource.file.readBytes()

        val visual =
            removePreparedVideoMetadata(
                mapOf(
                    "legacy" to legacyNative.toPendingAttachment(),
                    "clean" to alreadyClean,
                    "photo" to photo,
                    "avi" to avi,
                    "staged" to staged,
                ),
            )

        assertClean(checkNotNull(visual.ready["legacy"]).plaintextBytes)
        assertArrayEquals("the native draft's own array is never rewritten", tagged, legacyNative.plaintext)
        assertSame(alreadyClean, visual.ready["clean"])
        assertSame(photo, visual.ready["photo"])
        assertEquals(setOf("avi", "staged"), visual.refused)
        val stagedAfter = stagedSource.file.readBytes()
        assertArrayEquals("a staged draft file is never rewritten in place", stagedBefore, stagedAfter)
        stagedSource.close()

        val documents = filesCases().associate { case -> case.name to draft(case.name, case.mime, case.bytes.copyOf()) }
        val checked = removePreparedDocumentVideoMetadata(documents.mapValues { (_, it) -> it.toPendingAttachment() })
        filesCases().forEach { case ->
            assertArrayEquals(case.name, case.expected, checked.ready[case.name]?.plaintextBytes)
            assertEquals(case.name, case.expected == null, case.name in checked.refused)
        }
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

    /** The staged visual probe reads the snapshot only after the scrub succeeded inside staging. */
    @Test
    fun fileBackedProbeRunsOnTheScrubbedSnapshot() {
        val reader = File("src/main/java/dev/ipf/whitenoise/android/ui/conversation/ConversationMediaSender.kt")
        val fileBacked = reader.readText().functionBody("readFileBackedVideo")
        val scrub = fileBacked.indexOf("scrubVideoMetadata()")
        assertTrue(scrub in 0 until fileBacked.indexOf("readVideoFileMetadata("))
        assertTrue(fileBacked.contains("metadataRemoved &&"))
    }

    /**
     * The Files routing table: what each pick's content and reported type must produce, null meaning refused. The
     * audio-only MP4, Ogg and WMA files keep their tags, because imported audio keeps its original data, and so do
     * audio-only files with trailing bytes after a complete track list unless they are reported as video.
     */
    private fun filesCases(): List<FilesCase> {
        val typeScript = ascii("export const greeting: string = 'hello';\n")
        val shortTypeScript = ascii("let skipped = 0;\n")
        val vorbis = oggFile(OggPackets.vorbis)
        val opus = oggFile(OggPackets.opus)
        val wma = asfFile(asfStream(AsfGuids.audioMedia))
        val river = ascii("The wide river ran past the old mill and on to the sea.\n")
        val audioOnly =
            syntheticMovie(
                listOf(SyntheticTrack("soun", samples(listOf(8, 8), 0x31))),
                movieExtras = listOf(isoBox("udta", ascii("WNPrivateSong"))),
            ).bytes
        val audioWithTrailer = concat(audioOnly, byteArrayOf(1))
        val unknownBrandVideo = listOf(SyntheticTrack("vide", samples(listOf(8), 0x21)))
        val unknownBrandVideoWithTrailer =
            concat(syntheticMovie(unknownBrandVideo, brands = listOf("wxyz", "isom")).bytes, byteArrayOf(1))
        return listOf(
            FilesCase("tagged.mp4", "video/mp4", tagged, clean),
            FilesCase("movie.bin", "application/octet-stream", tagged, clean),
            FilesCase("trailer.bin", "application/octet-stream", taggedWithTrailer, null),
            FilesCase("unknown-trailer.bin", "application/octet-stream", unknownBrandVideoWithTrailer, null),
            // Trailing bytes after a complete audio-only track list leave audio byte-for-byte unless reported as video.
            FilesCase("song-trailer.m4a", "audio/mp4", audioWithTrailer, audioWithTrailer),
            FilesCase("song-id3.m4a", "audio/mp4", m4aWithId3v1Tag(), m4aWithId3v1Tag()),
            FilesCase("song-id3.bin", "application/octet-stream", m4aWithId3v1Tag(), m4aWithId3v1Tag()),
            FilesCase("song-id3.mp4", "video/mp4", m4aWithId3v1Tag(), null),
            FilesCase("voice-junk.webm", "audio/webm", audioOnlyWebmWithJunk(), audioOnlyWebmWithJunk()),
            FilesCase("voice-junk.bin", "application/octet-stream", audioOnlyWebmWithJunk(), audioOnlyWebmWithJunk()),
            FilesCase("voice-junk-video.webm", "video/webm", audioOnlyWebmWithJunk(), null),
            FilesCase("broken.mka", "audio/x-matroska", matroskaRefusedBeforeTracks(), null),
            FilesCase("broken.bin", "application/octet-stream", matroskaRefusedBeforeTracks(), null),
            FilesCase("index.ts", "video/mp2t", typeScript, typeScript),
            FilesCase("skipped.ts", "video/mp2t", shortTypeScript, shortTypeScript),
            FilesCase("river.ts", "video/mp2t", river, river),
            FilesCase("river.txt", "application/octet-stream", river, river),
            // Uncleanable containers: proven audio stays byte-for-byte, and proven or possible video is refused.
            FilesCase("song.ogg", "audio/ogg", vorbis, vorbis),
            FilesCase("song-ogg.bin", "application/octet-stream", vorbis, vorbis),
            FilesCase("voice.ogg", "audio/ogg", opus, opus),
            FilesCase("voice-ogg.bin", "application/octet-stream", opus, opus),
            FilesCase("clip.ogg", "audio/ogg", oggFile(OggPackets.theora), null),
            FilesCase("song.wma", "audio/x-ms-wma", wma, wma),
            FilesCase("song-wma.bin", "application/octet-stream", wma, wma),
            FilesCase("broken.wma", "audio/x-ms-wma", wma.copyOf(60), null),
            FilesCase("old.bin", "application/octet-stream", aviVideoBytes(), null),
            FilesCase("song.m4a", "audio/mp4", audioOnly, audioOnly),
            FilesCase("voice.webm", "audio/webm", audioOnlyWebm(), audioOnlyWebm()),
            FilesCase("old.avi", "video/x-msvideo", aviVideoBytes(), null),
            FilesCase("tape.dv", "video/dv", dvShapedVideo(), null),
            FilesCase("tape.bin", "application/octet-stream", dvShapedVideo(), null),
            FilesCase("IMG_0001.CR3", "application/octet-stream", cr3ShapedPhoto(), cr3ShapedPhoto()),
            FilesCase("sequence.avifs", "application/octet-stream", avifSequenceShaped(), avifSequenceShaped()),
            FilesCase("photo.heic", "application/octet-stream", heicShapedImage(), heicShapedImage()),
        )
    }

    /** Files left in this process's staging directory. */
    private fun stagedFiles(): List<File> {
        val root = File(context.cacheDir, MediaCacheDirs.UPLOAD_SOURCES)
        return root.walk().filter(File::isFile).toList()
    }

    /** Temporary copies the in-memory video read leaves in this process's video cache. */
    private fun videoTempFiles(): List<File> {
        val root = File(context.cacheDir, MediaCacheDirs.VIDEO)
        return root.listFiles().orEmpty().filter { it.name.startsWith("vidmeta-") }
    }

    /** One Files routing case: [expected] is what must be sent, or null when the pick must be refused. */
    private class FilesCase(
        val name: String,
        val mime: String,
        val bytes: ByteArray,
        val expected: ByteArray?,
    )

    /** Checks that [bytes] are the scrubbed fixture and that none of its private fields survive. */
    private fun assertClean(bytes: ByteArray) {
        assertArrayEquals(clean, bytes)
        PRIVATE.forEach { assertFalse("$it reached the upload", bytes.containsAscii(it)) }
    }

    /** The production sender over [controller], with generous native file limits. */
    private fun sender(
        state: WhiteNoiseAppState,
        controller: ConversationController,
    ) = ConversationMediaSender(state, controller, state.appContext, fileBackedLimits = { LIMITS }) {}

    /** A tagged video staged to a private snapshot, as a file-backed pick would be. */
    private fun stagedVideoSnapshot(): StagedUploadSource {
        val uri = pick("staged-draft.mp4", "video/mp4", tagged, declaredSize = LARGE)
        val budget = checkNotNull(reader().fileBackedBudget())
        return checkNotNull(budget.stage(tagged.size.toLong()) { context.contentResolver.openInputStream(uri) }.source)
    }

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

                /** Drops draft writes, because these tests read picks only. */
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
        return ConversationAttachmentReader(state, context, fileBackedLimits = { LIMITS })
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

        // The visual album, the fresh Files pick and the Files draft each upload as their own message.
        const val EXPECTED_IN_MEMORY_UPLOADS = 3
        val LIMITS = FileBackedSendLimits(perFileBytes = 64L * MIB, batchCiphertextBytes = 900L * MIB)
        val PRIVATE =
            listOf("WNPrivateTitle", "WNPrivateAuthor", "WNPrivateComment", "WNPrivateVideoHandler", "Lavf", "libx264")
    }
}
