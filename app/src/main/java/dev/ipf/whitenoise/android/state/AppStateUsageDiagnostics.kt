package dev.ipf.whitenoise.android.state

/** Saves the explicit sharing choice and returns false while the retryable error remains visible. */
suspend fun WhiteNoiseAppState.setTelemetryEnabled(enabled: Boolean): Boolean = diagnostics.choose(marmot(), enabled)

/** Retries the failed user choice, rather than discarding it with a passive settings read. */
internal suspend fun WhiteNoiseAppState.retryUsageDiagnostics() = diagnostics.retry(marmot())

/** Whether the current MDK policy has a confirmed explicit grant. */
fun WhiteNoiseAppState.isUsageDiagnosticsGranted(): Boolean = diagnostics.granted
