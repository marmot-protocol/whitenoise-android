package dev.ipf.darkmatter.updates

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ZapstoreAppSelfUpdateFlowTest {
    private lateinit var context: Context
    private val states = mutableListOf<AppSelfUpdateState>()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        states.clear()
        AppSelfUpdateStorage.updatesDirectory(context).listFiles()?.forEach { it.delete() }
    }

    @Test
    fun happyPathResolvesDownloadsAndInstalls() =
        runBlocking {
            val apkBytes = byteArrayOf(0x50, 0x4b, 0x03, 0x04)
            val asset = sampleAsset(sha256Hex = sha256(apkBytes).toHex(), sizeBytes = apkBytes.size.toLong())
            var installed = false
            val flow =
                createFlow(
                    resolveAsset = { _, _, _ -> asset },
                    downloadVerifiedApk = downloadToFile(apkBytes),
                    launchInstallIntent = { _, _ ->
                        installed = true
                        true
                    },
                )

            resolveAndDownload(flow, this)
            assertTrue(flow.state is AppSelfUpdateState.Verified)
            assertTrue(flow.launchInstall(context) { record(it) })
            assertTrue(installed)
            assertEquals(AppSelfUpdateState.Idle, flow.state)
        }

    @Test
    fun deniedInstallPermissionSurvivesSettingsRoundTripThenInstalls() =
        runBlocking {
            var canInstall = false
            var installerCalls = 0
            val apkBytes = byteArrayOf(0x01, 0x02, 0x03)
            val asset = sampleAsset(sha256Hex = sha256(apkBytes).toHex(), sizeBytes = apkBytes.size.toLong())
            val flow =
                createFlow(
                    resolveAsset = { _, _, _ -> asset },
                    downloadVerifiedApk = downloadToFile(apkBytes),
                    canRequestPackageInstalls = { canInstall },
                    launchInstallIntent = { _, _ ->
                        installerCalls++
                        true
                    },
                )

            resolveAndDownload(flow, this)
            assertTrue(flow.state is AppSelfUpdateState.PermissionRequired)
            assertFalse(flow.launchInstall(context) { record(it) })
            assertEquals(0, installerCalls)

            val permissionState = flow.state
            flow.onBackground { record(it) }
            assertEquals(permissionState, flow.state)

            canInstall = true
            flow.refreshInstallPermission { record(it) }
            assertTrue(flow.state is AppSelfUpdateState.Verified)
            assertTrue(flow.launchInstall(context) { record(it) })
            assertEquals(1, installerCalls)
        }

    @Test
    fun launchInstallRejectsMissingOrSameLengthTamperedCachedApk() =
        runBlocking {
            val apkBytes = byteArrayOf(0x0a, 0x0b, 0x0c, 0x0d)
            val asset = sampleAsset(sha256Hex = sha256(apkBytes).toHex(), sizeBytes = apkBytes.size.toLong())

            val missingFlow = createFlow(resolveAsset = { _, _, _ -> asset }, downloadVerifiedApk = downloadToFile(apkBytes))
            resolveAndDownload(missingFlow, this)
            val missingFile = (missingFlow.state as AppSelfUpdateState.Verified).apkFile
            missingFile.delete()
            assertFalse(missingFlow.launchInstall(context) { record(it) })
            assertTrue(missingFlow.state is AppSelfUpdateState.Error)

            val tamperedFlow = createFlow(resolveAsset = { _, _, _ -> asset }, downloadVerifiedApk = downloadToFile(apkBytes))
            resolveAndDownload(tamperedFlow, this)
            val tamperedFile = (tamperedFlow.state as AppSelfUpdateState.Verified).apkFile
            tamperedFile.writeBytes(byteArrayOf(0x0a, 0x0b, 0x0c, 0x0e))
            assertFalse(tamperedFlow.launchInstall(context) { record(it) })
            assertTrue(tamperedFlow.state is AppSelfUpdateState.Error)
            assertFalse(tamperedFile.exists())
        }

    @Test
    fun cancelThenImmediateRetryUsesDistinctFilesAndDeletesStaleCompletion() =
        runBlocking {
            val apkBytes = byteArrayOf(0x11, 0x22, 0x33, 0x44)
            val asset = sampleAsset(sha256Hex = sha256(apkBytes).toHex(), sizeBytes = apkBytes.size.toLong())
            val firstEntered = CompletableDeferred<Unit>()
            val releaseFirst = CompletableDeferred<Unit>()
            val destinations = mutableListOf<File>()
            var downloadCount = 0
            val flow =
                createFlow(
                    resolveAsset = { _, _, _ -> asset },
                    downloadVerifiedApk = { _, destination, onProgress, _ ->
                        downloadCount++
                        destinations += destination
                        if (downloadCount == 1) {
                            firstEntered.complete(Unit)
                            onProgress(1, apkBytes.size.toLong())
                            try {
                                releaseFirst.await()
                            } catch (_: CancellationException) {
                                withContext(NonCancellable) { releaseFirst.await() }
                            }
                        }
                        destination.parentFile?.mkdirs()
                        destination.writeBytes(apkBytes)
                        Result.success(Unit)
                    },
                )

            flow.start(this, VERSION) { record(it) }
            awaitUntil(flow) { it is AppSelfUpdateState.Confirming }
            flow.confirmDownload(this) { record(it) }
            firstEntered.await()
            flow.cancel(deleteVerifiedApk = true) { record(it) }
            flow.start(this, VERSION) { record(it) }
            awaitUntil(flow) { it is AppSelfUpdateState.Confirming }
            flow.confirmDownload(this) { record(it) }
            awaitUntil(flow) { it is AppSelfUpdateState.Verified }
            val retryFile = (flow.state as AppSelfUpdateState.Verified).apkFile

            releaseFirst.complete(Unit)
            repeat(10) { yield() }

            assertEquals(2, downloadCount)
            assertEquals(2, destinations.size)
            assertNotEquals(destinations[0], destinations[1])
            assertFalse(destinations[0].exists())
            assertEquals(retryFile, destinations[1])
            assertTrue(retryFile.isFile)
            assertTrue(flow.state is AppSelfUpdateState.Verified)
        }

    @Test
    fun backgroundCancelsResolvingAndDownloadingButPreservesPermissionState() =
        runBlocking {
            val resolveEntered = CompletableDeferred<Unit>()
            val resolveHang = CompletableDeferred<Unit>()
            val resolvingFlow =
                createFlow(
                    resolveAsset = { _, _, _ ->
                        resolveEntered.complete(Unit)
                        resolveHang.await()
                        sampleAsset()
                    },
                )
            resolvingFlow.start(this, VERSION) { record(it) }
            resolveEntered.await()
            resolvingFlow.onBackground { record(it) }
            assertEquals(AppSelfUpdateState.Idle, resolvingFlow.state)

            val apkBytes = byteArrayOf(0x21, 0x22)
            val asset = sampleAsset(sha256Hex = sha256(apkBytes).toHex(), sizeBytes = apkBytes.size.toLong())
            val downloadEntered = CompletableDeferred<Unit>()
            val downloadHang = CompletableDeferred<Unit>()
            val downloadingFlow =
                createFlow(
                    resolveAsset = { _, _, _ -> asset },
                    downloadVerifiedApk = { _, _, onProgress, _ ->
                        downloadEntered.complete(Unit)
                        onProgress(1, apkBytes.size.toLong())
                        downloadHang.await()
                        Result.success(Unit)
                    },
                )
            downloadingFlow.start(this, VERSION) { record(it) }
            awaitUntil(downloadingFlow) { it is AppSelfUpdateState.Confirming }
            downloadingFlow.confirmDownload(this) { record(it) }
            downloadEntered.await()
            downloadingFlow.onBackground { record(it) }
            assertEquals(AppSelfUpdateState.Idle, downloadingFlow.state)

            val permissionFlow =
                createFlow(
                    resolveAsset = { _, _, _ -> asset },
                    downloadVerifiedApk = downloadToFile(apkBytes),
                    canRequestPackageInstalls = { false },
                )
            resolveAndDownload(permissionFlow, this)
            val permissionState = permissionFlow.state
            assertTrue(permissionState is AppSelfUpdateState.PermissionRequired)
            permissionFlow.onBackground { record(it) }
            assertEquals(permissionState, permissionFlow.state)
        }

    @Test
    fun backgroundDuringVerificationPreventsStaleInstallerLaunch() =
        runBlocking {
            val apkBytes = byteArrayOf(0x31, 0x32, 0x33)
            val asset = sampleAsset(sha256Hex = sha256(apkBytes).toHex(), sizeBytes = apkBytes.size.toLong())
            val verifyEntered = CompletableDeferred<Unit>()
            val releaseVerify = CompletableDeferred<Unit>()
            var installerCalls = 0
            val flow =
                createFlow(
                    resolveAsset = { _, _, _ -> asset },
                    downloadVerifiedApk = downloadToFile(apkBytes),
                    verifyCachedApk = { _, _, _, _ ->
                        verifyEntered.complete(Unit)
                        releaseVerify.await()
                        true
                    },
                    launchInstallIntent = { _, _ ->
                        installerCalls++
                        true
                    },
                )
            resolveAndDownload(flow, this)

            val install = async { flow.launchInstall(context) { record(it) } }
            verifyEntered.await()
            flow.onBackground { record(it) }
            releaseVerify.complete(Unit)

            assertFalse(install.await())
            assertEquals(0, installerCalls)
            assertEquals(AppSelfUpdateState.Idle, flow.state)
        }

    @Test
    fun installerHandoffIsSingleFlight() =
        runBlocking {
            val apkBytes = byteArrayOf(0x41, 0x42, 0x43)
            val asset = sampleAsset(sha256Hex = sha256(apkBytes).toHex(), sizeBytes = apkBytes.size.toLong())
            val verifyEntered = CompletableDeferred<Unit>()
            val releaseVerify = CompletableDeferred<Unit>()
            var installerCalls = 0
            val flow =
                createFlow(
                    resolveAsset = { _, _, _ -> asset },
                    downloadVerifiedApk = downloadToFile(apkBytes),
                    verifyCachedApk = { _, _, _, _ ->
                        verifyEntered.complete(Unit)
                        releaseVerify.await()
                        true
                    },
                    launchInstallIntent = { _, _ ->
                        installerCalls++
                        true
                    },
                )
            resolveAndDownload(flow, this)

            val first = async { flow.launchInstall(context) { record(it) } }
            verifyEntered.await()
            assertFalse(flow.launchInstall(context) { record(it) })
            releaseVerify.complete(Unit)

            assertTrue(first.await())
            assertEquals(1, installerCalls)
        }

    @Test
    fun lateProgressCannotResurrectDownloadingAfterVerification() =
        runBlocking {
            val apkBytes = byteArrayOf(0x51, 0x52, 0x53)
            val asset = sampleAsset(sha256Hex = sha256(apkBytes).toHex(), sizeBytes = apkBytes.size.toLong())
            var emitLateProgress: (() -> Unit)? = null
            val flow =
                createFlow(
                    resolveAsset = { _, _, _ -> asset },
                    downloadVerifiedApk = { _, destination, onProgress, _ ->
                        destination.parentFile?.mkdirs()
                        destination.writeBytes(apkBytes)
                        emitLateProgress = { onProgress(1L, apkBytes.size.toLong()) }
                        Result.success(Unit)
                    },
                )
            resolveAndDownload(flow, this)
            assertTrue(flow.state is AppSelfUpdateState.Verified)

            emitLateProgress?.invoke()
            repeat(5) { yield() }

            assertTrue(flow.state is AppSelfUpdateState.Verified)
        }

    @Test
    fun callOwnerCancelsCallsRegisteredBeforeOrAfterCancellation() {
        val client = OkHttpClient()
        val first = client.newCall(Request.Builder().url("https://example.com/first").build())
        val firstOwner = DownloadCallOwner()
        firstOwner.register(first)
        firstOwner.cancel()
        assertTrue(first.isCanceled())

        val second = client.newCall(Request.Builder().url("https://example.com/second").build())
        val secondOwner = DownloadCallOwner()
        secondOwner.cancel()
        secondOwner.register(second)
        assertTrue(second.isCanceled())
    }

    private suspend fun resolveAndDownload(
        flow: ZapstoreAppSelfUpdateFlow,
        scope: kotlinx.coroutines.CoroutineScope,
    ) {
        flow.start(scope, VERSION) { record(it) }
        awaitUntil(flow) { it is AppSelfUpdateState.Confirming }
        flow.confirmDownload(scope) { record(it) }
        awaitUntil(flow) { it is AppSelfUpdateState.Verified || it is AppSelfUpdateState.PermissionRequired }
    }

    private fun createFlow(
        resolveAsset: suspend (ZapstoreReleaseClient, String, String) -> ZapstoreApkAsset? =
            { _, _, _ -> sampleAsset() },
        downloadVerifiedApk: suspend (
            ZapstoreApkAsset,
            File,
            (Long, Long?) -> Unit,
            (Call) -> Unit,
        ) -> Result<Unit> = downloadToFile(byteArrayOf(1, 2, 3)),
        canRequestPackageInstalls: (Context) -> Boolean = { true },
        launchInstallIntent: (Context, File) -> Boolean = { _, _ -> true },
        verifyCachedApk: suspend (Context, File, ZapstoreApkAsset, kotlinx.coroutines.CoroutineDispatcher) -> Boolean =
            { _, apkFile, asset, dispatcher ->
                AppSelfUpdateVerifier.verifyCachedApk(apkFile, asset, dispatcher)
            },
    ): ZapstoreAppSelfUpdateFlow =
        ZapstoreAppSelfUpdateFlow(
            appContext = context,
            primaryAbiProvider = { "arm64-v8a" },
            mainDispatcher = Dispatchers.Unconfined,
            ioDispatcher = Dispatchers.Unconfined,
            canRequestPackageInstalls = canRequestPackageInstalls,
            launchInstallIntent = launchInstallIntent,
            verifyCachedApk = verifyCachedApk,
            resolveAsset = resolveAsset,
            downloadVerifiedApk = downloadVerifiedApk,
        )

    private fun downloadToFile(
        bytes: ByteArray,
    ): suspend (
        ZapstoreApkAsset,
        File,
        (Long, Long?) -> Unit,
        (Call) -> Unit,
    ) -> Result<Unit> =
        { _, destination, _, _ ->
            destination.parentFile?.mkdirs()
            destination.writeBytes(bytes)
            Result.success(Unit)
        }

    private fun sampleAsset(
        sha256Hex: String = sha256(byteArrayOf(1, 2, 3)).toHex(),
        sizeBytes: Long? = 3L,
    ): ZapstoreApkAsset =
        ZapstoreApkAsset(
            eventId = "a".repeat(64),
            appId = APP_ID,
            version = VERSION,
            sha256Hex = sha256Hex,
            downloadUrl = "https://cdn.example.com/app.apk",
            sizeBytes = sizeBytes,
            platformIds = setOf(PLATFORM_ID),
        )

    private fun record(state: AppSelfUpdateState) {
        states += state
    }

    private suspend fun awaitUntil(
        flow: ZapstoreAppSelfUpdateFlow,
        predicate: (AppSelfUpdateState) -> Boolean,
    ) {
        repeat(100) {
            if (predicate(flow.state)) return
            yield()
        }
        error("Timed out waiting for state, still ${flow.state}")
    }

    private companion object {
        private const val APP_ID = "org.parres.darkmatter"
        private const val VERSION = "2026.6.99"
        private const val PLATFORM_ID = "android-arm64-v8a"
    }
}
