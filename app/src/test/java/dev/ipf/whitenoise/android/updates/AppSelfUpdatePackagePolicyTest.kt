package dev.ipf.whitenoise.android.updates

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppSelfUpdatePackagePolicyTest {
    private val installed =
        AppPackageIdentity(
            packageName = "dev.ipf.whitenoise.android",
            versionName = "2026.9.2",
            versionCode = 11,
            currentSignerSha256 = setOf("installed-signer"),
            signerHistorySha256 = setOf("installed-signer"),
            hasMultipleSigners = false,
        )

    @Test
    fun acceptsNewerPackageWithExpectedIdentityAndSigner() {
        assertTrue(
            isTrustedSelfUpdatePackage(
                installed = installed,
                candidate = candidate(),
                expectedVersion = "2026.9.4",
            ),
        )
    }

    @Test
    fun rejectsDifferentApplicationId() {
        assertFalse(
            isTrustedSelfUpdatePackage(
                installed = installed,
                candidate = candidate(packageName = "org.parres.whitenoise"),
                expectedVersion = "2026.9.4",
            ),
        )
    }

    @Test
    fun rejectsVersionThatDoesNotMatchSignedReleaseMetadata() {
        assertFalse(
            isTrustedSelfUpdatePackage(
                installed = installed,
                candidate = candidate(versionName = "2026.5.22"),
                expectedVersion = "2026.9.4",
            ),
        )
    }

    @Test
    fun rejectsVersionCodeThatCannotUpgradeInstalledApp() {
        assertFalse(
            isTrustedSelfUpdatePackage(
                installed = installed,
                candidate = candidate(versionCode = installed.versionCode),
                expectedVersion = "2026.9.4",
            ),
        )
    }

    @Test
    fun rejectsUnrelatedSigningIdentity() {
        assertFalse(
            isTrustedSelfUpdatePackage(
                installed = installed,
                candidate =
                    candidate(
                        currentSignerSha256 = setOf("other-signer"),
                        signerHistorySha256 = setOf("other-signer"),
                    ),
                expectedVersion = "2026.9.4",
            ),
        )
    }

    @Test
    fun acceptsValidatedSigningCertificateRotation() {
        assertTrue(
            isTrustedSelfUpdatePackage(
                installed = installed,
                candidate =
                    candidate(
                        currentSignerSha256 = setOf("rotated-signer"),
                        signerHistorySha256 = setOf("installed-signer", "rotated-signer"),
                    ),
                expectedVersion = "2026.9.4",
            ),
        )
    }

    @Test
    fun multipleSignerUpdatesRequireTheExactCurrentSignerSet() {
        val multiSignerInstalled =
            installed.copy(
                currentSignerSha256 = setOf("signer-a", "signer-b"),
                signerHistorySha256 = setOf("signer-a", "signer-b"),
                hasMultipleSigners = true,
            )

        assertTrue(
            isTrustedSelfUpdatePackage(
                installed = multiSignerInstalled,
                candidate =
                    candidate(
                        currentSignerSha256 = setOf("signer-a", "signer-b"),
                        signerHistorySha256 = setOf("signer-a", "signer-b"),
                        hasMultipleSigners = true,
                    ),
                expectedVersion = "2026.9.4",
            ),
        )
        assertFalse(
            isTrustedSelfUpdatePackage(
                installed = multiSignerInstalled,
                candidate =
                    candidate(
                        currentSignerSha256 = setOf("signer-a"),
                        signerHistorySha256 = setOf("signer-a"),
                    ),
                expectedVersion = "2026.9.4",
            ),
        )
    }

    private fun candidate(
        packageName: String = installed.packageName,
        versionName: String = "2026.9.4",
        versionCode: Long = 12,
        currentSignerSha256: Set<String> = setOf("installed-signer"),
        signerHistorySha256: Set<String> = currentSignerSha256,
        hasMultipleSigners: Boolean = false,
    ) = AppPackageIdentity(
        packageName = packageName,
        versionName = versionName,
        versionCode = versionCode,
        currentSignerSha256 = currentSignerSha256,
        signerHistorySha256 = signerHistorySha256,
        hasMultipleSigners = hasMultipleSigners,
    )
}
