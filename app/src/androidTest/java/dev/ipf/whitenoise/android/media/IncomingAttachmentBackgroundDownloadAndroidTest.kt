package dev.ipf.whitenoise.android.media

import android.content.Context
import android.os.Build
import android.os.Process
import androidx.activity.ComponentActivity
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.NetworkType
import androidx.work.WorkInfo
import androidx.work.WorkManager
import dev.ipf.marmotkit.AccountSummaryFfi
import dev.ipf.marmotkit.AttachmentTransferStateFfi
import dev.ipf.marmotkit.Marmot
import dev.ipf.marmotkit.MarmotAndroid
import dev.ipf.marmotkit.MediaAttachmentReferenceFfi
import dev.ipf.whitenoise.android.ManualDeviceFixture
import dev.ipf.whitenoise.android.WhiteNoiseApplication
import dev.ipf.whitenoise.android.media.BackgroundReceiptFixtureSupport.DiscardedDrafts
import dev.ipf.whitenoise.android.media.BackgroundReceiptFixtureSupport.assertDurableOwners
import dev.ipf.whitenoise.android.media.BackgroundReceiptFixtureSupport.awaitLedger
import dev.ipf.whitenoise.android.media.BackgroundReceiptFixtureSupport.awaitValidatedInternet
import dev.ipf.whitenoise.android.media.BackgroundReceiptFixtureSupport.nativeState
import dev.ipf.whitenoise.android.media.BackgroundReceiptFixtureSupport.permissions
import dev.ipf.whitenoise.android.media.BackgroundReceiptFixtureSupport.receipt
import dev.ipf.whitenoise.android.media.BackgroundReceiptFixtureSupport.reportStall
import dev.ipf.whitenoise.android.media.BackgroundReceiptFixtureSupport.requestFrom
import dev.ipf.whitenoise.android.media.BackgroundReceiptFixtureSupport.requestJson
import dev.ipf.whitenoise.android.media.BackgroundReceiptFixtureSupport.visibleActivities
import dev.ipf.whitenoise.android.state.AppMarmotRuntime
import dev.ipf.whitenoise.android.state.AttachmentDownloadPriority
import dev.ipf.whitenoise.android.state.AttachmentDownloadWorker
import dev.ipf.whitenoise.android.state.AttachmentTransferRequest
import dev.ipf.whitenoise.android.state.AutomaticAttachmentHold
import dev.ipf.whitenoise.android.state.DraftStore
import dev.ipf.whitenoise.android.state.MediaAutoDownloadNetwork
import dev.ipf.whitenoise.android.state.MediaAutoDownloadType
import dev.ipf.whitenoise.android.state.PendingAttachment
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.state.attachmentDownloadWorkName
import dev.ipf.whitenoise.android.state.attachmentIntentStore
import dev.ipf.whitenoise.android.state.downloadAttachmentPlaintextSource
import dev.ipf.whitenoise.android.state.enforceAppOwnedAttachmentAcquisitionPolicy
import dev.ipf.whitenoise.android.state.hasNativeAttachment
import dev.ipf.whitenoise.android.state.openNativeAttachment
import dev.ipf.whitenoise.android.ui.conversation.media.TileFixtureSupport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.random.Random

private const val IMAGE_SEED = 33L
private const val AUDIO_BYTES = 300 * 1024
private const val FILE_BYTES = 768 * 1024
private const val HELD_FILE_BYTES = 4 * 1024 * 1024
private const val NETWORK_VIDEO_BYTES = 1024 * 1024
private const val READY_TIMEOUT_MILLIS = 90_000L
private const val RESUME_TIMEOUT_MILLIS = 150_000L
private const val NETWORK_READY_TIMEOUT_MILLIS = 240_000L
private const val SETTLE_MILLIS = 5_000L
private const val ACTIVITY_STOP_TIMEOUT_MILLIS = 15_000L
private const val FLUSH_MILLIS = 500L

/** Closed fixture boundaries only; progress is diagnostic, never qualification evidence. */
private enum class ReceiptBoundary {
    BACKGROUND_SEND,
    BACKGROUND_SENT,
    BACKGROUND_PROJECTED,
    BACKGROUND_READY,
    RETURN_LOCAL_COMPLETE,
    DISABLED_COMPLETE,
    NETWORK_POLICY_START,
    WIFI_COMMAND_START,
    WIFI_COMMAND_COMPLETE,
    WIFI_SNAPSHOT_WAIT_START,
    WIFI_SNAPSHOT_CONFIRMED,
    WIFI_VALIDATION_START,
    WIFI_VALIDATION_COMPLETE,
    POLICY_UPDATE_START,
    POLICY_UPDATE_COMPLETE,
    NETWORK_POLICY_APPLIED,
    WIFI_DISABLE_START,
    WIFI_DISABLED,
    NETWORK_SEND,
    NETWORK_PROJECTED,
    WIFI_RESTORE_START,
    WIFI_RESTORED,
    NETWORK_COMPLETE,
    STOP_WORK_START,
}

private const val STAGE_MAIN = "main"
private const val STAGE_RESUME = "resume"
private const val MANIFEST = "background-receipt-recreate.json"

// Ledger markers bound each case, so the host checker can tell its uploads and fetches apart.
private const val DISABLED_START_MARKER = 10
private const val DISABLED_END_MARKER = 11
private const val NETWORK_START_MARKER = 20
private const val NETWORK_RESTORED_MARKER = 21

/**
 * Received media downloads in the background under its own type's policy (#3308). A generated sender in the same
 * MarmotKit runtime sends to a receiver whose app state runs the shipping receipt pipeline with its Activity stopped
 * and the conversation never opened. Each case asserts native READY, exact bytes and the durable WorkManager owner
 * before anything returns to the foreground; every fact reported is closed (counts, booleans, times), never an
 * identity, name or byte.
 *
 * The runner launches the test twice on one preserved root. The `main` stage runs the connected, return,
 * policy-disabled and network-held cases, then has Android stop a held download and ends its own process. The
 * `resume` stage, in a new Android process, binds the same account and waits for the same WorkManager work to finish.
 */
@ManualDeviceFixture
@RunWith(AndroidJUnit4::class)
class IncomingAttachmentBackgroundDownloadAndroidTest {
    private lateinit var context: Context
    private lateinit var root: File
    private lateinit var relays: List<String>
    private var blobPort = 0
    private var marmot: Marmot? = null
    private var state: WhiteNoiseAppState? = null
    private var restoreApplication: (suspend () -> Unit)? = null
    private var wifiDisabled = false
    private val accounts = mutableListOf<String>()
    private val requests = mutableListOf<AttachmentTransferRequest>()

    /** Runs the stage the runner selected against the session it preserved between the two processes. */
    @Test
    fun receivedMediaDownloadsInTheBackgroundUnderItsOwnPolicy() =
        runBlocking {
            context = InstrumentationRegistry.getInstrumentation().targetContext
            val arguments = InstrumentationRegistry.getArguments()
            assumeTrue(arguments.getString("allowControlledAttachmentProbe") == "true")
            check(context.packageName == "dev.ipf.whitenoise.android.medialatency")
            check(PlatformBackgroundAttachmentProbe.shell("getprop ro.kernel.qemu").trim() == "1")
            val stage = arguments.getString("fixtureBackgroundStage") ?: STAGE_MAIN
            require(stage in setOf(STAGE_MAIN, STAGE_RESUME))
            val session = requireNotNull(arguments.getString("fixtureRestartSession"))
            blobPort = requireNotNull(arguments.getString("fixtureBlobPort")).toInt()
            relays = listOf("ws://127.0.0.1:${requireNotNull(arguments.getString("fixtureRelayPort")).toInt()}")
            MarmotAndroid.initialize(context)
            if (stage == STAGE_MAIN) runMain(session) else runResume(session)
        }

    /**
     * Runs every in-process case, then hands the stopped download to the next process by ending this one with no
     * cleanup at all, as the system does when it reclaims memory. Any failure before that cleans up normally.
     */
    private suspend fun runMain(session: String) {
        root = RestartAttachmentRetentionProbe.createRoot(context, "prepare", session)
        var handedOff = false
        try {
            val runtime = TileFixtureSupport.openRuntime(root, relays).also { marmot = it }
            runtime.start()
            val (sender, receiver, group) = createPeers(runtime)
            bindReceiver(runtime, receiver)
            awaitValidatedInternet(requireNotNull(state))
            setPolicy(BackgroundReceiptPolicyCase.Initial, receiver, MediaAutoDownloadType.entries.toSet())
            ActivityScenario.launch(ComponentActivity::class.java).use {
                stopActivities()
                val background = backgroundAlbum(sender, receiver, group)
                val returned = returnWithoutFetching(background)
                reportBoundary(ReceiptBoundary.RETURN_LOCAL_COMPLETE)
                stopActivities()
                val disabled = disabledTypeIsNeverFetched(sender, receiver, group)
                reportBoundary(ReceiptBoundary.DISABLED_COMPLETE)
                val network = networkHeldTypeWaitsForWifi(sender, receiver, group)
                reportBoundary(ReceiptBoundary.NETWORK_COMPLETE)
                reportBoundary(ReceiptBoundary.STOP_WORK_START)
                val stopped = stopHeldWorkForTheNextProcess(sender, receiver, group)
                ControlledAttachmentProbe.report(
                    JSONObject()
                        .put("phase", "background-receipt")
                        .put("success", true)
                        .put("android_api", Build.VERSION.SDK_INT)
                        .put("background", background.report)
                        .put("returned", returned)
                        .put("disabled_type", disabled)
                        .put("network_held", network)
                        .put("stopped", stopped),
                )
            }
            ControlledAttachmentProbe.report(JSONObject().put("phase", "background-receipt-held"))
            handedOff = true
        } finally {
            if (!handedOff) cleanUp()
        }
        // The status lines must reach the host before the process goes, then it ends with no cleanup at all.
        delay(FLUSH_MILLIS)
        Process.killProcess(Process.myPid())
        delay(Long.MAX_VALUE)
    }

    /** One generated album and what the background case observed about it. */
    private class Album(
        val requests: List<AttachmentTransferRequest>,
        val references: List<MediaAttachmentReferenceFfi>,
        val bytes: List<ByteArray>,
        val report: JSONObject,
    )

    /** Image, video, audio and file arrive with the Activity stopped and each becomes locally ready on its own. */
    private suspend fun backgroundAlbum(
        sender: AccountSummaryFfi,
        receiver: AccountSummaryFfi,
        group: String,
    ): Album {
        val image = FixtureMediaAssets.jpeg(IMAGE_SEED)
        val video = FixtureMediaAssets.video()
        val audio = Random(2L).nextBytes(AUDIO_BYTES)
        val file = Random(3L).nextBytes(FILE_BYTES)
        val attachments =
            listOf(
                FixtureMediaAssets.attachment(image, "image/jpeg", "receipt.jpg", "1280x960"),
                FixtureMediaAssets.attachment(video, "video/mp4", "receipt.mp4", "320x240"),
                FixtureMediaAssets.attachment(audio, "audio/mp4", "receipt.m4a"),
                FixtureMediaAssets.attachment(file, "application/pdf", "receipt.pdf"),
            )
        HeldAttachmentCancellationProbe.control(blobPort, "/__background-start")
        val started = System.nanoTime()
        reportBoundary(ReceiptBoundary.BACKGROUND_SEND)
        val sent = send(sender, group, attachments)
        reportBoundary(ReceiptBoundary.BACKGROUND_SENT)
        val album = project(receiver, group, sent)
        reportBoundary(ReceiptBoundary.BACKGROUND_PROJECTED)
        awaitReady(receiver, group, album)
        reportBoundary(ReceiptBoundary.BACKGROUND_READY)
        val readyMillis = (System.nanoTime() - started) / 1_000_000
        val visible = visibleActivities()
        assertEquals("an Activity returned to the foreground", 0, visible)
        assertExactBytes(album, attachments.map { it.plaintextBytes })
        assertDurableOwners(context, album.requests)
        HeldAttachmentCancellationProbe.control(blobPort, "/__background-end")
        val gets = HeldAttachmentCancellationProbe.ledger(blobPort).count { it.getString("kind") == "get" }
        return Album(
            album.requests,
            album.references,
            attachments.map { it.plaintextBytes },
            JSONObject()
                .put("attachments", album.requests.size)
                .put("types", "image,video,audio,file")
                .put("activity_stopped", true)
                .put("visible_activities", visible)
                .put("durable_work_succeeded", album.requests.size)
                .put("interactive_intent", false)
                .put("plaintext_exact", true)
                .put("ready_ms", readyMillis)
                .put("http_gets", gets),
        )
    }

    /**
     * Opening the app reads every received attachment through the bubbles' automatic path with no new fetch. A
     * stopped scenario cannot be resumed from behind the launcher, so a fresh Activity stands in for the return.
     */
    private suspend fun returnWithoutFetching(album: Album): JSONObject {
        val before = HeldAttachmentCancellationProbe.ledger(blobPort).count { it.getString("kind") == "get" }
        val receiverState = requireNotNull(state)
        ActivityScenario.launch(ComponentActivity::class.java).use { returned ->
            assertEquals(Lifecycle.State.RESUMED, returned.state)
            withContext(Dispatchers.Main.immediate) { receiverState.setAppInForeground(true) }
            album.requests.forEachIndexed { index, request ->
                receiverState
                    .downloadAttachmentPlaintextSource(
                        request,
                        album.references[index],
                        AttachmentDownloadPriority.Automatic,
                        persistInteractiveIntent = false,
                    ).use { assertArrayEquals(album.bytes[index], it.toByteArray()) }
            }
        }
        val after = HeldAttachmentCancellationProbe.ledger(blobPort).count { it.getString("kind") == "get" }
        assertEquals("returning to the app fetched again", before, after)
        return JSONObject().put("local_reads", album.requests.size).put("new_http_gets", after - before)
    }

    /** With video off, the album's image downloads and its video is neither queued nor fetched. */
    private suspend fun disabledTypeIsNeverFetched(
        sender: AccountSummaryFfi,
        receiver: AccountSummaryFfi,
        group: String,
    ): JSONObject {
        setPolicy(
            BackgroundReceiptPolicyCase.DisabledType,
            receiver,
            MediaAutoDownloadType.entries.toSet() - MediaAutoDownloadType.Video,
        )
        HeldAttachmentCancellationProbe.control(blobPort, "/__marker/$DISABLED_START_MARKER")
        val before = HeldAttachmentCancellationProbe.ledger(blobPort).count { it.getString("kind") == "get" }
        val image = FixtureMediaAssets.jpeg(IMAGE_SEED + 1)
        val video = FixtureMediaAssets.paddedVideo(FixtureMediaAssets.video(), 2 * 1024 * 1024, 5L)
        val attachments =
            listOf(
                FixtureMediaAssets.attachment(image, "image/jpeg", "disabled.jpg", "1280x960"),
                FixtureMediaAssets.attachment(video, "video/mp4", "disabled.mp4", "320x240"),
            )
        val album = project(receiver, group, send(sender, group, attachments))
        val imageRequest = album.requests[0]
        val videoRequest = album.requests[1]
        withTimeout(READY_TIMEOUT_MILLIS) {
            while (!requireNotNull(state).hasNativeAttachment(imageRequest)) delay(50L)
        }
        delay(SETTLE_MILLIS)
        val manager = WorkManager.getInstance(context)
        assertTrue(
            "a disabled type was queued",
            manager.getWorkInfosForUniqueWork(attachmentDownloadWorkName(videoRequest)).get().isEmpty(),
        )
        assertFalse("a disabled type became local", requireNotNull(state).hasNativeAttachment(videoRequest))
        val videoState = nativeState(requireNotNull(marmot), receiver, group, videoRequest)
        val undemanded = setOf(AttachmentTransferStateFfi.NOT_REQUESTED, AttachmentTransferStateFfi.POLICY_BLOCKED)
        assertTrue("a disabled type was demanded: $videoState", videoState in undemanded)
        val after = HeldAttachmentCancellationProbe.ledger(blobPort).count { it.getString("kind") == "get" }
        assertEquals("only the enabled image was fetched", 1, after - before)
        HeldAttachmentCancellationProbe.control(blobPort, "/__marker/$DISABLED_END_MARKER")
        requests += album.requests
        setPolicy(BackgroundReceiptPolicyCase.DisabledType, receiver, MediaAutoDownloadType.entries.toSet())
        return JSONObject()
            .put("enabled_fetched", 1)
            .put("disabled_queued", false)
            .put("disabled_fetched", false)
            .put("disabled_native_state", videoState.name.lowercase())
    }

    /**
     * With Wi-Fi off, a received video whose policy allows only Wi-Fi is queued but not fetched: its work waits for an
     * unmetered network and the worker's live recheck holds it for the network. Once Wi-Fi returns, the same work
     * downloads it. The host checker confirms from the ledger that nothing was fetched before Wi-Fi returned.
     */
    private suspend fun networkHeldTypeWaitsForWifi(
        sender: AccountSummaryFfi,
        receiver: AccountSummaryFfi,
        group: String,
    ): JSONObject {
        val receiverState = requireNotNull(state)
        reportBoundary(ReceiptBoundary.NETWORK_POLICY_START)
        setWifi(receiver, enabled = true)
        setPolicy(
            BackgroundReceiptPolicyCase.NetworkHeld,
            receiver,
            MediaAutoDownloadType.entries.toSet(),
            wifiOnly = setOf(MediaAutoDownloadType.Video),
        )
        reportBoundary(ReceiptBoundary.NETWORK_POLICY_APPLIED)
        reportBoundary(ReceiptBoundary.WIFI_DISABLE_START)
        setWifi(receiver, enabled = false)
        reportBoundary(ReceiptBoundary.WIFI_DISABLED)
        val video = FixtureMediaAssets.paddedVideo(FixtureMediaAssets.video(), NETWORK_VIDEO_BYTES, 7L)
        val attachment = FixtureMediaAssets.attachment(video, "video/mp4", "network.mp4", "320x240")
        HeldAttachmentCancellationProbe.control(blobPort, "/__marker/$NETWORK_START_MARKER")
        reportBoundary(ReceiptBoundary.NETWORK_SEND)
        val request = project(receiver, group, send(sender, group, listOf(attachment))).requests.single()
        reportBoundary(ReceiptBoundary.NETWORK_PROJECTED)
        requests += request
        val manager = WorkManager.getInstance(context)
        val name = attachmentDownloadWorkName(request)
        withTimeout(READY_TIMEOUT_MILLIS) {
            while (manager.getWorkInfosForUniqueWork(name).get().none { !it.state.isFinished }) delay(50L)
        }
        delay(SETTLE_MILLIS)
        val waiting = manager.getWorkInfosForUniqueWork(name).get().single { !it.state.isFinished }
        assertEquals(NetworkType.UNMETERED, waiting.constraints.requiredNetworkType)
        assertFalse("a network-held type became local", receiverState.hasNativeAttachment(request))
        val hold = receiverState.automaticAttachmentPolicy(receiver.label).hold(MediaAutoDownloadType.Video)
        assertEquals(AutomaticAttachmentHold.Network, hold)
        HeldAttachmentCancellationProbe.control(blobPort, "/__marker/$NETWORK_RESTORED_MARKER")
        reportBoundary(ReceiptBoundary.WIFI_RESTORE_START)
        setWifi(receiver, enabled = true)
        reportBoundary(ReceiptBoundary.WIFI_RESTORED)
        withTimeout(NETWORK_READY_TIMEOUT_MILLIS) {
            while (manager.getWorkInfosForUniqueWork(name).get().none { it.state == WorkInfo.State.SUCCEEDED }) {
                delay(50L)
            }
        }
        receiverState.openNativeAttachment(request).use { local ->
            assertArrayEquals(attachment.plaintextBytes, requireNotNull(local).toByteArray())
        }
        val visible = visibleActivities()
        assertEquals("an Activity returned to the foreground", 0, visible)
        setPolicy(BackgroundReceiptPolicyCase.NetworkHeld, receiver, MediaAutoDownloadType.entries.toSet())
        return JSONObject()
            .put("queued_without_wifi", true)
            .put("constraint", "unmetered")
            .put("hold_reason", AutomaticAttachmentHold.Network.reason)
            .put("work_state_while_held", waiting.state.name.lowercase())
            .put("same_work_succeeded", true)
            .put("plaintext_exact", true)
            .put("visible_activities", visible)
    }

    /**
     * Android stops the receipt-scheduled file's job mid-body and WorkManager holds it in backoff. The identities the
     * next process needs are written to the preserved root; the caller then ends this process. The file arrives while
     * files are off, so its body can be held before the receipt path schedules it again through the shipping handler.
     */
    @Suppress("LongMethod") // One lifetime owns the stopped job and the evidence the next process reads.
    private suspend fun stopHeldWorkForTheNextProcess(
        sender: AccountSummaryFfi,
        receiver: AccountSummaryFfi,
        group: String,
    ): JSONObject {
        setPolicy(
            BackgroundReceiptPolicyCase.ProcessStop,
            receiver,
            MediaAutoDownloadType.entries.toSet() - MediaAutoDownloadType.Document,
        )
        val bytes = heldFileBytes()
        val attachment = FixtureMediaAssets.attachment(bytes, "application/pdf", "held.pdf")
        val sent = send(sender, group, listOf(attachment))
        val request = project(receiver, group, sent).requests.single()
        requests += request
        HeldAttachmentCancellationProbe.control(blobPort, "/__hold-resumable-acquisition")
        setPolicy(BackgroundReceiptPolicyCase.ProcessStop, receiver, MediaAutoDownloadType.entries.toSet())
        val receiverState = requireNotNull(state)
        val diagnostics = backgroundReceiptProcessStopDiagnostics(receiverState, receiver.label)
        diagnostics.trace(BackgroundReceiptProcessStopStage.ReceiptProcessing) {
            receiverState.processNotificationUpdateForTest(receipt(receiver, group, request))
        }
        val manager = WorkManager.getInstance(context)
        val name = attachmentDownloadWorkName(request)
        val running = diagnostics.awaitRunningWork(manager, name)
        diagnostics.trace(BackgroundReceiptProcessStopStage.HeldLedger) {
            awaitLedger(blobPort, diagnostics::observeLedger) { events ->
                events.any { it.getString("kind") == "held" }
            }
        }
        val job = PlatformInterruptedAttachmentProbe.findScheduledWork(context, running.id.toString())
        HeldAttachmentCancellationProbe.control(blobPort, "/__platform-stop-marker")
        val namespace = if (job.first == null) "" else "-n ${job.first} "
        val command = "cmd jobscheduler timeout -u 0 ${namespace}${context.packageName} ${job.second}"
        val stopped = PlatformBackgroundAttachmentProbe.shell(command)
        check(stopped.contains("Timing out:") || stopped.contains("Stopping job:")) { "Android did not stop the job" }
        withTimeout(10_000L) {
            while (manager.getWorkInfosForUniqueWork(name).get().none { it.state == WorkInfo.State.ENQUEUED }) {
                delay(25L)
            }
        }
        val manifest =
            JSONObject()
                .put("schema", 1)
                .put("hold_pid", Process.myPid())
                .put("stopped_at_ms", System.currentTimeMillis())
                .put("receiver", receiver.label)
                .put("request", requestJson(request))
                .put("accounts", JSONArray(accounts))
                .put("requests", JSONArray(requests.map(::requestJson)))
        File(root, MANIFEST).writeText(manifest.toString())
        return JSONObject()
            .put("actual_platform_stop", true)
            .put("held_before_stop", true)
            .put("work_enqueued_after_stop", true)
    }

    /**
     * In a new Android process, binds the preserved account before the stopped work's backoff ends and waits for that
     * same WorkManager work to finish with exact bytes. The application's own state must not have started, and the
     * work must still be waiting, or the work ran without the fixture's account and the run proves nothing.
     */
    private suspend fun runResume(session: String) {
        root = RestartAttachmentRetentionProbe.createRoot(context, "read", session)
        val manifest = JSONObject(File(root, MANIFEST).readText())
        val accountList = manifest.getJSONArray("accounts")
        accounts += List(accountList.length()) { accountList.getString(it) }
        val requestList = manifest.getJSONArray("requests")
        requests += List(requestList.length()) { requestFrom(requestList.getJSONObject(it)) }
        try {
            val holdPid = manifest.getInt("hold_pid")
            assertNotEquals("stage did not cross an Android process boundary", holdPid, Process.myPid())
            val application = context.applicationContext as WhiteNoiseApplication
            val startedBeforeBind = application.initializedAppState() != null
            check(!startedBeforeBind) { "the application's own state started before the fixture bound its account" }
            val request = requestFrom(manifest.getJSONObject("request"))
            val manager = WorkManager.getInstance(context)
            val name = attachmentDownloadWorkName(request)
            val beforeBind = manager.getWorkInfosForUniqueWork(name).get().map { it.state }
            check(WorkInfo.State.ENQUEUED in beforeBind && beforeBind.none { it == WorkInfo.State.SUCCEEDED }) {
                "the stopped work ran before the fixture bound its account: $beforeBind"
            }
            val runtime = TileFixtureSupport.openRuntime(root, relays).also { marmot = it }
            runtime.start()
            val receiver = runtime.listAccounts().single { it.label == manifest.getString("receiver") }
            bindReceiver(runtime, receiver)
            withContext(Dispatchers.Main.immediate) { requireNotNull(state).setAppInForeground(false) }
            val backoffEnds = manifest.getLong("stopped_at_ms") + AttachmentDownloadWorker.BACKOFF_SECONDS * 1_000L
            val marginMillis = backoffEnds - System.currentTimeMillis()
            awaitResumedWork(receiver, request)
            val visible = visibleActivities()
            assertEquals("an Activity came to the foreground", 0, visible)
            requireNotNull(state).openNativeAttachment(request).use { local ->
                assertArrayEquals(heldFileBytes(), requireNotNull(local).toByteArray())
            }
            assertFalse(attachmentIntentStore(context).isInteractive(request))
            ControlledAttachmentProbe.report(
                JSONObject()
                    .put("phase", "background-receipt-resumed")
                    .put("process_restarted", true)
                    .put("app_state_started_before_bind", false)
                    .put("work_waiting_before_bind", true)
                    .put("same_work_succeeded", true)
                    .put("plaintext_exact", true)
                    .put("interactive_intent", false)
                    .put("visible_activities", visible)
                    .put("backoff_margin_ms", marginMillis),
            )
        } finally {
            cleanUp()
        }
    }

    /** Waits for the stopped owner without changing its deadline, retaining closed state evidence on a timeout. */
    private suspend fun awaitResumedWork(
        receiver: AccountSummaryFfi,
        request: AttachmentTransferRequest,
    ) {
        val manager = WorkManager.getInstance(context)
        val name = attachmentDownloadWorkName(request)
        try {
            withTimeout(RESUME_TIMEOUT_MILLIS) {
                while (manager.getWorkInfosForUniqueWork(name).get().none { it.state == WorkInfo.State.SUCCEEDED }) {
                    delay(50L)
                }
            }
        } catch (stalled: kotlinx.coroutines.TimeoutCancellationException) {
            reportStall(
                context,
                requireNotNull(state),
                requireNotNull(marmot),
                receiver,
                request.groupIdHex,
                listOf(request),
            )
            throw stalled
        }
    }

    /** The held file's generated bytes, reproduced identically in both processes. */
    private fun heldFileBytes(): ByteArray = Random(4L).nextBytes(HELD_FILE_BYTES)

    /** Creates the generated sender and receiver and waits for the receiver's genuine welcome. */
    private suspend fun createPeers(runtime: Marmot): Triple<AccountSummaryFfi, AccountSummaryFfi, String> {
        val sender = runtime.createIdentity(relays, relays).also { accounts += it.label }
        val receiver = runtime.createIdentity(relays, relays).also { accounts += it.label }
        runtime.enforceAppOwnedAttachmentAcquisitionPolicy(listOf(receiver.label, sender.label))
        val group = runtime.createGroup(sender.label, "Generated fixture", listOf(receiver.accountIdHex), null)
        ControlledAttachmentProbe.awaitReceivedGroup(runtime, receiver.label, group)
        return Triple(sender, receiver, group)
    }

    /** Builds the receiver's app state with real network callbacks and binds it as the application's state. */
    private suspend fun bindReceiver(
        runtime: Marmot,
        receiver: AccountSummaryFfi,
    ) {
        val wrapped = AppMarmotRuntime(root.absolutePath, runtime)
        val receiverState =
            withContext(Dispatchers.Main.immediate) {
                WhiteNoiseAppState(
                    context = context,
                    draftStore = DraftStore(DiscardedDrafts),
                    accountIdHexResolver = { if (it == receiver.label) receiver.accountIdHex else null },
                    accounts = listOf(receiver),
                    activeAccountRef = receiver.label,
                    initialMarmotRuntime = wrapped,
                    marmotRuntimeFactory = { wrapped },
                )
            }
        withContext(Dispatchers.IO) {
            // The injected constructor skips process callbacks; register the unchanged real listener explicitly.
            WhiteNoiseAppState::class.java
                .getDeclaredMethod("registerActiveNetworkListener")
                .apply { isAccessible = true }
                .invoke(receiverState)
        }
        state = receiverState
        restoreApplication = PlatformBackgroundAttachmentProbe.bindGeneratedApplication(context, receiverState)
        withContext(Dispatchers.Main.immediate) {
            receiverState.setAppInForeground(true)
            receiverState.bootstrap()
        }
    }

    /**
     * Applies exactly [enabled], on every network except Wi-Fi alone for [wifiOnly], and waits until receipt and
     * native policy both reflect it on the current Wi-Fi connection.
     */
    private suspend fun setPolicy(
        case: BackgroundReceiptPolicyCase,
        receiver: AccountSummaryFfi,
        enabled: Set<MediaAutoDownloadType>,
        wifiOnly: Set<MediaAutoDownloadType> = emptySet(),
    ) {
        val receiverState = requireNotNull(state)
        reportBoundary(ReceiptBoundary.POLICY_UPDATE_START)
        withContext(Dispatchers.Main.immediate) {
            MediaAutoDownloadType.entries.forEach { type ->
                MediaAutoDownloadNetwork.entries.forEach { network ->
                    val on = type in enabled && (type !in wifiOnly || network == MediaAutoDownloadNetwork.WiFi)
                    receiverState.setMediaAutoDownload(type, network, enabled = on)
                }
            }
        }
        reportBoundary(ReceiptBoundary.POLICY_UPDATE_COMPLETE)
        val snapshot = {
            backgroundReceiptPolicySnapshot(
                receiverState.automaticAttachmentPolicy(receiver.label),
                enabled,
                wifiOnly,
                permissions(receiverState).appliedRevision(receiver.label) != null,
            )
        }
        withBackgroundReceiptPolicyDiagnostics(case, BackgroundReceiptPolicyStage.HostPolicy, snapshot) {
            withTimeout(15_000L) {
                while (receiverState.automaticAttachmentPolicy(receiver.label).allowedNow() != enabled) delay(25L)
            }
        }
        withContext(Dispatchers.Main.immediate) { receiverState.refreshNativeAttachmentPermissions() }
        withBackgroundReceiptPolicyDiagnostics(case, BackgroundReceiptPolicyStage.NativeApproval, snapshot) {
            withTimeout(15_000L) { permissions(receiverState).awaitApplied(receiver.label) }
        }
    }

    /** Turns the emulator's Wi-Fi on or off and waits until the receiver's network snapshot follows. */
    private suspend fun setWifi(
        receiver: AccountSummaryFfi,
        enabled: Boolean,
    ) {
        reportBoundary(ReceiptBoundary.WIFI_COMMAND_START)
        BackgroundReceiptFixtureSupport.setWifiEnabled(enabled)
        reportBoundary(ReceiptBoundary.WIFI_COMMAND_COMPLETE)
        wifiDisabled = !enabled
        val receiverState = requireNotNull(state)
        reportBoundary(ReceiptBoundary.WIFI_SNAPSHOT_WAIT_START)
        withBackgroundReceiptPolicyDiagnostics(
            BackgroundReceiptPolicyCase.NetworkHeld,
            BackgroundReceiptPolicyStage.WifiSnapshot,
            { backgroundReceiptNetworkSnapshot(context, receiverState, receiver.label, enabled) },
        ) {
            BackgroundReceiptFixtureSupport.awaitWifiSnapshot(receiverState, receiver.label, enabled)
        }
        reportBoundary(ReceiptBoundary.WIFI_SNAPSHOT_CONFIRMED)
        if (enabled) {
            reportBoundary(ReceiptBoundary.WIFI_VALIDATION_START)
            awaitValidatedInternet(receiverState)
            reportBoundary(ReceiptBoundary.WIFI_VALIDATION_COMPLETE)
        }
    }

    /** Leaves every generated Activity behind Home so the process runs with no visible UI. */
    private suspend fun stopActivities() {
        PlatformBackgroundAttachmentProbe.shell("input keyevent KEYCODE_HOME")
        withTimeout(ACTIVITY_STOP_TIMEOUT_MILLIS) { while (visibleActivities() > 0) delay(10L) }
        withContext(Dispatchers.Main.immediate) { requireNotNull(state).setAppInForeground(false) }
    }

    /** Emits no identities, locators, exception messages or free-form native state. */
    private fun reportBoundary(boundary: ReceiptBoundary) {
        ControlledAttachmentProbe.report(
            JSONObject().put("phase", "background-receipt-stage").put("stage", boundary.name.lowercase()),
        )
    }

    /** Sends [attachments] as one message through the shipping Android send path. */
    private suspend fun send(
        sender: AccountSummaryFfi,
        group: String,
        attachments: List<PendingAttachment>,
    ): SentFixtureMessage =
        sendAndroidFixtureMedia(
            context,
            root,
            requireNotNull(marmot),
            sender,
            group,
            blobPort,
            attachments,
        )

    /** One projected album: the receiver's request and the sent reference for each attachment, in order. */
    private class Projected(
        val requests: List<AttachmentTransferRequest>,
        val references: List<MediaAttachmentReferenceFfi>,
    )

    /** Resolves the receiver's identities for [sent] without demanding or reading anything. */
    private suspend fun project(
        receiver: AccountSummaryFfi,
        group: String,
        sent: SentFixtureMessage,
    ): Projected {
        val projected =
            MediaLifecycleAttachmentProbe.projectRequests(requireNotNull(marmot), receiver, group, sent.references)
        return Projected(projected, sent.references)
    }

    /** Waits for every album attachment to reach native READY without any foreground reader. */
    private suspend fun awaitReady(
        receiver: AccountSummaryFfi,
        group: String,
        album: Projected,
    ) {
        val receiverState = requireNotNull(state)
        requests += album.requests
        try {
            withTimeout(READY_TIMEOUT_MILLIS) {
                while (album.requests.any { !receiverState.hasNativeAttachment(it) }) delay(50L)
            }
        } catch (stalled: kotlinx.coroutines.TimeoutCancellationException) {
            reportStall(context, receiverState, requireNotNull(marmot), receiver, group, album.requests)
            throw stalled
        }
    }

    /** Every attachment's retained native bytes equal the sent plaintext. */
    private suspend fun assertExactBytes(
        album: Projected,
        expected: List<ByteArray>,
    ) {
        album.requests.forEachIndexed { index, request ->
            requireNotNull(state).openNativeAttachment(request).use {
                assertArrayEquals(expected[index], requireNotNull(it).toByteArray())
            }
        }
    }

    /** Releases generated work, state, accounts and files, and turns Wi-Fi back on, on every path. */
    private suspend fun cleanUp() {
        withContext(NonCancellable) {
            if (wifiDisabled) runCatching { BackgroundReceiptFixtureSupport.setWifiEnabled(true) }
            val manager = WorkManager.getInstance(context)
            requests.forEach { runCatching { manager.cancelUniqueWork(attachmentDownloadWorkName(it)).result.get() } }
            try {
                state?.stopNotificationListenerForAccountTeardown()
                state?.mutationsScope?.cancel()
            } finally {
                restoreApplication?.invoke()
            }
            val runtime =
                marmot ?: run {
                    // A run that never opened its runtime still owns the preserved root and its local accounts.
                    root.deleteRecursively()
                    return@withContext
                }
            val cleanup = accounts.map { runCatching { withTimeout(5_000L) { runtime.removeAccount(it) } } }
            try {
                runtime.shutdownAndClose()
            } finally {
                root.deleteRecursively()
            }
            check(cleanup.all { it.isSuccess }) { "fixture account cleanup failed" }
        }
    }
}
