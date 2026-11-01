package dev.ipf.whitenoise.android.media

import android.content.Context
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

    /** Turns the emulator's Wi-Fi on or off; the fixture's loopback servers stay reachable through adb either way. */
    fun setWifiEnabled(enabled: Boolean) {
        PlatformBackgroundAttachmentProbe.shell("cmd wifi set-wifi-enabled ${if (enabled) "enabled" else "disabled"}")
    }

    /** Waits until [state]'s network snapshot for [account] matches Wi-Fi being [enabled]. */
    suspend fun awaitWifiSnapshot(
        state: WhiteNoiseAppState,
        account: String,
        enabled: Boolean,
    ) {
        withTimeout(NETWORK_CHANGE_TIMEOUT_MILLIS) {
            while ((MediaAutoDownloadNetwork.WiFi in state.automaticAttachmentPolicy(account).networks) != enabled) {
                delay(50L)
            }
        }
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
