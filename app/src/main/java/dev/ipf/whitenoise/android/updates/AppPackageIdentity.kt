package dev.ipf.whitenoise.android.updates

/** Package metadata used to decide whether an APK is an installable update of this app. */
internal data class AppPackageIdentity(
    val packageName: String,
    val versionName: String,
    val versionCode: Long,
    val currentSignerSha256: Set<String>,
    val signerHistorySha256: Set<String>,
    val hasMultipleSigners: Boolean,
)

/**
 * Rejects APKs that Android cannot treat as a strictly newer, correctly signed version of the
 * installed app, even when their download hash matches publisher-provided Zapstore metadata.
 */
internal fun isTrustedSelfUpdatePackage(
    installed: AppPackageIdentity,
    candidate: AppPackageIdentity,
    expectedVersion: String,
): Boolean {
    val hasSigningContinuity =
        when {
            installed.currentSignerSha256.isEmpty() || candidate.currentSignerSha256.isEmpty() -> false
            installed.hasMultipleSigners || candidate.hasMultipleSigners ->
                installed.hasMultipleSigners &&
                    candidate.hasMultipleSigners &&
                    installed.currentSignerSha256 == candidate.currentSignerSha256
            else -> candidate.signerHistorySha256.containsAll(installed.currentSignerSha256)
        }

    return candidate.packageName == installed.packageName &&
        candidate.versionName == expectedVersion &&
        candidate.versionCode > installed.versionCode &&
        hasSigningContinuity
}
