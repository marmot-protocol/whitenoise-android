package dev.ipf.whitenoise.android.ui.conversation

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import dev.ipf.marmotkit.MessageDraftAttachmentFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.media.MediaCacheDirs
import dev.ipf.whitenoise.android.media.pdf.PdfMetadataRefusal
import dev.ipf.whitenoise.android.media.pdf.PdfMetadataResult
import dev.ipf.whitenoise.android.media.pdf.PdfMetadataScrubber
import dev.ipf.whitenoise.android.share.PrivateShareFiles
import dev.ipf.whitenoise.android.share.ShareFileImporter
import dev.ipf.whitenoise.android.share.SharePayload
import dev.ipf.whitenoise.android.share.ShareRequest
import dev.ipf.whitenoise.android.share.ShareSourceMetadata
import dev.ipf.whitenoise.android.state.AppText
import dev.ipf.whitenoise.android.state.PendingAttachment
import dev.ipf.whitenoise.android.state.acceptedUploadResult
import dev.ipf.whitenoise.android.state.finishMediaFixture
import dev.ipf.whitenoise.android.state.mediaSendReconciliationAppState
import dev.ipf.whitenoise.android.state.uploadCapturingController
import dev.ipf.whitenoise.android.ui.conversation.media.ComposerPdfState
import dev.ipf.whitenoise.android.ui.conversation.media.FileBackedSendLimits
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.File
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.DataFormatException
import java.util.zip.Inflater

/**
 * Invariant gate for #3377: every PDF a person attaches through Files reaches MDK without its private document
 * metadata, and a PDF whose metadata cannot be removed (signed, encrypted or laid out in a way that cannot be
 * rewritten exactly) is never sent until the person chooses to send it as it is.
 *
 * Every case drives the real `ConversationMediaSender.sendStagedAttachments` against a controller that captures the
 * native upload requests, and reads the bytes MDK receives: the in-memory plaintext, and each `sourcePath` while the
 * upload holds it. A PDF is clean when no `WNPrivate*` value or the `Ghostscript` producer appears in its raw bytes
 * or in any FlateDecode stream, found by this test's own inflater rather than the production parser, and when its
 * length is unchanged. The cases cover:
 * - fresh Files picks in memory and staged to a file, with a plain text file beside them byte-for-byte
 * - legacy prepared Files drafts, cleaned on a copy that leaves the native draft's array untouched
 * - refused PDFs with no choice, fresh and prepared, which produce no upload request and the PDF notice
 * - refused PDFs the person chose to send as they are, through the prepared flag or the composer's choice
 * - an incoming share imported into private files and read through the same Files reader
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class DocumentUploadPrivacyCoverageTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val text = "Meeting notes: bring the WNPrivate agenda and the Ghostscript manual.\n".toByteArray()

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
     * The fixtures carry their private values both in plain bytes and inside compressed streams, and this test's
     * inflater finds the compressed ones, so a clean upload below is evidence rather than a blind scan.
     */
    @Test
    fun fixturesCarryMetadataTheScanFinds() {
        TAGGED.forEach { name -> assertTrue("$name is tagged", leaks(fixture(name)).isNotEmpty()) }
        val objectStreams = fixture("object-streams")
        assertFalse(objectStreams.containsText("WNPrivateCompany"))
        assertTrue(inflatedStreams(objectStreams).any { it.containsText("WNPrivateCompany") })
        REFUSED.forEach { (name, reason) ->
            assertEquals(name, PdfMetadataResult.Refused(reason), PdfMetadataScrubber.inspect(fixture(name)))
        }
    }

    /** Tagged PDFs picked through Files and read into memory reach MDK clean; a text file beside them is unchanged. */
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun inMemoryFilesPicksReachMdkClean() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            try {
                val sources = TAGGED.associate { "$it.pdf" to fixture(it) }
                val uris = sources.map { (name, bytes) -> pick(name, PDF, bytes, bytes.size.toLong()) }
                val notes = pick("notes.txt", "text/plain", text, text.size.toLong())

                val sent = send(uris + notes, expectedUploads = sources.size + 1)

                assertTrue(sent.accepted)
                sources.forEach { (name, source) -> assertCleanCopyOf(name, source, sent.upload(name)) }
                assertArrayEquals("a non-PDF document is sent byte-for-byte", text, sent.upload("notes.txt"))
            } finally {
                Dispatchers.resetMain()
            }
        }

    /** Tagged PDFs too large for memory are staged to files, and the files MDK uploads from are clean. */
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun stagedFilesPicksReachMdkClean() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            try {
                val sources = STAGED.associate { "large-$it.pdf" to fixture(it) }
                val uris = sources.map { (name, bytes) -> pick(name, PDF, bytes, LARGE) }
                val notes = pick("large-notes.txt", "text/plain", text, LARGE)

                val sent = send(uris + notes, expectedUploads = sources.size + 1)

                assertTrue(sent.accepted)
                assertEquals("every staged pick uploads from a file", sources.size + 1, sent.fromFiles)
                sources.forEach { (name, source) -> assertCleanCopyOf(name, source, sent.upload(name)) }
                assertArrayEquals("a staged non-PDF document is unchanged", text, sent.upload("large-notes.txt"))
            } finally {
                Dispatchers.resetMain()
            }
        }

    /** A legacy Files draft saved with its PDF metadata is cleaned on a copy; the native draft's array is untouched. */
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun preparedFilesDraftsAreCleanedOnACopy() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            try {
                val names = listOf("ghostscript", "flate-xmp")
                val drafts = names.associate { "draft-$it.pdf" to draft("draft-$it.pdf", PDF, fixture(it)) }
                val prepared =
                    drafts.entries.associate { (name, draft) ->
                        pick(name, PDF, draft.plaintext, draft.plaintext.size.toLong()) to draft.toPendingAttachment()
                    }

                val sent = send(prepared.keys.toList(), prepared, expectedUploads = drafts.size)

                assertTrue(sent.accepted)
                names.forEach { name ->
                    val source = fixture(name)
                    val draft = checkNotNull(drafts["draft-$name.pdf"])
                    assertCleanCopyOf(name, source, sent.upload("draft-$name.pdf"))
                    assertArrayEquals("the native $name draft's own array is never rewritten", source, draft.plaintext)
                }
            } finally {
                Dispatchers.resetMain()
            }
        }

    /**
     * A signed, an encrypted and an unsupported PDF with no choice never reach MDK: picked in memory, staged to a
     * file, or reused from a prepared draft. Each send is rejected with the PDF notice and leaves the draft's array
     * unchanged.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun refusedPdfsWithoutAChoiceNeverReachMdk() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            try {
                REFUSED.keys.forEach { name ->
                    val source = fixture(name)
                    val small = pick("refused-$name.pdf", PDF, source, source.size.toLong())
                    val large = pick("refused-large-$name.pdf", PDF, source, LARGE)
                    val draftUri = pick("refused-draft-$name.pdf", PDF, source, source.size.toLong())
                    val draft = draft("refused-draft-$name.pdf", PDF, source.copyOf())
                    val prepared = mapOf(draftUri to draft.toPendingAttachment())
                    listOf(
                        "$name in memory" to send(listOf(small), expectedUploads = 0),
                        "$name staged" to send(listOf(large), expectedUploads = 0),
                        "$name draft" to send(listOf(draftUri), prepared, expectedUploads = 0),
                    ).forEach { (label, sent) ->
                        assertFalse("$label is rejected", sent.accepted)
                        assertTrue("$label never reaches MDK: ${sent.uploads.keys}", sent.uploads.isEmpty())
                        assertEquals(label, AppText.Resource(R.string.media_file_pdf_metadata_kept), sent.toast)
                    }
                    assertArrayEquals("the native $name draft's own array is never rewritten", source, draft.plaintext)
                }
            } finally {
                Dispatchers.resetMain()
            }
        }

    /** A prepared signed PDF the person chose to send as it is reaches MDK with its original bytes. */
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun preparedPdfMarkedToKeepMetadataUploadsTheOriginal() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            try {
                val signed = fixture("signed")
                val uri = pick("kept-signed.pdf", PDF, signed, signed.size.toLong())
                val kept = PendingAttachment(signed.copyOf(), PDF, "kept-signed.pdf")
                val unconfirmed = send(listOf(uri), mapOf(uri to kept), expectedUploads = 0)
                assertTrue("a refused PDF without a choice is not sent", unconfirmed.uploads.isEmpty())

                val sent =
                    send(listOf(uri), mapOf(uri to kept), expectedUploads = 1) { sender ->
                        sender.pdfChoices.record(
                            uri,
                            ComposerPdfState("kept-signed.pdf", PdfMetadataScrubber.inspect(signed)),
                        )
                        sender.pdfChoices.confirmThen(listOf(uri), {}) {}
                        sender.pdfChoices.confirm()
                    }

                assertTrue(sent.accepted)
                assertArrayEquals(signed, sent.uploads.values.single())
            } finally {
                Dispatchers.resetMain()
            }
        }

    /**
     * Once the person confirms the composer's question, a fresh signed PDF pick reaches MDK with its original bytes,
     * in memory and staged to a file.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun confirmedChoiceUploadsTheOriginal() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            try {
                val signed = fixture("signed")
                val small = pick("chosen-signed.pdf", PDF, signed, signed.size.toLong())
                val large = pick("chosen-large-signed.pdf", PDF, signed, LARGE)
                listOf(small, large).forEach { uri ->
                    val sent =
                        send(listOf(uri), expectedUploads = 1) { sender ->
                            val choices = sender.pdfChoices
                            choices.record(uri, ComposerPdfState("signed.pdf", PdfMetadataScrubber.inspect(signed)))
                            choices.confirmThen(listOf(uri), {}) {}
                            assertNotNull("a refused PDF asks before it is sent", choices.decision)
                            choices.confirm()
                            assertTrue(choices.keepsOriginal(uri))
                        }

                    assertTrue("$uri is accepted", sent.accepted)
                    assertArrayEquals("$uri is sent as it is", signed, sent.uploads.values.single())
                }
            } finally {
                Dispatchers.resetMain()
            }
        }

    /** A tagged PDF shared into the app is imported to a private file and read through Files clean. */
    @Test
    fun incomingSharedPdfIsCleaned() =
        runBlocking {
            val source = fixture("ghostscript")
            val files = PrivateShareFiles(context)
            val importer =
                ShareFileImporter(
                    files,
                    { _, _ -> ShareSourceMetadata("shared.pdf", PDF, source.size.toLong()) },
                    { _, _ -> ByteArrayInputStream(source) },
                )
            val request =
                ShareRequest(
                    SharePayload(null, listOf(Uri.parse("content://external/shared-pdf")), PDF),
                    shortcutId = null,
                    requestId = "pdf-metadata-share",
                )
            try {
                val uri =
                    importer
                        .import(request)
                        .payload.streamUris
                        .single()
                val reader =
                    ConversationAttachmentReader(
                        mediaSendReconciliationAppState(),
                        context,
                        fileBackedLimits = { LIMITS },
                    )
                val read = reader.readPickedDocuments(listOf(uri))
                assertCleanCopyOf("shared", source, read.attachments.single().plaintextBytes)
            } finally {
                files.leases.releaseRequest(request.requestId)
            }
        }

    /** What MDK received from one send, by file name, and how the send ended. */
    private class SentDocuments(
        val accepted: Boolean,
        val uploads: Map<String, ByteArray>,
        val fromFiles: Int,
        val toast: AppText?,
    ) {
        fun upload(name: String): ByteArray = checkNotNull(uploads[name]) { "$name never reached MDK: ${uploads.keys}" }
    }

    /**
     * Sends [documents] through the real sender, with [prepared] drafts and the [choose] step a composer would take
     * first, and waits until it is rejected or MDK has received [expectedUploads] attachments.
     */
    private suspend fun TestScope.send(
        documents: List<Uri>,
        prepared: Map<Uri, PendingAttachment> = emptyMap(),
        expectedUploads: Int,
        choose: (ConversationMediaSender) -> Unit = {},
    ): SentDocuments {
        val state = mediaSendReconciliationAppState()
        val uploads = ConcurrentHashMap<String, ByteArray>()
        val fileNames = ConcurrentHashMap.newKeySet<String>()
        val uploaded = CompletableDeferred<Unit>()
        val record = { name: String, bytes: ByteArray ->
            uploads[name] = bytes
            if (uploads.size >= expectedUploads) uploaded.complete(Unit)
        }
        val controller =
            uploadCapturingController(
                state,
                inMemory = { request ->
                    request.attachments.forEach { record(it.fileName, it.plaintext) }
                    acceptedUploadResult(request.attachments.size)
                },
                fromFiles = { request ->
                    request.attachments.forEach {
                        fileNames += it.fileName
                        record(it.fileName, File(it.sourcePath).readBytes())
                    }
                    acceptedUploadResult(request.attachments.size)
                },
            )
        val outcome = CompletableDeferred<Boolean>()
        try {
            controller.retryMembers()
            val sender = ConversationMediaSender(state, controller, state.appContext, fileBackedLimits = { LIMITS }) {}
            choose(sender)
            sender.sendStagedAttachments(
                imageSlots = emptyList(),
                documentUris = documents,
                caption = "",
                preparedDocumentAttachments = prepared,
                onAccepted = { outcome.complete(true) },
                onRejected = { outcome.complete(false) },
            )
            val accepted = outcome.await()
            if (accepted && expectedUploads > 0) uploaded.await()
            testScheduler.advanceUntilIdle()
            return SentDocuments(accepted, uploads.toMap(), fileNames.size, state.toast?.title)
        } finally {
            finishMediaFixture(controller, state)
        }
    }

    /** Checks that [uploaded] is a same-length rewrite of the tagged [source] with no private value left. */
    private fun assertCleanCopyOf(
        label: String,
        source: ByteArray,
        uploaded: ByteArray,
    ) {
        assertEquals("$label keeps its length", source.size, uploaded.size)
        assertFalse("$label was cleaned", source.contentEquals(uploaded))
        assertTrue("$label leaked ${leaks(uploaded)}", leaks(uploaded).isEmpty())
    }

    /** The private values in [bytes], raw or inside any stream that inflates. */
    private fun leaks(bytes: ByteArray): Set<String> =
        (listOf(bytes) + inflatedStreams(bytes)).flatMapTo(sortedSetOf()) { data ->
            PRIVATE_PATTERN.findAll(String(data, Charsets.ISO_8859_1)).map { it.value }.toList()
        }

    /**
     * Every stream body in [bytes] that inflates as zlib or raw deflate, found by scanning for `stream` followed by
     * an end of line. Independent of the production parser on purpose: it does not read dictionaries, lengths or
     * filters, so a stream the scrubber misread is still inspected.
     */
    private fun inflatedStreams(bytes: ByteArray): List<ByteArray> {
        val marker = "stream".toByteArray(Charsets.ISO_8859_1)
        val streams = mutableListOf<ByteArray>()
        var at = bytes.indexOf(marker, 0)
        while (at >= 0) {
            var start = at + marker.size
            if (start < bytes.size && bytes[start] == '\r'.code.toByte()) start++
            if (start < bytes.size && bytes[start] == '\n'.code.toByte()) {
                val data = start + 1
                (inflate(bytes, data, nowrap = false) ?: inflate(bytes, data, nowrap = true))?.let(streams::add)
            }
            at = bytes.indexOf(marker, at + 1)
        }
        return streams
    }

    /** What inflating [bytes] from [start] produces, or null when it is not a deflate stream. */
    private fun inflate(
        bytes: ByteArray,
        start: Int,
        nowrap: Boolean,
    ): ByteArray? {
        val inflater = Inflater(nowrap).apply { setInput(bytes, start, bytes.size - start) }
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(BUFFER_BYTES)
        return try {
            while (!inflater.finished() && out.size() < MAX_INFLATED_BYTES) {
                val count = inflater.inflate(buffer)
                if (count == 0 && (inflater.needsInput() || inflater.needsDictionary())) break
                out.write(buffer, 0, count)
            }
            out.toByteArray().takeIf { it.isNotEmpty() }
        } catch (_: DataFormatException) {
            null
        } finally {
            inflater.end()
        }
    }

    private fun ByteArray.indexOf(
        needle: ByteArray,
        from: Int,
    ): Int {
        for (i in from..size - needle.size) {
            if (needle.indices.all { this[i + it] == needle[it] }) return i
        }
        return -1
    }

    private fun ByteArray.containsText(text: String): Boolean = indexOf(text.toByteArray(Charsets.ISO_8859_1), 0) >= 0

    /** A committed fixture, decoded from its Base64 resource. */
    private fun fixture(name: String): ByteArray {
        val resource = checkNotNull(javaClass.getResourceAsStream("/pdf-metadata/$name.pdf.b64")) { name }
        return Base64.getMimeDecoder().decode(resource.use { it.readBytes() })
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
        dim = null,
        thumbhash = null,
        durationSeconds = null,
        waveformSamples = emptyList(),
    )

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
        const val AUTHORITY = "dev.ipf.whitenoise.test.pdfmetadata"
        const val PDF = "application/pdf"
        const val MIB = 1024L * 1024L
        const val LARGE = 40L * MIB
        const val BUFFER_BYTES = 8 * 1024
        const val MAX_INFLATED_BYTES = 4 * 1024 * 1024
        val LIMITS = FileBackedSendLimits(perFileBytes = 64L * MIB, batchCiphertextBytes = 900L * MIB)
        val PRIVATE_PATTERN = Regex("WNPrivate[A-Za-z0-9]*|Ghostscript")
        val TAGGED = listOf("ghostscript", "object-streams", "flate-xmp", "incremental", "form-photo", "pdfa")
        val STAGED = listOf("ghostscript", "object-streams", "incremental")
        val REFUSED =
            mapOf(
                "signed" to PdfMetadataRefusal.Signed,
                "encrypted" to PdfMetadataRefusal.Encrypted,
                "hex-xmp" to PdfMetadataRefusal.Unsupported,
            )
    }
}
