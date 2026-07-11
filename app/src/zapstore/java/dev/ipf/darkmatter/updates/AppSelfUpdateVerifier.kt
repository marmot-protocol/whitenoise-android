package dev.ipf.darkmatter.updates

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

internal data class ApkArchiveIdentity(
    val packageName: String,
    val versionName: String,
    val versionCode: Long,
    val currentSignerDigests: Set<String>,
    val signerHistoryDigests: Set<String>,
)

internal fun isExpectedUpdateArchive(
    installed: ApkArchiveIdentity,
    archive: ApkArchiveIdentity,
    expectedVersion: String,
): Boolean =
    archive.packageName == installed.packageName &&
        archive.versionName == expectedVersion &&
        archive.versionCode > installed.versionCode &&
        installed.currentSignerDigests.isNotEmpty() &&
        installed.currentSignerDigests.all { it in archive.signerHistoryDigests }

internal object AppSelfUpdateVerifier {
    suspend fun verifyCachedApk(
        apkFile: File,
        asset: ZapstoreApkAsset,
        dispatcher: CoroutineDispatcher = Dispatchers.IO,
    ): Boolean =
        withContext(dispatcher) {
            runCatching { verifySignedPayload(apkFile, asset) }.getOrDefault(false)
        }

    suspend fun verifyInstallableApk(
        context: Context,
        apkFile: File,
        asset: ZapstoreApkAsset,
        dispatcher: CoroutineDispatcher = Dispatchers.IO,
    ): Boolean =
        withContext(dispatcher) {
            runCatching {
                verifySignedPayload(apkFile, asset) && verifyArchiveIdentity(context, apkFile, asset.version)
            }.getOrDefault(false)
        }

    private fun verifySignedPayload(
        apkFile: File,
        asset: ZapstoreApkAsset,
    ): Boolean {
        if (!apkFile.isFile || !apkFile.canRead()) return false
        val length = apkFile.length()
        if (length <= 0L || length > AppSelfUpdateLimits.MAX_APK_BYTES) return false
        if (asset.sizeBytes != null && length != asset.sizeBytes) return false
        val digest = MessageDigest.getInstance("SHA-256")
        apkFile.inputStream().buffered().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read == -1) break
                digest.update(buffer, 0, read)
            }
        }
        return constantTimeEqualsHex(digest.digest(), asset.sha256Hex)
    }

    private fun verifyArchiveIdentity(
        context: Context,
        apkFile: File,
        expectedVersion: String,
    ): Boolean {
        val packageManager = context.packageManager
        val flags = PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong())
        val installed = packageManager.getPackageInfo(context.packageName, flags).toArchiveIdentity()
        val archive = packageManager.getPackageArchiveInfo(apkFile.absolutePath, flags)?.toArchiveIdentity() ?: return false
        return isExpectedUpdateArchive(installed, archive, expectedVersion)
    }

    private fun PackageInfo.toArchiveIdentity(): ApkArchiveIdentity {
        val info = signingInfo ?: error("APK signing info missing")
        val current = info.apkContentsSigners.mapTo(linkedSetOf()) { it.sha256Hex() }
        val history =
            if (info.hasMultipleSigners()) {
                current
            } else {
                info.signingCertificateHistory.mapTo(linkedSetOf()) { it.sha256Hex() }
            }
        return ApkArchiveIdentity(
            packageName = packageName,
            versionName = versionName ?: "",
            versionCode = longVersionCode,
            currentSignerDigests = current,
            signerHistoryDigests = history.ifEmpty { current },
        )
    }

    private fun android.content.pm.Signature.sha256Hex(): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(toByteArray())
            .toHex()
}
