package dev.ipf.whitenoise.android.updates

import android.content.Context
import dev.ipf.whitenoise.android.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

@Suppress("TooManyFunctions") // Interface actions and cancellation helpers share one operation owner.
internal class ZapstoreAppSelfUpdateFlow(
    private val appContext: Context,
    private val client: ZapstoreReleaseClient = ZapstoreReleaseClient(),
    private val downloader: AppSelfUpdateDownloader = AppSelfUpdateDownloader(),
    private val primaryAbiProvider: () -> String =
        {
            android.os.Build.SUPPORTED_ABIS
                .firstOrNull()
                .orEmpty()
        },
    private val resolveAsset: suspend (version: String, platformId: String) -> ZapstoreApkAsset? =
        { version, platformId ->
            ZapstoreApkAssetResolver.resolveApkAsset(client, version, platformId)
        },
    private val verifyPackage: suspend (apkFile: File, expectedVersion: String) -> Boolean = { file, version ->
        withContext(Dispatchers.IO) { AppSelfUpdateInstaller.isTrustedUpdatePackage(appContext, file, version) }
    },
) : AppSelfUpdateFlow {
    override var state: AppSelfUpdateState = AppSelfUpdateState.Idle
        private set

    private var activeJob: Job? = null
    private val operationMutex = Mutex()
    private var verifiedApkFile: File? = null
    private var operationGeneration = 0L

    /** Resolve a publisher-verified asset only after the preceding operation has finished cleanup. */
    override fun start(
        scope: CoroutineScope,
        version: String,
        onStateChanged: (AppSelfUpdateState) -> Unit,
    ) {
        cancel(deleteVerifiedApk = true)
        val generation = operationGeneration
        launchOperation(scope, generation, AppSelfUpdateState.Resolving, onStateChanged) {
            runCatching {
                val primaryAbi = primaryAbiProvider()
                if (!AndroidAbi.isSupportedPrimaryAbi(primaryAbi)) {
                    fail(R.string.app_self_update_no_asset, retryable = true, onStateChanged)
                    return@launchOperation
                }
                val platformId = AndroidAbi.platformIdForPrimaryAbi(primaryAbi)
                val asset = resolveAsset(version, platformId)
                ensureCurrentOperation(generation)
                if (asset == null) {
                    fail(R.string.app_self_update_no_asset, retryable = true, onStateChanged)
                    return@launchOperation
                }
                transition(AppSelfUpdateState.Confirming(asset), onStateChanged)
            }.onFailure { error ->
                if (error is CancellationException) throw error
                ensureCurrentOperation(generation)
                fail(R.string.app_self_update_resolve_failed, retryable = true, onStateChanged)
            }
        }
    }

    /** Keeps checksum and package trust inside one owned operation; ready states require both checks. */
    override fun confirmDownload(
        scope: CoroutineScope,
        onStateChanged: (AppSelfUpdateState) -> Unit,
    ) {
        val asset = (state as? AppSelfUpdateState.Confirming)?.asset ?: return
        cancel(deleteVerifiedApk = true)
        val generation = operationGeneration
        val destination = AppSelfUpdateStorage.apkFileForVersion(appContext, asset.version)
        launchOperation(
            scope,
            generation,
            AppSelfUpdateState.Downloading(asset, 0L, asset.sizeBytes),
            onStateChanged,
        ) {
            try {
                downloadAndVerify(asset, destination, generation, onStateChanged)
            } finally {
                if (verifiedApkFile != destination) AppSelfUpdateStorage.deleteFile(destination)
            }
        }
    }

    /**
     * Register ownership before callbacks can cancel or replace it. The mutex holds through cleanup,
     * so canceling any number of waiting replacements cannot bypass a noncooperative verifier.
     */
    @Suppress("TooGenericExceptionCaught") // Cancel registered work before propagating any callback failure.
    private fun launchOperation(
        scope: CoroutineScope,
        generation: Long,
        initialState: AppSelfUpdateState,
        onStateChanged: (AppSelfUpdateState) -> Unit,
        operation: suspend () -> Unit,
    ) {
        val job =
            scope.launch(start = CoroutineStart.LAZY) {
                operationMutex.withLock {
                    ensureCurrentOperation(generation)
                    operation()
                }
            }
        activeJob = job
        try {
            transition(initialState, onStateChanged)
        } catch (error: Throwable) {
            job.cancel()
            throw error
        }
        job.start()
    }

    /** The downloader announces the actual digest boundary; package verification extends that phase. */
    private suspend fun downloadAndVerify(
        asset: ZapstoreApkAsset,
        destination: File,
        generation: Long,
        onStateChanged: (AppSelfUpdateState) -> Unit,
    ) {
        val result =
            downloader.downloadVerifiedApk(
                asset = asset,
                destination = destination,
                onVerificationStarted = {
                    transitionOperation(AppSelfUpdateState.Verifying(asset), generation, onStateChanged)
                },
                onProgress = { bytesRead, totalBytes ->
                    transitionOperation(
                        AppSelfUpdateState.Downloading(asset, bytesRead, totalBytes ?: asset.sizeBytes),
                        generation,
                        onStateChanged,
                    )
                },
            )
        ensureCurrentOperation(generation)
        val failure = result.exceptionOrNull()
        if (failure != null) {
            if (failure is CancellationException) throw failure
            fail(
                if (failure is AppSelfUpdateDownloader.HashMismatchException) {
                    R.string.app_self_update_hash_mismatch
                } else {
                    R.string.app_self_update_download_failed
                },
                retryable = true,
                onStateChanged,
            )
            return
        }
        val trusted =
            try {
                verifyPackage(destination, asset.version)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                false
            }
        ensureCurrentOperation(generation)
        if (!trusted) {
            fail(R.string.app_self_update_install_failed, retryable = true, onStateChanged)
            return
        }
        verifiedApkFile = destination
        val next =
            if (AppSelfUpdateInstaller.canRequestPackageInstalls(appContext)) {
                AppSelfUpdateState.Verified(asset, destination)
            } else {
                AppSelfUpdateState.PermissionRequired(asset, destination)
            }
        transitionOperation(next, generation, onStateChanged)
    }

    /** Dispatches UI state on Main and rejects canceled/replaced operations before and after callbacks. */
    private suspend fun transitionOperation(
        next: AppSelfUpdateState,
        generation: Long,
        onStateChanged: (AppSelfUpdateState) -> Unit,
    ) = withContext(Dispatchers.Main.immediate) {
        ensureCurrentOperation(generation)
        transition(next, onStateChanged)
        ensureCurrentOperation(generation)
    }

    /** Blocking native work may return after cancellation; it cannot publish a late trusted/permission result. */
    private suspend fun ensureCurrentOperation(generation: Long) {
        currentCoroutineContext().ensureActive()
        if (generation != operationGeneration) throw CancellationException("Update operation replaced")
    }

    /** Restart release resolution only for an explicitly retryable failure. */
    override fun retry(
        scope: CoroutineScope,
        version: String,
        onStateChanged: (AppSelfUpdateState) -> Unit,
    ) {
        when (val current = state) {
            is AppSelfUpdateState.Error -> {
                if (current.retryable) start(scope, version, onStateChanged)
            }
            else -> Unit
        }
    }

    /** Invalidate callbacks immediately; the operation mutex retains file ownership until cleanup finishes. */
    override fun cancel(
        deleteVerifiedApk: Boolean,
        onStateChanged: ((AppSelfUpdateState) -> Unit)?,
    ) {
        operationGeneration += 1
        activeJob?.cancel()
        activeJob = null
        if (deleteVerifiedApk) {
            AppSelfUpdateStorage.deleteFile(verifiedApkFile)
            verifiedApkFile = null
        }
        transition(AppSelfUpdateState.Idle, onStateChanged)
    }

    /** Recheck Android permission for an already verified APK without bypassing package trust. */
    override fun refreshInstallPermission(onStateChanged: (AppSelfUpdateState) -> Unit) {
        when (val current = state) {
            is AppSelfUpdateState.PermissionRequired -> {
                if (AppSelfUpdateInstaller.canRequestPackageInstalls(appContext)) {
                    transition(
                        AppSelfUpdateState.Verified(asset = current.asset, apkFile = current.apkFile),
                        onStateChanged,
                    )
                }
            }
            else -> Unit
        }
    }

    /** Revalidate package trust immediately before handing the verified file to Android’s installer. */
    override fun launchInstall(
        context: Context,
        onStateChanged: (AppSelfUpdateState) -> Unit,
    ): Boolean {
        val (asset, apkFile) =
            when (val current = state) {
                is AppSelfUpdateState.Verified -> current.asset to current.apkFile
                is AppSelfUpdateState.PermissionRequired -> {
                    if (!AppSelfUpdateInstaller.canRequestPackageInstalls(appContext)) {
                        return false
                    }
                    current.asset to current.apkFile
                }
                else -> return false
            }
        if (!AppSelfUpdateInstaller.canRequestPackageInstalls(appContext)) {
            transition(
                AppSelfUpdateState.PermissionRequired(asset = asset, apkFile = apkFile),
                onStateChanged,
            )
            return false
        }
        if (!AppSelfUpdateInstaller.isTrustedUpdatePackage(appContext, apkFile, asset.version)) {
            fail(R.string.app_self_update_install_failed, retryable = true, onStateChanged)
            AppSelfUpdateStorage.deleteFile(apkFile)
            verifiedApkFile = null
            return false
        }
        val launched = AppSelfUpdateInstaller.launchInstall(context, apkFile)
        if (!launched) {
            fail(R.string.app_self_update_install_failed, retryable = true, onStateChanged)
            AppSelfUpdateStorage.deleteFile(apkFile)
            verifiedApkFile = null
            return false
        }
        transition(AppSelfUpdateState.Idle, onStateChanged)
        return true
    }

    /** Open this application’s unknown-source permission settings; no installer action is implied. */
    override fun openInstallPermissionSettings(context: Context) {
        runCatching { context.startActivity(AppSelfUpdateInstaller.installPermissionSettingsIntent(context)) }
    }

    /** Delegate old APK and abandoned-partial cleanup to the existing storage policy. */
    override fun sweepStaleApks() {
        AppSelfUpdateStorage.sweepStaleApks(appContext)
    }

    /** Remove retained update files and report the existing typed recovery category. */
    private fun fail(
        messageRes: Int,
        retryable: Boolean,
        onStateChanged: (AppSelfUpdateState) -> Unit,
    ) {
        AppSelfUpdateStorage.deleteFile(verifiedApkFile)
        verifiedApkFile = null
        AppSelfUpdateStorage.sweepStaleApks(appContext)
        transition(AppSelfUpdateState.Error(messageRes = messageRes, retryable = retryable), onStateChanged)
    }

    /** Publish a caller-owned phase; asynchronous operations must first pass their generation guard. */
    private fun transition(
        next: AppSelfUpdateState,
        onStateChanged: ((AppSelfUpdateState) -> Unit)?,
    ) {
        state = next
        onStateChanged?.invoke(next)
    }
}
