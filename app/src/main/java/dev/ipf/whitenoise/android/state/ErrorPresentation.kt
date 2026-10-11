package dev.ipf.whitenoise.android.state

import android.os.Build
import androidx.annotation.StringRes
import dev.ipf.whitenoise.android.BuildConfig
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.core.DiagnosticFormatter
import dev.ipf.whitenoise.android.diagnostics.StartupDiagnosticStage
import java.time.Instant

enum class NoticeTier {
    Confirmation,
    ActionableError,
}

data class ErrorPresentation(
    val message: AppText,
    val report: String,
    val retryable: Boolean = true,
)

/** Builds a bounded copyable report; startupDetail accepts only the fixed host stage schema. */
internal fun privacySafeErrorPresentation(
    operationCode: String,
    throwable: Throwable,
    message: AppText = AppText.Resource(R.string.error_try_again),
    appVersion: String = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
    androidVersion: String =
        runCatching { "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})" }
            .getOrDefault("unknown"),
    occurredAtUtc: String = Instant.now().toString(),
    retryable: Boolean = true,
    startupDetail: String? = null,
): ErrorPresentation =
    ErrorPresentation(
        message = message,
        retryable = retryable,
        report =
            DiagnosticFormatter.errorReport(
                operationCode = operationCode,
                throwable = throwable,
                context =
                    DiagnosticFormatter.ErrorReportContext(
                        appVersion = appVersion,
                        androidVersion = androidVersion,
                        occurredAtUtc = occurredAtUtc,
                    ),
            ) + startupReportFields(startupDetail),
    )

/** Keeps every startup field while rejecting free text and retaining the ordinary error scrubber's bound. */
private fun startupReportFields(detail: String?): String {
    val fields = detail?.split(' ') ?: return ""
    val stages = StartupDiagnosticStage.entries.map { it.name } + "BETWEEN_STAGES"
    val valid =
        fields.size <= MAX_STARTUP_COPY_FIELDS &&
            fields.all { field ->
                val key = field.substringBefore('=')
                val value = field.substringAfter('=', "")
                when (key) {
                    "stage", "failed_stage" -> value in stages
                    "stage_elapsed_ms", "attempt", "attempt_elapsed_ms", "active_count" ->
                        value.toLongOrNull()?.let { it >= 0 } == true
                    "mdk_sha" -> value.matches(Regex("[0-9a-f]{8}"))
                    else -> false
                }
            }
    return if (valid) fields.joinToString(separator = "\nstartup_", prefix = "\nstartup_") else ""
}

private const val MAX_STARTUP_COPY_FIELDS = 7

fun WhiteNoiseAppState.presentFailure(
    @StringRes titleRes: Int,
    operationCode: String,
    throwable: Throwable,
    detail: AppText = AppText.Resource(R.string.error_try_again),
    sendAttempt: SendFailureAttempt? = null,
) {
    presentFailure(AppText.Resource(titleRes), operationCode, throwable, detail, sendAttempt)
}

fun WhiteNoiseAppState.presentFailure(
    title: AppText,
    operationCode: String,
    throwable: Throwable,
    detail: AppText = AppText.Resource(R.string.error_try_again),
    sendAttempt: SendFailureAttempt? = null,
) {
    val presentation = privacySafeErrorPresentation(operationCode, throwable, detail)
    presentText(
        title = title,
        detail = presentation.message,
        copyable = true,
        diagnosticReport = presentation.report,
        sendAttempt = sendAttempt,
    )
}
