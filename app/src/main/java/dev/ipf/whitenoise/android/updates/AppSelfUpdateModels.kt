package dev.ipf.whitenoise.android.updates

import java.io.File

data class ZapstoreApkAsset(
    val eventId: String,
    val appId: String,
    val version: String,
    val sha256Hex: String,
    val downloadUrl: String,
    val sizeBytes: Long?,
    val platformIds: Set<String>,
)

sealed interface AppSelfUpdateState {
    data object Idle : AppSelfUpdateState

    data object Resolving : AppSelfUpdateState

    data class Confirming(
        val asset: ZapstoreApkAsset,
    ) : AppSelfUpdateState

    data class Downloading(
        val asset: ZapstoreApkAsset,
        val bytesRead: Long,
        val totalBytes: Long?,
    ) : AppSelfUpdateState

    data class Verified(
        val asset: ZapstoreApkAsset,
        val apkFile: File,
    ) : AppSelfUpdateState

    data class PermissionRequired(
        val asset: ZapstoreApkAsset,
        val apkFile: File,
    ) : AppSelfUpdateState

    data class Error(
        val messageRes: Int,
        val retryable: Boolean,
    ) : AppSelfUpdateState
}
