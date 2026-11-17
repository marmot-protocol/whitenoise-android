package dev.ipf.whitenoise.android.ui.devices

import dev.ipf.marmotkit.DeviceLinkEmojiFfi
import dev.ipf.marmotkit.LinkedDeviceStatusFfi
import dev.ipf.marmotkit.UnrecognizedSignInFfi
import dev.ipf.marmotkit.UnrecognizedSignInStateFfi
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

/** Seven emoji with their descriptions, as MDK supplies a comparison code. */
internal fun fixtureCode(): List<DeviceLinkEmojiFfi> =
    listOf(
        "🦊" to "fox",
        "🌵" to "cactus",
        "🚲" to "bicycle",
        "🎈" to "balloon",
        "🍋" to "lemon",
        "⚓" to "anchor",
        "🔔" to "bell",
    ).map { (emoji, description) -> DeviceLinkEmojiFfi(emoji, description) }

/** An unrecognized sign-in waiting for an answer in [slot]. */
internal fun signIn(
    slot: String,
    beforeThisDevice: Boolean = false,
) = UnrecognizedSignInFfi(
    slotIdHex = slot,
    client = "White Noise",
    appearedAt = FIXTURE_LINKED_AT.toULong(),
    lastSeenAt = FIXTURE_LINKED_AT.toULong() + 3_600u,
    beforeThisDevice = beforeThisDevice,
    signedIn = true,
    state = UnrecognizedSignInStateFfi.PROMPT,
    linkedBySlotIdHex = null,
    previouslyLinkedHere = false,
)
