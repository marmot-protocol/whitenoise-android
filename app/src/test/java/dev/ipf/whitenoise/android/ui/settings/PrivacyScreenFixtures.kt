package dev.ipf.whitenoise.android.ui.settings

import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import dev.ipf.marmotkit.AccountSummaryFfi
import dev.ipf.marmotkit.AuditDeliveryStatusV5Ffi
import dev.ipf.marmotkit.AuditLogRetentionFfi
import dev.ipf.marmotkit.AuditLogSettingsFfi
import dev.ipf.marmotkit.AuditLogTrackerUpdateResultV5Ffi
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
    auditRenewal: Boolean = false,
    audit: PrivacyAuditFixture = PrivacyAuditFixture(),
    beforeSave: () -> Unit = {},
): WhiteNoiseAppState {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val preferences = context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
    preferences
        .edit()
        .clear()
        .putBoolean("audit_upload_disclosure_pending", auditRenewal)
        .apply(seedPreferences)
        .commit()
    val marmot = privacyMarmot(decision, beforeSave, audit)
    return WhiteNoiseAppState(
        context = context,
        draftStore = DraftStore(EmptyDraftPersistence),
        accountIdHexResolver = { null },
        accounts =
            if (hasAccount) {
                listOf(AccountSummaryFfi("account", "aa".repeat(32), true, false, false, true, false))
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
    audit: PrivacyAuditFixture = PrivacyAuditFixture(),
): MarmotInterface {
    var decision = initialDecision
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
            "toString" -> "DevicePrivacyScreenshotMarmotFake"
            "hashCode" -> System.identityHashCode(proxy)
            "equals" -> proxy === arguments?.firstOrNull()
            else -> audit.invoke(method.name, arguments, beforeSave)
        }
    } as MarmotInterface
}

/** Device-wide policy and delivery records used by the Android settings fixture. */
internal class PrivacyAuditFixture(
    val enabled: Boolean = false,
    @Volatile var retention: AuditLogRetentionFfi = AuditLogRetentionFfi(30u, 256uL * 1024uL * 1024uL),
    @Volatile var statuses: List<AuditDeliveryStatusV5Ffi> = emptyList(),
    val clearPauseOnResume: Boolean = true,
) {
    @Volatile var retentionWrites = 0

    @Volatile var deliveryReads = 0

    @Volatile var resumeCalls = 0

    private var auditSettings = AuditLogSettingsFfi(enabled = enabled)

    /** Handles the audit subset of the fixture's Marmot interface. */
    fun invoke(
        method: String,
        arguments: Array<out Any?>?,
        beforeSave: () -> Unit,
    ): Any? =
        when (method) {
            "setAuditLogTrackerConfig", "setAuditOtlpConfigV5" -> arguments!!.first()
            "auditLogSettings" -> auditSettings
            "auditLogRetention" -> retention
            "setAuditLogRetention" -> {
                retention = arguments!!.first() as AuditLogRetentionFfi
                retentionWrites++
                retention
            }
            "auditDeliveryStatusV5" -> {
                deliveryReads++
                statuses
            }
            "postAuditLogTrackerUpdateV5" -> {
                resumeCalls++
                if (clearPauseOnResume) {
                    statuses =
                        statuses.map {
                            it.copy(paused = false, consecutiveFailures = 0uL, lastFailure = null)
                        }
                }
                AuditLogTrackerUpdateResultV5Ffi(true, emptyList(), null, null)
            }
            "setAuditLogSettings" -> {
                beforeSave()
                auditSettings = arguments!!.first() as AuditLogSettingsFfi
                auditSettings
            }
            else -> error("Unexpected Marmot call: $method")
        }
}

/** Draft storage that never persists, so fixtures start clean. */
private object EmptyDraftPersistence : DraftPersistence {
    /** In-memory draft persistence: returns the stored values. */
    override fun read(): Map<String, String> = emptyMap()

    /** In-memory draft persistence: stores or clears one value. */
    override fun write(
        key: String,
        value: String?,
    ) = Unit
}
