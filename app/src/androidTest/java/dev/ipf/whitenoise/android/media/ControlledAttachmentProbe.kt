package dev.ipf.whitenoise.android.media

import android.content.Context
import android.os.Bundle
import android.os.Debug
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import dev.ipf.marmotkit.AccountSummaryFfi
import dev.ipf.marmotkit.AttachmentAcquisitionModeFfi
import dev.ipf.marmotkit.AttachmentLocalTargetFfi
import dev.ipf.marmotkit.AttachmentPageReadFfi
import dev.ipf.marmotkit.Marmot
import dev.ipf.marmotkit.MarmotAndroid
import dev.ipf.marmotkit.MarmotOptions
import dev.ipf.marmotkit.MediaAttachmentOutcomeFfi
import dev.ipf.marmotkit.MediaAttachmentReferenceFfi
import dev.ipf.marmotkit.MediaUploadAttachmentRequestFfi
import dev.ipf.marmotkit.MediaUploadRequestFfi
import dev.ipf.marmotkit.RelayPolicyFfi
import dev.ipf.whitenoise.android.state.AppMarmotRuntime
import dev.ipf.whitenoise.android.state.AttachmentTransferRequest
import dev.ipf.whitenoise.android.state.DraftPersistence
import dev.ipf.whitenoise.android.state.DraftStore
import dev.ipf.whitenoise.android.state.NativeAttachmentProgress
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.state.cacheKey
import dev.ipf.whitenoise.android.state.downloadAttachmentPlaintextSource
import dev.ipf.whitenoise.android.state.enforceAppOwnedAttachmentAcquisitionPolicy
import dev.ipf.whitenoise.android.state.openNativeAttachment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicLong

/** Runs only generated peers through the unchanged Android resolver and the packaged native runtime. */
internal object ControlledAttachmentProbe {
    private const val PHASE_PAYLOAD_BYTES = 32 * 1024 * 1024
    private const val FAILING_PAYLOAD_BYTES = 1024 * 1024
    private const val FAILING_PATTERN = 241
    private const val FAILING_FILE_NAME = "failure.txt"
    private const val PHASES_CASE = "native-phases"

    /** Explicit large-read comparisons leave the normal small-file baseline unchanged. */
    @Suppress("LongMethod") // One guarded sequence keeps disposable peers and measured source identity together.
    suspend fun run(
        onUnknownLengthProgress: ((NativeAttachmentProgress, MediaAttachmentReferenceFfi) -> Unit)? = null,
    ) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue(arguments.getString("allowControlledAttachmentProbe") == "true")
        check(context.packageName == "dev.ipf.whitenoise.android.medialatency")
        val options = fixtureOptions(arguments)
        val androidSendController = options.androidSendController
        val compareLargeReads = options.compareLargeReads
        val bodyCase = options.bodyCase
        val payloadBytes = options.payloadBytes
        val blobPort = options.blobPort
        val relayPort = options.relayPort
        MarmotAndroid.initialize(context)
        val restartRole = arguments.getString("fixtureRestartRole")
        val session = arguments.getString("fixtureRestartSession")
        val root = RestartAttachmentRetentionProbe.createRoot(context, restartRole, session)
        var preserveRestartFixture = false
        val relays = listOf("ws://127.0.0.1:$relayPort")
        var marmot =
            Marmot.newWithConfiguration(
                root.absolutePath,
                relays,
                MarmotOptions(
                    relayPolicy = RelayPolicyFfi.ALLOW_LOOPBACK_RELAYS_AND_BLOBS,
                    attachmentAcquisitionMode = AttachmentAcquisitionModeFfi.HOST_MANAGED,
                ),
            )
        val accounts = mutableListOf<String>()
        var fixtureState: WhiteNoiseAppState? = null
        try {
            withTimeout(options.deadlineMillis) {
                marmot.start()
                if (restartRole == "read") {
                    RestartAttachmentRetentionProbe.read(context, root, marmot, accounts)
                    return@withTimeout
                }
                val receiver = marmot.createIdentity(relays, relays)
                accounts += receiver.label
                val sender = marmot.createIdentity(relays, relays)
                accounts += sender.label
                marmot.enforceAppOwnedAttachmentAcquisitionPolicy(listOf(receiver.label, sender.label))
                val group = marmot.createGroup(sender.label, "Generated fixture", listOf(receiver.accountIdHex), null)
                awaitReceivedGroup(marmot, receiver.label, group)
                val bytes = ByteArray(payloadBytes) { (it % 251).toByte() }
                val reference =
                    if (androidSendController) {
                        sendAndroidFixtureAttachment(
                            context,
                            root,
                            marmot,
                            sender,
                            group,
                            blobPort,
                            bytes,
                            qualifyOwnLocalCache = compareLargeReads,
                        )
                    } else {
                        val uploaded =
                            marmot.uploadMedia(
                                sender.label,
                                group,
                                MediaUploadRequestFfi(
                                    attachments =
                                        listOf(
                                            MediaUploadAttachmentRequestFfi(
                                                "fixture.txt",
                                                "text/plain",
                                                bytes,
                                                null,
                                                null,
                                            ),
                                        ),
                                    caption = null,
                                    send = true,
                                    blossomServer = "http://127.0.0.1:$blobPort",
                                ),
                            )
                        check(requireNotNull(uploaded.sent).messageIds.size == 1)
                        uploaded.attachments.single().reference
                    }
                val failing =
                    sendFailingFixture(bodyCase == PHASES_CASE, context, root, marmot, sender, group, blobPort)
                report(JSONObject().put("phase", "fixture-stage").put("stage", "upload-published"))
                marmot.catchUpAccounts()
                val request = projectedRequest(marmot, receiver.label, group, reference)
                // History lookup is native authority; no retained row or attachment state is seeded.
                val runtime = AppMarmotRuntime(root.absolutePath, marmot)
                val state =
                    withContext(Dispatchers.Main.immediate) {
                        WhiteNoiseAppState(
                            context = context,
                            draftStore = DraftStore(DiscardedDrafts),
                            accountIdHexResolver = { account ->
                                automaticFixtureAccountId(bodyCase, account, receiver.label, receiver.accountIdHex)
                            },
                            accounts = emptyList(),
                            activeAccountRef = receiver.label,
                            initialMarmotRuntime = runtime,
                            marmotRuntimeFactory = { runtime },
                        )
                    }
                fixtureState = state
                report(JSONObject().put("phase", "fixture-stage").put("stage", "received-projected"))
                if (bodyCase != null) {
                    val inputs =
                        BodyCaseInputs(bodyCase, onUnknownLengthProgress, failing) {
                            projectedRequest(marmot, receiver.label, group, it)
                        }
                    runBodyCase(state, request, reference, blobPort, bytes, inputs)
                    return@withTimeout
                }
                if (arguments.getString("fixtureCancellation") == "true") {
                    measure("held-body-cancellation-overall") {
                        HeldAttachmentCancellationProbe.run(state, request, reference, blobPort, bytes)
                    }
                    return@withTimeout
                }
                measure("received-cold", payloadBytes) {
                    state
                        .downloadAttachmentPlaintextSource(request, reference, persistInteractiveIntent = false)
                        .use {
                            assertArrayEquals(bytes, it.toByteArray())
                        }
                }
                denyAcquisition(blobPort)
                if (compareLargeReads) {
                    LargeAttachmentLocalReadComparison.run(state, request, bytes)
                    return@withTimeout
                }
                repeat(10) {
                    assertNoAndroidCache(state, request)
                    measure("received-retained") {
                        state
                            .downloadAttachmentPlaintextSource(request, reference, persistInteractiveIntent = false)
                            .use {
                                assertArrayEquals(bytes, it.toByteArray())
                            }
                    }
                }
                val sentSource = projectedRequest(marmot, sender.label, group, reference)
                val outgoing =
                    marmot
                        .attachmentLocalAssets(
                            sender.label,
                            group,
                            listOf(
                                AttachmentLocalTargetFfi(
                                    sentSource.messageIdHex,
                                    requireNotNull(sentSource.sourceMessageIdHex),
                                    0u,
                                ),
                            ),
                        ).single()
                assertTrue("genuine native send did not retain its source", outgoing.reference != null)
                state.openNativeAttachment(sentSource).use { local ->
                    assertArrayEquals(bytes, requireNotNull(local).toByteArray())
                }
                if (restartRole == "prepare") {
                    RestartAttachmentRetentionProbe.prepare(root, request, sentSource, androidSendController)
                    preserveRestartFixture = true
                    return@withTimeout
                }
                state.mutationsScope.cancel()
                marmot.shutdownAndClose()
                marmot =
                    Marmot.newWithConfiguration(
                        root.absolutePath,
                        relays,
                        MarmotOptions(
                            relayPolicy = RelayPolicyFfi.ALLOW_LOOPBACK_RELAYS_AND_BLOBS,
                            attachmentAcquisitionMode = AttachmentAcquisitionModeFfi.HOST_MANAGED,
                        ),
                    )
                marmot.start()
                val reopenedState =
                    withContext(Dispatchers.Main.immediate) {
                        WhiteNoiseAppState(
                            context = context,
                            draftStore = DraftStore(DiscardedDrafts),
                            accountIdHexResolver = { null },
                            accounts = emptyList(),
                            activeAccountRef = receiver.label,
                            initialMarmotRuntime = AppMarmotRuntime(root.absolutePath, marmot),
                        )
                    }
                fixtureState = reopenedState
                assertNoAndroidCache(reopenedState, request)
                measure("received-native-reopen-unavailable-endpoint") {
                    reopenedState
                        .downloadAttachmentPlaintextSource(request, reference, persistInteractiveIntent = false)
                        .use {
                            assertArrayEquals(bytes, it.toByteArray())
                        }
                }
                reopenedState.openNativeAttachment(sentSource).use { local ->
                    assertArrayEquals(bytes, requireNotNull(local).toByteArray())
                }
                report(
                    JSONObject()
                        .put("phase", "genuine-native-send-retention")
                        .put("available", true)
                        .put("exact_native_lease_bytes", true)
                        .put("native_runtime_reopen_exact_bytes", true)
                        .put("android_send_controller_qualified", androidSendController)
                        .put("process_restart_offline_qualified", false),
                )
            }
        } finally {
            fixtureState?.mutationsScope?.cancel()
            val cleanup =
                if (preserveRestartFixture) {
                    emptyList()
                } else {
                    accounts.map { runCatching { withTimeout(5_000L) { marmot.removeAccount(it) } } }
                }
            try {
                marmot.shutdownAndClose()
            } finally {
                if (!preserveRestartFixture) root.deleteRecursively()
            }
            check(cleanup.all { it.isSuccess }) { "fixture account cleanup failed" }
        }
    }

    /** Only the automatic scheduler case resolves its generated account for real per-account policy persistence. */
    private fun automaticFixtureAccountId(
        scenario: String?,
        requested: String,
        receiver: String,
        accountId: String,
    ): String? = if (scenario == "automatic-platform-resume" && requested == receiver) accountId else null

    /** Everything a selected body scenario needs beyond the primary attachment. */
    private class BodyCaseInputs(
        val scenario: String,
        val onUnknownLengthProgress: ((NativeAttachmentProgress, MediaAttachmentReferenceFfi) -> Unit)?,
        val failing: FailingFixture?,
        val project: suspend (MediaAttachmentReferenceFfi) -> AttachmentTransferRequest,
    )

    /** A second, small generated file with its own canonical identity for the permanent-miss scenario. */
    private class FailingFixture(
        val bytes: ByteArray,
        val reference: MediaAttachmentReferenceFfi,
    )

    /** Sends the independent failure fixture only for the native-phase scenario; other modes send nothing extra. */
    @Suppress("LongParameterList") // Mirrors the genuine sender's explicit generated-session inputs.
    private suspend fun sendFailingFixture(
        enabled: Boolean,
        context: Context,
        root: File,
        marmot: Marmot,
        sender: AccountSummaryFfi,
        group: String,
        blobPort: Int,
    ): FailingFixture? {
        if (!enabled) return null
        val bytes = ByteArray(FAILING_PAYLOAD_BYTES) { (it % FAILING_PATTERN).toByte() }
        val reference =
            sendAndroidFixtureAttachment(
                context,
                root,
                marmot,
                sender,
                group,
                blobPort,
                bytes,
                fileName = FAILING_FILE_NAME,
            )
        return FailingFixture(bytes, reference)
    }

    /** Measures only the selected native body scenario, leaving baseline and large-read paths unchanged. */
    private suspend fun runBodyCase(
        state: WhiteNoiseAppState,
        request: AttachmentTransferRequest,
        reference: MediaAttachmentReferenceFfi,
        port: Int,
        bytes: ByteArray,
        inputs: BodyCaseInputs,
    ) {
        val scenario = inputs.scenario
        if (scenario == "automatic-platform-resume") {
            measure("automatic-platform-resume-overall", bytes.size) {
                PlatformInterruptedAttachmentProbe.run(state, request, port, bytes)
            }
        } else if (scenario in setOf("platform-background", "platform-lock")) {
            measure("platform-background-overall", bytes.size) {
                PlatformBackgroundAttachmentProbe.run(
                    state,
                    request,
                    reference,
                    port,
                    bytes,
                    lockScreen = scenario == "platform-lock",
                )
            }
        } else if (scenario == "unknown-length") {
            measure("unknown-length-overall", bytes.size) {
                UnknownLengthAttachmentProbe.run(state, request, reference, port, bytes, inputs.onUnknownLengthProgress)
            }
        } else if (scenario == PHASES_CASE) {
            measure("native-phases-overall", bytes.size) {
                val failing = requireNotNull(inputs.failing)
                NativePhaseAttachmentProbe.run(
                    state,
                    FixtureAttachment(request, reference, bytes),
                    FixtureAttachment(inputs.project(failing.reference), failing.reference, failing.bytes),
                    port,
                )
            }
        } else {
            measure("transport-resume-overall", bytes.size) {
                TransportResumeAttachmentProbe.run(state, request, reference, port, bytes, scenario)
            }
        }
    }

    /** Rejects incompatible probe modes and unsafe ports before any native runtime is opened. */
    private fun fixtureOptions(arguments: Bundle): FixtureOptions {
        val androidSendController = arguments.getString("fixtureUseAndroidSendController") == "true"
        val compareLargeReads = arguments.getString("fixtureCompareLargeLocalReads") == "true"
        val nativePhases = arguments.getString("fixtureNativePhases") == "true"
        val bodyCase = selectBodyCase(arguments, androidSendController, compareLargeReads)
        val payloadBytes =
            when {
                nativePhases -> PHASE_PAYLOAD_BYTES
                bodyCase != null -> 4 * 1024 * 1024
                else -> LargeAttachmentLocalReadComparison.payloadBytes(compareLargeReads)
            }
        val blobPort = requireNotNull(arguments.getString("fixtureBlobPort")).toInt()
        val relayPort = requireNotNull(arguments.getString("fixtureRelayPort")).toInt()
        require(blobPort in 1024..65535 && relayPort in 1024..65535)
        val deadline = LargeAttachmentLocalReadComparison.timeoutMillis(compareLargeReads || nativePhases)
        return FixtureOptions(
            androidSendController,
            compareLargeReads,
            bodyCase,
            payloadBytes,
            blobPort,
            relayPort,
            deadline,
        )
    }

    /** Selects the single received-side body scenario and rejects every combination of two or more modes. */
    private fun selectBodyCase(
        arguments: Bundle,
        androidSendController: Boolean,
        compareLargeReads: Boolean,
    ): String? {
        val resumeCase = arguments.getString("fixtureTransportResume")
        val functionalCase = arguments.getString("fixtureFunctionalBodyCase")
        val unknownLength = arguments.getString("fixtureUnknownLength") == "true"
        val nativePhases = arguments.getString("fixtureNativePhases") == "true"
        val platformCases = setOf("platform-background", "platform-lock", "automatic-platform-resume")
        val selected = listOf(functionalCase != null, resumeCase != null, unknownLength, nativePhases).count { it }
        require(selected <= 1) { "at most one received-side body scenario may be selected" }
        require(functionalCase == null || functionalCase in platformCases)
        val bodyCase =
            when {
                functionalCase != null -> functionalCase
                unknownLength -> "unknown-length"
                nativePhases -> PHASES_CASE
                else -> resumeCase
            }
        val allowedCases = platformCases + setOf("compatible", "changed-validator", "unknown-length", PHASES_CASE)
        require(bodyCase == null || bodyCase in allowedCases)
        require(bodyCase == null || (!compareLargeReads && androidSendController))
        return bodyCase
    }

    private data class FixtureOptions(
        val androidSendController: Boolean,
        val compareLargeReads: Boolean,
        val bodyCase: String?,
        val payloadBytes: Int,
        val blobPort: Int,
        val relayPort: Int,
        val deadlineMillis: Long,
    )

    /** Makes accidental downloads fail visibly while preserving the external attempt ledger. */
    private suspend fun denyAcquisition(port: Int) =
        withContext(Dispatchers.IO) {
            val endpoint = URL("http://127.0.0.1:$port/__acquisition-unavailable")
            val connection = endpoint.openConnection() as HttpURLConnection
            try {
                connection.requestMethod = "POST"
                connection.connectTimeout = 5_000
                connection.readTimeout = 5_000
                check(connection.responseCode == 200)
                connection.inputStream.use { it.readBytes() }
            } finally {
                connection.disconnect()
            }
        }

    /** Forbids warm Android cache substitution for the canonical native retained-read measurement. */
    internal suspend fun assertNoAndroidCache(
        state: WhiteNoiseAppState,
        request: AttachmentTransferRequest,
    ) {
        val key = request.cacheKey()
        withContext(Dispatchers.Main.immediate) { assertNull(state.cachedMediaPlaintext(key)) }
        withContext(Dispatchers.IO) { assertFalse(state.diskMediaCache.containsAfterHydration(key)) }
    }

    /** Waits for the actual welcome; transient worker readiness cannot become fixture state. */
    private suspend fun awaitReceivedGroup(
        marmot: Marmot,
        account: String,
        group: String,
    ) {
        withTimeout(30_000L) {
            while (runCatching { marmot.acceptGroupInvite(account, group) }.isFailure) delay(100L)
        }
    }

    /** Native history supplies each account's canonical identity without borrowing a peer's receipt. */
    private suspend fun projectedRequest(
        marmot: Marmot,
        account: String,
        group: String,
        reference: MediaAttachmentReferenceFfi,
    ): AttachmentTransferRequest =
        withTimeout(30_000L) {
            var request: AttachmentTransferRequest? = null
            while (request == null) {
                val read = marmot.attachmentHistoryPage(account, group, 100u, null)
                if (read is AttachmentPageReadFfi.Page) {
                    val page = read.page
                    try {
                        val entry =
                            page.entries.singleOrNull {
                                val accepted = it.attachment as? MediaAttachmentOutcomeFfi.Accepted
                                accepted?.reference?.ciphertextSha256 == reference.ciphertextSha256
                            }
                        if (entry != null) {
                            request =
                                AttachmentTransferRequest(
                                    account,
                                    group,
                                    entry.messageIdHex,
                                    0,
                                    entry.sourceMessageIdHex,
                                )
                        }
                    } finally {
                        page.nextCursor?.close()
                        page.version.close()
                    }
                }
                if (request == null) delay(100L)
            }
            request
        }

    /** Records absolute Java/native peaks, latency and success without identifiers or exception text. */
    internal suspend fun measure(
        phase: String,
        payloadBytes: Int = 1024,
        block: suspend () -> Unit,
    ) {
        val started = SystemClock.elapsedRealtimeNanos()
        val peakJava = AtomicLong()
        val peakNative = AtomicLong()
        var success = false

        fun sample() {
            val runtime = Runtime.getRuntime()
            peakJava.updateAndGet { maxOf(it, runtime.totalMemory() - runtime.freeMemory()) }
            peakNative.updateAndGet { maxOf(it, Debug.getNativeHeapAllocatedSize()) }
        }
        sample()
        coroutineScope {
            val sampler =
                launch(Dispatchers.Default) {
                    while (true) {
                        sample()
                        delay(10L)
                    }
                }
            try {
                block()
                success = true
            } finally {
                sampler.cancel()
                sampler.join()
                sample()
                report(
                    JSONObject()
                        .put("phase", phase)
                        .put("success", success)
                        .put("payload_bytes", payloadBytes)
                        .put("elapsed_ms", (SystemClock.elapsedRealtimeNanos() - started) / 1_000_000.0)
                        .put("java_peak_bytes", peakJava.get())
                        .put("native_peak_bytes", peakNative.get()),
                )
            }
        }
        assertTrue(success)
    }

    /** Sends closed metrics to the host runner; no synthetic identity or locator is exported. */
    internal fun report(value: JSONObject) {
        InstrumentationRegistry.getInstrumentation().sendStatus(
            0,
            Bundle().apply { putString("controlled_attachment_json", value.toString()) },
        )
    }

    /** Fixture drafts stay in memory and never touch the installed app's draft store. */
    private object DiscardedDrafts : DraftPersistence {
        override fun read(): Map<String, String> = emptyMap()

        override fun write(
            key: String,
            value: String?,
        ) = Unit
    }
}
