package dev.ipf.whitenoise.android.ui.settings

import dev.ipf.marmotkit.AccountKeyPackageRelayEventFfi
import org.junit.Assert.assertEquals
import org.junit.Test

class KeyPackageRelayHistoryTest {
    /** The current slot winner leads, then superseded events newest first. */
    @Test
    fun currentFirstThenNewestSuperseded() {
        val rows =
            relayHistoryRows(
                listOf(
                    event("old", createdAt = 10uL, current = false),
                    event("newer", createdAt = 30uL, current = false),
                    event("current", createdAt = 20uL, current = true),
                ),
            )
        assertEquals(listOf("current", "newer", "old"), rows.map { it.eventIdHex })
    }

    private fun event(
        id: String,
        createdAt: ULong,
        current: Boolean,
    ) = AccountKeyPackageRelayEventFfi(
        accountIdHex = "a".repeat(64),
        keyPackageId = id,
        keyPackageRefHex = "b".repeat(64),
        eventIdHex = id,
        createdAt = createdAt,
        keyPackageBytes = 1uL,
        sourceRelays = listOf("wss://relay.example"),
        isCurrent = current,
    )
}
