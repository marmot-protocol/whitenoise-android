package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.ChatNotificationSettingsFfi
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MuteOverrideReconciliationTest {
    @Test
    fun staleProjectionDoesNotDropOverrideAfterCommandFinishes() {
        assertFalse(
            shouldDropMuteOverride(
                override = settings(muted = true),
                projectedMuted = false,
                projectedMutedUntilMs = null,
            ),
        )
    }

    @Test
    fun matchingProjectionDropsOverride() {
        val override = settings(muted = true, mutedUntilMs = 2_000)

        assertTrue(shouldDropMuteOverride(override, true, 2_000))
    }

    @Test
    fun expiredTimedOverrideNoLongerReportsMutedOrAnExpiry() {
        val effective = effectiveMuteOverride(settings(muted = true, mutedUntilMs = 1_000), nowMillis = 1_000)

        assertFalse(requireNotNull(effective).muted)
        assertNull(effective.mutedUntilMs)
    }

    private fun settings(
        muted: Boolean,
        mutedUntilMs: Long? = null,
    ) = ChatNotificationSettingsFfi(
        accountRef = "account",
        accountIdHex = "account-id",
        groupIdHex = "group",
        muted = muted,
        mutedUntilMs = mutedUntilMs,
        updatedAtMs = 1,
    )
}
