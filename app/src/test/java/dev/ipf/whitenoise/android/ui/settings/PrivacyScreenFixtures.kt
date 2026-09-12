package dev.ipf.whitenoise.android.ui.settings

import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import dev.ipf.marmotkit.AccountSummaryFfi
import dev.ipf.marmotkit.AuditLogSettingsFfi
import dev.ipf.marmotkit.DiagnosticsExporterStatusFfi
import dev.ipf.marmotkit.MarmotInterface
import dev.ipf.marmotkit.RelayTelemetrySettingsFfi
import dev.ipf.marmotkit.UsageDiagnosticsDecisionFfi
import dev.ipf.marmotkit.UsageDiagnosticsSettingsFfi
import dev.ipf.marmotkit.UsageDiagnosticsStatusFfi
import dev.ipf.whitenoise.android.state.AppMarmotRuntime
import dev.ipf.whitenoise.android.state.DraftPersistence
import dev.ipf.whitenoise.android.state.DraftStore
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import java.lang.reflect.Proxy

/** Creates a state whose only native reads are fixed device-privacy values, on its own cleared preferences. */
internal fun privacyAppState(
    decision: UsageDiagnosticsDecisionFfi,
    hasAccount: Boolean = true,
    preferencesName: String = "device-privacy-screenshot-${decision.name}",
    seedPreferences: SharedPreferences.Editor.() -> Unit = {},
    beforeSave: () -> Unit = {},
): WhiteNoiseAppState {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val preferences = context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
    preferences
        .edit()
        .clear()
        .apply(seedPreferences)
        .commit()
    val marmot = privacyMarmot(decision, beforeSave)
    return WhiteNoiseAppState(
        context = context,
        draftStore = DraftStore(EmptyDraftPersistence),
        accountIdHexResolver = { null },
        accounts =
            if (hasAccount) {
                listOf(AccountSummaryFfi("account", "aa".repeat(32), true, false, false, true))
            } else {
                emptyList()
            },
        activeAccountRef = "account",
        marmotRuntimeFactory = { AppMarmotRuntime(rootPath = "test", marmot = marmot) },
        preferences = preferences,
    ).also { state ->
        WhiteNoiseAppState::class.java
            .getDeclaredField("marmotRuntime")
            .apply { isAccessible = true }
            .set(state, AppMarmotRuntime(rootPath = "test", marmot = marmot))
    }
}

/** Implements device-privacy reads and explicit consent writes using disposable state. */
internal fun privacyMarmot(
    initialDecision: UsageDiagnosticsDecisionFfi,
    beforeSave: () -> Unit,
): MarmotInterface {
    var decision = initialDecision
    var auditSettings = AuditLogSettingsFfi(enabled = false)
    return Proxy.newProxyInstance(
        MarmotInterface::class.java.classLoader,
        arrayOf(MarmotInterface::class.java),
    ) { proxy, method, arguments ->
        when (method.name) {
            "setUsageDiagnosticsConsent" -> {
                beforeSave()
                decision =
                    if (arguments?.firstOrNull() == true) {
                        UsageDiagnosticsDecisionFfi.GRANTED
                    } else {
                        UsageDiagnosticsDecisionFfi.DECLINED
                    }
                UsageDiagnosticsSettingsFfi(decision, "test-policy", "test-registry", 0L, false)
            }
            "usageDiagnosticsSettings" ->
                UsageDiagnosticsSettingsFfi(
                    decision = decision,
                    policyRevision = "test-policy",
                    registryRevision = "test-registry",
                    updatedAtMs = 0L,
                    previouslyEnabled = decision == UsageDiagnosticsDecisionFfi.GRANTED,
                )
            "usageDiagnosticsStatus" ->
                UsageDiagnosticsStatusFfi(
                    consent = decision,
                    telemetry =
                        if (decision == UsageDiagnosticsDecisionFfi.GRANTED) {
                            DiagnosticsExporterStatusFfi.READY
                        } else {
                            DiagnosticsExporterStatusFfi.DISABLED
                        },
                    productAnalytics = DiagnosticsExporterStatusFfi.UNCONFIGURED,
                    queuedEvents = 0uL,
                    droppedEvents = 0uL,
                    acceptedBatches = 0uL,
                    failedBatches = 0uL,
                )
            "relayTelemetrySettings" ->
                RelayTelemetrySettingsFfi(
                    exportEnabled = false,
                    exportIntervalSeconds = 60uL,
                )
            "auditLogSettings" -> auditSettings
            "setAuditLogSettings" -> {
                beforeSave()
                auditSettings = arguments!!.first() as AuditLogSettingsFfi
                auditSettings
            }
            "toString" -> "DevicePrivacyScreenshotMarmotFake"
            "hashCode" -> System.identityHashCode(proxy)
            "equals" -> proxy === arguments?.firstOrNull()
            else -> error("Unexpected Marmot call: ${method.name}")
        }
    } as MarmotInterface
}

/** Draft storage that never persists, so fixtures start clean. */
private object EmptyDraftPersistence : DraftPersistence {
    override fun read(): Map<String, String> = emptyMap()

    override fun write(
        key: String,
        value: String?,
    ) = Unit
}
