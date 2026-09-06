package dev.ipf.whitenoise.android.updates

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import dev.ipf.whitenoise.android.core.nostr.toHex
import java.io.File
import java.security.MessageDigest

object AppSelfUpdateInstaller {
    /** Returns whether Android currently allows this app to request package installation. */
    fun canRequestPackageInstalls(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return true
        return context.packageManager.canRequestPackageInstalls()
    }

    fun installPermissionSettingsIntent(context: Context): Intent =
        Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${context.packageName}"),
        ).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

    /**
     * Parses the installed app and downloaded APK before installation, independently of the
     * publisher-provided download hash.
     */
    fun isTrustedUpdatePackage(
        context: Context,
        apkFile: File,
        expectedVersion: String,
    ): Boolean =
        runCatching {
            val packageManager = context.packageManager
            val installed =
                packageManager
                    .getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
                    .toAppPackageIdentity()
            val candidate =
                packageManager
                    .getPackageArchiveInfo(apkFile.absolutePath, PackageManager.GET_SIGNING_CERTIFICATES)
                    ?.toAppPackageIdentity()
            installed != null &&
                candidate != null &&
                isTrustedSelfUpdatePackage(installed, candidate, expectedVersion)
        }.getOrDefault(false)

    /** Launches Android's package installer for an APK that has already passed identity checks. */
    fun launchInstall(
        context: Context,
        apkFile: File,
    ): Boolean {
        val uri =
            FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                apkFile,
            )
        val intent =
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, AndroidAbi.APK_MIME)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        return runCatching { context.startActivity(intent) }.isSuccess
    }

    private fun PackageInfo.toAppPackageIdentity(): AppPackageIdentity? {
        val signing = signingInfo ?: return null
        val currentSigners = signing.apkContentsSigners?.mapTo(mutableSetOf(), ::sha256Hex).orEmpty()
        val signerHistory =
            if (signing.hasMultipleSigners()) {
                currentSigners
            } else {
                signing.signingCertificateHistory?.mapTo(mutableSetOf(), ::sha256Hex).orEmpty()
            }
        return AppPackageIdentity(
            packageName = packageName,
            versionName = versionName.orEmpty(),
            versionCode = longVersionCode,
            currentSignerSha256 = currentSigners,
            signerHistorySha256 = signerHistory,
            hasMultipleSigners = signing.hasMultipleSigners(),
        )
    }

    private fun sha256Hex(signature: android.content.pm.Signature): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(signature.toByteArray())
            .toHex()
}
