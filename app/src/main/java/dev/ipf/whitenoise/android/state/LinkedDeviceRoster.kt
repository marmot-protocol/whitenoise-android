package dev.ipf.whitenoise.android.state

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
