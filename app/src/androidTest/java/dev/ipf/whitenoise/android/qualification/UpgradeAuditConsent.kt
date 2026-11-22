package dev.ipf.whitenoise.android.qualification

import android.content.Context
import dev.ipf.marmotkit.MarmotInterface
import dev.ipf.marmotkit.UsageDiagnosticsDecisionFfi
import dev.ipf.whitenoise.android.state.AuditUploadConsent
import dev.ipf.whitenoise.android.state.configureAuditRuntime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse

/** Seed through the old APK’s consent controller and read both stores after production startup. */
internal suspend fun verifyUpgradeAuditConsent(
    context: Context,
    runtime: MarmotInterface,
    phase: String,
    enabled: Boolean,
) {
    val preferences = context.getSharedPreferences("whitenoise", Context.MODE_PRIVATE)
    val consent = AuditUploadConsent(preferences)
    if (phase == "seed") {
        runtime.setUsageDiagnosticsConsent(false)
        consent.applyChoice(
            runtime.auditLogSettings().copy(enabled = enabled),
            configureUpload = { runtime.configureAuditRuntime(uploadConsentGranted = it) },
            persistSettings = { runtime.setAuditLogSettings(it) },
        )
    }
    assertEquals("Android audit acknowledgement", if (enabled) 2 else 0, preferences.getInt("audit_upload_disclosure_revision", -1))
    assertEquals(UsageDiagnosticsDecisionFfi.DECLINED, runtime.usageDiagnosticsSettings().decision)
    assertEquals("Native audit recorder setting", enabled, runtime.auditLogSettings().enabled)
    assertEquals(enabled, consent.granted)
    assertFalse(consent.requiresChoice)
}
