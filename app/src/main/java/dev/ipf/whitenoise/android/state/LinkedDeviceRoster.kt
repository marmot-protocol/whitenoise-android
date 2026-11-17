package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.DeviceLinkPromptStateFfi
import dev.ipf.marmotkit.DeviceLinkSnapshotFfi
import dev.ipf.marmotkit.LinkedDeviceFfi
import dev.ipf.marmotkit.LinkedDeviceStatusFfi

/**
 * One of the account's own devices as a roster row shows it. Every field is MDK's; [lastActiveAtSeconds] is when a
 * heartbeat last showed the device running, which is an observation and never a claim that it is online now.
 */
internal data class LinkedDeviceRow(
    val slotIdHex: String,
    val isThisDevice: Boolean,
    val label: String,
    val client: String,
    val platform: String,
    val linkedAtSeconds: Long,
    val status: LinkedDeviceStatusFfi,
    val lastActiveAtSeconds: Long?,
    val inactive: Boolean,
    val removalPending: Boolean,
)

/**
 * The roster grouped for display: this device, the other devices newest link first, and the devices this user
 * removed here whose removal the device group has not taken yet.
 */
internal data class LinkedDeviceRoster(
    val thisDevice: LinkedDeviceRow?,
    val others: List<LinkedDeviceRow>,
    val removing: List<LinkedDeviceRow>,
) {
    /** Whether MDK listed no device at all, which is how an account that has not linked anything reads. */
    val isEmpty: Boolean
        get() = thisDevice == null && others.isEmpty() && removing.isEmpty()

    /** Whether the account has any device besides this one, removal pending or not. */
    val hasOtherDevices: Boolean
        get() = others.isNotEmpty() || removing.isNotEmpty()
}

/** Groups MDK's roster for display without adding, dropping or reinterpreting a device. */
internal fun linkedDeviceRoster(devices: List<LinkedDeviceFfi>): LinkedDeviceRoster {
    val rows = devices.map(LinkedDeviceFfi::toRow)
    val siblings =
        rows
            .filterNot(LinkedDeviceRow::isThisDevice)
            .sortedWith(compareByDescending(LinkedDeviceRow::linkedAtSeconds).thenBy(LinkedDeviceRow::slotIdHex))
    return LinkedDeviceRoster(
        thisDevice = rows.firstOrNull(LinkedDeviceRow::isThisDevice),
        others = siblings.filterNot(LinkedDeviceRow::removalPending),
        removing = siblings.filter(LinkedDeviceRow::removalPending),
    )
}

private fun LinkedDeviceFfi.toRow(): LinkedDeviceRow =
    LinkedDeviceRow(
        slotIdHex = slotIdHex,
        isThisDevice = isThisDevice,
        label = label,
        client = client,
        platform = platform,
        linkedAtSeconds = linkedAt.toLong(),
        status = status,
        lastActiveAtSeconds = lastActiveAt?.toLong(),
        inactive = inactive,
        removalPending = removalPending,
    )

/** A link request waiting on one of this installation's profiles, named by MDK's opaque prompt id. */
internal data class PendingDeviceLinkRequest(
    val accountRef: String,
    val promptId: String,
    val requestedAtSeconds: Long,
)

/**
 * Whether a prompt still needs this user or is still in progress. A prompt that linked, was added by another device
 * or ended is settled and only reports its outcome.
 */
internal fun DeviceLinkPromptStateFfi.isOpen(): Boolean =
    when (this) {
        DeviceLinkPromptStateFfi.Preparing,
        is DeviceLinkPromptStateFfi.Comparing,
        DeviceLinkPromptStateFfi.CompetingRequests,
        is DeviceLinkPromptStateFfi.RateLimited,
        is DeviceLinkPromptStateFfi.CannotLink,
        DeviceLinkPromptStateFfi.TooManyDevices,
        DeviceLinkPromptStateFfi.Linking,
        -> true
        DeviceLinkPromptStateFfi.Linked,
        is DeviceLinkPromptStateFfi.AddedElsewhere,
        is DeviceLinkPromptStateFfi.Ended,
        -> false
    }

/**
 * The open link requests across [snapshots] (one per profile), oldest first. This only reads: a request on a profile
 * that is not active is listed so the user can be told about it, and is answered only after that profile is activated
 * and its prompt re-read.
 */
internal fun pendingDeviceLinkRequests(snapshots: Map<String, DeviceLinkSnapshotFfi>): List<PendingDeviceLinkRequest> =
    snapshots
        .flatMap { (accountRef, snapshot) ->
            snapshot.prompts
                .filter { it.state.isOpen() }
                .map { PendingDeviceLinkRequest(accountRef, it.promptId, it.requestedAt.toLong()) }
        }.sortedWith(
            compareBy(PendingDeviceLinkRequest::requestedAtSeconds)
                .thenBy(PendingDeviceLinkRequest::accountRef)
                .thenBy(PendingDeviceLinkRequest::promptId),
        )

/**
 * Chooses which link request the app shell presents. A presented request stays presented while MDK still lists it,
 * so a newer request never replaces a comparison in progress. Nothing new is presented while [blocked] (the app is
 * locked or another protected flow is on screen). Closing the shell prompt hides that request here and answers
 * nothing: declining is a separate native action.
 */
internal data class DeviceLinkRequestQueue(
    val presented: PendingDeviceLinkRequest? = null,
    val hidden: Set<PendingDeviceLinkRequest> = emptySet(),
) {
    /** The queue after MDK's latest [pending] list. Requests MDK no longer lists are forgotten. */
    fun offer(
        pending: List<PendingDeviceLinkRequest>,
        blocked: Boolean,
    ): DeviceLinkRequestQueue {
        val stillHidden = hidden.intersect(pending.toSet())
        val kept = presented?.takeIf { it in pending }
        val next = kept ?: pending.firstOrNull { it !in stillHidden }.takeUnless { blocked }
        return DeviceLinkRequestQueue(presented = next, hidden = stillHidden)
    }

    /** The queue after the user closed the presented request without answering it. */
    fun hidePresented(): DeviceLinkRequestQueue {
        val closed = presented ?: return this
        return DeviceLinkRequestQueue(presented = null, hidden = hidden + closed)
    }
}
