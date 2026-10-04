package dev.ipf.whitenoise.android.media

import android.accessibilityservice.AccessibilityService
import android.app.UiAutomation
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Process
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.platform.app.InstrumentationRegistry
import dev.ipf.marmotkit.AttachmentAcquisitionModeFfi
import dev.ipf.marmotkit.AttachmentLocalTargetFfi
import dev.ipf.marmotkit.AttachmentTransferStateFfi
import dev.ipf.marmotkit.Marmot
import dev.ipf.marmotkit.MarmotAndroid
import dev.ipf.marmotkit.MarmotOptions
import dev.ipf.marmotkit.MediaAttachmentReferenceFfi
import dev.ipf.marmotkit.RelayPolicyFfi
import dev.ipf.whitenoise.android.BuildConfig
import dev.ipf.whitenoise.android.state.AttachmentTransferRequest
import dev.ipf.whitenoise.android.state.PendingAttachment
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.state.downloadAttachmentPlaintextSource
import dev.ipf.whitenoise.android.state.nativeProgress
import dev.ipf.whitenoise.android.ui.conversation.media.ANDROID_PACKAGE_MIME
import dev.ipf.whitenoise.android.ui.conversation.media.OpenAttachmentResult
import dev.ipf.whitenoise.android.ui.conversation.media.materializeDocumentAttachmentSource
import dev.ipf.whitenoise.android.ui.conversation.media.openAttachmentExternally
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** One genuinely sent file: how it is named and labelled on the wire, and the platform outcome that is expected. */
private class ApkCase(
    val key: String,
    val fileName: String,
    val mediaType: String,
    val bytes: ByteArray,
)

/** A received, verified file on disk, ready for platform dispatch without any further transfer. */
private class ReceivedApk(
    val key: String,
    val fileName: String,
    val file: File,
    val mediaType: String,
)

/** The gaps a host run opts into; every selector is absent by default, so the emulator qualification is unchanged. */
private class ApkOptions(
    val cancelRetry: Boolean,
    val largePayload: String?,
    val noInstaller: Boolean,
)

private const val NO_INSTALLER_MESSAGE = "fixture: no installer available"

/**
 * Lets the real open path meet the exception the platform raises when no activity handles the install intent.
 * Classification, permission state and the FileProvider grant stay genuine, only the final launch is absent.
 */
private class NoInstallerContext(
    base: Context,
) : ContextWrapper(base) {
    /** The platform throws this when no installer activity resolves. */
    override fun startActivity(intent: Intent): Unit = throw ActivityNotFoundException(NO_INSTALLER_MESSAGE)

    /** The options overload is absent for the same reason. */
    override fun startActivity(
        intent: Intent,
        options: Bundle?,
    ): Unit = throw ActivityNotFoundException(NO_INSTALLER_MESSAGE)
}

/**
 * Sends genuine APK-shaped files through the shipping controller, receives and verifies them, then drives each one
 * through the real Android open path under every install-permission state this distribution can reach. It never
 * confirms an installation, so no package is ever installed or replaced.
 */
internal object ApkInstallerAttachmentProbe {
    private const val DEADLINE_MILLIS = 600_000L
    private const val INSTALLER_TIMEOUT_MILLIS = 10_000L
    private const val STAGING_TIMEOUT_MILLIS = 60_000L
    private const val PROGRESS_BAR_CLASS = "android.widget.ProgressBar"
    private const val POLL_MILLIS = 100L
    private const val UNEXPECTED_OBSERVE_MILLIS = 1_000L
    private const val DISMISS_ATTEMPTS = 3
    private const val ZERO_BYTE_PAD = 4096
    private const val MAX_SENDABLE_BYTES = 31 * 1024 * 1024
    private const val MANIFEST = "apk-installer.json"
    private const val PUBLISHED_PREFIX = "document_"
    private const val RECREATE_HOLD = "recreate-hold"
    private const val RECREATE_RESUME = "recreate-resume"
    private const val RECREATE_MANIFEST = "apk-recreate.json"
    private const val RECREATE_MIN_PREFIX_BYTES = 1024L * 1024L
    private const val RECREATE_HOLD_TIMEOUT_MILLIS = 30_000L
    private const val RECREATE_FLUSH_MILLIS = 500L
    private const val RECREATE_RESUME_TIMEOUT_MILLIS = 120_000L
    private const val CIPHERTEXT_TAG_BYTES = 16
    private const val HASH_BUFFER_BYTES = 64 * 1024
    private val STAGES =
        setOf("prepare", "dispatch-denied", "dispatch-allowed", "dispatch-na", RECREATE_HOLD, RECREATE_RESUME)
    private val PRESERVING_STAGES = setOf("prepare", "dispatch-denied", RECREATE_HOLD)
    private val FINAL_STAGES = setOf("dispatch-allowed", "dispatch-na")
    private const val NANOS_PER_MILLI = 1_000_000.0
    private const val LARGE_CASE = "large"
    private const val LARGE_MIN_BYTES = 30 * 1024 * 1024
    private const val PAYLOAD_TIMEOUT_MILLIS = 60_000
    private const val NO_INSTALLER_PERMISSION = "no-installer-simulated"
    private val PAYLOAD_TOKEN = Regex("[a-z0-9-]{1,64}")

    /**
     * Runs one stage against generated peers on a disposable emulator or an explicitly authorized physical device;
     * no personal account or file is read. Changing the install-unknown-apps app-op kills the app process, so the
     * host toggles it between stages and each dispatch stage reopens the restored runtime in a new process.
     */
    suspend fun run() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue(arguments.getString("allowControlledAttachmentProbe") == "true")
        check(context.packageName == "dev.ipf.whitenoise.android.medialatency")
        val stage = requireNotNull(arguments.getString("fixtureApkStage"))
        require(stage in STAGES)
        val options = options(arguments)
        val blobPort = requireNotNull(arguments.getString("fixtureBlobPort")).toInt()
        val relayPort = requireNotNull(arguments.getString("fixtureRelayPort")).toInt()
        require(blobPort in 1024..65535 && relayPort in 1024..65535)
        MarmotAndroid.initialize(context)
        val role = if (stage == "prepare" || stage == RECREATE_HOLD) "prepare" else "read"
        val restartSession = arguments.getString("fixtureRestartSession")
        val root = RestartAttachmentRetentionProbe.createRoot(context, role, restartSession)
        val relays = listOf("ws://127.0.0.1:$relayPort")
        val marmot = openRuntime(root, relays)
        val session = FixtureSession(context, root, marmot, relays, blobPort)
        try {
            withTimeout(DEADLINE_MILLIS) {
                marmot.start()
                when (stage) {
                    "prepare" -> prepare(session, options)
                    RECREATE_HOLD -> recreateHold(session, options)
                    RECREATE_RESUME -> recreateResume(session)
                    else -> dispatchStage(session, stage, options)
                }
            }
        } finally {
            // Every stage but the last keeps the generated runtime for the next separately launched process.
            session.close(preserve = stage in PRESERVING_STAGES)
        }
    }

    /** Reads the closed gap selectors; the no-installer branch exists only where self-update is enabled. */
    private fun options(arguments: Bundle): ApkOptions {
        val payload = arguments.getString("fixtureApkLargePayload")?.also { require(PAYLOAD_TOKEN.matches(it)) }
        val noInstaller = arguments.getString("fixtureApkNoInstaller") == "true"
        require(!noInstaller || BuildConfig.SELF_UPDATE_ENABLED) { "the no-installer branch needs a self-update build" }
        return ApkOptions(arguments.getString("fixtureApkCancelRetry") == "true", payload, noInstaller)
    }

    /** Opens the generated loopback-only runtime used by every fixture probe. */
    private fun openRuntime(
        root: File,
        relays: List<String>,
    ): Marmot =
        Marmot.newWithConfiguration(
            root.absolutePath,
            relays,
            MarmotOptions(
                relayPolicy = RelayPolicyFfi.ALLOW_LOOPBACK_RELAYS_AND_BLOBS,
                attachmentAcquisitionMode = AttachmentAcquisitionModeFfi.HOST_MANAGED,
            ),
        )

    /** Sends and receives every case once, verifies the bytes, then makes acquisition unavailable for later stages. */
    private suspend fun prepare(
        session: FixtureSession,
        options: ApkOptions,
    ) {
        val peers = session.createPeers()
        val manifest = JSONArray()
        for (case in cases(session.blobPort, options.largePayload)) {
            val received = receive(session, peers, case, cancelRetry = options.cancelRetry && case.key == "valid")
            manifest.put(received)
        }
        HeldAttachmentCancellationProbe.control(session.blobPort, "/__acquisition-unavailable")
        val receipt = JSONObject().put("schema", 1).put("prepare_pid", Process.myPid()).put("cases", manifest)
        File(session.root, MANIFEST).writeText(receipt.toString())
        ControlledAttachmentProbe.report(JSONObject().put("phase", "fixture-stage").put("stage", "apk-prepared"))
    }

    /** Reopens the received artifacts without the network and dispatches the cases that belong to this stage. */
    private suspend fun dispatchStage(
        session: FixtureSession,
        stage: String,
        options: ApkOptions,
    ) {
        val receipt = JSONObject(File(session.root, MANIFEST).readText())
        val previousPid = receipt.getInt("prepare_pid")
        assertNotEquals("stage did not cross an Android process boundary", previousPid, Process.myPid())
        val cases = receipt.getJSONArray("cases")
        val byKey = (0 until cases.length()).map { cases.getJSONObject(it) }.associateBy { it.getString("case") }
        session.accounts += byKey.values.map { it.getJSONObject("request").getString("account") }.distinct()

        suspend fun received(key: String) = reopen(session, byKey.getValue(key))
        when (stage) {
            "dispatch-denied" -> dispatch(session.context, received("valid"), "denied")
            "dispatch-allowed" -> {
                dispatch(session.context, received("valid"), "allowed", expectInstaller = true)
                dispatch(session.context, received("generic"), "allowed", expectInstaller = true)
                dispatch(session.context, received("conflict"), "allowed", expectInstaller = false)
                if (options.largePayload != null) {
                    dispatch(session.context, received(LARGE_CASE), "allowed", expectInstaller = true)
                }
                if (options.noInstaller) {
                    dispatch(NoInstallerContext(session.context), received("valid"), NO_INSTALLER_PERMISSION)
                }
                invalidCases(session, ::received, "any")
            }
            else -> {
                dispatch(session.context, received("valid"), "n/a")
                dispatch(session.context, received("generic"), "n/a")
                dispatch(session.context, received("conflict"), "n/a", expectInstaller = false)
                if (options.largePayload != null) dispatch(session.context, received(LARGE_CASE), "n/a")
                invalidCases(session, ::received, "any")
            }
        }
        if (stage in FINAL_STAGES) {
            ControlledAttachmentProbe.report(
                JSONObject()
                    .put("phase", "apk-complete")
                    .put("self_update_enabled", BuildConfig.SELF_UPDATE_ENABLED)
                    .put("cases", byKey.size),
            )
        }
    }

    /** Malformed packages are rejected before any installer launch on every distribution. */
    private suspend fun invalidCases(
        session: FixtureSession,
        received: suspend (String) -> ReceivedApk,
        permission: String,
    ) {
        dispatch(session.context, received("no-manifest"), permission)
        dispatch(session.context, received("truncated"), permission)
    }

    /**
     * A valid signed package (this fixture's own APK), the same bytes mislabelled, malformed lookalikes and, when the
     * host registered one, a host-built signed package of 30 to 31 MiB that still fits the Android sender's cap.
     */
    private suspend fun cases(
        blobPort: Int,
        largePayload: String?,
    ): List<ApkCase> {
        val own =
            File(
                InstrumentationRegistry
                    .getInstrumentation()
                    .context.packageCodePath,
            ).readBytes()
        check(own.size <= MAX_SENDABLE_BYTES) { "fixture APK exceeds the Android sender's file limit" }
        val base =
            listOf(
                ApkCase("valid", "valid.apk", ANDROID_PACKAGE_MIME, own),
                ApkCase("generic", "generic.apk", "application/octet-stream", own),
                ApkCase("conflict", "conflict.apk", "image/png", own),
                ApkCase("no-manifest", "no-manifest.apk", ANDROID_PACKAGE_MIME, zipWithoutManifest()),
                ApkCase("truncated", "truncated.apk", ANDROID_PACKAGE_MIME, own.copyOf(own.size / 2)),
            )
        val large = largePayload?.let { fetchPayload(blobPort, it) } ?: return base
        check(large.size in LARGE_MIN_BYTES..MAX_SENDABLE_BYTES) { "large payload is outside the 30 to 31 MiB range" }
        return base + ApkCase(LARGE_CASE, "large.apk", ANDROID_PACKAGE_MIME, large)
    }

    /** Fetches the host-built payload from the loopback fixture server, outside its counted acquisition ledger. */
    private suspend fun fetchPayload(
        port: Int,
        token: String,
    ): ByteArray =
        withContext(Dispatchers.IO) {
            val connection = URL("http://127.0.0.1:$port/__payload/$token").openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = PAYLOAD_TIMEOUT_MILLIS
                connection.readTimeout = PAYLOAD_TIMEOUT_MILLIS
                check(connection.responseCode == HttpURLConnection.HTTP_OK) { "payload $token is not registered" }
                connection.inputStream.use { it.readBytes() }
            } finally {
                connection.disconnect()
            }
        }

    /** A real ZIP with a dex entry but no AndroidManifest.xml, which must not be treated as an installable package. */
    private fun zipWithoutManifest(): ByteArray =
        ByteArrayOutputStream().use { buffer ->
            ZipOutputStream(buffer).use { zip ->
                zip.putNextEntry(ZipEntry("classes.dex"))
                zip.write(ByteArray(ZERO_BYTE_PAD))
                zip.closeEntry()
            }
            buffer.toByteArray()
        }

    /** One genuine send through the shipping controller and the receiver's projected request for it. */
    private suspend fun send(
        session: FixtureSession,
        peers: FixturePeers,
        case: ApkCase,
    ): Pair<AttachmentTransferRequest, MediaAttachmentReferenceFfi> {
        val sent =
            sendAndroidFixtureMedia(
                session.context,
                session.root,
                session.marmot,
                peers.sender,
                peers.group,
                session.blobPort,
                listOf(PendingAttachment(case.bytes, case.mediaType, case.fileName)),
            )
        session.marmot.catchUpAccounts()
        val request =
            MediaLifecycleAttachmentProbe
                .projectRequests(session.marmot, peers.receiver, peers.group, sent.references)
                .single()
        return request to sent.references.single()
    }

    /**
     * One genuine send, one genuine receiver download and the production verified-file publication. With
     * [cancelRetry] the shared held-body probe first cancels a real held download of this case and admits one
     * deliberate Retry, so the verified file comes from the retried transfer, never from a partial body.
     */
    private suspend fun receive(
        session: FixtureSession,
        peers: FixturePeers,
        case: ApkCase,
        cancelRetry: Boolean,
    ): JSONObject {
        val (request, reference) = send(session, peers, case)
        val state = session.state(peers.receiver.label)
        if (cancelRetry) {
            HeldAttachmentCancellationProbe.run(state, request, reference, session.blobPort, case.bytes)
        }
        var file: File? = null
        ControlledAttachmentProbe.measure("apk-transfer-${case.key}", case.bytes.size) {
            file = materializeVerified(session.context, state, request, reference)
        }
        val verified = requireNotNull(file).readBytes()
        assertArrayEquals("received bytes differ from sent bytes for ${case.key}", case.bytes, verified)
        val received = JSONObject().put("phase", "apk-received").put("case", case.key)
        ControlledAttachmentProbe.report(received.put("bytes", case.bytes.size).put("exact", true))
        return JSONObject()
            .put("case", case.key)
            .put("file", case.fileName)
            .put("mediaType", reference.mediaType)
            .put("request", request.toJson())
    }

    /**
     * Starts a real receiver download of one package, holds its body part-way, records the identity the next process
     * needs and then ends this process abruptly, as the system does when it reclaims memory. Nothing here cancels,
     * pauses or releases the transfer: the process simply stops while bytes are in flight.
     */
    private suspend fun recreateHold(
        session: FixtureSession,
        options: ApkOptions,
    ) = coroutineScope {
        val peers = session.createPeers()
        val case = cases(session.blobPort, options.largePayload).first { it.key == recreationCase(options) }
        val (request, reference) = send(session, peers, case)
        val state = session.state(peers.receiver.label)
        HeldAttachmentCancellationProbe.control(session.blobPort, "/__hold-resumable-acquisition")
        val ciphertextBytes = (case.bytes.size + CIPHERTEXT_TAG_BYTES).toULong()
        val download = async { runCatching { materializeVerified(session.context, state, request, reference) } }
        val progress =
            withTimeout(RECREATE_HOLD_TIMEOUT_MILLIS) {
                state.nativeProgress(request).first {
                    it?.phase == AttachmentTransferStateFfi.DOWNLOADING &&
                        it.total == ciphertextBytes &&
                        it.received >= RECREATE_MIN_PREFIX_BYTES.toULong()
                }
            }
        HeldAttachmentCancellationProbe.awaitLedger(session.blobPort, ::anyHeld)
        check(!download.isCompleted) { "the download finished before the process could be ended" }
        val receipt =
            JSONObject()
                .put("schema", 1)
                .put("hold_pid", Process.myPid())
                .put("case", case.key)
                .put("file", case.fileName)
                .put("mediaType", reference.mediaType)
                .put("bytes", case.bytes.size)
                .put("sha256", sha256Hex(case.bytes))
                .put("request", request.toJson())
        File(session.root, RECREATE_MANIFEST).writeText(receipt.toString())
        ControlledAttachmentProbe.report(
            JSONObject()
                .put("phase", "apk-recreate-held")
                .put("case", case.key)
                .put("received_bytes", requireNotNull(progress).received.toLong())
                .put("total_ciphertext_bytes", ciphertextBytes.toLong()),
        )
        // The status line must reach the host before the process goes, then the process ends with no cleanup at all.
        delay(RECREATE_FLUSH_MILLIS)
        Process.killProcess(Process.myPid())
        delay(Long.MAX_VALUE)
    }

    /** True once the fixture server has recorded a body held part-way. */
    private fun anyHeld(events: List<JSONObject>): Boolean = events.any { it.optString("kind") == "held" }

    /** The case a recreation run drives: the host-built package when one was registered, otherwise the valid one. */
    private fun recreationCase(options: ApkOptions): String = if (options.largePayload != null) LARGE_CASE else "valid"

    /**
     * In a new process, retries the interrupted transfer through the same production path a reader's tap uses, proves
     * the published file is the complete verified package, and only then hands it to the platform installer.
     */
    private suspend fun recreateResume(session: FixtureSession) {
        val receipt = JSONObject(File(session.root, RECREATE_MANIFEST).readText())
        assertNotEquals("stage did not cross an Android process boundary", receipt.getInt("hold_pid"), Process.myPid())
        val request = requestFrom(receipt.getJSONObject("request"))
        session.accounts += request.accountRef
        val reference = MediaLifecycleAttachmentProbe.publishedReference(session.marmot, request)
        val state = session.state(request.accountRef)
        val target =
            AttachmentLocalTargetFfi(
                request.messageIdHex,
                requireNotNull(request.sourceMessageIdHex),
                request.attachmentIndex.toUInt(),
            )
        val before =
            state.marmotIo {
                attachmentTransferSnapshot(request.accountRef, request.groupIdHex, listOf(target)).items.single()
            }
        ControlledAttachmentProbe.report(
            JSONObject()
                .put("phase", "apk-recreate-found")
                .put("native_state", before.state.name)
                .put("attempt", before.attempt.toLong()),
        )

        /** Retries through the production path with a bound, so a transfer that never resumes fails with evidence. */
        suspend fun retry(): File {
            val published =
                withTimeout(RECREATE_RESUME_TIMEOUT_MILLIS) {
                    materializeVerified(session.context, state, request, reference)
                }
            return published
        }
        var file: File? = null
        ControlledAttachmentProbe.measure("apk-transfer-recreated", receipt.getInt("bytes")) { file = retry() }
        val verified = requireNotNull(file)
        val sizeMatches = verified.length() == receipt.getInt("bytes").toLong()
        val exact = sizeMatches && sha256Hex(verified) == receipt.getString("sha256")
        ControlledAttachmentProbe.report(
            JSONObject()
                .put("phase", "apk-received")
                .put("case", receipt.getString("case"))
                .put("bytes", verified.length())
                .put("exact", exact),
        )
        assertTrue("recreated download is not the complete verified package", exact)
        val received =
            ReceivedApk(receipt.getString("case"), receipt.getString("file"), verified, receipt.getString("mediaType"))
        val installs = BuildConfig.SELF_UPDATE_ENABLED
        dispatch(session.context, received, if (installs) "allowed" else "n/a", expectInstaller = installs)
        ControlledAttachmentProbe.report(
            JSONObject().put("phase", "apk-recreate-complete").put("self_update_enabled", installs),
        )
    }

    /** Streams the bytes through SHA-256 so a 31 MiB package never needs a second copy on the Java heap. */
    private fun sha256Hex(file: File): String =
        MessageDigest.getInstance("SHA-256").let { digest ->
            file.inputStream().use { input ->
                val buffer = ByteArray(HASH_BUFFER_BYTES)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                }
            }
            digest.digest().joinToString("") { "%02x".format(it) }
        }

    /** The digest of bytes already in memory, for the sender's own copy. */
    private fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { "%02x".format(it) }
    }

    /** Reopens one received artifact from native retention in a new process; acquisition is unavailable. */
    private suspend fun reopen(
        session: FixtureSession,
        entry: JSONObject,
    ): ReceivedApk {
        val request = requestFrom(entry.getJSONObject("request"))
        val reference = MediaLifecycleAttachmentProbe.publishedReference(session.marmot, request)
        val state = session.state(request.accountRef)
        // The materializer returns a valid published copy before it asks for the source, so remove every copy first.
        removePublishedCopies(session.context)
        var readRetention = false
        val file = materializeVerified(session.context, state, request, reference) { readRetention = true }
        assertTrue("readback was served without reading the retained bytes", readRetention)
        return ReceivedApk(entry.getString("case"), entry.getString("file"), file, entry.getString("mediaType"))
    }

    /** Deletes the published copies, so a readback must republish from native retention, not from the cache. */
    private suspend fun removePublishedCopies(context: Context) =
        withContext(Dispatchers.IO) {
            val shared = File(context.cacheDir, MediaCacheDirs.SHARED)
            shared.listFiles { file -> file.name.startsWith(PUBLISHED_PREFIX) }?.forEach { file ->
                check(file.delete()) { "could not remove a published copy before readback" }
            }
        }

    /**
     * Publishes the completed download exactly as the installer handoff does, never a partial file. [onResolve] runs
     * only when the materializer actually asks for the source, that is, when no valid published copy existed.
     */
    private suspend fun materializeVerified(
        context: Context,
        state: WhiteNoiseAppState,
        request: AttachmentTransferRequest,
        reference: MediaAttachmentReferenceFfi,
        onResolve: () -> Unit = {},
    ): File =
        materializeDocumentAttachmentSource(context, request.messageIdHex, request.attachmentIndex, reference) {
            onResolve()
            state.downloadAttachmentPlaintextSource(request, reference, persistInteractiveIntent = false)
        }

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

    /** One real platform dispatch; reports the result and whether the system installer actually reached the screen. */
    private suspend fun dispatch(
        context: Context,
        received: ReceivedApk,
        permission: String,
        expectInstaller: Boolean = false,
    ) {
        val started = SystemClock.elapsedRealtimeNanos()
        val result =
            openAttachmentExternally(
                context = context,
                source = received.file,
                mediaType = received.mediaType,
                fileName = received.fileName,
            )
        val dispatchMillis = (SystemClock.elapsedRealtimeNanos() - started) / NANOS_PER_MILLI
        // The screen is watched for every result, so an installer behind any status, not only Opened, is seen.
        val installer = awaitInstallerAndDismiss(context, expectInstaller, result == OpenAttachmentResult.Opened)
        ControlledAttachmentProbe.report(
            JSONObject()
                .put("phase", "apk-dispatch")
                .put("case", received.key)
                .put("permission", permission)
                .put("result", result.name)
                .put("installer_shown", installer.shown)
                .put("installer_observed_ms", installer.observedMillis)
                .put("installer_settled", installer.settled)
                .put("installer_progress_seen", installer.progressSeen)
                .put("installer_staging_ms", installer.stagingMillis)
                .put("dispatch_ms", dispatchMillis)
                .put("transfer_reused", received.file.isFile),
        )
    }

    /**
     * Whether the installer reached the screen after one dispatch, for how long the screen was watched, whether the
     * installer had finished staging the package before Back was sent, whether its progress indicator was ever seen,
     * and how long the wait for it lasted. A package whose indicator was never seen was not proven to have finished
     * staging, because an installer that draws it under another class name looks the same as one that never staged.
     */
    private class InstallerObservation(
        val shown: Boolean,
        val observedMillis: Long,
        val settled: Boolean,
        val progressSeen: Boolean,
        val stagingMillis: Long,
    )

    /** The outcome of waiting for staging to finish and whether the progress indicator was seen at any poll. */
    private class StagingWait(
        val settled: Boolean,
        val progressSeen: Boolean,
    )

    /**
     * Watches the screen after any dispatch result. When an installer is expected it waits for it, and otherwise it
     * watches for [UNEXPECTED_OBSERVE_MILLIS] so a launch behind a non-Opened status is still seen. An installer that
     * appeared is given time to finish staging the package before it is dismissed with Back without installing, since
     * Back sent while a large package is still being copied from the file provider does not cancel the staging and the
     * dialog outlives the probe's process. Whatever an Opened dispatch left on screen is dismissed as well.
     */
    private suspend fun awaitInstallerAndDismiss(
        context: Context,
        expectInstaller: Boolean,
        opened: Boolean,
    ): InstallerObservation {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val installers = installerPackages(context)
        val started = SystemClock.elapsedRealtime()
        val shown =
            if (expectInstaller) {
                runCatching {
                    withTimeout(INSTALLER_TIMEOUT_MILLIS) { pollForeground(automation, installers) }
                }.getOrDefault(false)
            } else {
                watchForInstaller(automation, installers)
            }
        val observedMillis = SystemClock.elapsedRealtime() - started
        val stagingStarted = SystemClock.elapsedRealtime()
        val staging =
            if (shown) awaitStagingFinished(automation) else StagingWait(settled = false, progressSeen = false)
        val stagingMillis = if (shown) SystemClock.elapsedRealtime() - stagingStarted else 0L
        if (shown || opened) {
            repeat(DISMISS_ATTEMPTS) {
                automation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
                delay(POLL_MILLIS)
            }
        }
        return InstallerObservation(shown, observedMillis, staging.settled, staging.progressSeen, stagingMillis)
    }

    /** Polls for the whole observation window and reports whether an installer package owned the screen at any poll. */
    private suspend fun watchForInstaller(
        automation: UiAutomation,
        installers: Set<String>,
    ): Boolean {
        val deadline = SystemClock.elapsedRealtime() + UNEXPECTED_OBSERVE_MILLIS
        while (SystemClock.elapsedRealtime() < deadline) {
            if (foregroundPackage(automation) in installers) return true
            delay(POLL_MILLIS)
        }
        return false
    }

    /**
     * Polls until the installer window no longer shows the progress indicator it draws while staging a package, and
     * records whether the indicator was visible at any poll, so a wait that never saw it is told apart from one that
     * watched it disappear.
     */
    private suspend fun awaitStagingFinished(automation: UiAutomation): StagingWait {
        var progressSeen = false
        val settled =
            runCatching {
                withTimeout(STAGING_TIMEOUT_MILLIS) {
                    while (automation.rootInActiveWindow?.let(::showsProgress) == true) {
                        progressSeen = true
                        delay(POLL_MILLIS)
                    }
                    true
                }
            }.getOrDefault(false)
        return StagingWait(settled, progressSeen)
    }

    /** True when this window or any descendant is a progress bar. */
    private fun showsProgress(node: AccessibilityNodeInfo): Boolean =
        node.className?.toString() == PROGRESS_BAR_CLASS ||
            (0 until node.childCount).any { index -> node.getChild(index)?.let(::showsProgress) == true }

    /** Resolves the package that handles APK installation on this device instead of assuming a vendor package. */
    private fun installerPackages(context: Context): Set<String> {
        val intent =
            Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse("content://fixture/apk"), ANDROID_PACKAGE_MIME)
        return context.packageManager
            .queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
            .map { it.activityInfo.packageName }
            .toSet()
    }

    /** Polls the active window until an installer package owns it. */
    private suspend fun pollForeground(
        automation: UiAutomation,
        installers: Set<String>,
    ): Boolean {
        while (foregroundPackage(automation) !in installers) delay(POLL_MILLIS)
        return true
    }

    /** The package that owns the active accessibility window, or null when none is exposed. */
    private fun foregroundPackage(automation: UiAutomation): String? {
        val root = automation.rootInActiveWindow
        return root?.packageName?.toString()
    }
}
