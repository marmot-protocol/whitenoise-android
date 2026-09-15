package dev.ipf.whitenoise.android.state

import android.os.Build
import dev.ipf.marmotkit.MarmotInterface
import dev.ipf.marmotkit.RelayTelemetryResourceFfi
import dev.ipf.marmotkit.RelayTelemetryRuntimeConfigFfi
import dev.ipf.whitenoise.android.BuildConfig

/** Installs the independent OTLP destination; native usage consent remains authoritative. */
internal suspend fun MarmotInterface.configureTelemetryRuntime() {
    val installId = runCatchingCancellable { telemetryInstallId() }.getOrNull().orEmpty()
    setRelayTelemetryRuntimeConfig(
        RelayTelemetryRuntimeConfigFfi(
            otlpEndpoint = BuildConfig.WHITENOISE_OTLP_ENDPOINT.nonBlankOrNull(),
            authorizationBearerToken = BuildConfig.WHITENOISE_OTLP_AUTH_TOKEN.nonBlankOrNull(),
            resource =
                RelayTelemetryResourceFfi(
                    serviceVersion = telemetryServiceVersion(BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE),
                    serviceInstanceId = installId,
                    deploymentEnvironment =
                        telemetryDeploymentEnvironment(BuildConfig.WHITENOISE_DEPLOYMENT_ENVIRONMENT),
                    tenant = BuildConfig.WHITENOISE_TELEMETRY_TENANT.ifBlank { "whitenoise-android" },
                    osType = "linux",
                    osVersion = Build.VERSION.RELEASE.ifBlank { Build.VERSION.SDK_INT.toString() },
                    deviceModelIdentifier = telemetryDeviceModelIdentifier(Build.MODEL),
                ),
        ),
    )
}
