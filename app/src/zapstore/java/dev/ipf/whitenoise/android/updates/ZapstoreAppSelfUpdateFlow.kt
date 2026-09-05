package dev.ipf.whitenoise.android.updates

import android.content.Context
import dev.ipf.whitenoise.android.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.File

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
) : AppSelfUpdateFlow {
    override var state: AppSelfUpdateState = AppSelfUpdateState.Idle
        private set

    private var activeJob: Job? = null
    private var verifiedApkFile: File? = null

    override fun start(
        scope: CoroutineScope,
        version: String,
        onStateChanged: (AppSelfUpdateState) -> Unit,
    ) {
        cancel(deleteVerifiedApk = true)
        transition(AppSelfUpdateState.Resolving, onStateChanged)
        activeJob =
            scope.launch {
                runCatching {
                    val primaryAbi = primaryAbiProvider()
                    if (!AndroidAbi.isSupportedPrimaryAbi(primaryAbi)) {
                        fail(R.string.app_self_update_no_asset, retryable = true, onStateChanged)
                        return@launch
                    }
                    val platformId = AndroidAbi.platformIdForPrimaryAbi(primaryAbi)
                    val asset =
                        ZapstoreApkAssetResolver.resolveApkAsset(
                            client = client,
                            version = version,
                            platformId = platformId,
                        )
                    if (asset == null) {
                        fail(R.string.app_self_update_no_asset, retryable = true, onStateChanged)
                        return@launch
                    }
                    transition(AppSelfUpdateState.Confirming(asset), onStateChanged)
                }.onFailure { error ->
                    if (error is CancellationException) throw error
                    fail(R.string.app_self_update_resolve_failed, retryable = true, onStateChanged)
                }
            }
    }

    override fun confirmDownload(
        scope: CoroutineScope,
        onStateChanged: (AppSelfUpdateState) -> Unit,
    ) {
        val asset = (state as? AppSelfUpdateState.Confirming)?.asset ?: return
        cancel(deleteVerifiedApk = true)
        val destination = AppSelfUpdateStorage.apkFileForVersion(appContext, asset.version)
        transition(
            AppSelfUpdateState.Downloading(asset = asset, bytesRead = 0L, totalBytes = asset.sizeBytes),
            onStateChanged,
        )
        activeJob =
            scope.launch {
                val result =
                    downloader.downloadVerifiedApk(
                        asset = asset,
                        destination = destination,
                        onProgress = { bytesRead, totalBytes ->
                            transition(
                                AppSelfUpdateState.Downloading(
                                    asset = asset,
                                    bytesRead = bytesRead,
                                    totalBytes = totalBytes ?: asset.sizeBytes,
                                ),
                                onStateChanged,
                            )
                        },
                    )
                result
                    .onSuccess {
                        if (!AppSelfUpdateInstaller.isTrustedUpdatePackage(appContext, destination, asset.version)) {
                            AppSelfUpdateStorage.deleteFile(destination)
                            fail(R.string.app_self_update_install_failed, retryable = true, onStateChanged)
                            return@onSuccess
                        }
                        verifiedApkFile = destination
                        val next =
                            if (AppSelfUpdateInstaller.canRequestPackageInstalls(appContext)) {
                                AppSelfUpdateState.Verified(asset = asset, apkFile = destination)
                            } else {
                                AppSelfUpdateState.PermissionRequired(asset = asset, apkFile = destination)
                            }
                        transition(next, onStateChanged)
                    }.onFailure { error ->
                        if (error is CancellationException) throw error
                        val messageRes =
                            if (error is AppSelfUpdateDownloader.HashMismatchException) {
                                R.string.app_self_update_hash_mismatch
                            } else {
                                R.string.app_self_update_download_failed
                            }
                        fail(messageRes, retryable = true, onStateChanged)
                    }
            }
    }

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

    override fun cancel(
        deleteVerifiedApk: Boolean,
        onStateChanged: ((AppSelfUpdateState) -> Unit)?,
    ) {
        activeJob?.cancel()
        activeJob = null
        if (deleteVerifiedApk) {
            AppSelfUpdateStorage.deleteFile(verifiedApkFile)
            verifiedApkFile = null
        }
        transition(AppSelfUpdateState.Idle, onStateChanged)
    }

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

    override fun openInstallPermissionSettings(context: Context) {
        runCatching { context.startActivity(AppSelfUpdateInstaller.installPermissionSettingsIntent(context)) }
    }

    override fun sweepStaleApks() {
        AppSelfUpdateStorage.sweepStaleApks(appContext)
    }

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

    private fun transition(
        next: AppSelfUpdateState,
        onStateChanged: ((AppSelfUpdateState) -> Unit)?,
    ) {
        state = next
        onStateChanged?.invoke(next)
    }
}
