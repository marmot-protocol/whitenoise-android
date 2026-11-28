package dev.ipf.whitenoise.android.state

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.ipf.marmotkit.DeviceLinkEndReasonFfi
import dev.ipf.marmotkit.DeviceLinkPromptFfi
import dev.ipf.marmotkit.DeviceLinkPromptStateFfi
import dev.ipf.marmotkit.DeviceLinkSnapshotFfi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class DeviceLinkAttemptHistoryTest {
    @Test
    fun onlyUnreviewedExpirySurvivesSnapshotAndProcessChanges() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val history = DeviceLinkAttemptHistory(context, nowSeconds = { NOW })
        history.reviewed(DeviceLinkRequest("alice", "reviewed", NOW - 2))
        history.observe(
            mapOf("alice" to snapshot(expired("reviewed"), expired("missed"), cancelled())),
            watchedAccounts = setOf("alice", "bob"),
        )
        history.observe(mapOf("alice" to snapshot()), watchedAccounts = setOf("alice", "bob"))

        val restarted = DeviceLinkAttemptHistory(context, nowSeconds = { NOW })
        assertEquals(listOf("missed"), restarted.missed("alice").map(DeviceLinkAttempt::id))
        assertTrue(restarted.missed("bob").isEmpty())
        restarted.observe(emptyMap(), watchedAccounts = setOf("bob"))
        assertTrue(DeviceLinkAttemptHistory(context, nowSeconds = { NOW }).missed("alice").isEmpty())
    }

    @Test
    fun forgettingTheOnlyProfileRemovesItsPersistedAttempts() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val history = DeviceLinkAttemptHistory(context, nowSeconds = { NOW })
        history.observe(mapOf("alice" to snapshot(expired("old"))), watchedAccounts = setOf("alice"))
        history.forget("alice")

        assertTrue(DeviceLinkAttemptHistory(context, nowSeconds = { NOW }).missed("alice").isEmpty())
    }

    companion object {
        private const val NOW = 1_800_000_000L

        private fun snapshot(vararg prompts: DeviceLinkPromptFfi) =
            DeviceLinkSnapshotFfi(
                newDevice = null,
                prompts = prompts.toList(),
                moves = emptyList(),
            )

        private fun expired(id: String) =
            DeviceLinkPromptFfi(
                promptId = id,
                slotIdHex = null,
                requestedAt = (NOW - 2).toULong(),
                state = DeviceLinkPromptStateFfi.Ended(DeviceLinkEndReasonFfi.TIMED_OUT),
            )

        private fun cancelled(): DeviceLinkPromptFfi {
            val state = DeviceLinkPromptStateFfi.Ended(DeviceLinkEndReasonFfi.CANCELLED)
            return expired("cancelled").copy(state = state)
        }
    }
}
