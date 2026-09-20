package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.AccountSummaryFfi
import dev.ipf.whitenoise.android.functionBody
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class IdentityCreationFlowTest {
    /** Direct creation retains the native receipt when qualification fails and accepts it exactly once later. */
    @Test
    fun directCreationRetriesPolicyForTheSameReceipt() =
        runTest {
            val coordinator = IdentityPolicyQualification()
            val account = account("created", "11")
            var creations = 0
            var qualifications = 0
            var accepts = 0
            val create = {
                creations += 1
                account
            }
            val qualify: suspend (AccountSummaryFfi) -> Unit = {
                qualifications += 1
                if (qualifications == 1) error("policy failed")
            }
            val accept: (AccountSummaryFfi) -> Unit = { accepts += 1 }

            assertTrue(runCatching { coordinator.createQualifyAndAccept(create, qualify, accept) }.isFailure)
            assertEquals(account, coordinator.createQualifyAndAccept(create, qualify, accept))
            assertEquals(1, creations)
            assertEquals(2, qualifications)
            assertEquals(1, accepts)
        }

    /** Identity creation still supplies the full bootstrap set to both engine relay parameters. */
    @Test
    fun creationKeepsTheFullRelaySetOnTheEngineCall() {
        val body = appStateSource("MarmotAttachmentAcquisitionPolicy.kt").readText()

        assertTrue(body.contains("createIdentityWithBootstrapRelays"))
        assertTrue(body.contains("val relays = MarmotClient.bootstrapRelays"))
        assertTrue(body.contains("createIdentity(relays, relays)"))
        assertFalse(body.contains("take(1)"))
    }

    @Test
    fun readyStatePrecedesPostCreateWarmup() {
        val body = appStateSource("AppProfileSignUp.kt").readText().functionBody("createIdentityWithoutProfile")
        val ready = body.indexOf("markReady()")
        val warmup = body.indexOf("launchIdentityPostCreateWarmup(summary)")

        assertTrue("identity must become ready before best-effort warm-up starts", ready >= 0 && warmup > ready)
    }

    /** Post create warmup is best effort and account scoped. */
    @Test
    fun postCreateWarmupIsBestEffortAndAccountScoped() {
        val body = appStateSource("AppProfileSignUp.kt").readText().functionBody("launchIdentityPostCreateWarmup")

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

    /** Builds an account fixture. */
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

    /** The AppState source file under either working directory. */
    private fun appStateSource(name: String = "AppState.kt"): File =
        listOf(
            File("src/main/java/dev/ipf/whitenoise/android/state/$name"),
            File("app/src/main/java/dev/ipf/whitenoise/android/state/$name"),
        ).firstOrNull { it.exists() }
            ?: error("Missing $name source file")
}
