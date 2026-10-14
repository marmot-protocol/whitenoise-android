package dev.ipf.whitenoise.android.translation

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import java.security.MessageDigest

internal const val OFFLINE_TRANSLATOR_PACKAGE = "dev.davidv.translator"
internal const val OFFLINE_TRANSLATOR_ACTION = "dev.davidv.translator.ITranslationService"
internal const val OFFLINE_TRANSLATOR_SIGNER = "2b3806e745d809018a51be58d0635ffc74cc9733439407ab1ed0424a4db3e1fb"
internal const val OFFLINE_TRANSLATOR_INSTALL = "https://f-droid.org/packages/dev.davidv.translator/"

internal data class VerifiedTranslationService(
    val component: ComponentName,
    val identity: TranslationEngineIdentity,
)

/** Never uses another package or trusts a self-declared local capability. */
internal fun discoverOfflineTranslator(context: Context): VerifiedTranslationService {
    val manager = context.packageManager
    val info = installedTranslatorPackage(manager)
    validateTranslatorPackage(info)
    val intent = Intent(OFFLINE_TRANSLATOR_ACTION).setPackage(OFFLINE_TRANSLATOR_PACKAGE)
    val service =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            manager.resolveService(intent, PackageManager.ResolveInfoFlags.of(0))?.serviceInfo
        } else {
            manager.resolveService(intent, 0)?.serviceInfo
        }
    if (!compatibleTranslatorService(service)) {
        throw TranslationException(TranslationFailure.INCOMPATIBLE_ENGINE)
    }
    checkNotNull(service)
    return VerifiedTranslationService(
        ComponentName(service.packageName, service.name),
        TranslationEngineIdentity(OFFLINE_TRANSLATOR_PACKAGE, OFFLINE_TRANSLATOR_SIGNER, info.longVersionCode),
    )
}

private fun installedTranslatorPackage(manager: PackageManager): PackageInfo =
    try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            manager.getPackageInfo(
                OFFLINE_TRANSLATOR_PACKAGE,
                PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong()),
            )
        } else {
            manager.getPackageInfo(OFFLINE_TRANSLATOR_PACKAGE, PackageManager.GET_SIGNING_CERTIFICATES)
        }
    } catch (_: PackageManager.NameNotFoundException) {
        throw TranslationException(TranslationFailure.MISSING_ENGINE)
    }

private fun validateTranslatorPackage(info: PackageInfo) {
    val signers = info.signingInfo?.apkContentsSigners.orEmpty()
    if (signers.size != 1 || certificateHash(signers.single().toByteArray()) != OFFLINE_TRANSLATOR_SIGNER) {
        throw TranslationException(TranslationFailure.UNVERIFIED_ENGINE)
    }
    if (info.versionName !in setOf("0.8.3", "0.8.4", "0.8.5")) {
        throw TranslationException(TranslationFailure.INCOMPATIBLE_ENGINE)
    }
}

private fun compatibleTranslatorService(service: ServiceInfo?): Boolean {
    val declaredLocal = service?.packageName == OFFLINE_TRANSLATOR_PACKAGE && service.permission.isNullOrEmpty()
    return declaredLocal && service?.exported == true && service.enabled
}

internal fun certificateHash(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {
        "%02x".format(it)
    }
