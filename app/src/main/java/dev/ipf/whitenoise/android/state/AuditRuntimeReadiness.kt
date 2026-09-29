package dev.ipf.whitenoise.android.state

import android.os.Build
import android.util.Log
import dev.ipf.marmotkit.AuditLogTrackerConfigV4Ffi
import dev.ipf.marmotkit.AuditLogUploadSourceV4Ffi
import dev.ipf.marmotkit.AuditOtlpConfigV5Ffi
import dev.ipf.marmotkit.MarmotInterface
import dev.ipf.whitenoise.android.BuildConfig
import java.net.URI
import java.util.concurrent.atomic.AtomicBoolean

internal const val AUDIT_RUNTIME_DATA_MODE = "obfuscated_sensitive_data"
internal const val AUDIT_RUNTIME_MARKER_PREFIX = "WHITENOISE_AUDIT_READY_V1 "

internal class AuditRuntimeReadinessMarker(
    private val emit: (String) -> Unit,
) {
    private val emitted = AtomicBoolean(false)

    /** Writes the one-time marker only for a started recorder with an enabled v5 upload route. */
    fun emitAfterRuntimeStarted(
        required: Boolean,
        packageName: String,
        uploadConfig: AuditOtlpConfigV5Ffi,
        dataMode: String,
        recorderStarted: Boolean,
    ): Boolean {
        val ready =
            required &&
                recorderStarted &&
                uploadConfig.enabled &&
                !uploadConfig.endpoint.isNullOrBlank() &&
                !uploadConfig.authorizationBearerToken.isNullOrBlank() &&
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

/** Configures v5 delivery and keeps source metadata without a legacy v4 upload destination. */
internal suspend fun MarmotInterface.configureAuditRuntime(uploadConsentGranted: Boolean = false) {
    setAuditOtlpConfigV5(
        auditOtlpConfigV5(
            uploadConsentGranted = uploadConsentGranted,
            endpoint = BuildConfig.WHITENOISE_AUDIT_OTLP_ENDPOINT,
            authorizationBearerToken = BuildConfig.WHITENOISE_AUDIT_OTLP_AUTH_TOKEN,
            deploymentEnvironment = BuildConfig.WHITENOISE_DEPLOYMENT_ENVIRONMENT,
        ),
    )
    setAuditLogTrackerConfig(
        AuditLogTrackerConfigV4Ffi(
            endpoint = null,
            authorizationBearerToken = null,
            source =
                AuditLogUploadSourceV4Ffi(
                    hardwareModel = Build.MODEL.trim().takeIf(String::isNotEmpty),
                    platform = "android",
                    appVersion = BuildConfig.VERSION_NAME,
                ),
        ),
    )
    // Build configuration never substitutes for an explicit disclosure acknowledgement.
}

/** A stable per-environment checkpoint identity with no token when upload consent is absent. */
internal fun auditOtlpConfigV5(
    uploadConsentGranted: Boolean,
    endpoint: String,
    authorizationBearerToken: String,
    deploymentEnvironment: String,
): AuditOtlpConfigV5Ffi {
    val trimmedEndpoint =
        endpoint.trim().takeIf { raw ->
            val parsed = runCatching { URI(raw) }.getOrNull()
            parsed?.scheme == "https" &&
                !parsed.host.isNullOrBlank() &&
                parsed.path == "/v1/logs" &&
                parsed.rawQuery == null &&
                parsed.rawFragment == null &&
                parsed.userInfo == null
        }
    val trimmedToken = authorizationBearerToken.trim().takeIf(String::isNotEmpty)
    val enabled = uploadConsentGranted && trimmedEndpoint != null && trimmedToken != null
    return AuditOtlpConfigV5Ffi(
        enabled = enabled,
        destination = if (enabled) "whitenoise-android-$deploymentEnvironment" else null,
        endpoint = trimmedEndpoint.takeIf { enabled },
        authorizationBearerToken = trimmedToken.takeIf { enabled },
        allowLoopbackDev = false,
    )
}

/** Emits readiness only when native accepted the consented v5 sender and the recorder started. */
internal suspend fun MarmotInterface.emitAuditRuntimeReadinessAfterStart(uploadConfigAccepted: Boolean) {
    if (!BuildConfig.WHITENOISE_AUDIT_RUNTIME_REQUIRED) return
    val uploadConfig =
        auditOtlpConfigV5(
            uploadConsentGranted = uploadConfigAccepted,
            endpoint = BuildConfig.WHITENOISE_AUDIT_OTLP_ENDPOINT,
            authorizationBearerToken = BuildConfig.WHITENOISE_AUDIT_OTLP_AUTH_TOKEN,
            deploymentEnvironment = BuildConfig.WHITENOISE_DEPLOYMENT_ENVIRONMENT,
        )
    processAuditRuntimeReadinessMarker.emitAfterRuntimeStarted(
        required = BuildConfig.WHITENOISE_AUDIT_RUNTIME_REQUIRED,
        packageName = BuildConfig.APPLICATION_ID,
        uploadConfig = uploadConfig,
        dataMode = BuildConfig.WHITENOISE_AUDIT_DATA_MODE,
        recorderStarted = auditLogSettings().enabled,
    )
}
