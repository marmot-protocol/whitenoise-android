package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.AccountSummaryFfi
import org.junit.Assert.assertEquals
import org.junit.Test

class IdentityCreationFlowTest {
    @Test
    fun fastIdentityCreationPlanLimitsBlockingCreateToFirstRelay() {
        val relays = listOf("wss://relay.us.whitenoise.chat", "wss://relay.eu.whitenoise.chat")

        val plan = fastIdentityCreationRelayPlan(relays)

        assertEquals(listOf("wss://relay.us.whitenoise.chat"), plan.createDefaultRelays)
        assertEquals(listOf("wss://relay.us.whitenoise.chat"), plan.createBootstrapRelays)
        assertEquals(relays, plan.publishDefaultRelays)
        assertEquals(relays, plan.publishBootstrapRelays)
    }

    @Test
    fun createdIdentityIsAppendedWithoutRefreshingAllAccounts() {
        val existing = account("alice", "aa")
        val created = account("bob", "bb")

        assertEquals(
            listOf(existing, created),
            accountSummariesWithCreatedIdentity(listOf(existing), created),
        )
    }

    @Test
    fun createdIdentityReplacesMatchingLabel() {
        val stale = account("alice", "aa", running = false)
        val created = account("alice", "bb", running = true)

        assertEquals(
            listOf(created),
            accountSummariesWithCreatedIdentity(listOf(stale), created),
        )
    }

    @Test
    fun createdIdentityReplacesMatchingHexCaseInsensitively() {
        val stale = account("old-label", "AABB", running = false)
        val created = account("new-label", "aabb", running = true)

        assertEquals(
            listOf(created),
            accountSummariesWithCreatedIdentity(listOf(stale), created),
        )
    }

    @Test
    fun bootstrapRetryDelayBacksOffWithCap() {
        assertEquals(1_000L, identityBootstrapRetryDelayMillis(0, initialDelayMillis = 1_000L, maxDelayMillis = 5_000L))
        assertEquals(2_000L, identityBootstrapRetryDelayMillis(1, initialDelayMillis = 1_000L, maxDelayMillis = 5_000L))
        assertEquals(4_000L, identityBootstrapRetryDelayMillis(2, initialDelayMillis = 1_000L, maxDelayMillis = 5_000L))
        assertEquals(5_000L, identityBootstrapRetryDelayMillis(3, initialDelayMillis = 1_000L, maxDelayMillis = 5_000L))
    }

    private fun account(
        label: String,
        accountIdHex: String,
        running: Boolean = true,
    ) = AccountSummaryFfi(
        label = label,
        accountIdHex = accountIdHex,
        localSigning = true,
        externalSigning = false,
        signedOut = false,
        running = running,
    )
}
