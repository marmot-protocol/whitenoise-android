package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.ChatNotificationSettingsFfi
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MuteOverrideReconciliationTest {
    @Test
    fun authoritativeProjectionDropsOverrideWhenNoCommandIsPending() {
        assertTrue(
            shouldDropMuteOverride(
                override = settings(muted = true),
                projectedMuted = false,
                projectedMutedUntilMs = null,
                commandPending = false,
            ),
        )
    }

    @Test
    fun pendingCommandKeepsOverrideUntilMatchingProjectionArrives() {
        val override = settings(muted = true, mutedUntilMs = 2_000)

        assertFalse(shouldDropMuteOverride(override, false, null, commandPending = true))
        assertTrue(shouldDropMuteOverride(override, true, 2_000, commandPending = true))
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
