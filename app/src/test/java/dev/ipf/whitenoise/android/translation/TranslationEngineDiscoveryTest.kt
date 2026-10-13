package dev.ipf.whitenoise.android.translation

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.ResolveInfo
import android.content.pm.ServiceInfo
import android.content.pm.Signature
import android.content.pm.SigningInfo
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.security.cert.CertificateFactory

/** Discovery rejects unsupported identities before any message can reach the service. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30, 36])
class TranslationEngineDiscoveryTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun absentEngineFailsClosedOnBothPackageManagerApiFamilies() {
        assertFailure(TranslationFailure.MISSING_ENGINE)
    }

    @Test
    fun reviewedPublisherReleasesRemainAvailableWithTheUiPrivacyWarning() {
        for ((version, code) in listOf("0.8.3" to 282L, "0.8.4" to 292L, "0.8.5" to 302L)) {
            installEngine(version, code)
            val service = discoverOfflineTranslator(context)
            assertEquals(OFFLINE_TRANSLATOR_PACKAGE, service.component.packageName)
            assertEquals(code, service.identity.version)
        }
    }

    @Test
    fun currentVersionWithAnotherSignerIsRejected() {
        installEngine("0.8.5", 302L, Signature(byteArrayOf(1, 2, 3)))
        assertFailure(TranslationFailure.UNVERIFIED_ENGINE)
    }

    @Test
    fun unreviewedPublisherReleaseIsRejected() {
        installEngine("0.8.6", 312L)
        assertFailure(TranslationFailure.INCOMPATIBLE_ENGINE)
    }

    @Test
    fun aNonExportedServiceCannotReceiveText() {
        installEngine("0.8.5", 302L, exported = false)
        assertFailure(TranslationFailure.INCOMPATIBLE_ENGINE)
    }

    private fun assertFailure(expected: TranslationFailure) {
        val failure = assertThrows(TranslationException::class.java) { discoverOfflineTranslator(context) }
        assertEquals(expected, failure.failure)
    }

    private fun installEngine(
        version: String,
        code: Long,
        signature: Signature = publisherSignature(),
        exported: Boolean = true,
    ) {
        val application =
            ApplicationInfo().apply {
                packageName = OFFLINE_TRANSLATOR_PACKAGE
                enabled = true
            }
        shadowOf(context.packageManager).installPackage(
            PackageInfo().apply {
                packageName = OFFLINE_TRANSLATOR_PACKAGE
                versionName = version
                setLongVersionCode(code)
                applicationInfo = application
                signingInfo = SigningInfo().also { shadowOf(it).setSignatures(arrayOf(signature)) }
            },
        )
        shadowOf(context.packageManager).addResolveInfoForIntent(
            Intent(OFFLINE_TRANSLATOR_ACTION).setPackage(OFFLINE_TRANSLATOR_PACKAGE),
            ResolveInfo().apply {
                serviceInfo =
                    ServiceInfo().apply {
                        packageName = OFFLINE_TRANSLATOR_PACKAGE
                        name = "$OFFLINE_TRANSLATOR_PACKAGE.AidlTranslationService"
                        enabled = true
                        this.exported = exported
                        applicationInfo = application
                    }
            },
        )
    }

    private fun publisherSignature(): Signature =
        checkNotNull(javaClass.getResourceAsStream("/translation/offline-translator-public.pem")).use {
            Signature(CertificateFactory.getInstance("X.509").generateCertificate(it).encoded)
        }
}
