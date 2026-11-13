package dev.ipf.whitenoise.android.media

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import androidx.work.WorkInfo
import androidx.work.WorkManager
import dev.ipf.marmotkit.AccountSummaryFfi
import dev.ipf.marmotkit.AttachmentLocalTargetFfi
import dev.ipf.marmotkit.AttachmentTransferStateFfi
import dev.ipf.marmotkit.Marmot
import dev.ipf.marmotkit.NotificationTrafficClassFfi
import dev.ipf.marmotkit.NotificationTriggerFfi
import dev.ipf.marmotkit.NotificationUpdateFfi
import dev.ipf.marmotkit.NotificationUserFfi
import dev.ipf.whitenoise.android.state.AttachmentTransferRequest
import dev.ipf.whitenoise.android.state.DraftPersistence
import dev.ipf.whitenoise.android.state.MediaAutoDownloadNetwork
import dev.ipf.whitenoise.android.state.NativeAttachmentPermissions
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.state.attachmentDownloadWorkName
import dev.ipf.whitenoise.android.state.attachmentIntentStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import org.junit.Assert.assertFalse

private const val LEDGER_TIMEOUT_MILLIS = 30_000L
private const val NETWORK_CHANGE_TIMEOUT_MILLIS = 30_000L
private const val OWNER_TIMEOUT_MILLIS = 30_000L

/**
 * Stateless helpers for the background receipt fixture (#3308): its synthetic receipt, the device and network
 * observations it waits on, and the closed identities it carries between its two Android processes.
 */
internal object BackgroundReceiptFixtureSupport {
    /** The receiver's NEW_MESSAGE update for [request]'s message, as MarmotKit delivers it on receipt. */
    fun receipt(
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
        sticker = null,
    )

    /** Activities of this process that are on screen or still leaving it. */
    suspend fun visibleActivities(): Int =
        withContext(Dispatchers.Main.immediate) {
            val monitor = ActivityLifecycleMonitorRegistry.getInstance()
            listOf(Stage.RESUMED, Stage.STARTED, Stage.PAUSED).sumOf { monitor.getActivitiesInStage(it).size }
        }

    /** Waits with the existing bound; [observe] may retain closed summaries of these already-required reads. */
    suspend fun awaitLedger(
        blobPort: Int,
        observe: (List<JSONObject>) -> Unit = {},
        done: (List<JSONObject>) -> Boolean,
    ): List<JSONObject> =
        withTimeout(LEDGER_TIMEOUT_MILLIS) {
            var events = HeldAttachmentCancellationProbe.ledger(blobPort)
            observe(events)
            while (!done(events)) {
                delay(25L)
                events = HeldAttachmentCancellationProbe.ledger(blobPort)
                observe(events)
            }
            events
        }

    /** One existing network-change budget covers the toggle, commands and real default-network observation. */
    fun wifiChangeDeadline(): Long = SystemClock.elapsedRealtime() + NETWORK_CHANGE_TIMEOUT_MILLIS

    /** Turns only this fixture emulator's radio on or off; cleanup receives its own bounded deadline. */
    fun setWifiEnabled(
        enabled: Boolean,
        deadline: Long = wifiChangeDeadline(),
    ) {
        wifiShell("cmd wifi set-wifi-enabled ${if (enabled) "enabled" else "disabled"}", deadline)
    }

    /** Waits for the actual default network; command initiation alone supplies no successful observation. */
    suspend fun awaitWifiSnapshot(
        state: WhiteNoiseAppState,
        account: String,
        enabled: Boolean,
        deadline: Long,
    ) {
        withTimeout(deadline - SystemClock.elapsedRealtime()) {
            if (enabled) {
                traceBackgroundReceiptWifiAssociation(deadline) { associateWifi(deadline) }
            }
            while (!wifiSnapshotMatches(state, account, enabled)) delay(50L)
            check(SystemClock.elapsedRealtime() < deadline) { "emulator Wi-Fi observation exceeded its deadline" }
        }
    }

    /** Initiates one association, never a retry loop, to the AP the host actually observed. */
    private fun associateWifi(deadline: Long) {
        val ssid = InstrumentationRegistry.getArguments().getString("fixtureWifiSsidHex")
        check(ssid != null && Regex("(?:[0-9a-f]{2}){1,32}").matches(ssid)) {
            "the disposable emulator AP observation is missing"
        }
        check(wifiShell("id -u", deadline, asRoot = true).trim() == "0") {
            "the disposable emulator cannot associate Wi-Fi as root"
        }
        val help = wifiShell("cmd wifi help", deadline, asRoot = true)
        val arguments = wifiAssociationArguments(ssid, help)
        val result = wifiShell("cmd wifi connect-network $arguments", deadline, asRoot = true)
        check("Connection initiated" in result) { "emulator Wi-Fi association was not initiated" }
    }

    /** Hex is supported by newer shells; older shells accept only safe, single-token observed names. */
    private fun wifiAssociationArguments(
        ssid: String,
        help: String,
    ): String {
        if (help.lineSequence().any { it.trim().startsWith("-x - Specifies the SSID as hex") }) return "$ssid open -x"
        val name =
            ssid
                .chunked(2)
                .map { it.toInt(16).toByte() }
                .toByteArray()
                .toString(Charsets.US_ASCII)
        check(Regex("[A-Za-z0-9_.-]{1,32}").matches(name)) {
            "the emulator shell cannot safely represent this observed AP"
        }
        return "$name open"
    }

    /** A coroutine timeout cannot interrupt a blocking PFD read; toybox owns the command's real deadline. */
    private fun wifiShell(
        command: String,
        deadline: Long,
        asRoot: Boolean = false,
    ): String {
        val remaining = deadline - SystemClock.elapsedRealtime()
        check(remaining > 0) { "emulator Wi-Fi command deadline expired" }
        val seconds = remaining.toDouble() / 1000.0
        val bounded = "/system/bin/toybox timeout -s KILL $seconds $command"
        // The root timer must be able to signal its root child; a shell-UID timer cannot kill that child.
        val invocation = if (asRoot) "/system/xbin/su 0 $bounded" else bounded
        val result = PlatformBackgroundAttachmentProbe.shell(invocation)
        check(SystemClock.elapsedRealtime() < deadline) { "emulator Wi-Fi command exceeded its deadline" }
        return result
    }

    /** Association initiation is not qualification: require the real default, validation and app mirror. */
    private fun wifiSnapshotMatches(
        state: WhiteNoiseAppState,
        account: String,
        enabled: Boolean,
    ): Boolean {
        val appWifi = MediaAutoDownloadNetwork.WiFi in state.automaticAttachmentPolicy(account).networks
        if (!enabled) return !appWifi
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val manager = context.getSystemService(ConnectivityManager::class.java)
        val network = manager?.activeNetwork
        val capabilities = manager?.getNetworkCapabilities(network)
        val connected =
            network != null &&
                network == manager?.activeNetwork &&
                capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        val usable =
            capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true &&
                capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true &&
                capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) == true
        val mirrored = appWifi && state.hasValidatedInternet()
        return connected && usable && mirrored
    }

    /** Automatic downloads need validated internet; a run without it fails here with a plain reason. */
    suspend fun awaitValidatedInternet(state: WhiteNoiseAppState) {
        val validated =
            withTimeoutOrNull(NETWORK_CHANGE_TIMEOUT_MILLIS) {
                while (!state.hasValidatedInternet()) delay(100L)
                true
            } == true
        check(validated) { "the emulator has no validated internet connection; automatic downloads cannot run" }
    }

    /**
     * Each attachment was fetched by its own automatic WorkManager owner, never an interactive one. Native READY can
     * land a moment before the owner records its result, so each owner is given time to finish.
     */
    suspend fun assertDurableOwners(
        context: Context,
        requests: List<AttachmentTransferRequest>,
    ) {
        val manager = WorkManager.getInstance(context)
        val intents = attachmentIntentStore(context)
        requests.forEach { request ->
            val name = attachmentDownloadWorkName(request)
            withTimeout(OWNER_TIMEOUT_MILLIS) {
                while (manager.getWorkInfosForUniqueWork(name).get().none { it.state == WorkInfo.State.SUCCEEDED }) {
                    delay(50L)
                }
            }
            assertFalse("automatic work became interactive", intents.isInteractive(request))
        }
    }

    /** Records closed facts about a stalled album: native and WorkManager states, policy and approval only. */
    suspend fun reportStall(
        context: Context,
        state: WhiteNoiseAppState,
        marmot: Marmot,
        receiver: AccountSummaryFfi,
        group: String,
        stalled: List<AttachmentTransferRequest>,
    ) {
        val manager = WorkManager.getInstance(context)
        val nativeStates =
            stalled.map { runCatching { nativeState(marmot, receiver, group, it).name }.getOrElse { "error" } }
        val workStates =
            stalled.map { request ->
                manager.getWorkInfosForUniqueWork(attachmentDownloadWorkName(request)).get().map { it.state.name }
            }
        ControlledAttachmentProbe.report(
            JSONObject()
                .put("phase", "background-receipt-stall")
                .put("native_states", nativeStates.toString())
                .put("work_states", workStates.toString())
                .put("allowed_types", state.automaticAttachmentPolicy(receiver.label).allowedNow().toString())
                .put("approval_applied", permissions(state).appliedRevision(receiver.label) != null)
                .put("validated_internet", state.hasValidatedInternet()),
        )
    }

    /** Reads MDK's authoritative transfer state for one slot without demanding it. */
    suspend fun nativeState(
        marmot: Marmot,
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
        return marmot
            .attachmentTransferSnapshot(receiver.label, group, listOf(target))
            .items
            .single()
            .state
    }

    /** Keeps display and source identity distinct across the process boundary. */
    fun requestJson(request: AttachmentTransferRequest): JSONObject =
        JSONObject()
            .put("account", request.accountRef)
            .put("group", request.groupIdHex)
            .put("message", request.messageIdHex)
            .put("source", requireNotNull(request.sourceMessageIdHex))
            .put("index", request.attachmentIndex)

    /** Restores one request from the preserved manifest. */
    fun requestFrom(value: JSONObject) =
        AttachmentTransferRequest(
            value.getString("account"),
            value.getString("group"),
            value.getString("message"),
            value.getInt("index"),
            value.getString("source"),
        )

    /** [state]'s permission coordinator, which owns the approval durable work waits for. */
    fun permissions(state: WhiteNoiseAppState): NativeAttachmentPermissions =
        WhiteNoiseAppState::class.java
            .getDeclaredField("nativeAttachmentPermissions")
            .apply { isAccessible = true }
            .get(state) as NativeAttachmentPermissions

    /** The generated session has no persisted user draft. */
    object DiscardedDrafts : DraftPersistence {
        /** Never reads installed draft storage. */
        override fun read(): Map<String, String> = emptyMap()

        /** Discards only generated-fixture draft writes. */
        override fun write(
            key: String,
            value: String?,
        ) = Unit
    }
}
