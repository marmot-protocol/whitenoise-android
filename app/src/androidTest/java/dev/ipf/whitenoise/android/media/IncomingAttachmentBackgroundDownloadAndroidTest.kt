package dev.ipf.whitenoise.android.media

import android.content.Context
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import androidx.work.WorkInfo
import androidx.work.WorkManager
import dev.ipf.marmotkit.AccountSummaryFfi
import dev.ipf.marmotkit.AttachmentLocalTargetFfi
import dev.ipf.marmotkit.AttachmentTransferStateFfi
import dev.ipf.marmotkit.Marmot
import dev.ipf.marmotkit.MarmotAndroid
import dev.ipf.marmotkit.MediaAttachmentReferenceFfi
import dev.ipf.marmotkit.NotificationTrafficClassFfi
import dev.ipf.marmotkit.NotificationTriggerFfi
import dev.ipf.marmotkit.NotificationUpdateFfi
import dev.ipf.marmotkit.NotificationUserFfi
import dev.ipf.whitenoise.android.ManualDeviceFixture
import dev.ipf.whitenoise.android.state.AppMarmotRuntime
import dev.ipf.whitenoise.android.state.AttachmentDownloadPriority
import dev.ipf.whitenoise.android.state.AttachmentTransferRequest
import dev.ipf.whitenoise.android.state.DraftPersistence
import dev.ipf.whitenoise.android.state.DraftStore
import dev.ipf.whitenoise.android.state.MediaAutoDownloadNetwork
import dev.ipf.whitenoise.android.state.MediaAutoDownloadType
import dev.ipf.whitenoise.android.state.NativeAttachmentPermissions
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
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
private const val READY_TIMEOUT_MILLIS = 90_000L
private const val RESUME_TIMEOUT_MILLIS = 150_000L
private const val SETTLE_MILLIS = 5_000L

/**
 * Received media downloads in the background under its own type's policy (#3308). A generated sender in the same
 * MarmotKit runtime sends to a receiver whose app state runs the shipping receipt pipeline with its Activity stopped
 * and the conversation never opened. Each case asserts native READY, exact bytes and the durable WorkManager owner
 * before anything returns to the foreground; every fact reported is closed (counts, booleans, times), never an
 * identity, name or byte.
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
    private val accounts = mutableListOf<String>()
    private val requests = mutableListOf<AttachmentTransferRequest>()

    /** Runs the connected, return, policy-disabled and stopped-then-recreated cases against one generated session. */
    @Test
    fun receivedMediaDownloadsInTheBackgroundUnderItsOwnPolicy() =
        runBlocking {
            context = InstrumentationRegistry.getInstrumentation().targetContext
            val arguments = InstrumentationRegistry.getArguments()
            assumeTrue(arguments.getString("allowControlledAttachmentProbe") == "true")
            check(context.packageName == "dev.ipf.whitenoise.android.medialatency")
            check(PlatformBackgroundAttachmentProbe.shell("getprop ro.kernel.qemu").trim() == "1")
            blobPort = requireNotNull(arguments.getString("fixtureBlobPort")).toInt()
            relays = listOf("ws://127.0.0.1:${requireNotNull(arguments.getString("fixtureRelayPort")).toInt()}")
            MarmotAndroid.initialize(context)
            root = RestartAttachmentRetentionProbe.createRoot(context, null, null)
            try {
                val runtime = TileFixtureSupport.openRuntime(root, relays).also { marmot = it }
                runtime.start()
                val (sender, receiver, group) = createPeers(runtime)
                bindReceiver(runtime, receiver)
                setPolicy(receiver, MediaAutoDownloadType.entries.toSet())
                ActivityScenario.launch(ComponentActivity::class.java).use {
                    stopActivities()
                    val background = backgroundAlbum(sender, receiver, group)
                    val returned = returnWithoutFetching(background)
                    stopActivities()
                    val disabled = disabledTypeIsNeverFetched(sender, receiver, group)
                    val recreated = stoppedWorkResumesInARecreatedRuntime(sender, receiver, group)
                    ControlledAttachmentProbe.report(
                        JSONObject()
                            .put("phase", "background-receipt")
                            .put("success", true)
                            .put("android_api", Build.VERSION.SDK_INT)
                            .put("background", background.report)
                            .put("returned", returned)
                            .put("disabled_type", disabled)
                            .put("stopped_recreated", recreated),
                    )
                }
            } finally {
                cleanUp()
            }
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
        val sent = send(sender, group, attachments)
        val album = project(receiver, group, sent)
        awaitReady(receiver, group, album)
        val readyMillis = (System.nanoTime() - started) / 1_000_000
        assertEquals("an Activity returned to the foreground", 0, visibleActivities())
        assertExactBytes(album, attachments.map { it.plaintextBytes })
        assertDurableOwners(album)
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
                .put("conversation_opened", false)
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
        setPolicy(receiver, MediaAutoDownloadType.entries.toSet() - MediaAutoDownloadType.Video)
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
        val videoState = nativeState(receiver, group, videoRequest)
        val undemanded = setOf(AttachmentTransferStateFfi.NOT_REQUESTED, AttachmentTransferStateFfi.POLICY_BLOCKED)
        assertTrue("a disabled type was demanded: $videoState", videoState in undemanded)
        val after = HeldAttachmentCancellationProbe.ledger(blobPort).count { it.getString("kind") == "get" }
        assertEquals("only the enabled image was fetched", 1, after - before)
        requests += album.requests
        setPolicy(receiver, MediaAutoDownloadType.entries.toSet())
        return JSONObject()
            .put("enabled_fetched", 1)
            .put("disabled_queued", false)
            .put("disabled_fetched", false)
            .put("disabled_native_state", videoState.name.lowercase())
    }

    /**
     * Android stops the receipt-scheduled file's job mid-body; while WorkManager holds it in backoff the MarmotKit
     * runtime and app state are rebuilt on the same root, so automatic permission starts denied again. The resumed
     * job waits for the fresh approval and completes from the native checkpoint. The file arrives while files are off,
     * so its body can be held before the receipt path schedules it again through the shipping update handler.
     */
    @Suppress("LongMethod") // One lifetime owns the stopped job, the recreated runtime and their evidence.
    private suspend fun stoppedWorkResumesInARecreatedRuntime(
        sender: AccountSummaryFfi,
        receiver: AccountSummaryFfi,
        group: String,
    ): JSONObject {
        setPolicy(receiver, MediaAutoDownloadType.entries.toSet() - MediaAutoDownloadType.Document)
        val bytes = Random(4L).nextBytes(HELD_FILE_BYTES)
        val attachment = FixtureMediaAssets.attachment(bytes, "application/pdf", "held.pdf")
        val sent = send(sender, group, listOf(attachment))
        val request = project(receiver, group, sent).requests.single()
        HeldAttachmentCancellationProbe.control(blobPort, "/__hold-resumable-acquisition")
        setPolicy(receiver, MediaAutoDownloadType.entries.toSet())
        requireNotNull(state).processNotificationUpdateForTest(receipt(receiver, group, request))
        val manager = WorkManager.getInstance(context)
        val name = attachmentDownloadWorkName(request)
        val running =
            withTimeout(READY_TIMEOUT_MILLIS) {
                var info: WorkInfo? = null
                while (info == null) {
                    val infos = manager.getWorkInfosForUniqueWork(name).get()
                    info = infos.firstOrNull { it.state == WorkInfo.State.RUNNING }
                    if (info == null) delay(50L)
                }
                info
            }
        HeldAttachmentCancellationProbe.awaitLedger(blobPort) { events ->
            events.any { it.getString("kind") == "held" }
        }
        val job = PlatformInterruptedAttachmentProbe.findScheduledWork(context, running.id.toString())
        HeldAttachmentCancellationProbe.control(blobPort, "/__platform-stop-marker")
        val stopSeq =
            HeldAttachmentCancellationProbe
                .ledger(blobPort)
                .last { it.getString("kind") == "platform_stop_marker" }
                .getLong("seq")
        val namespace = if (job.first == null) "" else "-n ${job.first} "
        val command = "cmd jobscheduler timeout -u 0 ${namespace}${context.packageName} ${job.second}"
        val stopped = PlatformBackgroundAttachmentProbe.shell(command)
        check(stopped.contains("Timing out:") || stopped.contains("Stopping job:")) { "Android did not stop the job" }
        withTimeout(10_000L) {
            while (manager.getWorkInfosForUniqueWork(name).get().none { it.state == WorkInfo.State.ENQUEUED }) {
                delay(25L)
            }
        }
        HeldAttachmentCancellationProbe.control(blobPort, "/__interrupt-acquisition-held-resume")
        recreateRuntime(receiver)
        HeldAttachmentCancellationProbe.awaitLedger(blobPort) { events ->
            events.any { it.getString("kind") == "get" && it.getLong("seq") > stopSeq }
        }
        HeldAttachmentCancellationProbe.control(blobPort, "/__release-acquisition")
        withTimeout(RESUME_TIMEOUT_MILLIS) {
            while (manager.getWorkInfosForUniqueWork(name).get().none { it.state == WorkInfo.State.SUCCEEDED }) {
                delay(50L)
            }
        }
        assertEquals("an Activity returned to the foreground", 0, visibleActivities())
        requireNotNull(state).openNativeAttachment(request).use { local ->
            assertArrayEquals(bytes, requireNotNull(local).toByteArray())
        }
        assertFalse(attachmentIntentStore(context).isInteractive(request))
        requests += request
        return JSONObject()
            .put("actual_platform_stop", true)
            .put("runtime_recreated", true)
            .put("same_work_succeeded", true)
            .put("plaintext_exact", true)
            .put("android_process_restart_qualified", false)
    }

    /** The receiver's NEW_MESSAGE update for [request]'s message, as MarmotKit delivers it on receipt. */
    private fun receipt(
        receiver: AccountSummaryFfi,
        group: String,
        request: AttachmentTransferRequest,
    ) = NotificationUpdateFfi(
        notificationKey = "fixture:${request.messageIdHex}",
        conversationKey = "fixture:$group",
        trigger = NotificationTriggerFfi.NEW_MESSAGE,
        trafficClass = NotificationTrafficClassFfi.STANDARD,
        accountRef = receiver.label,
        accountIdHex = receiver.accountIdHex,
        groupIdHex = group,
        groupName = null,
        isDm = false,
        isMention = false,
        messageIdHex = request.messageIdHex,
        sender = NotificationUserFfi(accountIdHex = "sender", displayName = null, pictureUrl = null),
        receiver = NotificationUserFfi(accountIdHex = receiver.accountIdHex, displayName = null, pictureUrl = null),
        previewText = null,
        reactionEmoji = null,
        reactedToPreview = null,
        timestampMs = System.currentTimeMillis(),
        isFromSelf = false,
    )

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

    /** Applies exactly [enabled] on every network and waits until receipt and native policy both reflect it. */
    private suspend fun setPolicy(
        receiver: AccountSummaryFfi,
        enabled: Set<MediaAutoDownloadType>,
    ) {
        val receiverState = requireNotNull(state)
        withContext(Dispatchers.Main.immediate) {
            MediaAutoDownloadType.entries.forEach { type ->
                MediaAutoDownloadNetwork.entries.forEach { network ->
                    receiverState.setMediaAutoDownload(type, network, enabled = type in enabled)
                }
            }
        }
        withTimeout(15_000L) {
            while (receiverState.automaticAttachmentTypesAllowed(receiver.label) != enabled) delay(25L)
        }
        withContext(Dispatchers.Main.immediate) { receiverState.refreshNativeAttachmentPermissions() }
        withTimeout(15_000L) { permissions(receiverState).awaitApplied(receiver.label) }
    }

    /** Leaves every generated Activity behind Home so the process runs with no visible UI. */
    private suspend fun stopActivities() {
        PlatformBackgroundAttachmentProbe.shell("input keyevent KEYCODE_HOME")
        withTimeout(5_000L) { while (visibleActivities() > 0) delay(10L) }
        withContext(Dispatchers.Main.immediate) { requireNotNull(state).setAppInForeground(false) }
    }

    /** Activities of this process that are on screen or still leaving it. */
    private suspend fun visibleActivities(): Int =
        withContext(Dispatchers.Main.immediate) {
            val monitor = ActivityLifecycleMonitorRegistry.getInstance()
            listOf(Stage.RESUMED, Stage.STARTED, Stage.PAUSED).sumOf { monitor.getActivitiesInStage(it).size }
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
            reportStall(receiver, group, album.requests)
            throw stalled
        }
    }

    /** Records closed facts about a stalled album: native and WorkManager states, policy and approval only. */
    private suspend fun reportStall(
        receiver: AccountSummaryFfi,
        group: String,
        stalled: List<AttachmentTransferRequest>,
    ) {
        val receiverState = requireNotNull(state)
        val manager = WorkManager.getInstance(context)
        val applied =
            runCatching {
                val coordinator = permissions(receiverState)
                val field = NativeAttachmentPermissions::class.java.getDeclaredField("applied")
                field.isAccessible = true
                (field.get(coordinator) as kotlinx.coroutines.flow.StateFlow<*>)
                    .value
                    .toString()
                    .contains(receiver.label)
            }.getOrNull()
        val nativeStates = stalled.map { runCatching { nativeState(receiver, group, it).name }.getOrElse { "error" } }
        val workStates =
            stalled.map { request ->
                val name = attachmentDownloadWorkName(request)
                manager.getWorkInfosForUniqueWork(name).get().map { it.state.name }
            }
        ControlledAttachmentProbe.report(
            JSONObject()
                .put("phase", "background-receipt-stall")
                .put("native_states", nativeStates.toString())
                .put("work_states", workStates.toString())
                .put("allowed_types", receiverState.automaticAttachmentTypesAllowed(receiver.label).toString())
                .put("approval_applied", applied)
                .put("validated_internet", receiverState.hasValidatedInternet()),
        )
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

    /**
     * Each attachment was fetched by its own automatic WorkManager owner, never an interactive one. Native READY can
     * land a moment before the owner records its result, so each owner is given time to finish.
     */
    private suspend fun assertDurableOwners(album: Projected) {
        val manager = WorkManager.getInstance(context)
        val intents = attachmentIntentStore(context)
        album.requests.forEach { request ->
            val name = attachmentDownloadWorkName(request)
            withTimeout(30_000L) {
                while (manager.getWorkInfosForUniqueWork(name).get().none { it.state == WorkInfo.State.SUCCEEDED }) {
                    delay(50L)
                }
            }
            assertFalse("automatic work became interactive", intents.isInteractive(request))
        }
    }

    /** Reads MDK's authoritative transfer state for one slot without demanding it. */
    private suspend fun nativeState(
        receiver: AccountSummaryFfi,
        group: String,
        request: AttachmentTransferRequest,
    ): AttachmentTransferStateFfi {
        val target =
            AttachmentLocalTargetFfi(
                request.messageIdHex,
                requireNotNull(request.sourceMessageIdHex),
                request.attachmentIndex.toUInt(),
            )
        return requireNotNull(marmot)
            .attachmentTransferSnapshot(receiver.label, group, listOf(target))
            .items
            .single()
            .state
    }

    /** Rebuilds the MarmotKit runtime and the receiver's app state on the same root, as a fresh process would. */
    private suspend fun recreateRuntime(receiver: AccountSummaryFfi) {
        val oldState = requireNotNull(state)
        restoreApplication?.invoke()
        restoreApplication = null
        oldState.stopNotificationListenerForAccountTeardown()
        oldState.mutationsScope.cancel()
        requireNotNull(marmot).shutdownAndClose()
        val reopened = TileFixtureSupport.openRuntime(root, relays).also { marmot = it }
        reopened.start()
        bindReceiver(reopened, receiver)
        withContext(Dispatchers.Main.immediate) { requireNotNull(state).setAppInForeground(false) }
    }

    /** The receiver state's permission coordinator, which owns the approval durable work waits for. */
    private fun permissions(receiverState: WhiteNoiseAppState): NativeAttachmentPermissions =
        WhiteNoiseAppState::class.java
            .getDeclaredField("nativeAttachmentPermissions")
            .apply { isAccessible = true }
            .get(receiverState) as NativeAttachmentPermissions

    /** Releases generated work, state, accounts and files on every path. */
    private suspend fun cleanUp() {
        withContext(NonCancellable) {
            val manager = WorkManager.getInstance(context)
            requests.forEach { runCatching { manager.cancelUniqueWork(attachmentDownloadWorkName(it)).result.get() } }
            try {
                state?.stopNotificationListenerForAccountTeardown()
                state?.mutationsScope?.cancel()
            } finally {
                restoreApplication?.invoke()
            }
            val runtime = marmot ?: return@withContext
            val cleanup = accounts.map { runCatching { withTimeout(5_000L) { runtime.removeAccount(it) } } }
            try {
                runtime.shutdownAndClose()
            } finally {
                root.deleteRecursively()
            }
            check(cleanup.all { it.isSuccess }) { "fixture account cleanup failed" }
        }
    }

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
