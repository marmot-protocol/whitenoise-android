package dev.ipf.whitenoise.android.ui.devices

import dev.ipf.marmotkit.LinkedDeviceStatusFfi
import dev.ipf.whitenoise.android.state.LinkedDeviceRoster
import dev.ipf.whitenoise.android.state.LinkedDeviceRosterState
import dev.ipf.whitenoise.android.state.LinkedDeviceRow

/** 12 November 2026 12:00 UTC, so the same calendar day renders in every nearby time zone. */
internal const val FIXTURE_LINKED_AT = 1_794_484_800L

/** A roster row with only the fields a test cares about set away from their defaults. */
@Suppress("LongParameterList") // Mirrors the row so each test names what it varies.
internal fun fixtureDevice(
    slot: String,
    label: String = "",
    isThisDevice: Boolean = false,
    client: String = "White Noise",
    platform: String = "Android",
    status: LinkedDeviceStatusFfi = LinkedDeviceStatusFfi.ACTIVE,
    lastActiveAtSeconds: Long? = null,
    inactive: Boolean = false,
    removalPending: Boolean = false,
) = LinkedDeviceRow(
    slotIdHex = slot,
    isThisDevice = isThisDevice,
    label = label,
    client = client,
    platform = platform,
    linkedAtSeconds = FIXTURE_LINKED_AT,
    status = status,
    lastActiveAtSeconds = lastActiveAtSeconds,
    inactive = inactive,
    removalPending = removalPending,
)

/** This device, two siblings in different states and one pending removal. */
internal fun fixtureRoster() =
    LinkedDeviceRoster(
        thisDevice = fixtureDevice("self", label = "Pixel", isThisDevice = true),
        others =
            listOf(
                fixtureDevice("tablet", label = "Tablet", lastActiveAtSeconds = FIXTURE_LINKED_AT + 3_600),
                fixtureDevice("desk", platform = "Desktop", status = LinkedDeviceStatusFfi.INACTIVE, inactive = true),
            ),
        removing = listOf(fixtureDevice("old", label = "Old phone", removalPending = true)),
    )

/** A loaded state showing [fixtureRoster]. */
internal fun fixtureRosterState() = LinkedDeviceRosterState(roster = fixtureRoster())
