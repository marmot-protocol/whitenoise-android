package dev.ipf.whitenoise.android.state

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import java.util.Locale

internal fun telemetryServiceVersion(
    versionName: String,
    versionCode: Int,
): String = "${versionName.trim()}+$versionCode"

internal fun telemetryDeploymentEnvironment(value: String): String =
    when (val normalized = value.trim().lowercase(Locale.ROOT)) {
        "production", "staging", "development", "test" -> normalized
        "android-release" -> "production"
        "dev" -> "development"
        else -> "production"
    }

internal fun telemetryDeviceModelIdentifier(model: String): String? = model.trim().takeIf { it.isNotEmpty() }
