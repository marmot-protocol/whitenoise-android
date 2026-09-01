package dev.ipf.whitenoise.android.state

import android.os.Build
import android.util.Log
import dev.ipf.marmotkit.AuditLogTrackerConfigFfi
import dev.ipf.marmotkit.AuditLogUploadSourceFfi
import dev.ipf.marmotkit.MarmotInterface
import dev.ipf.whitenoise.android.BuildConfig
import java.util.concurrent.atomic.AtomicBoolean

internal const val AUDIT_RUNTIME_DATA_MODE = "obfuscated_sensitive_data"
internal const val AUDIT_RUNTIME_MARKER_PREFIX = "WHITENOISE_AUDIT_READY_V1 "

internal class AuditRuntimeReadinessMarker(
    private val emit: (String) -> Unit,
) {
    private val emitted = AtomicBoolean(false)

    fun emitAfterRuntimeStarted(
        required: Boolean,
        packageName: String,
        endpoint: String?,
        authorizationBearerToken: String?,
        dataMode: String,
        recorderStarted: Boolean,
    ): Boolean {
        val ready =
            required &&
                recorderStarted &&
                !endpoint.isNullOrBlank() &&
                !authorizationBearerToken.isNullOrBlank() &&
                dataMode == AUDIT_RUNTIME_DATA_MODE
        if (!ready || !emitted.compareAndSet(false, true)) return false

        require(packageName.matches(Regex("[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z0-9_]+)+")))
        emit(
            AUDIT_RUNTIME_MARKER_PREFIX +
                "{\"schema_version\":1,\"package_name\":\"$packageName\"," +
                "\"enabled\":true,\"recorder_started\":true," +
                "\"upload_configured\":true,\"data_mode\":\"$AUDIT_RUNTIME_DATA_MODE\"}",
        )
        return true
    }
}

private val processAuditRuntimeReadinessMarker =
    AuditRuntimeReadinessMarker { marker -> Log.i("WhiteNoiseAudit", marker) }

internal suspend fun MarmotInterface.configureAuditRuntime() {
    val auditEndpoint = BuildConfig.WHITENOISE_AUDIT_LOG_ENDPOINT.trim().takeIf(String::isNotEmpty)
    val auditAuthorizationBearerToken =
        BuildConfig.WHITENOISE_AUDIT_LOG_AUTH_TOKEN.trim().takeIf(String::isNotEmpty)
    setAuditLogTrackerConfig(
        AuditLogTrackerConfigFfi(
            endpoint = auditEndpoint,
            authorizationBearerToken = auditAuthorizationBearerToken,
            source =
                AuditLogUploadSourceFfi(
                    deviceLabel = Build.MODEL.trim().takeIf(String::isNotEmpty),
                    platform = "android",
                    appVersion = BuildConfig.VERSION_NAME,
                ),
        ),
    )
    val uploadConfigured = auditEndpoint != null && auditAuthorizationBearerToken != null
    val auditedRuntimeRequired =
        BuildConfig.WHITENOISE_AUDIT_RUNTIME_REQUIRED &&
            BuildConfig.WHITENOISE_AUDIT_DATA_MODE == AUDIT_RUNTIME_DATA_MODE
    if (uploadConfigured && auditedRuntimeRequired) {
        // This enables every live session and persists the setting for sessions
        // opened by start(). MarmotKit's pinned audit settings expose only this
        // enabled switch; the recorder's sensitive-data obfuscation is fixed.
        setAuditLogSettings(auditLogSettings().copy(enabled = true))
    }
}

internal suspend fun MarmotInterface.emitAuditRuntimeReadinessAfterStart() {
    if (!BuildConfig.WHITENOISE_AUDIT_RUNTIME_REQUIRED) return
    processAuditRuntimeReadinessMarker.emitAfterRuntimeStarted(
        required = BuildConfig.WHITENOISE_AUDIT_RUNTIME_REQUIRED,
        packageName = BuildConfig.APPLICATION_ID,
        endpoint = BuildConfig.WHITENOISE_AUDIT_LOG_ENDPOINT.takeIf(String::isNotBlank),
        authorizationBearerToken = BuildConfig.WHITENOISE_AUDIT_LOG_AUTH_TOKEN.takeIf(String::isNotBlank),
        dataMode = BuildConfig.WHITENOISE_AUDIT_DATA_MODE,
        recorderStarted = auditLogSettings().enabled,
    )
}
