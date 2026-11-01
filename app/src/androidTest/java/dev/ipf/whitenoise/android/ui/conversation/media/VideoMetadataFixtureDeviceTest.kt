package dev.ipf.whitenoise.android.ui.conversation.media

import android.content.Context
import android.net.Uri
import androidx.annotation.StringRes
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.ipf.marmotkit.AttachmentPageReadFfi
import dev.ipf.marmotkit.MarmotAndroid
import dev.ipf.marmotkit.MediaAttachmentOutcomeFfi
import dev.ipf.marmotkit.MediaAttachmentReferenceFfi
import dev.ipf.marmotkit.MessageDraftAttachmentFfi
import dev.ipf.whitenoise.android.ManualDeviceFixture
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.media.ControlledAttachmentProbe
import dev.ipf.whitenoise.android.media.FixturePeers
import dev.ipf.whitenoise.android.media.FixtureSession
import dev.ipf.whitenoise.android.media.MediaCacheDirs
import dev.ipf.whitenoise.android.media.MediaLifecycleAttachmentProbe
import dev.ipf.whitenoise.android.media.RestartAttachmentRetentionProbe
import dev.ipf.whitenoise.android.media.SenderHarness
import dev.ipf.whitenoise.android.media.VideoMetadataScrubber
import dev.ipf.whitenoise.android.media.VideoScrubResult
import dev.ipf.whitenoise.android.media.openSenderHarness
import dev.ipf.whitenoise.android.share.PrivateShareFiles
import dev.ipf.whitenoise.android.share.ShareFileImporter
import dev.ipf.whitenoise.android.share.ShareInboundStager
import dev.ipf.whitenoise.android.share.SharePayload
import dev.ipf.whitenoise.android.share.ShareRequest
import dev.ipf.whitenoise.android.share.ShareStagingStore
import dev.ipf.whitenoise.android.share.shareResolveMime
import dev.ipf.whitenoise.android.state.AppText
import dev.ipf.whitenoise.android.state.downloadAttachmentPlaintextSource
import dev.ipf.whitenoise.android.ui.conversation.ConversationMediaSender
import dev.ipf.whitenoise.android.ui.conversation.toPendingAttachment
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/**
 * End-to-end #3376 qualification on a disposable emulator, against the loopback Blossom fixture and relay. Tagged
 * videos enter [ConversationMediaSender.sendStagedAttachments] through its production content-URI preparation entry
 * points, the shipping controller and MDK really upload them, and the generated receiver downloads each one through
 * the shipping resolver. Every received plaintext must equal the scrubbed source byte for byte, carry none of the
 * source's private strings, location, platform version key or recording date, and keep its size, duration, rotation,
 * dimensions, first frame and every audio and video sample. Videos the scrubber cannot clean must be refused with the
 * existing notice, never uploaded, never received and leave no staged copy behind, including a recognised AVI that
 * its provider reports as `application/octet-stream`. The host checker also reads the
 * fixture's own upload ledger, so an upload the device did not report still fails the run.
 *
 * Route boundaries, stated so no report claims more than this lane proves:
 * - The visual and Files routes hand the sender `content://` URIs served by the test APK's provider, the same entry
 *   point a Photo Picker or Files result reaches. The system Photo Picker and Files UI are never driven.
 * - The share route imports through [ShareFileImporter] into private share storage and classifies the copy with
 *   [ShareInboundStager], as an inbound share does once its intent has arrived. The system share sheet and intent
 *   dispatch are never driven.
 * - The prepared draft routes inject a native draft attachment that holds unsanitized bytes, as an older app version
 *   saved one, straight into the send. This is prepared draft injection: no draft is saved, the process is not force
 *   stopped and nothing is restored from MDK, so MED-043's force-stop and reopen receipt is not covered here.
 */
@ManualDeviceFixture
@RunWith(AndroidJUnit4::class)
class VideoMetadataFixtureDeviceTest {
    /** Sends every accepted route, refuses every unreadable one, then checks both accounts' histories. */
    @Test
    fun contentUriPreparationEntryPointsSendOnlyScrubbedVideosToTheReceiver() =
        runBlocking {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val arguments = InstrumentationRegistry.getArguments()
            assumeTrue(arguments.getString("allowControlledAttachmentProbe") == "true")
            check(context.packageName == "dev.ipf.whitenoise.android.medialatency")
            val blobPort = requireNotNull(arguments.getString("fixtureBlobPort")).toInt()
            val relays = listOf("ws://127.0.0.1:${requireNotNull(arguments.getString("fixtureRelayPort")).toInt()}")
            ControlledAttachmentProbe.report(BOUNDARY)
            MarmotAndroid.initialize(context)
            val work = File(context.cacheDir, "video-metadata-fixture-${UUID.randomUUID()}").apply { check(mkdirs()) }
            val root = RestartAttachmentRetentionProbe.createRoot(context, null, null)
            val marmot = TileFixtureSupport.openRuntime(root, relays)
            val session = FixtureSession(context, root, marmot, relays, blobPort)
            try {
                withTimeout(TIMEOUT_MILLIS) {
                    marmot.start()
                    val peers = session.createPeers()
                    val harness = openSenderHarness(context, root, marmot, peers.sender, peers.group, blobPort, false)
                    try {
                        withContext(Dispatchers.Main.immediate) {
                            harness.controller.retryMembers()
                            check(harness.controller.canSendMessages) { "generated sender membership not ready" }
                        }
                        val run = VideoMetadataRun(session, peers, harness, work)
                        ACCEPTED.forEach { run.accepted(it) }
                        REFUSED.forEach { run.refused(it) }
                        run.reportHistories(ACCEPTED, REFUSED)
                    } finally {
                        harness.close()
                    }
                }
            } finally {
                try {
                    session.close(preserve = false)
                } finally {
                    // Nothing in this isolated package sweeps its private copies, so a failed run must not leave them.
                    work.deleteRecursively()
                    File(context.cacheDir, MediaCacheDirs.UPLOAD_SOURCES).deleteRecursively()
                }
            }
        }

    private companion object {
        const val MIB = 1024L * 1024L
        const val TIMEOUT_MILLIS = 15L * 60L * 1000L

        /** What this lane does not drive, reported first so even a failed run states it. */
        val BOUNDARY: JSONObject =
            JSONObject()
                .put("phase", "video-metadata-boundary")
                .put("system_photo_picker_ui", false)
                .put("system_files_ui", false)
                .put("system_share_sheet_ui", false)
                .put("force_stop_restored_draft", false)
                .put("physical_device", false)

        val MP4_PRIVATE =
            listOf(
                "WNPrivateTitle",
                "WNPrivateAuthor",
                "WNPrivateComment",
                "WNPrivateVideoHandler",
                "WNPrivateAudioHandler",
                "Lavf",
                "libx264",
            )
        val MOV_PRIVATE =
            listOf(
                "WNPrivateMake",
                "WNPrivateModel",
                "WNPrivateSoftware",
                "+12.3456-065.4321",
                "2024-05-06",
                "Lavf",
                "libx264",
            )
        val MKV_PRIVATE =
            listOf("WNPrivateTitle", "WNPrivateTrackName", "WNPrivateAttachmentNote", "+48.8584", "Lavf", "libx264")
        val WEBM_PRIVATE = listOf("WNPrivateTitle", "WNPrivateTrackName", "+48.8584", "Lavf", "libvpx")
        val MUXED_PRIVATE = listOf(ANDROID_VERSION_KEY)

        /** Every route that must reach the receiver scrubbed, each with its own plaintext so none is deduplicated. */
        val ACCEPTED =
            listOf(
                VideoRoute(
                    "visual-content-uri-in-memory",
                    VideoRouteEntry.VISUAL,
                    "vm-visual-small.mp4",
                    VideoSource(GeneratedVideoDocuments.MUXED_SOURCE, "video/mp4"),
                    VideoExpectation(MUXED_PRIVATE, locationPositive = true),
                ),
                VideoRoute(
                    "visual-content-uri-file-backed",
                    VideoRouteEntry.VISUAL,
                    "vm-visual-large.mp4",
                    VideoSource(GeneratedVideoDocuments.MUXED_SOURCE, "video/mp4", paddedBytes = 40 * MIB),
                    VideoExpectation(MUXED_PRIVATE, locationPositive = true),
                ),
                VideoRoute(
                    "files-content-uri-octet-in-memory",
                    VideoRouteEntry.FILES,
                    "vm-files-small.bin",
                    VideoSource("mp4-tagged.mp4", OCTET_STREAM),
                    VideoExpectation(MP4_PRIVATE),
                ),
                VideoRoute(
                    "files-content-uri-octet-file-backed",
                    VideoRouteEntry.FILES,
                    "vm-files-large.bin",
                    VideoSource("mov-quicktime-keys.mov", OCTET_STREAM, paddedBytes = 36 * MIB),
                    VideoExpectation(MOV_PRIVATE),
                ),
                VideoRoute(
                    "share-file-importer-in-memory",
                    VideoRouteEntry.SHARE,
                    "vm-share.mkv",
                    VideoSource("mkv-tagged.mkv", "video/x-matroska"),
                    VideoExpectation(MKV_PRIVATE),
                ),
                VideoRoute(
                    "prepared-draft-injection-visual",
                    VideoRouteEntry.VISUAL_DRAFT,
                    "vm-draft.webm",
                    VideoSource("webm-live-tagged.webm", "video/webm"),
                    VideoExpectation(WEBM_PRIVATE),
                ),
                VideoRoute(
                    "prepared-draft-injection-files",
                    VideoRouteEntry.FILES_DRAFT,
                    "vm-draft-files.bin",
                    VideoSource("mp4-timecode.mp4", OCTET_STREAM),
                    VideoExpectation(listOf("WNPrivateComment", "Lavf")),
                ),
            )

        /** Every route the sender must refuse with its existing notice, before anything is uploaded. */
        val REFUSED =
            listOf(
                VideoRoute(
                    "refused-visual-content-uri-avi",
                    VideoRouteEntry.VISUAL,
                    "vm-refused.avi",
                    VideoSource(GeneratedVideoDocuments.AVI_SOURCE, "video/x-msvideo"),
                    VideoExpectation(notice = R.string.toast_video_metadata_not_removable),
                ),
                VideoRoute(
                    "refused-files-content-uri-avi",
                    VideoRouteEntry.FILES,
                    "vm-refused-files.avi",
                    VideoSource(GeneratedVideoDocuments.AVI_SOURCE, "video/x-msvideo"),
                    VideoExpectation(notice = R.string.media_file_video_metadata_not_removable),
                ),
                VideoRoute(
                    "refused-files-content-uri-avi-octet",
                    VideoRouteEntry.FILES,
                    "vm-refused-avi.bin",
                    VideoSource(GeneratedVideoDocuments.AVI_SOURCE, OCTET_STREAM),
                    VideoExpectation(notice = R.string.media_file_video_metadata_not_removable),
                ),
                VideoRoute(
                    "refused-files-content-uri-trailer-in-memory",
                    VideoRouteEntry.FILES,
                    "vm-refused-trailer.bin",
                    VideoSource("mp4-tagged.mp4", OCTET_STREAM, trailingByte = true),
                    VideoExpectation(notice = R.string.media_file_video_metadata_not_removable),
                ),
                VideoRoute(
                    "refused-files-content-uri-trailer-file-backed",
                    VideoRouteEntry.FILES,
                    "vm-refused-trailer-large.bin",
                    VideoSource("mp4-tagged.mp4", OCTET_STREAM, paddedBytes = 34 * MIB, trailingByte = true),
                    VideoExpectation(notice = R.string.media_file_video_metadata_not_removable),
                ),
            )
    }
}

internal const val OCTET_STREAM = "application/octet-stream"
internal const val ANDROID_VERSION_KEY = "com.android.version"

/** How one route hands its video to the sender, named as the report names it. */
internal enum class VideoRouteEntry(
    val label: String,
) {
    VISUAL("visual-content-uri"),
    FILES("files-content-uri"),
    SHARE("share-file-importer"),
    VISUAL_DRAFT("prepared-draft-injection-visual"),
    FILES_DRAFT("prepared-draft-injection-files"),
}

/**
 * One provider document: [source] served as [mimeType], grown to exactly [paddedBytes] when positive and followed by
 * one extra byte when [trailingByte] is set.
 */
internal data class VideoSource(
    val source: String,
    val mimeType: String,
    val paddedBytes: Long = 0L,
    val trailingByte: Boolean = false,
)

/**
 * What a route must show: the [privateStrings] its source carries, whether the platform retriever reads a location
 * from the source ([locationPositive]), and for a refused route the [notice] the sender must present.
 */
internal data class VideoExpectation(
    val privateStrings: List<String> = emptyList(),
    val locationPositive: Boolean = false,
    @param:StringRes val notice: Int? = null,
)

/** One fixture route: a closed [name] for the report, its [entry], the [fileName] it is sent under and its bytes. */
internal data class VideoRoute(
    val name: String,
    val entry: VideoRouteEntry,
    val fileName: String,
    val source: VideoSource,
    val expect: VideoExpectation,
)

/** One generated session's sender, receiver and working directory, and the steps every route takes. */
private class VideoMetadataRun(
    private val session: FixtureSession,
    private val peers: FixturePeers,
    private val harness: SenderHarness,
    private val work: File,
) {
    private val context: Context = session.context
    private val testPackage = InstrumentationRegistry.getInstrumentation().context.packageName
    private val sender = ConversationMediaSender(harness.state, harness.controller, context) {}

    /**
     * Sends one route, waits for the sender's genuine publication, downloads it as the receiver and reports what the
     * received plaintext carries against its source and the expected scrubbed bytes.
     */
    suspend fun accepted(route: VideoRoute) {
        val original = readOriginal(route)
        val expected = scrubbedCopy(original, route)
        val sent = send(route, original)
        check(sent.accepted) { "${route.name} was not accepted" }
        val reference = awaitPublished(route.fileName)
        val received = receive(route, reference)
        val facts = VideoRouteFacts.accepted(route, original, expected, received)
        facts.put("published_sha256", reference.plaintextSha256)
        facts.put("share_imported_private_copy", sent.sharedPrivateCopy)
        facts.put("staged_left_after_send", awaitNoStagedFiles())
        ControlledAttachmentProbe.report(facts)
        listOf(original, expected, received).forEach(File::delete)
    }

    /** Offers one unreadable route and reports the notice, any upload and anything it left behind. */
    suspend fun refused(route: VideoRoute) {
        val original = readOriginal(route)
        withContext(Dispatchers.Main.immediate) { harness.state.clearToast() }
        val sent = send(route, original)
        val notice = withContext(Dispatchers.Main.immediate) { harness.state.toast?.title }
        // A refused send must never reach the group, so wait past the time a publish would take.
        delay(REFUSAL_SETTLE_MILLIS)
        val facts = VideoRouteFacts.refused(route, original)
        facts.put("rejected", !sent.accepted)
        facts.put("notice", noticeName(notice))
        facts.put("notice_matches", notice == route.expect.notice?.let { AppText.Resource(it) })
        facts.put("staged_left", stagedFiles().size)
        facts.put("temp_left", videoTempFiles().size)
        facts.put("published", route.fileName in publishedNames(peers.sender.label))
        ControlledAttachmentProbe.report(facts)
        original.delete()
    }

    /** Reports which of the [acceptedRoutes] and [refusedRoutes] each account's history holds once sends settle. */
    suspend fun reportHistories(
        acceptedRoutes: List<VideoRoute>,
        refusedRoutes: List<VideoRoute>,
    ) {
        delay(REFUSAL_SETTLE_MILLIS)
        val accepted = acceptedRoutes.map { it.fileName }
        val refused = refusedRoutes.map { it.fileName }
        val sent = publishedNames(peers.sender.label)
        val received = publishedNames(peers.receiver.label)
        ControlledAttachmentProbe.report(
            JSONObject()
                .put("phase", "video-metadata-history")
                .put("accepted_published", accepted.count { it in sent })
                .put("accepted_received", accepted.count { it in received })
                .put("refused_published", refused.count { it in sent })
                .put("refused_received", refused.count { it in received })
                .put("unexpected_published", (sent - accepted.toSet() - refused.toSet()).size),
        )
    }

    /** One send's outcome: whether the sender accepted it and whether it went through a private share copy. */
    private class Sent(
        val accepted: Boolean,
        val sharedPrivateCopy: Boolean,
    )

    /** Copies what the provider serves for [route] into the working directory, as the sender will read it. */
    private suspend fun readOriginal(route: VideoRoute): File =
        withContext(Dispatchers.IO) {
            val file = File(work, "original-${route.name}")
            val input = requireNotNull(context.contentResolver.openInputStream(uri(route, route.source.source)))
            input.use { stream -> file.outputStream().use { stream.copyTo(it) } }
            file
        }

    /** The bytes a correct sender uploads: [original] scrubbed in place on a copy by the production scrubber. */
    private suspend fun scrubbedCopy(
        original: File,
        route: VideoRoute,
    ): File =
        withContext(Dispatchers.IO) {
            val expected = original.copyTo(File(work, "expected-${route.name}"))
            val result = VideoMetadataScrubber.scrub(expected)
            check(result is VideoScrubResult.Scrubbed) { "${route.name} could not be scrubbed: $result" }
            expected
        }

    /** The provider URI for [route] serving [source], which a draft route replaces with one that cannot be read. */
    private fun uri(
        route: VideoRoute,
        source: String,
    ): Uri =
        GeneratedVideoDocuments.uri(
            testPackage,
            source,
            route.source.mimeType,
            route.fileName,
            route.source.paddedBytes,
            route.source.trailingByte,
        )

    /**
     * Hands [route] to [ConversationMediaSender.sendStagedAttachments] the way its entry does, and waits for the
     * sender to accept or reject it. A share import releases its request lease once the send has been decided.
     */
    private suspend fun send(
        route: VideoRoute,
        original: File,
    ): Sent {
        val shareRequest = if (route.entry == VideoRouteEntry.SHARE) importShare(route) else null
        val staged = shareRequest?.let(::shareStaging) ?: entryStaging(route, original)
        val decided = CompletableDeferred<Boolean>()
        withContext(Dispatchers.Main.immediate) {
            sender.sendStagedAttachments(
                imageSlots = staged.slots,
                documentUris = staged.documents,
                caption = "",
                preparedImageAttachments = staged.preparedImages,
                preparedDocumentAttachments = staged.preparedDocuments,
                onAccepted = { decided.complete(true) },
                onRejected = { decided.complete(false) },
            )
        }
        val accepted = withTimeout(DECISION_TIMEOUT_MILLIS) { decided.await() }
        shareRequest?.let { request ->
            withContext(Dispatchers.IO) { PrivateShareFiles(context).leases.releaseRequest(request.requestId) }
        }
        return Sent(accepted, sharedPrivateCopy = shareRequest != null)
    }

    /** What one send hands the sender: visual slots, document URIs and any prepared draft attachments. */
    private class Staging(
        val slots: List<PendingMediaSlot> = emptyList(),
        val documents: List<Uri> = emptyList(),
        val preparedImages: Map<String, dev.ipf.whitenoise.android.state.PendingAttachment> = emptyMap(),
        val preparedDocuments: Map<Uri, dev.ipf.whitenoise.android.state.PendingAttachment> = emptyMap(),
    )

    /**
     * The staging for a picker or draft entry. A draft entry's slot or document URI can no longer be read, so the
     * send can only use the injected native draft's own bytes.
     */
    private fun entryStaging(
        route: VideoRoute,
        original: File,
    ): Staging {
        val picked = uri(route, route.source.source)
        val gone = uri(route, GeneratedVideoDocuments.ABSENT_SOURCE)
        val slotId = "slot-${route.name}"
        return when (route.entry) {
            VideoRouteEntry.VISUAL -> Staging(slots = listOf(PendingMediaSlot(slotId, picked)))
            VideoRouteEntry.FILES -> Staging(documents = listOf(picked))
            VideoRouteEntry.VISUAL_DRAFT ->
                Staging(
                    slots = listOf(PendingMediaSlot(slotId, gone)),
                    preparedImages = mapOf(slotId to legacyDraft(route, original).toPendingAttachment()),
                )
            VideoRouteEntry.FILES_DRAFT ->
                Staging(
                    documents = listOf(gone),
                    preparedDocuments = mapOf(gone to legacyDraft(route, original).toPendingAttachment()),
                )
            VideoRouteEntry.SHARE -> error("a share route is staged from its import")
        }
    }

    /** A native draft attachment holding the unsanitized source, as an app version before #3376 saved one. */
    private fun legacyDraft(
        route: VideoRoute,
        original: File,
    ) = MessageDraftAttachmentFfi(
        id = "legacy-${route.name}",
        fileName = route.fileName,
        mediaType = route.source.mimeType,
        plaintext = original.readBytes(),
        dim = "48x32",
        thumbhash = null,
        durationSeconds = null,
        waveformSamples = emptyList(),
    )

    /**
     * Imports the route's provider document through the production [ShareFileImporter] into private share storage,
     * as an inbound share does once its intent has arrived, and requires exactly one private copy.
     */
    private suspend fun importShare(route: VideoRoute): ShareRequest {
        val request =
            ShareRequest(
                payload = SharePayload(null, listOf(uri(route, route.source.source)), route.source.mimeType),
                shortcutId = null,
                requestId = UUID.randomUUID().toString(),
            )
        val imported = ShareFileImporter(context).import(request)
        val copies = imported.payload.streamUris
        check(imported.payload.importErrors.isEmpty()) { "share import failed: ${imported.payload.importErrors}" }
        check(copies.size == 1 && PrivateShareFiles(context).owns(copies.single())) { "share import kept no copy" }
        return imported
    }

    /** Classifies an imported share with the production [ShareInboundStager], as the composer receives it. */
    private fun shareStaging(request: ShareRequest): Staging {
        val stager = ShareInboundStager({ _, _, _ -> }, ShareStagingStore(), ::shareResolveMime)
        val streams = requireNotNull(stager.prepare(context, request.payload).streamStaging)
        return Staging(
            slots = streams.mediaUris.mapIndexed { index, uri -> PendingMediaSlot("share-$index", uri) },
            documents = streams.documentUris,
        )
    }

    /** Waits for the sender's genuine publication of [fileName] and returns its reference. */
    private suspend fun awaitPublished(fileName: String): MediaAttachmentReferenceFfi =
        withTimeout(PUBLISH_TIMEOUT_MILLIS) {
            var reference = historyReferences(peers.sender.label)?.firstOrNull { it.fileName == fileName }
            while (reference == null) {
                delay(POLL_MILLIS)
                reference = historyReferences(peers.sender.label)?.firstOrNull { it.fileName == fileName }
            }
            reference
        }

    /** Downloads the receiver's copy of [reference] through the shipping resolver into the working directory. */
    private suspend fun receive(
        route: VideoRoute,
        reference: MediaAttachmentReferenceFfi,
    ): File {
        val request =
            MediaLifecycleAttachmentProbe
                .projectRequests(session.marmot, peers.receiver, peers.group, listOf(reference))
                .single()
        val file = File(work, "received-${route.name}")
        val state = session.state(peers.receiver.label)
        state.downloadAttachmentPlaintextSource(request, reference, persistInteractiveIntent = false).use { lease ->
            withContext(Dispatchers.IO) { file.outputStream().use(lease::copyTo) }
        }
        return file
    }

    /**
     * The file names [account]'s history has accepted. A history that cannot be read fails the fixture rather than
     * passing as "nothing published".
     */
    private suspend fun publishedNames(account: String): Set<String> =
        withTimeout(PUBLISH_TIMEOUT_MILLIS) {
            var references = historyReferences(account)
            while (references == null) {
                delay(POLL_MILLIS)
                references = historyReferences(account)
            }
            references.mapNotNull { it.fileName }.toSet()
        }

    /** Every accepted attachment reference in [account]'s group history, or null while no page can be read. */
    private suspend fun historyReferences(account: String): List<MediaAttachmentReferenceFfi>? {
        val read = session.marmot.attachmentHistoryPage(account, peers.group, 100u, null)
        val page = (read as? AttachmentPageReadFfi.Page)?.page
        return page?.let {
            try {
                it.entries.mapNotNull { entry -> (entry.attachment as? MediaAttachmentOutcomeFfi.Accepted)?.reference }
            } finally {
                it.nextCursor?.close()
                it.version.close()
            }
        }
    }

    /** Waits for the controller's off-main release of every staged snapshot and reports how many are left. */
    private suspend fun awaitNoStagedFiles(): Int {
        var left = stagedFiles().size
        var attempts = 0
        while (left > 0 && attempts < STAGED_RELEASE_POLLS) {
            delay(POLL_MILLIS)
            left = stagedFiles().size
            attempts++
        }
        return left
    }

    /** Private snapshots of large picks still on disk. */
    private fun stagedFiles(): List<File> {
        val root = File(context.cacheDir, MediaCacheDirs.UPLOAD_SOURCES)
        return root.walk().filter(File::isFile).toList()
    }

    /** The in-memory video read's temporary copies still on disk. */
    private fun videoTempFiles(): List<File> {
        val root = File(context.cacheDir, MediaCacheDirs.VIDEO)
        return root.listFiles().orEmpty().filter { it.name.startsWith("vidmeta-") }
    }

    /** The resource name of the notice the sender presented, or "none". */
    private fun noticeName(notice: AppText?): String {
        val resource = notice as? AppText.Resource
        return resource?.let { context.resources.getResourceEntryName(it.resId) } ?: "none"
    }

    private companion object {
        const val DECISION_TIMEOUT_MILLIS = 180_000L
        const val PUBLISH_TIMEOUT_MILLIS = 180_000L
        const val REFUSAL_SETTLE_MILLIS = 5_000L
        const val POLL_MILLIS = 100L
        const val STAGED_RELEASE_POLLS = 100
    }
}
