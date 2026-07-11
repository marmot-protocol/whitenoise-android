package dev.ipf.darkmatter.updates

import android.content.Context
import dev.ipf.darkmatter.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.Call
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.EmptyCoroutineContext

internal class DownloadCallOwner {
    private val cancelled = AtomicBoolean(false)
    private val call = AtomicReference<Call?>(null)

    fun register(startedCall: Call) {
        if (cancelled.get() || !call.compareAndSet(null, startedCall)) {
            startedCall.cancel()
            return
        }
        if (cancelled.get() && call.compareAndSet(startedCall, null)) {
            startedCall.cancel()
        }
    }

    fun cancel() {
        cancelled.set(true)
        call.getAndSet(null)?.cancel()
    }
}

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
    private val mainDispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val canRequestPackageInstalls: (Context) -> Boolean = AppSelfUpdateInstaller::canRequestPackageInstalls,
    private val launchInstallIntent: (Context, File) -> Boolean = AppSelfUpdateInstaller::launchInstall,
    private val verifyCachedApk: suspend (Context, File, ZapstoreApkAsset, CoroutineDispatcher) -> Boolean =
        { context, apkFile, asset, dispatcher ->
            AppSelfUpdateVerifier.verifyInstallableApk(
                context = context,
                apkFile = apkFile,
                asset = asset,
                dispatcher = dispatcher,
            )
        },
    private val resolveAsset: suspend (ZapstoreReleaseClient, String, String) -> ZapstoreApkAsset? =
        { releaseClient, version, platformId ->
            ZapstoreApkAssetResolver.resolveApkAsset(
                client = releaseClient,
                version = version,
                platformId = platformId,
            )
        },
    private val downloadVerifiedApk: suspend (
        ZapstoreApkAsset,
        File,
        (Long, Long?) -> Unit,
        (Call) -> Unit,
    ) -> Result<Unit> = { asset, destination, onProgress, onCallStarted ->
        downloader.downloadVerifiedApk(
            asset = asset,
            destination = destination,
            onProgress = onProgress,
            onCallStarted = onCallStarted,
        )
    },
) : AppSelfUpdateFlow {
    override var state: AppSelfUpdateState = AppSelfUpdateState.Idle
        private set

    private val mainScope = CoroutineScope(SupervisorJob() + mainDispatcher)
    private var activeGeneration = 0L
    private var activeJob: Job? = null
    private var activeCallOwner: DownloadCallOwner? = null
    private var installGeneration: Long? = null
    private var verifiedApkFile: File? = null

    override fun start(
        scope: CoroutineScope,
        version: String,
        onStateChanged: (AppSelfUpdateState) -> Unit,
    ) {
        runOnMain {
            val generation = beginOperation(deleteVerifiedApk = true, onStateChanged)
            transition(AppSelfUpdateState.Resolving, onStateChanged)
            activeJob =
                scope.launch {
                    runCatching {
                        val primaryAbi = primaryAbiProvider()
                        if (!AndroidAbi.isSupportedPrimaryAbi(primaryAbi)) {
                            fail(generation, R.string.app_self_update_no_asset, retryable = true, onStateChanged)
                            return@launch
                        }
                        val platformId = AndroidAbi.platformIdForPrimaryAbi(primaryAbi)
                        val asset = resolveAsset(client, version, platformId)
                        if (asset == null) {
                            fail(generation, R.string.app_self_update_no_asset, retryable = true, onStateChanged)
                            return@launch
                        }
                        transitionIfCurrent(
                            generation,
                            AppSelfUpdateState.Confirming(asset),
                            onStateChanged,
                        )
                    }.onFailure { error ->
                        if (error is CancellationException) throw error
                        fail(generation, R.string.app_self_update_resolve_failed, retryable = true, onStateChanged)
                    }
                }
        }
    }

    override fun confirmDownload(
        scope: CoroutineScope,
        onStateChanged: (AppSelfUpdateState) -> Unit,
    ) {
        runOnMain {
            val asset = (state as? AppSelfUpdateState.Confirming)?.asset ?: return@runOnMain
            val generation = beginOperation(deleteVerifiedApk = true, onStateChanged)
            val destination = AppSelfUpdateStorage.apkFileForOperation(appContext, asset.version, generation)
            val callOwner = DownloadCallOwner()
            activeCallOwner = callOwner
            transition(
                AppSelfUpdateState.Downloading(asset = asset, bytesRead = 0L, totalBytes = asset.sizeBytes),
                onStateChanged,
            )
            activeJob =
                scope.launch {
                    val result =
                        downloadVerifiedApk(
                            asset,
                            destination,
                            { bytesRead, totalBytes ->
                                mainScope.launch {
                                    transitionDownloadProgressIfCurrent(
                                        generation,
                                        AppSelfUpdateState.Downloading(
                                            asset = asset,
                                            bytesRead = bytesRead,
                                            totalBytes = totalBytes ?: asset.sizeBytes,
                                        ),
                                        onStateChanged,
                                    )
                                }
                            },
                            callOwner::register,
                        )
                    if (generation != activeGeneration) {
                        AppSelfUpdateStorage.deleteFile(destination)
                        return@launch
                    }
                    if (activeCallOwner === callOwner) activeCallOwner = null
                    activeJob = null
                    result
                        .onSuccess {
                            verifiedApkFile = destination
                            val next =
                                if (canRequestPackageInstalls(appContext)) {
                                    AppSelfUpdateState.Verified(asset = asset, apkFile = destination)
                                } else {
                                    AppSelfUpdateState.PermissionRequired(asset = asset, apkFile = destination)
                                }
                            transitionIfCurrent(generation, next, onStateChanged)
                        }.onFailure { error ->
                            if (error is CancellationException) throw error
                            if (generation != activeGeneration) return@onFailure
                            val messageRes =
                                when (error) {
                                    is AppSelfUpdateDownloader.HashMismatchException ->
                                        R.string.app_self_update_hash_mismatch
                                    is AppSelfUpdateDownloader.DownloadSizeLimitExceededException ->
                                        R.string.app_self_update_download_failed
                                    else -> R.string.app_self_update_download_failed
                                }
                            fail(generation, messageRes, retryable = true, onStateChanged)
                        }
                }
        }
    }

    override fun retry(
        scope: CoroutineScope,
        version: String,
        onStateChanged: (AppSelfUpdateState) -> Unit,
    ) {
        runOnMain {
            when (val current = state) {
                is AppSelfUpdateState.Error -> {
                    if (current.retryable) start(scope, version, onStateChanged)
                }
                else -> Unit
            }
        }
    }

    override fun cancel(
        deleteVerifiedApk: Boolean,
        onStateChanged: ((AppSelfUpdateState) -> Unit)?,
    ) {
        runOnMain {
            cancelOperation(deleteVerifiedApk, onStateChanged)
        }
    }

    override fun refreshInstallPermission(onStateChanged: (AppSelfUpdateState) -> Unit) {
        runOnMain {
            when (val current = state) {
                is AppSelfUpdateState.PermissionRequired -> {
                    if (canRequestPackageInstalls(appContext)) {
                        transition(
                            AppSelfUpdateState.Verified(asset = current.asset, apkFile = current.apkFile),
                            onStateChanged,
                        )
                    }
                }
                else -> Unit
            }
        }
    }

    override suspend fun launchInstall(
        context: Context,
        onStateChanged: (AppSelfUpdateState) -> Unit,
    ): Boolean {
        val snapshot =
            withContext(mainDispatcher) {
                if (installGeneration != null) return@withContext null
                when (val current = state) {
                    is AppSelfUpdateState.Verified -> {
                        installGeneration = activeGeneration
                        InstallSnapshot(activeGeneration, current.asset, current.apkFile)
                    }
                    is AppSelfUpdateState.PermissionRequired -> {
                        if (!canRequestPackageInstalls(appContext)) return@withContext null
                        installGeneration = activeGeneration
                        InstallSnapshot(activeGeneration, current.asset, current.apkFile)
                    }
                    else -> return@withContext null
                }
            } ?: return false

        val asset = snapshot.asset
        val apkFile = snapshot.apkFile
        if (!canRequestPackageInstalls(appContext)) {
            withContext(mainDispatcher) {
                if (isCurrentInstall(snapshot)) {
                    installGeneration = null
                    transition(
                        AppSelfUpdateState.PermissionRequired(asset = asset, apkFile = apkFile),
                        onStateChanged,
                    )
                }
            }
            return false
        }

        val verified = runCatching { verifyCachedApk(context, apkFile, asset, ioDispatcher) }.getOrDefault(false)
        if (!verified) {
            withContext(mainDispatcher) {
                if (isCurrentInstall(snapshot)) {
                    installGeneration = null
                    AppSelfUpdateStorage.deleteFile(apkFile)
                    verifiedApkFile = null
                    fail(snapshot.generation, R.string.app_self_update_hash_mismatch, retryable = true, onStateChanged)
                }
            }
            return false
        }

        return withContext(mainDispatcher) {
            if (!isCurrentInstall(snapshot)) return@withContext false
            val launched = launchInstallIntent(context, apkFile)
            installGeneration = null
            if (!launched) {
                fail(snapshot.generation, R.string.app_self_update_install_failed, retryable = true, onStateChanged)
                AppSelfUpdateStorage.deleteFile(apkFile)
                verifiedApkFile = null
                return@withContext false
            }
            transition(AppSelfUpdateState.Idle, onStateChanged)
            true
        }
    }

    override fun openInstallPermissionSettings(context: Context) {
        runCatching { context.startActivity(AppSelfUpdateInstaller.installPermissionSettingsIntent(context)) }
    }

    override fun sweepStaleApks() {
        mainScope.launch(ioDispatcher) {
            AppSelfUpdateStorage.sweepStaleApks(appContext)
        }
    }

    override fun onBackground(onStateChanged: (AppSelfUpdateState) -> Unit) {
        runOnMain {
            when (state) {
                AppSelfUpdateState.Resolving,
                is AppSelfUpdateState.Downloading,
                -> cancelOperation(deleteVerifiedApk = true, onStateChanged)
                else -> if (installGeneration != null) cancelOperation(deleteVerifiedApk = true, onStateChanged)
            }
        }
    }

    private fun beginOperation(
        deleteVerifiedApk: Boolean,
        onStateChanged: ((AppSelfUpdateState) -> Unit)?,
    ): Long {
        activeGeneration++
        activeJob?.cancel()
        activeCallOwner?.cancel()
        activeCallOwner = null
        activeJob = null
        installGeneration = null
        if (deleteVerifiedApk) {
            AppSelfUpdateStorage.deleteFile(verifiedApkFile)
            verifiedApkFile = null
        }
        transition(AppSelfUpdateState.Idle, onStateChanged)
        return activeGeneration
    }

    private fun cancelOperation(
        deleteVerifiedApk: Boolean,
        onStateChanged: ((AppSelfUpdateState) -> Unit)?,
    ) {
        activeGeneration++
        activeJob?.cancel()
        activeCallOwner?.cancel()
        activeCallOwner = null
        activeJob = null
        installGeneration = null
        if (deleteVerifiedApk) {
            AppSelfUpdateStorage.deleteFile(verifiedApkFile)
            verifiedApkFile = null
        }
        transition(AppSelfUpdateState.Idle, onStateChanged)
    }

    private fun fail(
        generation: Long,
        messageRes: Int,
        retryable: Boolean,
        onStateChanged: (AppSelfUpdateState) -> Unit,
    ) {
        if (generation != activeGeneration) return
        AppSelfUpdateStorage.deleteFile(verifiedApkFile)
        verifiedApkFile = null
        AppSelfUpdateStorage.sweepStaleApks(appContext)
        transition(AppSelfUpdateState.Error(messageRes = messageRes, retryable = retryable), onStateChanged)
    }

    private fun transitionIfCurrent(
        generation: Long,
        next: AppSelfUpdateState,
        onStateChanged: ((AppSelfUpdateState) -> Unit)?,
    ) {
        if (generation != activeGeneration) return
        transition(next, onStateChanged)
    }

    private fun transitionDownloadProgressIfCurrent(
        generation: Long,
        next: AppSelfUpdateState.Downloading,
        onStateChanged: ((AppSelfUpdateState) -> Unit)?,
    ) {
        if (generation != activeGeneration || state !is AppSelfUpdateState.Downloading) return
        transition(next, onStateChanged)
    }

    private fun transition(
        next: AppSelfUpdateState,
        onStateChanged: ((AppSelfUpdateState) -> Unit)?,
    ) {
        state = next
        onStateChanged?.invoke(next)
    }

    private fun isCurrentInstall(snapshot: InstallSnapshot): Boolean =
        installGeneration == snapshot.generation &&
            activeGeneration == snapshot.generation &&
            verifiedApkFile == snapshot.apkFile &&
            when (state) {
                is AppSelfUpdateState.Verified,
                is AppSelfUpdateState.PermissionRequired,
                -> true
                else -> false
            }

    private fun runOnMain(block: () -> Unit) {
        if (!mainDispatcher.isDispatchNeeded(EmptyCoroutineContext)) {
            block()
            return
        }
        runBlocking(mainDispatcher) { block() }
    }

    private data class InstallSnapshot(
        val generation: Long,
        val asset: ZapstoreApkAsset,
        val apkFile: File,
    )
}
