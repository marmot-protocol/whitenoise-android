package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.AttachmentDownloadPolicyFfi
import dev.ipf.whitenoise.android.functionBody
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AttachmentPolicyStartupOrderingTest {
    /** Runtime policy I/O precedes startup without widening the passive notification boundary. */
    @Test
    fun existingAccountsAreContainedBeforeStartAndListenerStillFollowsImmediately() {
        val bootstrap = source("AppState.kt").readText().functionBody("startBootstrapRuntime")
        val configure = bootstrap.indexOf("enforceAppOwnedAttachmentAcquisitionForKnownAccounts()")
        val startCall = bootstrap.indexOf("startMarmotWithNotificationListener(runtime)")
        val body = source("AppState.kt").readText().functionBody("startMarmotWithNotificationListener")
        val start = body.indexOf("runtime.marmot.start()")
        val publishStart = body.indexOf("runtimeStartResult.complete(Result.success(Unit))")
        val listener = body.indexOf("runNotificationListenerLoop(runtime.marmot)")

        assertTrue(
            "containment must be part of configure, before native start",
            configure >= 0 && startCall > configure,
        )
        assertTrue(
            "post-start policy I/O would reopen the admission race",
            !body.contains("enforceAppOwnedAttachmentAcquisition"),
        )

        assertTrue(
            "the listener must follow start without policy I/O in the no-replay gap",
            start >= 0 && publishStart > start,
        )
        assertTrue(
            "the listener loop must begin immediately after publishing native readiness",
            listener > publishStart,
        )
    }

    /** Both identity creation entry points install containment before accepting the new account. */
    @Test
    fun createdIdentitiesAreContainedBeforeActivation() {
        val direct = source("AppProfileSignUp.kt").readText().functionBody("createIdentityWithoutProfile")
        assertOrdered(direct, "createIdentityWithBootstrapRelays()", "enforceAppOwnedAttachmentAcquisitionPolicy")
        assertOrdered(direct, "enforceAppOwnedAttachmentAcquisitionPolicy", "accept = activateCreatedIdentity")

        val profileSignUp = source("AppProfileSignUp.kt").readText().functionBody("begin")
        assertTrue(profileSignUp.contains("createIdentityWithBootstrapRelays()"))
        assertTrue(profileSignUp.contains("qualify ="))
    }

    /** Imported and external identities pass through policy-aware account refresh before activation. */
    @Test
    fun importedIdentitiesAreContainedBeforeActivation() {
        val appState = source("AppState.kt").readText()
        val imported = appState.functionBody("activateImportedIdentity")
        assertOrdered(imported, "enforceAppOwnedAttachmentAcquisitionPolicy", "refreshAccounts()")
        assertOrdered(imported, "refreshAccounts()", "setActiveAccount(summary.label)")

        val amber = appState.functionBody("loginWithAmber")
        assertOrdered(amber, "enforceAppOwnedAttachmentAcquisitionPolicy", "refreshAccounts()")
        assertOrdered(amber, "refreshAccounts()", "setActiveAccount(summary.label)")

        val refresh = appState.functionBody("refreshAccountSnapshot")
        assertTrue(refresh.contains("listAccountsWithAppAttachmentPolicy()"))
    }

    /** Disabling automatic acquisition preserves every native numeric storage limit. */
    @Test
    fun containmentChangesOnlyAutomatic() =
        runTest {
            val original =
                AttachmentDownloadPolicyFfi(
                    automatic = true,
                    retainedBytes = 2_000uL,
                    diskReserve = 300uL,
                    transferLimit = 40uL,
                )
            val writes = mutableListOf<Pair<String, AttachmentDownloadPolicyFfi>>()

            enforceAppOwnedAttachmentAcquisitionPolicy(
                accountRefs = listOf("personal", "personal"),
                readPolicy = { original },
                writePolicy = { accountRef, policy -> writes += accountRef to policy },
            )

            assertEquals(listOf("personal" to original.copy(automatic = false)), writes)
        }

    /** An already-contained account is not rewritten during startup or refresh. */
    @Test
    fun disabledPolicyDoesNotCauseAWrite() =
        runTest {
            var writes = 0

            enforceAppOwnedAttachmentAcquisitionPolicy(
                accountRefs = listOf("personal"),
                readPolicy = {
                    AttachmentDownloadPolicyFfi(
                        automatic = false,
                        retainedBytes = 2_000uL,
                        diskReserve = 300uL,
                        transferLimit = 40uL,
                    )
                },
                writePolicy = { _, _ -> writes += 1 },
            )

            assertEquals(0, writes)
        }

    /** A failed policy write aborts containment instead of publishing a partially safe account set. */
    @Test
    fun policyFailureStopsBeforeLaterAccounts() =
        runTest {
            val reads = mutableListOf<String>()
            val failure = IllegalStateException("fixture policy write failed")

            val thrown =
                runCatching {
                    enforceAppOwnedAttachmentAcquisitionPolicy(
                        accountRefs = listOf("personal", "work"),
                        readPolicy = { accountRef ->
                            reads += accountRef
                            AttachmentDownloadPolicyFfi(true, 2_000uL, 300uL, 40uL)
                        },
                        writePolicy = { _, _ -> throw failure },
                    )
                }.exceptionOrNull()

            assertTrue(thrown === failure)
            assertEquals(listOf("personal"), reads)
        }

    /** Asserts two source markers occur in the required safety order. */
    private fun assertOrdered(
        body: String,
        first: String,
        second: String,
    ) {
        val firstIndex = body.indexOf(first)
        val secondIndex = body.indexOf(second)
        assertTrue("expected $first before $second", firstIndex >= 0 && secondIndex > firstIndex)
    }

    /** Returns one production state source file from the app module test working directory. */
    private fun source(name: String): File = File("src/main/java/dev/ipf/whitenoise/android/state/$name")
}
