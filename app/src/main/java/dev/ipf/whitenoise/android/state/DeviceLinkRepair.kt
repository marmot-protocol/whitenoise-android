package dev.ipf.whitenoise.android.state

import android.content.SharedPreferences
import dev.ipf.marmotkit.DeviceLinkSnapshotFfi

/** MDK confirmed the discard, but this account still needs a fresh KeyPackage before pairing. */
internal class DeviceRepairPublicationFailed(
    cause: Throwable,
) : Exception(cause)

/** Only a new explicit Repair decision can resolve an uncertain destructive native call. */
internal class DeviceRepairConfirmationRequired(
    cause: Throwable? = null,
) : Exception(cause)

/**
 * Completes an explicitly confirmed repair across screen and process lifetimes. Callers hold account lifetime
 * on native IO. A saved true means the discard outcome is unknown, false means only publication is due.
 */
internal class DeviceLinkRepair(
    private val preferences: SharedPreferences,
    accountIdHex: String,
) {
    private val key = DeviceLinkOffers.REPAIR_PREFIX + accountIdHex.lowercase()

    /** Show Repair for an uncertain outcome even when MDK's current group is healthy. */
    val needsConfirmation: Boolean
        get() = preferences.getBoolean(key, false)

    /** Only call from the destructive Repair confirmation. A normal pairing attempt can never discard a group. */
    suspend fun reset(
        discard: suspend () -> Unit,
        rotate: suspend () -> Unit,
    ) {
        if (!preferences.contains(key) || needsConfirmation) confirmDiscard(discard)
        runCatchingCancellable { publishIfPending(rotate) }.getOrElse { throw DeviceRepairPublicationFailed(it) }
    }

    /** A lost reply keeps the uncertainty durable. Only MDK's authoritative discard or absence advances the receipt. */
    private suspend fun confirmDiscard(discard: suspend () -> Unit) {
        val retry = needsConfirmation
        save(uncertain = true)
        val failure = runCatchingCancellable { discard() }.exceptionOrNull()
        if (failure != null) {
            val refusal = deviceGroupRefusal(failure)
            if (refusal == null) throw DeviceRepairConfirmationRequired(failure)
            if (!retry || refusal != "no_device_group") {
                if (!retry) clear()
                throw failure
            }
        }
        save(uncertain = false)
    }

    /** Every new pairing completes owed publication and refuses an uncertain discard until the user confirms Repair. */
    suspend fun start(
        rotate: suspend () -> Unit,
        pair: suspend () -> DeviceLinkSnapshotFfi,
    ): DeviceLinkSnapshotFfi {
        publishIfPending(rotate)
        return pair()
    }

    private suspend fun publishIfPending(rotate: suspend () -> Unit) {
        if (needsConfirmation) throw DeviceRepairConfirmationRequired()
        if (!preferences.contains(key)) return
        rotate()
        clear()
    }

    private fun save(uncertain: Boolean) {
        check(preferences.edit().putBoolean(key, uncertain).commit()) { "Could not record link repair stage" }
    }

    private fun clear() {
        check(preferences.edit().remove(key).commit()) { "Could not finish link repair" }
    }
}
