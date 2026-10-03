package dev.ipf.whitenoise.android.media

import android.content.Context
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.os.Process
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import dev.ipf.marmotkit.AccountSummaryFfi
import dev.ipf.marmotkit.AttachmentAcquisitionModeFfi
import dev.ipf.marmotkit.AttachmentPageReadFfi
import dev.ipf.marmotkit.Marmot
import dev.ipf.marmotkit.MarmotAndroid
import dev.ipf.marmotkit.MarmotOptions
import dev.ipf.marmotkit.MediaAttachmentOutcomeFfi
import dev.ipf.marmotkit.MediaAttachmentReferenceFfi
import dev.ipf.marmotkit.RelayPolicyFfi
import dev.ipf.whitenoise.android.state.AppMarmotRuntime
import dev.ipf.whitenoise.android.state.AttachmentTransferRequest
import dev.ipf.whitenoise.android.state.DraftPersistence
import dev.ipf.whitenoise.android.state.DraftStore
import dev.ipf.whitenoise.android.state.PendingAttachment
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.state.cacheKey
import dev.ipf.whitenoise.android.state.downloadAttachmentPlaintextSource
import dev.ipf.whitenoise.android.state.enforceAppOwnedAttachmentAcquisitionPolicy
import dev.ipf.whitenoise.android.state.openNativeAttachment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import java.io.File
import java.io.OutputStream
import java.security.MessageDigest
import java.util.UUID

/** One generated send: a single image, a single video or an album, with its own genuine message identity. */
private class FixtureMessage(
    val key: String,
    val kind: String,
    val ownCacheQualified: Boolean,
    val build: () -> List<PendingAttachment>,
)

/** Everything the read process needs about one attachment, produced by the prepare process. */
private class ManifestAttachment(
    val message: String,
    val kind: String,
    val media: String,
    val index: Int,
    val bytes: Long,
    val sha256: String,
    val sender: AttachmentTransferRequest,
    val receiver: AttachmentTransferRequest,
)

/**
 * Sends genuine images, videos and an album, downloads them as the receiving account, then verifies in a separately
 * launched process, with acquisition unavailable, that every copy is still readable and decodes a first frame.
 */
internal object MediaLifecycleAttachmentProbe {
    private const val MANIFEST = "media-lifecycle.json"
    private const val MIB = 1024 * 1024
    private const val DEADLINE_MILLIS = 600_000L
    private const val PROJECTION_TIMEOUT_MILLIS = 30_000L
    private const val POLL_MILLIS = 100L
    private const val HEX_BYTE_MASK = 0xff
    private const val NANOS_PER_MILLI = 1_000_000.0

    /** Runs the one role selected by the host; no public endpoint, personal account or installed draft is touched. */
    suspend fun run() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue(arguments.getString("allowControlledAttachmentProbe") == "true")
        check(context.packageName == "dev.ipf.whitenoise.android.medialatency")
        val role = requireNotNull(arguments.getString("fixtureRestartRole"))
        require(role in setOf("prepare", "read"))
        val blobPort = requireNotNull(arguments.getString("fixtureBlobPort")).toInt()
        val relayPort = requireNotNull(arguments.getString("fixtureRelayPort")).toInt()
        require(blobPort in 1024..65535 && relayPort in 1024..65535)
        MarmotAndroid.initialize(context)
        val restartSession = arguments.getString("fixtureRestartSession")
        val root = RestartAttachmentRetentionProbe.createRoot(context, role, restartSession)
        val relays = listOf("ws://127.0.0.1:$relayPort")
        val marmot =
            Marmot.newWithConfiguration(
                root.absolutePath,
                relays,
                MarmotOptions(
                    relayPolicy = RelayPolicyFfi.ALLOW_LOOPBACK_RELAYS_AND_BLOBS,
                    attachmentAcquisitionMode = AttachmentAcquisitionModeFfi.HOST_MANAGED,
                ),
            )
        val session = Session(context, root, marmot, relays, blobPort)
        try {
            withTimeout(DEADLINE_MILLIS) {
                marmot.start()
                if (role == "prepare") prepare(session) else read(session)
            }
        } finally {
            session.close(preserve = role == "prepare")
        }
    }

    /** The runtime, generated accounts and per-account states for one process, always released on every path. */
    private class Session(
        val context: Context,
        val root: File,
        val marmot: Marmot,
        val relays: List<String>,
        val blobPort: Int,
    ) {
        val accounts = mutableListOf<String>()
        val states = mutableMapOf<String, WhiteNoiseAppState>()

        /** Builds a fixture-only state for [account]; its drafts and caches never touch the installed app. */
        suspend fun state(account: String): WhiteNoiseAppState =
            states.getOrPut(account) {
                withContext(Dispatchers.Main.immediate) {
                    WhiteNoiseAppState(
                        context = context,
                        draftStore = DraftStore(DiscardedDrafts),
                        accountIdHexResolver = { null },
                        accounts = emptyList(),
                        activeAccountRef = account,
                        initialMarmotRuntime = AppMarmotRuntime(root.absolutePath, marmot),
                    )
                }
            }

        /** Cancels fixture work, removes only generated accounts and, unless preserved, the generated root. */
        suspend fun close(preserve: Boolean) {
            states.values.forEach { it.mutationsScope.cancel() }
            val cleanup =
                if (preserve) {
                    emptyList()
                } else {
                    accounts.map { runCatching { withTimeout(5_000L) { marmot.removeAccount(it) } } }
                }
            try {
                marmot.shutdownAndClose()
            } finally {
                if (!preserve) root.deleteRecursively()
            }
            check(cleanup.all { it.isSuccess }) { "fixture account cleanup failed" }
        }
    }

    /** The generated sends: sizes straddle the 8 MiB memory-entry ceiling and the 32 MiB send ceiling. */
    private fun messages(): List<FixtureMessage> =
        listOf(
            FixtureMessage("single-image", "image", false) {
                listOf(image("photo-single.jpg", 1L))
            },
            FixtureMessage("video-small", "video", false) {
                listOf(video("clip-small.mp4", FixtureMediaAssets.video()))
            },
            FixtureMessage("video-9mib", "video", false) {
                listOf(video("clip-9mib.mp4", FixtureMediaAssets.paddedVideo(FixtureMediaAssets.video(), 9 * MIB, 9L)))
            },
            FixtureMessage("video-24mib", "video", true) {
                val padded = FixtureMediaAssets.paddedVideo(FixtureMediaAssets.video(), 24 * MIB, 24L)
                listOf(video("clip-24mib.mp4", padded))
            },
            FixtureMessage("album-3", "album", false) {
                listOf(
                    image("album-a.jpg", 2L),
                    image("album-b.jpg", 3L),
                    video("album-clip.mp4", FixtureMediaAssets.video()),
                )
            },
        )

    /** A real JPEG attachment with its declared dimensions. */
    private fun image(
        name: String,
        seed: Long,
    ) = FixtureMediaAssets.attachment(FixtureMediaAssets.jpeg(seed), "image/jpeg", name, "1280x960")

    /** A real MP4 attachment. */
    private fun video(
        name: String,
        bytes: ByteArray,
    ) = FixtureMediaAssets.attachment(bytes, "video/mp4", name, "320x240")

    /** Sends every message genuinely, reads the sender's retained copy, then downloads each as the receiver. */
    private suspend fun prepare(session: Session) {
        val sender = session.marmot.createIdentity(session.relays, session.relays)
        session.accounts += sender.label
        val receiver = session.marmot.createIdentity(session.relays, session.relays)
        session.accounts += receiver.label
        session.marmot.enforceAppOwnedAttachmentAcquisitionPolicy(listOf(receiver.label, sender.label))
        val group = session.marmot.createGroup(sender.label, "Generated fixture", listOf(receiver.accountIdHex), null)
        ControlledAttachmentProbe.awaitReceivedGroup(session.marmot, receiver.label, group)
        val manifest = JSONArray()
        for (message in messages()) {
            val attachments = message.build()
            val sent =
                sendAndroidFixtureMedia(
                    session.context,
                    session.root,
                    session.marmot,
                    sender,
                    group,
                    session.blobPort,
                    attachments,
                    message.ownCacheQualified,
                    awaitOwnHostPublication = !message.ownCacheQualified,
                )
            session.marmot.catchUpAccounts()
            sent.ownHostCached.forEachIndexed { index, cached ->
                val own = JSONObject().put("phase", "media-own-host-publication").put("message", message.key)
                ControlledAttachmentProbe.report(own.put("index", index).put("published", cached))
            }
            val received = projectRequests(session.marmot, receiver, group, sent.references)
            attachments.forEachIndexed { index, attachment ->
                val entry =
                    ManifestAttachment(
                        message = message.key,
                        kind = message.kind,
                        media = if (attachment.mediaType.startsWith("video/")) "video" else "image",
                        index = index,
                        bytes = attachment.plaintextBytes.size.toLong(),
                        sha256 = sha256Hex(attachment.plaintextBytes),
                        sender =
                            AttachmentTransferRequest(
                                sender.label,
                                group,
                                sent.messageIdHex,
                                index,
                                sent.sourceMessageIdHex,
                            ),
                        receiver = received[index],
                    )
                verifySenderRetained(session.state(sender.label), entry)
                downloadAsReceiver(session.state(receiver.label), entry, sent.references[index])
                manifest.put(entry.toJson())
            }
            val sentReport = JSONObject().put("phase", "media-message-sent").put("message", message.key)
            ControlledAttachmentProbe.report(sentReport.put("attachments", attachments.size))
        }
        HeldAttachmentCancellationProbe.control(session.blobPort, "/__acquisition-unavailable")
        val receipt = JSONObject().put("schema", 1).put("prepare_pid", Process.myPid()).put("items", manifest)
        File(session.root, MANIFEST).writeText(receipt.toString())
        ControlledAttachmentProbe.report(JSONObject().put("phase", "fixture-stage").put("stage", "media-prepared"))
    }

    /** The sender's genuine native copy is complete, exact and independent of any receiver download. */
    private suspend fun verifySenderRetained(
        state: WhiteNoiseAppState,
        entry: ManifestAttachment,
    ) {
        val label = "media-sender-retained-${entry.message}-${entry.index}"
        ControlledAttachmentProbe.measure(label, entry.bytes.toInt()) {
            val local = requireNotNull(state.openNativeAttachment(entry.sender)) { "sender retained copy missing" }
            local.use { assertEquals(entry.sha256, digestOf(it)) }
        }
    }

    /** One cold, interactive receiver download through the unchanged resolver; this is the only acquisition. */
    private suspend fun downloadAsReceiver(
        state: WhiteNoiseAppState,
        entry: ManifestAttachment,
        reference: MediaAttachmentReferenceFfi,
    ) {
        ControlledAttachmentProbe.measure("media-receiver-cold-${entry.message}-${entry.index}", entry.bytes.toInt()) {
            state.downloadAttachmentPlaintextSource(entry.receiver, reference, persistInteractiveIntent = false).use {
                assertEquals(entry.sha256, digestOf(it))
            }
        }
    }

    /** Verifies, in a new process with acquisition unavailable, every attachment in both directions. */
    private suspend fun read(session: Session) {
        val manifest = JSONObject(File(session.root, MANIFEST).readText())
        check(manifest.getInt("schema") == 1)
        val previousPid = manifest.getInt("prepare_pid")
        assertNotEquals("fixture did not cross an Android process boundary", previousPid, Process.myPid())
        val items = manifest.getJSONArray("items")
        val attachments = List(items.length()) { manifestAttachmentFrom(items.getJSONObject(it)) }
        session.accounts += attachments.flatMap { listOf(it.sender.accountRef, it.receiver.accountRef) }.distinct()
        for (attachment in attachments) {
            for ((role, request) in listOf("sent" to attachment.sender, "received" to attachment.receiver)) {
                verifyLocal(session, attachment, role, request)
            }
        }
        verifyAlbumIdentity(attachments)
        val complete = JSONObject().put("phase", "media-readback-complete")
        ControlledAttachmentProbe.report(complete.put("attachments", attachments.size))
    }

    /** One direction of one attachment: no memory hit, exact retained bytes, resolver parity and a decoded preview. */
    private suspend fun verifyLocal(
        session: Session,
        attachment: ManifestAttachment,
        role: String,
        request: AttachmentTransferRequest,
    ) {
        val state = session.state(request.accountRef)
        val key = request.cacheKey()
        withContext(Dispatchers.Main.immediate) {
            assertNull("a fresh process has no memory entry", state.cachedMediaPlaintext(key))
        }
        val hostDisk = withContext(Dispatchers.IO) { state.diskMediaCache.containsAfterHydration(key) }
        val label = "$role-${attachment.message}-${attachment.index}"
        val size = attachment.bytes.toInt()
        ControlledAttachmentProbe.measure("media-native-read-$label", size) {
            requireNotNull(state.openNativeAttachment(request)).use { assertEquals(attachment.sha256, digestOf(it)) }
        }
        val reference = publishedReference(session.marmot, request)
        ControlledAttachmentProbe.measure("media-resolver-read-$label", size) {
            state.downloadAttachmentPlaintextSource(request, reference, persistInteractiveIntent = false).use {
                assertEquals(attachment.sha256, digestOf(it))
            }
        }
        val preview = decodePreview(session, state, request, attachment)
        ControlledAttachmentProbe.report(
            JSONObject()
                .put("phase", "media-readback")
                .put("message", attachment.message)
                .put("index", attachment.index)
                .put("role", role)
                .put("kind", attachment.kind)
                .put("bytes", attachment.bytes)
                .put("exact", true)
                .put("memory_hit", false)
                .put("host_disk_hit", hostDisk)
                .put("preview_ok", preview.first)
                .put("preview_ms", preview.second),
        )
    }

    /** Decodes what a tile would show first: a bitmap for an image, a first frame and duration for a video. */
    private suspend fun decodePreview(
        session: Session,
        state: WhiteNoiseAppState,
        request: AttachmentTransferRequest,
        attachment: ManifestAttachment,
    ): Pair<Boolean, Double> {
        val started = SystemClock.elapsedRealtimeNanos()
        val local = requireNotNull(state.openNativeAttachment(request))
        val ok =
            local.use { lease ->
                if (attachment.media == "video") {
                    decodeVideoFrame(session, lease)
                } else {
                    val bitmap = BitmapFactory.decodeByteArray(lease.toByteArray(), 0, lease.size.toInt())
                    (bitmap != null && bitmap.width > 0 && bitmap.height > 0).also { bitmap?.recycle() }
                }
            }
        assertTrue("preview did not decode for ${attachment.message}#${attachment.index}", ok)
        return ok to (SystemClock.elapsedRealtimeNanos() - started) / NANOS_PER_MILLI
    }

    /** Writes the lease to a private temp file and extracts frame zero, as the poster path does. */
    private suspend fun decodeVideoFrame(
        session: Session,
        lease: AttachmentPlaintext,
    ): Boolean =
        withContext(Dispatchers.IO) {
            val file = File(session.context.cacheDir, "media-lifecycle-${UUID.randomUUID()}.mp4")
            try {
                file.outputStream().use { lease.copyTo(it) }
                val retriever = MediaMetadataRetriever()
                try {
                    retriever.setDataSource(file.absolutePath)
                    val frame = retriever.getFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                    val rawDuration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    val duration = rawDuration?.toLongOrNull() ?: 0L
                    (frame != null && duration > 0L).also { frame?.recycle() }
                } finally {
                    retriever.release()
                }
            } finally {
                file.delete()
            }
        }

    /** Each album index resolves to its own plaintext, so the logical identity survives across processes. */
    private fun verifyAlbumIdentity(attachments: List<ManifestAttachment>) {
        val album = attachments.filter { it.message == "album-3" }
        assertEquals(3, album.size)
        assertEquals(listOf(0, 1, 2), album.map { it.index })
        assertEquals("distinct album members must not share bytes", 3, album.map { it.sha256 }.toSet().size)
        assertEquals(1, album.map { it.receiver.messageIdHex }.toSet().size)
    }

    /** Native history, not the manifest, supplies the reference after restart. */
    private suspend fun publishedReference(
        marmot: Marmot,
        request: AttachmentTransferRequest,
    ): MediaAttachmentReferenceFfi {
        val read = marmot.attachmentHistoryPage(request.accountRef, request.groupIdHex, 100u, null)
        val page = (read as AttachmentPageReadFfi.Page).page
        return try {
            val accepted =
                page.entries
                    .filter { it.messageIdHex == request.messageIdHex }
                    .mapNotNull { it.attachment as? MediaAttachmentOutcomeFfi.Accepted }
                    .single { it.attachmentIndex.toInt() == request.attachmentIndex }
            accepted.reference
        } finally {
            page.nextCursor?.close()
            page.version.close()
        }
    }

    /** Resolves the receiver's canonical identity for every reference, preserving each album index. */
    private suspend fun projectRequests(
        marmot: Marmot,
        receiver: AccountSummaryFfi,
        group: String,
        references: List<MediaAttachmentReferenceFfi>,
    ): List<AttachmentTransferRequest> =
        withTimeout(PROJECTION_TIMEOUT_MILLIS) {
            var found: List<AttachmentTransferRequest>? = null
            while (found == null) {
                found = projected(marmot, receiver.label, group, references)
                if (found == null) delay(POLL_MILLIS)
            }
            found
        }

    /** One native history read; null until every reference has been received. */
    private suspend fun projected(
        marmot: Marmot,
        account: String,
        group: String,
        references: List<MediaAttachmentReferenceFfi>,
    ): List<AttachmentTransferRequest>? {
        val read = marmot.attachmentHistoryPage(account, group, 100u, null)
        if (read !is AttachmentPageReadFfi.Page) return null
        val page = read.page
        return try {
            val requests =
                references.map { reference ->
                    page.entries.firstNotNullOfOrNull { entry ->
                        val accepted = entry.attachment as? MediaAttachmentOutcomeFfi.Accepted
                        accepted
                            ?.takeIf { it.reference.ciphertextSha256 == reference.ciphertextSha256 }
                            ?.let {
                                AttachmentTransferRequest(
                                    account,
                                    group,
                                    entry.messageIdHex,
                                    it.attachmentIndex.toInt(),
                                    entry.sourceMessageIdHex,
                                )
                            }
                    }
                }
            requests.takeIf { all -> all.all { it != null } }?.map { requireNotNull(it) }
        } finally {
            page.nextCursor?.close()
            page.version.close()
        }
    }

    /** Streams a lease through SHA-256 without a second full-size allocation. */
    private suspend fun digestOf(local: AttachmentPlaintext): String {
        val digest = MessageDigest.getInstance("SHA-256")
        local.copyTo(
            object : OutputStream() {
                override fun write(value: Int) = digest.update(value.toByte())

                override fun write(
                    bytes: ByteArray,
                    offset: Int,
                    length: Int,
                ) = digest.update(bytes, offset, length)
            },
        )
        return digest.digest().toHex()
    }

    /** Hex digest of generated plaintext, kept so the read process can compare without the original bytes. */
    private fun sha256Hex(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).toHex()

    /** Lowercase hex without allocating a string per byte. */
    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it.toInt() and HEX_BYTE_MASK) }

    /** Serializes only generated synthetic identities, which stay on the device. */
    private fun ManifestAttachment.toJson(): JSONObject =
        JSONObject()
            .put("message", message)
            .put("kind", kind)
            .put("media", media)
            .put("index", index)
            .put("bytes", bytes)
            .put("sha256", sha256)
            .put("sender", sender.toJson())
            .put("receiver", receiver.toJson())

    /** Keeps display and source identity distinct across the process boundary. */
    private fun AttachmentTransferRequest.toJson(): JSONObject =
        JSONObject()
            .put("account", accountRef)
            .put("group", groupIdHex)
            .put("message", messageIdHex)
            .put("source", requireNotNull(sourceMessageIdHex))
            .put("index", attachmentIndex)

    /** Restores one request from the private receipt. */
    private fun requestFrom(value: JSONObject) =
        AttachmentTransferRequest(
            value.getString("account"),
            value.getString("group"),
            value.getString("message"),
            value.getInt("index"),
            value.getString("source"),
        )

    /** Restores one manifest entry. */
    private fun manifestAttachmentFrom(value: JSONObject) =
        ManifestAttachment(
            value.getString("message"),
            value.getString("kind"),
            value.getString("media"),
            value.getInt("index"),
            value.getLong("bytes"),
            value.getString("sha256"),
            requestFrom(value.getJSONObject("sender")),
            requestFrom(value.getJSONObject("receiver")),
        )

    /** The generated session has no persisted user draft. */
    private object DiscardedDrafts : DraftPersistence {
        /** Never reads installed draft storage. */
        override fun read(): Map<String, String> = emptyMap()

        /** Discards only generated-fixture draft writes. */
        override fun write(
            key: String,
            value: String?,
        ) = Unit
    }
}
