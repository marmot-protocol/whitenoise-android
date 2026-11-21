package dev.ipf.whitenoise.android.state

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.ipf.marmotkit.DeviceLinkSnapshotFfi
import dev.ipf.marmotkit.MarmotEventFfi
import dev.ipf.marmotkit.MarmotKitException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** MDK's device-management reads and edits for one runtime, so tests can stand in for the native library. */
internal interface LinkedDeviceManagementNative {
    /** Renames the device in [slotIdHex]; an empty label clears the name. */
    suspend fun renameLinkedDevice(
        accountRef: String,
        slotIdHex: String,
        label: String,
    )

    /** Patches the device's settings; a null field keeps the roster's current value. */
    suspend fun updateLinkedDeviceSettings(
        accountRef: String,
        slotIdHex: String,
        patch: LinkedDeviceSettingsPatch,
    )

    /** Removes another of the account's devices from the device group and every chat. */
    suspend fun removeLinkedDevice(
        accountRef: String,
        slotIdHex: String,
    )

    /** How many of this device's chats still hold a device the account no longer has. */
    suspend fun removedDeviceLeftoverChats(accountRef: String): UInt

    /** How many of this device's chats cannot take any device of this account. */
    suspend fun chatsClosedToLinkedDevices(accountRef: String): UInt

    /** Whether this device's KeyPackage refresh is overdue. */
    suspend fun keyPackageRefreshOverdue(accountRef: String): Boolean

    /** Discards this device's local device-group copy so it can be linked afresh. */
    suspend fun resetDeviceGroupForRepair(accountRef: String): DeviceLinkSnapshotFfi
}

/**
 * One edit of a device's settings. Only the field the user changed is set; MDK fills the others from the current
 * roster, so a toggle never restores another setting from a stale screen.
 */
internal data class LinkedDeviceSettingsPatch(
    val addToAllChats: Boolean? = null,
    val bringHistory: Boolean? = null,
    val keepWhileInactive: Boolean? = null,
)

/** The two chat counts MDK reports beside the roster; null until read. Neither count has a change event. */
internal data class LinkedDeviceChatCounts(
    val closedToLinkedDevices: Int? = null,
    val holdingRemovedDevice: Int? = null,
)

/** A management action the user started, so the screen can show which is running. */
internal enum class LinkedDeviceAction {
    Rename,
    Settings,
    Remove,
    Repair,
}

/**
 * Why MDK did not make a device change. [Busy] and [LinkPending] have their own advice because waiting helps;
 * [Other] carries the failure for the app's copyable report.
 */
internal sealed interface LinkedDeviceActionFailure {
    val action: LinkedDeviceAction

    /** The device group has an earlier change in flight; the same action can be tried again shortly. */
    data class Busy(
        override val action: LinkedDeviceAction,
    ) : LinkedDeviceActionFailure

    /** The device's own link may still fail, so it cannot be removed yet. */
    data class LinkPending(
        override val action: LinkedDeviceAction,
    ) : LinkedDeviceActionFailure

    /** Any other refusal or failure. */
    data class Other(
        override val action: LinkedDeviceAction,
        val cause: Throwable,
    ) : LinkedDeviceActionFailure
}

private const val DEVICE_GROUP_REFUSED_PREFIX = "device group refused: "

/**
 * MDK's reason for refusing a device-group change, or null when [error] is some other failure. MDK reports the
 * refusal as its runtime error with details `device group refused: <reason>`; this is the one place that reads it.
 */
internal fun deviceGroupRefusal(error: Throwable): String? =
    (error as? MarmotKitException.Runtime)
        ?.details
        ?.takeIf { it.startsWith(DEVICE_GROUP_REFUSED_PREFIX) }
        ?.removePrefix(DEVICE_GROUP_REFUSED_PREFIX)

/** Sorts a failed [action] into the advice the screen gives for it. */
internal fun linkedDeviceActionFailure(
    action: LinkedDeviceAction,
    error: Throwable,
): LinkedDeviceActionFailure =
    when (deviceGroupRefusal(error)) {
        "device_group_busy" -> LinkedDeviceActionFailure.Busy(action)
        "device_link_pending" -> LinkedDeviceActionFailure.LinkPending(action)
        else -> LinkedDeviceActionFailure.Other(action, error)
    }

/**
 * Edits one account's linked devices through MDK and mirrors the facts shown beside the roster. MDK owns every
 * rule: this controller sends what the user chose and reports what MDK answered. The roster itself is re-read by
 * [DeviceLinkController] when MDK announces the change, so nothing here predicts the result of an edit.
 *
 * One action runs at a time; a second tap while one runs is dropped. Results that arrive after [retire], or after
 * the account or runtime changed, are dropped too.
 */
@Stable
internal class LinkedDeviceManagementController(
    val accountRef: String,
    private val native: LinkedDeviceManagementNative,
    private val owns: () -> Boolean = { true },
) {
    var counts by mutableStateOf(LinkedDeviceChatCounts())
        private set

    var keyPackageRefreshOverdue by mutableStateOf(false)
        private set

    var running by mutableStateOf<LinkedDeviceAction?>(null)
        private set

    var failure by mutableStateOf<LinkedDeviceActionFailure?>(null)
        private set

    private val actions = Mutex()
    private val reads = Mutex()

    @Volatile
    private var retired = false

    private val current: Boolean
        get() = !retired && owns()

    /** Stops publishing for good. */
    fun retire() {
        retired = true
    }

    /** Forgets the failure once the screen has shown it. */
    fun failureShown() {
        failure = null
    }

    /**
     * Re-reads both chat counts and the overdue-refresh flag. A failed read keeps the value already shown: a count
     * that could not be read is never reported as zero.
     */
    suspend fun refresh() {
        reads.withLock {
            val closed = read { native.chatsClosedToLinkedDevices(accountRef).toInt() }
            val leftover = read { native.removedDeviceLeftoverChats(accountRef).toInt() }
            val overdue = read { native.keyPackageRefreshOverdue(accountRef) }
            if (!current) return
            counts =
                LinkedDeviceChatCounts(
                    closedToLinkedDevices = closed ?: counts.closedToLinkedDevices,
                    holdingRemovedDevice = leftover ?: counts.holdingRemovedDevice,
                )
            if (overdue != null) keyPackageRefreshOverdue = overdue
        }
    }

    /** Re-reads when MDK reports this account's roster or overdue flag changed; the counts have no own event. */
    suspend fun onEvent(event: MarmotEventFfi) {
        val mine =
            when (event) {
                is MarmotEventFfi.LinkedDevicesChanged -> event.accountLabel == accountRef
                is MarmotEventFfi.KeyPackageRefreshOverdueChanged -> event.accountLabel == accountRef
                else -> false
            }
        if (mine) refresh()
    }

    /** Renames a device. The name shown afterwards is the one MDK's roster returns, not the one typed. */
    suspend fun rename(
        slotIdHex: String,
        label: String,
    ) = act(LinkedDeviceAction.Rename) { native.renameLinkedDevice(accountRef, slotIdHex, label) }

    /** Changes one or more settings of a device, leaving the fields not named in [patch] to MDK. */
    suspend fun updateSettings(
        slotIdHex: String,
        patch: LinkedDeviceSettingsPatch,
    ) = act(LinkedDeviceAction.Settings) { native.updateLinkedDeviceSettings(accountRef, slotIdHex, patch) }

    /** Removes another device. MDK records the decision at once and lists the device as pending until it is done. */
    suspend fun remove(slotIdHex: String) {
        act(LinkedDeviceAction.Remove) { native.removeLinkedDevice(accountRef, slotIdHex) }
    }

    /**
     * Discards this device's local device-group copy. Returns MDK's snapshot on success so the caller can show the
     * state linking starts from, or null when the reset did not happen.
     */
    suspend fun resetForRepair(): DeviceLinkSnapshotFfi? {
        var snapshot: DeviceLinkSnapshotFfi? = null
        act(LinkedDeviceAction.Repair) { snapshot = native.resetDeviceGroupForRepair(accountRef) }
        return snapshot.takeIf { current }
    }

    /** Runs one action, then re-reads the counts it may have changed. */
    @Suppress("TooGenericExceptionCaught") // Every non-cancellation native failure is shown to the user once.
    private suspend fun act(
        action: LinkedDeviceAction,
        run: suspend () -> Unit,
    ) {
        if (!current || !actions.tryLock()) return
        try {
            running = action
            run()
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Throwable) {
            if (current) failure = linkedDeviceActionFailure(action, error)
        } finally {
            running = null
            actions.unlock()
        }
        refresh()
    }

    @Suppress("TooGenericExceptionCaught") // Every non-cancellation native failure keeps the prior value.
    private suspend fun <T> read(call: suspend () -> T): T? =
        try {
            call()
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Throwable) {
            null
        }
}
