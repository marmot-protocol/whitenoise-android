package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.AccountSummaryFfi
import dev.ipf.whitenoise.android.functionBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class IdentityCreationFlowTest {
    @Test
    fun creationKeepsTheFullRelaySetOnTheEngineCall() {
        val body = appStateSource().readText().functionBody("createIdentity")

        assertTrue(
            body.contains("createIdentity(MarmotClient.bootstrapRelays, MarmotClient.bootstrapRelays)"),
        )
        assertFalse(body.contains("take(1)"))
    }

    @Test
    fun readyStatePrecedesPostCreateWarmup() {
        val body = appStateSource().readText().functionBody("createIdentity")
        val ready = body.indexOf("phase = AppPhase.Ready")
        val warmup = body.indexOf("launchIdentityPostCreateWarmup(summary)")

        assertTrue("identity must become ready before best-effort warm-up starts", ready >= 0 && warmup > ready)
    }

    @Test
    fun postCreateWarmupIsBestEffortAndAccountScoped() {
        val body = appStateSource().readText().functionBody("launchIdentityPostCreateWarmup")

        assertTrue(body.contains("runBestEffortPostCommitSteps("))
        assertTrue(body.contains("activeAccountRef == summary.label"))
        assertTrue(body.contains("refreshAccounts()"))
        assertTrue(body.contains("syncNativePushRegistrationIfEnabled()"))
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

    private fun appStateSource(): File =
        listOf(
            File("src/main/java/dev/ipf/whitenoise/android/state/AppState.kt"),
            File("app/src/main/java/dev/ipf/whitenoise/android/state/AppState.kt"),
        ).firstOrNull { it.exists() }
            ?: error("Missing AppState.kt source file")
}
