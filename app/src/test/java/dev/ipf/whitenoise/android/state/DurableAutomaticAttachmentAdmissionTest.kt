package dev.ipf.whitenoise.android.state

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.NetworkType
import dev.ipf.marmotkit.AccountSummaryFfi
import dev.ipf.marmotkit.AttachmentTransferStateFfi
import dev.ipf.whitenoise.android.state.MediaDownloadIntegrationFixture.Companion.ACCOUNT
import dev.ipf.whitenoise.android.state.MediaDownloadIntegrationFixture.Companion.qualifiedRequest
import dev.ipf.whitenoise.android.state.MediaDownloadIntegrationFixture.Companion.reference
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
        try {
            fixture.closeAndDrain(dispatcher)
        } finally {
            Dispatchers.resetMain()
        }
    }

    /** Work queued under an allowing policy that has since turned the type off finishes without a fetch. */
    @Test
    fun automaticWorkIsDeferredWhenTheLivePolicyNoLongerAllowsItsType() =
        runTest(dispatcher) {
            persistMatrix(VIDEO_ON_WIFI)
            setNetwork(setOf(MediaAutoDownloadNetwork.WiFi))

            expectDeferred(MediaAutoDownloadType.Image, AutomaticAttachmentHold.Disabled)
            assertEquals(0, fixture.automaticDemands.get())
        }

    /** A policy that enables nothing anywhere is decided before the attachment is even looked up. */
    @Test
    fun policyThatEnablesNothingDefersBeforeTheLookup() =
        runTest(dispatcher) {
            persistMatrix(MediaAutoDownloadMatrix(emptySet()))
            setNetwork(setOf(MediaAutoDownloadNetwork.WiFi))

            expectDeferred(type = null, hold = AutomaticAttachmentHold.Disabled)
            assertEquals(0, fixture.automaticDemands.get())
        }

    /** A paused backlog or an account no longer on the device holds every type back. */
    @Test
    fun pausedBacklogAndRemovedAccountDeferEveryType() =
        runTest(dispatcher) {
            setNetwork(setOf(MediaAutoDownloadNetwork.WiFi))
            expectDeferred(type = null, hold = AutomaticAttachmentHold.UnknownAccount, account = "removed-account")

            intents().pauseAutomatic(ACCOUNT)
            expectDeferred(type = null, hold = AutomaticAttachmentHold.Paused)
            assertEquals(0, fixture.automaticDemands.get())
        }

    /**
     * Before any account list has loaded, as when it failed to load on a cold start, the work waits and retries
     * instead of finishing: only a loaded list that lacks the account ends it for good.
     */
    @Test
    fun anUnloadedAccountListWaitsInsteadOfFinishing() =
        runTest(dispatcher) {
            fixture.close()
            fixture = MediaDownloadIntegrationFixture(accounts = emptyList())
            setNetwork(setOf(MediaAutoDownloadNetwork.WiFi))

            expectDeferred(type = null, hold = AutomaticAttachmentHold.AccountsUnloaded)

            assertTrue("an unloaded account list is waited out", AutomaticAttachmentHold.AccountsUnloaded.waits)
            assertFalse(
                "a loaded list lacking the account still ends the work",
                AutomaticAttachmentHold.UnknownAccount.waits,
            )
            assertEquals(0, fixture.automaticDemands.get())
        }

    /**
     * Once an account list has loaded, an empty one means the last account is gone: legacy work for it ends rather
     * than retrying forever as if the list had not loaded yet.
     */
    @Test
    fun aLoadedEmptyAccountListEndsTheWork() =
        runTest(dispatcher) {
            fixture.close()
            fixture = MediaDownloadIntegrationFixture(accounts = emptyList())
            fixture.markAccountListLoaded()
            setNetwork(setOf(MediaAutoDownloadNetwork.WiFi))

            expectDeferred(type = null, hold = AutomaticAttachmentHold.UnknownAccount)

            assertFalse("a removed account's work ends", AutomaticAttachmentHold.UnknownAccount.waits)
            assertEquals(0, fixture.automaticDemands.get())
        }

    /**
     * After an account was wiped, a failed account read leaves an empty list that is not authoritative, so a
     * surviving account's queued work waits for the next read instead of finishing without downloading.
     */
    @Test
    fun aFailedRefreshAfterAWipeKeepsSurvivingWorkWaiting() =
        runTest(dispatcher) {
            fixture.markAccountListLoaded()
            setNetwork(setOf(MediaAutoDownloadNetwork.WiFi))

            fixture.state.publishRefreshedAccountList(Result.failure(IllegalStateException("read failed")))

            expectDeferred(type = null, hold = AutomaticAttachmentHold.AccountsUnloaded)
            assertEquals(0, fixture.automaticDemands.get())
        }

    /** A connection without validated internet holds the type for the network only, so the work waits for one. */
    @Test
    fun automaticWorkWaitsForTheNetworkWithoutValidatedInternet() =
        runTest(dispatcher) {
            setNetwork(setOf(MediaAutoDownloadNetwork.WiFi), validated = false)

            expectDeferred(MediaAutoDownloadType.Image, AutomaticAttachmentHold.Network)
            assertEquals(0, fixture.automaticDemands.get())
        }

    /**
     * Work that runs on a metered mobile network under the default settings is held for the network only and
     * carries the unmetered network its policy allows, so the worker can narrow the queued spec to wait for it.
     */
    @Test
    fun networkHoldCarriesTheNetworkThePolicyAllows() =
        runTest(dispatcher) {
            setNetwork(setOf(MediaAutoDownloadNetwork.Mobile, MediaAutoDownloadNetwork.Metered))

            expectDeferred(
                MediaAutoDownloadType.Image,
                AutomaticAttachmentHold.Network,
                networkType = NetworkType.UNMETERED,
            )
            assertEquals(0, fixture.automaticDemands.get())
        }

    /** Work a bubble already made local finishes without consulting the policy or MDK's demand. */
    @Test
    fun locallyAvailableWorkFinishesWithoutAdmission() =
        runTest(dispatcher) {
            setNetwork(setOf(MediaAutoDownloadNetwork.WiFi))
            fixture.state.refreshNativeAttachmentPermissions()
            advanceUntilIdle()
            val first = async { durable(AttachmentDownloadPriority.Automatic) }
            fixture.entered.receive().succeed(PAYLOAD)
            assertTrue(first.await())

            persistMatrix(MediaAutoDownloadMatrix(emptySet()))
            assertTrue(durable(AttachmentDownloadPriority.Automatic))
            assertEquals(1, fixture.automaticDemands.get())
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

    /**
     * Network inputs that change before their refresh runs leave the previous network's denial applied. The work
     * waits for an approval that allows its type instead of asking MDK under that denial, so a refresh delayed
     * past the wait ends the run as retryable, never as a final refusal, and the retry finishes the same work.
     */
    @Test
    fun newlyAllowedNetworkWaitsForAnApprovalThatAllowsTheType() =
        runTest(dispatcher) {
            fixture.enforceNativePermission = true
            setNetwork(setOf(MediaAutoDownloadNetwork.Mobile, MediaAutoDownloadNetwork.Metered))
            fixture.state.refreshNativeAttachmentPermissions()
            advanceUntilIdle()
            // Wi-Fi is published, but the callback has not reached its permission refresh yet.
            setNetwork(setOf(MediaAutoDownloadNetwork.WiFi))
            val first = async { runCatching { durable(AttachmentDownloadPriority.Automatic) }.exceptionOrNull() }
            advanceTimeBy(NATIVE_ATTACHMENT_PERMISSION_SETTLE_MILLIS + 1)
            advanceUntilIdle()

            val failure = first.await()
            assertTrue("$failure", failure is AttachmentPermissionPendingException)
            assertEquals("work asked MDK under the previous network's denial", 0, fixture.automaticDemands.get())

            advanceUntilIdle()
            val retry = async { durable(AttachmentDownloadPriority.Automatic) }
            fixture.entered.receive().succeed(PAYLOAD)
            assertTrue(retry.await())
            assertEquals(1, fixture.automaticDemands.get())
        }

    /**
     * Album workers that time out together while a refresh is still applying leave that refresh to land. None
     * starts a new generation, which would revoke the one about to apply and supersede it, so the refresh in
     * flight is the only one MDK sees and its approval is the one that applies.
     */
    @Test
    fun concurrentSettleTimeoutsLeaveTheInFlightRefreshToLand() =
        runTest(dispatcher) {
            setNetwork(setOf(MediaAutoDownloadNetwork.WiFi))
            val commits = hostPreferenceCommits()
            commits.lock()
            fixture.state.refreshNativeAttachmentPermissions()
            advanceUntilIdle()
            assertEquals("the refresh began and waits on the queued settings save", 1, fixture.permissionBegins.get())

            val first = async { runCatching { durable(AttachmentDownloadPriority.Automatic) }.exceptionOrNull() }
            val second = async { runCatching { durable(AttachmentDownloadPriority.Automatic) }.exceptionOrNull() }
            advanceTimeBy(NATIVE_ATTACHMENT_PERMISSION_SETTLE_MILLIS + 1)
            advanceUntilIdle()
            assertTrue("${first.await()}", first.await() is AttachmentPermissionPendingException)
            assertTrue("${second.await()}", second.await() is AttachmentPermissionPendingException)

            commits.unlock()
            advanceUntilIdle()
            assertEquals("no timeout began another generation", 1, fixture.permissionBegins.get())
            assertEquals("the refresh in flight applied", 1, fixture.permissionApplications.get())
        }

    /**
     * Network callbacks that arrive while a coalesced refresh is still applying join one follow-up instead of
     * invalidating the generation in flight: the first refresh stays the only one until its apply has finished.
     */
    @Test
    fun networkRefreshesDuringAnApplyFoldIntoOneFollowUp() =
        runTest(dispatcher) {
            setNetwork(setOf(MediaAutoDownloadNetwork.WiFi))
            val commits = hostPreferenceCommits()
            commits.lock()
            requestNetworkRefresh()
            advanceUntilIdle()
            assertEquals("the first refresh began and waits on the settings save", 1, fixture.permissionBegins.get())

            requestNetworkRefresh()
            requestNetworkRefresh()
            advanceUntilIdle()

            commits.unlock()
            advanceUntilIdle()
            assertEquals("they folded into one follow-up", 2, fixture.permissionBegins.get())
            assertEquals(
                "the first generation was not superseded and applied, then the follow-up",
                2,
                fixture.permissionApplications.get(),
            )
        }

    /** A settle timeout's in-flight check and its refresh share one lock, so concurrent timeouts start one. */
    @Test
    fun aSettleTimeoutChecksAndStartsItsRefreshUnderOneLock() {
        val source =
            listOf(
                java.io.File("src/main/java/dev/ipf/whitenoise/android/state/AppState.kt"),
                java.io.File("app/src/main/java/dev/ipf/whitenoise/android/state/AppState.kt"),
            ).first(java.io.File::isFile).readText()
        val timeout = source.substringAfter("private fun refreshAfterSettleTimeout()").substringBefore("\n    }\n")
        assertTrue("the check holds the refresh lock", "synchronized(permissionRefreshLock)" in timeout)
        val refresh =
            source
                .substringAfter("internal fun refreshNativeAttachmentPermissions(")
                .substringBefore("return mutationsScope")
        val lock = refresh.indexOf("synchronized(permissionRefreshLock)")
        assertTrue("the refresh invalidates under the same lock", lock in 0 until refresh.indexOf(".invalidate("))
    }

    /** A refusal from a revocation window is asked again once the host's approval has moved on. */
    @Test
    fun policyBlockedRequestIsAskedAgainOnceTheApprovalMovesOn() =
        runTest(dispatcher) {
            setNetwork(setOf(MediaAutoDownloadNetwork.WiFi))
            fixture.state.refreshNativeAttachmentPermissions()
            advanceUntilIdle()
            fixture.automaticPolicyBlocks.set(1)
            // The network changes while the request is in flight: approval is revoked and re-applied.
            fixture.onAutomaticPolicyBlock = { fixture.state.refreshNativeAttachmentPermissions() }

            val download = async { durable(AttachmentDownloadPriority.Automatic) }
            fixture.entered.receive().succeed(PAYLOAD)

            assertTrue(download.await())
            assertEquals(2, fixture.automaticDemands.get())
            assertEquals(2, fixture.permissionApplications.get())
        }

    /** A refusal under an unchanged approval is MDK's own answer, such as a size cap, and is not asked again. */
    @Test
    fun policyBlockUnderAnUnchangedApprovalIsFinal() =
        runTest(dispatcher) {
            setNetwork(setOf(MediaAutoDownloadNetwork.WiFi))
            fixture.state.refreshNativeAttachmentPermissions()
            advanceUntilIdle()
            fixture.automaticPolicyBlocks.set(2)

            val failure = runCatching { durable(AttachmentDownloadPriority.Automatic) }.exceptionOrNull()

            assertTrue("$failure", failure is NativeAttachmentTerminalException)
            assertEquals(
                AttachmentTransferStateFfi.POLICY_BLOCKED,
                (failure as NativeAttachmentTerminalException).state,
            )
            assertFalse(isTransientAttachmentDownloadFailure(failure))
            assertEquals(1, fixture.automaticDemands.get())
        }

    /** An approval that never lands bounds the wait, asks for a fresh approval, and reports a retryable failure. */
    @Test
    fun approvalThatNeverLandsEndsTheWaitAsPendingPermission() =
        runTest(dispatcher) {
            setNetwork(setOf(MediaAutoDownloadNetwork.WiFi))
            val download = async { runCatching { durable(AttachmentDownloadPriority.Automatic) }.exceptionOrNull() }
            advanceTimeBy(NATIVE_ATTACHMENT_PERMISSION_SETTLE_MILLIS + 1)
            advanceUntilIdle()

            assertTrue("${download.await()}", download.await() is AttachmentPermissionPendingException)
            assertEquals(0, fixture.automaticDemands.get())
            assertEquals(1, fixture.permissionApplications.get())
        }

    /**
     * A transfer MDK paused because its type was turned off mid-download frees its download slot, and the
     * work then finishes as deferred instead of holding the slot until the platform stops it.
     */
    @Test
    fun transferPausedAfterItsTypeWasTurnedOffFreesItsSlot() =
        runTest(dispatcher) {
            setNetwork(setOf(MediaAutoDownloadNetwork.WiFi))
            fixture.state.refreshNativeAttachmentPermissions()
            advanceUntilIdle()
            val download = async { runCatching { durable(AttachmentDownloadPriority.Automatic) }.exceptionOrNull() }
            val call = fixture.entered.receive()

            persistMatrix(VIDEO_ON_WIFI)
            call.pause()
            val failure = download.await()

            assertTrue("$failure", failure is AttachmentAutomaticPolicyDeferredException)
            assertEquals(AutomaticAttachmentHold.Disabled, (failure as AttachmentAutomaticPolicyDeferredException).hold)
            assertFalse(fixture.state.hasActiveAttachmentAcquisition(qualifiedRequest(0).cacheKey()))
            assertEquals(1, fixture.automaticDemands.get())
        }

    /** A pause that outlasts the grace while the type is still allowed is asked once more, then retried later. */
    @Test
    fun lastingPauseWhileStillAllowedBecomesPendingPermission() =
        runTest(dispatcher) {
            setNetwork(setOf(MediaAutoDownloadNetwork.WiFi))
            fixture.state.refreshNativeAttachmentPermissions()
            advanceUntilIdle()
            val download = async { runCatching { durable(AttachmentDownloadPriority.Automatic) }.exceptionOrNull() }
            fixture.entered.receive().pause()

            val failure = download.await()

            assertTrue("$failure", failure is AttachmentPermissionPendingException)
            assertEquals(2, fixture.automaticDemands.get())
            assertFalse(fixture.state.hasActiveAttachmentAcquisition(qualifiedRequest(0).cacheKey()))
        }

    /** A bubble shown while durable work is fetching joins that work instead of starting a second transfer. */
    @Test
    fun bubbleJoinsInFlightDurableWork() =
        runTest(dispatcher) {
            setNetwork(setOf(MediaAutoDownloadNetwork.WiFi))
            fixture.state.refreshNativeAttachmentPermissions()
            advanceUntilIdle()
            val download = async { durable(AttachmentDownloadPriority.Automatic) }
            val call = fixture.entered.receive()
            val bubble =
                async {
                    fixture.state
                        .downloadAttachmentPlaintextSource(
                            request = qualifiedRequest(0),
                            reference = reference(0),
                            priority = AttachmentDownloadPriority.Automatic,
                        ).use { }
                }
            call.succeed(PAYLOAD)

            assertTrue(download.await())
            bubble.await()
            assertEquals(1, fixture.calls.size)
            assertEquals(1, fixture.automaticDemands.get())
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
    private suspend fun durable(
        priority: AttachmentDownloadPriority,
        account: String = ACCOUNT,
    ): Boolean {
        val request = qualifiedRequest(0).copy(accountRef = account)
        return fixture.state.downloadAttachmentForDurableWork(request, priority)
    }

    /** The fixture state's permission coordinator, which owns the approval wait. */
    private fun permissions(): NativeAttachmentPermissions =
        WhiteNoiseAppState::class.java
            .getDeclaredField("nativeAttachmentPermissions")
            .apply { isAccessible = true }
            .get(fixture.state) as NativeAttachmentPermissions

    /** The fixture state's settings-commit lock, which a permission evaluation waits on. */
    private fun hostPreferenceCommits(): kotlinx.coroutines.sync.Mutex =
        WhiteNoiseAppState::class.java
            .getDeclaredField("hostPreferenceCommitMutex")
            .apply { isAccessible = true }
            .get(fixture.state) as kotlinx.coroutines.sync.Mutex

    /** The fixture state's durable intent store, which records a stopped backlog. */
    private fun intents(): AttachmentDownloadIntentStore =
        WhiteNoiseAppState::class.java
            .getDeclaredField("attachmentDownloadIntents")
            .apply { isAccessible = true }
            .get(fixture.state) as AttachmentDownloadIntentStore

    /**
     * Asserts the durable entry point defers [type] for [hold] without demanding anything, and waits for
     * [networkType] when one is given.
     */
    private suspend fun expectDeferred(
        type: MediaAutoDownloadType?,
        hold: AutomaticAttachmentHold,
        account: String = ACCOUNT,
        networkType: NetworkType? = null,
    ) {
        try {
            durable(AttachmentDownloadPriority.Automatic, account)
            fail("automatic work ran against the live policy")
        } catch (deferred: AttachmentAutomaticPolicyDeferredException) {
            assertEquals(type, deferred.type)
            assertEquals(hold, deferred.hold)
            networkType?.let { assertEquals(it, deferred.networkType) }
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

    /** Asks for a coalesced network-triggered permission refresh, as a network callback does. */
    private fun requestNetworkRefresh() {
        WhiteNoiseAppState::class.java
            .getDeclaredMethod("requestNetworkPermissionRefresh")
            .apply { isAccessible = true }
            .invoke(fixture.state)
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
        val VIDEO_ON_WIFI =
            MediaAutoDownloadMatrix(emptySet()).withToggle(
                MediaAutoDownloadType.Video,
                MediaAutoDownloadNetwork.WiFi,
                on = true,
            )
    }
}
