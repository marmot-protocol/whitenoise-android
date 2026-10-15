package dev.ipf.whitenoise.android.state

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.ipf.marmotkit.AccountSummaryFfi
import dev.ipf.whitenoise.android.state.MediaDownloadIntegrationFixture.Companion.ACCOUNT
import dev.ipf.whitenoise.android.state.MediaDownloadIntegrationFixture.Companion.qualifiedRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Durable automatic work re-checks the live policy for its attachment's type when it runs, then waits for
 * the native runtime to hold that approval before asking MDK, so a worker started in a fresh process or
 * during a network change neither fetches against policy nor fails as policy-blocked (#3308).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DurableAutomaticAttachmentAdmissionTest {
    private val dispatcher = StandardTestDispatcher()
    private lateinit var fixture: MediaDownloadIntegrationFixture

    /** Installs a deterministic main dispatcher and a fixture whose account the permission refresh covers. */
    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        fixture = MediaDownloadIntegrationFixture(accounts = listOf(account()))
    }

    /** Releases only this test's work, files and dispatcher. */
    @After
    fun tearDown() {
        fixture.close()
        Dispatchers.resetMain()
    }

    /** Work queued under an allowing policy that has since turned the type off finishes without a fetch. */
    @Test
    fun automaticWorkIsDeferredWhenTheLivePolicyNoLongerAllowsItsType() =
        runTest(dispatcher) {
            persistMatrix(MediaAutoDownloadMatrix(emptySet()))
            setNetwork(setOf(MediaAutoDownloadNetwork.WiFi))

            expectDeferred(MediaAutoDownloadType.Image)
            assertEquals(0, fixture.automaticDemands.get())
        }

    /** A connection without validated internet is the same conservative "not now" as the native permission. */
    @Test
    fun automaticWorkIsDeferredWithoutValidatedInternet() =
        runTest(dispatcher) {
            setNetwork(setOf(MediaAutoDownloadNetwork.WiFi), validated = false)

            expectDeferred(MediaAutoDownloadType.Image)
            assertEquals(0, fixture.automaticDemands.get())
        }

    /** Allowed work waits for the native approval instead of asking while MDK still denies it. */
    @Test
    fun allowedWorkWaitsForTheNativeApprovalBeforeAskingMdk() =
        runTest(dispatcher) {
            setNetwork(setOf(MediaAutoDownloadNetwork.WiFi))
            val download = async { durable(AttachmentDownloadPriority.Automatic) }
            permissions().waitersForTest().first { it > 0 }
            assertFalse(download.isCompleted)
            assertEquals(0, fixture.automaticDemands.get())

            fixture.state.refreshNativeAttachmentPermissions()
            fixture.entered.receive().succeed(PAYLOAD)

            assertTrue(download.await())
            assertEquals(1, fixture.automaticDemands.get())
            assertEquals(1, fixture.permissionApplications.get())
        }

    /** A refusal from a revocation window is asked again under the fresh approval, not turned into a failure. */
    @Test
    fun policyBlockedRequestIsAskedAgainUnderTheFreshApproval() =
        runTest(dispatcher) {
            setNetwork(setOf(MediaAutoDownloadNetwork.WiFi))
            fixture.state.refreshNativeAttachmentPermissions()
            advanceUntilIdle()
            fixture.automaticPolicyBlocks.set(1)

            val download = async { durable(AttachmentDownloadPriority.Automatic) }
            fixture.entered.receive().succeed(PAYLOAD)

            assertTrue(download.await())
            assertEquals(2, fixture.automaticDemands.get())
        }

    /** A second refusal is a transient "permission pending" failure that the worker retries after backoff. */
    @Test
    fun repeatedPolicyBlockIsReportedAsPendingPermission() =
        runTest(dispatcher) {
            setNetwork(setOf(MediaAutoDownloadNetwork.WiFi))
            fixture.state.refreshNativeAttachmentPermissions()
            advanceUntilIdle()
            fixture.automaticPolicyBlocks.set(2)

            val failure = runCatching { durable(AttachmentDownloadPriority.Automatic) }.exceptionOrNull()

            assertTrue("$failure", failure is AttachmentPermissionPendingException)
            assertTrue(isTransientAttachmentDownloadFailure(checkNotNull(failure)))
            assertEquals(2, fixture.automaticDemands.get())
        }

    /** An approval that never lands bounds the wait and reports a retryable failure. */
    @Test
    fun approvalThatNeverLandsEndsTheWaitAsPendingPermission() =
        runTest(dispatcher) {
            setNetwork(setOf(MediaAutoDownloadNetwork.WiFi))
            val download = async { runCatching { durable(AttachmentDownloadPriority.Automatic) }.exceptionOrNull() }
            advanceTimeBy(NATIVE_ATTACHMENT_PERMISSION_SETTLE_MILLIS + 1)
            advanceUntilIdle()

            assertTrue("${download.await()}", download.await() is AttachmentPermissionPendingException)
            assertEquals(0, fixture.automaticDemands.get())
        }

    /** The user's explicit download is not held to the automatic policy or its approval. */
    @Test
    fun explicitWorkSkipsAutomaticAdmission() =
        runTest(dispatcher) {
            persistMatrix(MediaAutoDownloadMatrix(emptySet()))
            val download = async { durable(AttachmentDownloadPriority.Interactive) }
            fixture.entered.receive().succeed(PAYLOAD)

            assertTrue(download.await())
            assertEquals(0, fixture.automaticDemands.get())
            assertEquals(1, fixture.explicitDemands.get())
        }

    /** Runs the production durable entry point for the fixture's first canonical image slot. */
    private suspend fun durable(priority: AttachmentDownloadPriority): Boolean {
        val request = qualifiedRequest(0)
        return fixture.state.downloadAttachmentForDurableWork(request, priority)
    }

    /** The fixture state's permission coordinator, which owns the approval wait. */
    private fun permissions(): NativeAttachmentPermissions =
        WhiteNoiseAppState::class.java
            .getDeclaredField("nativeAttachmentPermissions")
            .apply { isAccessible = true }
            .get(fixture.state) as NativeAttachmentPermissions

    /** Asserts the durable entry point defers [type] without demanding anything. */
    private suspend fun expectDeferred(type: MediaAutoDownloadType) {
        try {
            durable(AttachmentDownloadPriority.Automatic)
            fail("automatic work ran against the live policy")
        } catch (deferred: AttachmentAutomaticPolicyDeferredException) {
            assertEquals(type, deferred.type)
            assertFalse(isTransientAttachmentDownloadFailure(deferred))
        }
    }

    /** Seeds the fixture account's automatic-download matrix. */
    private fun persistMatrix(matrix: MediaAutoDownloadMatrix) {
        persistMediaAutoDownloadMatrix(
            ApplicationProvider
                .getApplicationContext<Context>()
                .getSharedPreferences("whitenoise", Context.MODE_PRIVATE),
            "media_auto_download_matrix:$ACCOUNT_ID",
            matrix,
        )
    }

    /** Drives the live network inputs the platform callbacks would otherwise maintain. */
    private fun setNetwork(
        types: Set<MediaAutoDownloadNetwork>,
        validated: Boolean = true,
    ) {
        val state = fixture.state
        setField(state, "hasActiveNetworkSnapshot", true)
        setField(state, "activeNetworkTypesSnapshot", types)
        val tracker =
            WhiteNoiseAppState::class.java
                .getDeclaredField("validatedInternetNetworks")
                .apply { isAccessible = true }
                .get(state) as ValidatedInternetNetworkTracker
        tracker.update(1L, available = validated)
    }

    /** Writes one private AppState field. */
    private fun setField(
        target: Any,
        name: String,
        value: Any,
    ) {
        WhiteNoiseAppState::class.java
            .getDeclaredField(name)
            .apply { isAccessible = true }
            .set(target, value)
    }

    /** The fixture's account, signed in, so the permission refresh applies an approval for it. */
    private fun account() =
        AccountSummaryFfi(
            label = ACCOUNT,
            accountIdHex = ACCOUNT_ID,
            localSigning = true,
            externalSigning = false,
            signedOut = false,
            running = true,
        )

    private companion object {
        const val ACCOUNT_ID = "synthetic-account-id"
        val PAYLOAD = ByteArray(64) { it.toByte() }
    }
}
