package dev.ipf.whitenoise.android.updates

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.ipf.whitenoise.android.R
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

private const val VERIFICATION_TIMEOUT_MS = 5_000L
private const val SHA256_HEX_LENGTH = 64

/** Real downloader and flow ordering; injected package gates model a held native check, never UI timers. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
@Suppress("TooManyFunctions") // Keeps the operation-race cases beside their shared native-boundary fixture.
class ZapstoreVerificationFlowTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var scope: CoroutineScope
    private val states = CopyOnWriteArrayList<AppSelfUpdateState>()
    private val updates = Channel<AppSelfUpdateState>(Channel.UNLIMITED)
    private lateinit var flow: ZapstoreAppSelfUpdateFlow
    private val destination get() = AppSelfUpdateStorage.apkFileForVersion(context, verificationAsset().version)

    /** Use the real Android permission query with a controlled test Main dispatcher. */
    @Before fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        shadowOf(context.packageManager).setCanRequestPackageInstalls(true)
        AppSelfUpdateStorage.deleteFile(destination)
    }

    /** Stop owned work and remove only this test's candidate after each case. */
    @After fun tearDown() {
        scope.cancel()
        AppSelfUpdateStorage.deleteFile(destination)
        AppSelfUpdateStorage.deleteFile(File(destination.parentFile, "${destination.name}.part"))
        updates.close()
        Dispatchers.resetMain()
    }

    /** Byte completion and hash success are insufficient until the package checker returns trusted. */
    @Test fun verifyingPersistsUntilPackageTrustAndNeverOffersInstallEarly() =
        runBlocking {
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Boolean>()
            flow =
                createFlow { file, version ->
                    assertTrue(file.readBytes().contentEquals(VERIFICATION_BYTES))
                    assertEquals(verificationAsset().version, version)
                    entered.complete(Unit)
                    release.await()
                }
            startDownload()
            withTimeout(VERIFICATION_TIMEOUT_MS) { entered.await() }
            assertTrue(flow.state is AppSelfUpdateState.Verifying)
            assertFalse(flow.launchInstall(context, ::record))
            val becameReady =
                states.any {
                    it is AppSelfUpdateState.Verified || it is AppSelfUpdateState.PermissionRequired
                }
            assertFalse(becameReady)
            release.complete(true)
            awaitState { it is AppSelfUpdateState.Verified }
            assertOrder()
            assertTrue(destination.isFile)
        }

    /** Hash mismatch cannot call the package checker or reach installer permission. */
    @Test fun badHashRemainsTypedAndSkipsPackageCheck() =
        runBlocking {
            var packageCalls = 0
            flow =
                createFlow(asset = verificationAsset().copy(sha256Hex = "0".repeat(SHA256_HEX_LENGTH))) { _, _ ->
                    packageCalls++
                    true
                }
            startDownload()
            val error = awaitState { it is AppSelfUpdateState.Error } as AppSelfUpdateState.Error
            assertEquals(R.string.app_self_update_hash_mismatch, error.messageRes)
            assertEquals(0, packageCalls)
            joinOwnedWork()
            assertFalse(destination.exists())
            val becameReady =
                states.any {
                    it is AppSelfUpdateState.Verified || it is AppSelfUpdateState.PermissionRequired
                }
            assertFalse(becameReady)
        }

    /** The actual platform package parser rejects these non-APK bytes after their valid checksum. */
    @Test fun defaultPackageVerifierRejectsInvalidArchiveAfterVerifying() =
        runBlocking {
            flow = createFlow()
            startDownload()
            val error = awaitState { it is AppSelfUpdateState.Error } as AppSelfUpdateState.Error
            assertEquals(R.string.app_self_update_install_failed, error.messageRes)
            assertTrue(states.any { it is AppSelfUpdateState.Verifying })
            joinOwnedWork()
            assertFalse(destination.exists())
        }

    /** A noncooperative native completion cannot revive a canceled update or retain its APK. */
    @Test fun cancellationDuringPackageCheckRejectsLateTrustedResult() =
        runBlocking {
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            flow =
                createFlow { _, _ ->
                    withContext(NonCancellable) {
                        entered.complete(Unit)
                        release.await()
                        true
                    }
                }
            try {
                startDownload()
                withTimeout(VERIFICATION_TIMEOUT_MS) { entered.await() }
                flow.cancel(deleteVerifiedApk = true, onStateChanged = ::record)
                release.complete(Unit)
                joinOwnedWork()
                assertEquals(AppSelfUpdateState.Idle, flow.state)
                assertFalse(destination.exists())
                val becameReady =
                    states.any {
                        it is AppSelfUpdateState.Verified || it is AppSelfUpdateState.PermissionRequired
                    }
                assertFalse(becameReady)
            } finally {
                release.complete(Unit)
                scope.cancel()
                joinOwnedWork()
            }
        }

    /** Reopening the same version waits for canceled verification cleanup before reusing its filename. */
    @Test fun sameVersionRestartCannotLoseNewCandidateToOldCleanup() =
        runBlocking {
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            var calls = 0
            flow =
                createFlow { _, _ ->
                    if (++calls == 1) {
                        withContext(NonCancellable) {
                            entered.complete(Unit)
                            release.await()
                        }
                    }
                    true
                }
            try {
                startDownload()
                withTimeout(VERIFICATION_TIMEOUT_MS) { entered.await() }
                flow.start(scope, verificationAsset().version, ::record)
                assertEquals(AppSelfUpdateState.Resolving, flow.state)
                release.complete(Unit)
                awaitState { it is AppSelfUpdateState.Confirming }
                flow.confirmDownload(scope, ::record)
                awaitState { it is AppSelfUpdateState.Verified }
                joinOwnedWork()
                assertEquals(2, calls)
                assertTrue(destination.readBytes().contentEquals(VERIFICATION_BYTES))
            } finally {
                release.complete(Unit)
                scope.cancel()
                joinOwnedWork()
            }
        }

    /** Canceling a replacement waiting on A cannot let C reuse the candidate before A cleans up. */
    @Test fun repeatedReplacementCannotBypassCanceledVerifierCleanup() =
        runBlocking {
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val resolutions = AtomicInteger()
            val packageCalls = AtomicInteger()
            flow =
                createFlow(resolve = {
                    resolutions.incrementAndGet()
                    verificationAsset()
                }) { _, _ ->
                    if (packageCalls.incrementAndGet() == 1) {
                        withContext(NonCancellable) {
                            entered.complete(Unit)
                            release.await()
                        }
                    }
                    true
                }
            try {
                startDownload()
                withTimeout(VERIFICATION_TIMEOUT_MS) { entered.await() }
                flow.start(scope, verificationAsset().version, ::record)
                assertEquals(AppSelfUpdateState.Resolving, flow.state)
                flow.cancel(deleteVerifiedApk = true, onStateChanged = ::record)
                assertEquals(AppSelfUpdateState.Idle, flow.state)
                flow.start(scope, verificationAsset().version, ::record)
                assertEquals(AppSelfUpdateState.Resolving, flow.state)
                assertEquals(1, resolutions.get())
                assertEquals(1, packageCalls.get())
                release.complete(Unit)
                awaitState { it is AppSelfUpdateState.Confirming }
                flow.confirmDownload(scope, ::record)
                awaitState { it is AppSelfUpdateState.Verified }
                joinOwnedWork()
                assertEquals(2, resolutions.get())
                assertEquals(2, packageCalls.get())
                assertEquals(1, states.count { it is AppSelfUpdateState.Verified })
                assertTrue(destination.readBytes().contentEquals(VERIFICATION_BYTES))
            } finally {
                release.complete(Unit)
                scope.cancel()
                joinOwnedWork()
            }
        }

    /** Reentrant replacement during Resolving remains the owned job and is canceled immediately. */
    @Test fun resolvingCallbackReplacementCannotEscapeCancellation() =
        runBlocking {
            val replacementJob = CompletableDeferred<Job>()
            val hold = CompletableDeferred<Unit>()
            flow =
                createFlow(resolve = {
                    replacementJob.complete(currentCoroutineContext()[Job]!!)
                    hold.await()
                    verificationAsset()
                })
            flow.start(scope, verificationAsset().version) { state ->
                record(state)
                if (state is AppSelfUpdateState.Resolving) {
                    flow.start(scope, verificationAsset().version, ::record)
                }
            }
            val owned = withTimeout(VERIFICATION_TIMEOUT_MS) { replacementJob.await() }
            flow.cancel(deleteVerifiedApk = true, onStateChanged = ::record)
            assertTrue(owned.isCancelled)
            joinOwnedWork()
            assertEquals(AppSelfUpdateState.Idle, flow.state)
            assertFalse(states.any { it is AppSelfUpdateState.Confirming })
        }

    /** Reentrant replacement during the initial Downloading callback cannot be overwritten by that download. */
    @Test fun downloadingCallbackReplacementCannotEscapeCancellation() =
        runBlocking {
            val replacementJob = CompletableDeferred<Job>()
            val hold = CompletableDeferred<Unit>()
            var resolutions = 0
            flow =
                createFlow(resolve = {
                    if (++resolutions > 1) {
                        replacementJob.complete(currentCoroutineContext()[Job]!!)
                        hold.await()
                    }
                    verificationAsset()
                })
            flow.start(scope, verificationAsset().version, ::record)
            awaitState { it is AppSelfUpdateState.Confirming }
            flow.confirmDownload(scope) { state ->
                record(state)
                if (state is AppSelfUpdateState.Downloading) {
                    flow.start(scope, verificationAsset().version, ::record)
                }
            }
            val owned = withTimeout(VERIFICATION_TIMEOUT_MS) { replacementJob.await() }
            flow.cancel(deleteVerifiedApk = true, onStateChanged = ::record)
            assertTrue(owned.isCancelled)
            joinOwnedWork()
            assertEquals(AppSelfUpdateState.Idle, flow.state)
            assertFalse(destination.exists())
            assertFalse(states.any { it is AppSelfUpdateState.Verifying })
        }

    /** Unknown-source permission is requested only after both verification boundaries complete. */
    @Test fun permissionRequiredFollowsVerification() =
        runBlocking {
            shadowOf(context.packageManager).setCanRequestPackageInstalls(false)
            flow = createFlow { _, _ -> true }
            startDownload()
            awaitState { it is AppSelfUpdateState.PermissionRequired }
            assertOrder()
            assertFalse(flow.launchInstall(context, ::record))
        }

    /** Uses the real flow; event resolution is a deterministic owned asset and the HTTP body stays in-process. */
    private fun createFlow(
        asset: ZapstoreApkAsset = verificationAsset(),
        resolve: suspend () -> ZapstoreApkAsset? = { asset },
        verify: (suspend (File, String) -> Boolean)? = null,
    ): ZapstoreAppSelfUpdateFlow =
        if (verify == null) {
            ZapstoreAppSelfUpdateFlow(
                context,
                downloader = verificationDownloader(),
                primaryAbiProvider = { "arm64-v8a" },
                resolveAsset = { _, _ -> resolve() },
            )
        } else {
            ZapstoreAppSelfUpdateFlow(
                context,
                downloader = verificationDownloader(),
                primaryAbiProvider = { "arm64-v8a" },
                resolveAsset = { _, _ -> resolve() },
                verifyPackage = verify,
            )
        }

    /** Drive the actual resolving/confirmation commands; do not assign the flow state. */
    private suspend fun startDownload() {
        flow.start(scope, verificationAsset().version, ::record)
        awaitState { it is AppSelfUpdateState.Confirming }
        flow.confirmDownload(scope, ::record)
    }

    /** Capture the real callback sequence for boundary-order assertions. */
    private fun record(state: AppSelfUpdateState) {
        states += state
        updates.trySend(state)
    }

    /** Await a real callback with a bounded timeout, without sleeps or synthetic phase changes. */
    private suspend fun awaitState(matches: (AppSelfUpdateState) -> Boolean): AppSelfUpdateState =
        withTimeout(VERIFICATION_TIMEOUT_MS) {
            var next = updates.receive()
            while (!matches(next)) next = updates.receive()
            next
        }

    /** Cancellation assertions wait for the owning operation's filesystem cleanup. */
    private suspend fun joinOwnedWork() =
        withTimeout(VERIFICATION_TIMEOUT_MS) {
            scope.coroutineContext[Job]!!
                .children
                .toList()
                .joinAll()
        }

    /** Every install-eligible result follows byte progress and the explicit real verification phase. */
    private fun assertOrder() {
        val download = states.indexOfFirst { it is AppSelfUpdateState.Downloading }
        val verify = states.indexOfFirst { it is AppSelfUpdateState.Verifying }
        val ready =
            states.indexOfFirst {
                it is AppSelfUpdateState.Verified || it is AppSelfUpdateState.PermissionRequired
            }
        assertTrue(download >= 0 && verify > download && ready > verify)
    }
}
