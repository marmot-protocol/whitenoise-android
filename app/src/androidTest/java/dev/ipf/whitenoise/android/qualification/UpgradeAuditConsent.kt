package dev.ipf.whitenoise.android.qualification

import android.content.Context
import dev.ipf.marmotkit.MarmotInterface
import dev.ipf.marmotkit.UsageDiagnosticsDecisionFfi
import dev.ipf.whitenoise.android.state.AuditUploadConsent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse

/** Seed only on the old APK, then load both stores before candidate startup can alter either. */
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
            configureUpload = {},
            persistSettings = { runtime.setAuditLogSettings(it) },
        )
    }
    assertEquals(if (enabled) 2 else 0, preferences.getInt("audit_upload_disclosure_revision", -1))
    assertEquals(UsageDiagnosticsDecisionFfi.DECLINED, runtime.usageDiagnosticsSettings().decision)
    assertEquals(enabled, runtime.auditLogSettings().enabled)
    val storedReceipt = preferences.all
    consent.prepare(runtime)
    assertEquals(storedReceipt, preferences.all)
    assertEquals(enabled, consent.granted)
    assertEquals(enabled, consent.startupUploadConfigured)
    assertFalse(consent.requiresChoice)
    assertEquals(enabled, runtime.auditLogSettings().enabled)
}
