package dev.ipf.whitenoise.android.state

import android.os.Build
import androidx.annotation.StringRes
import dev.ipf.whitenoise.android.BuildConfig
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.core.DiagnosticFormatter
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
            ),
    )

fun WhiteNoiseAppState.presentFailure(
    @StringRes titleRes: Int,
    operationCode: String,
    throwable: Throwable,
    detail: AppText = AppText.Resource(R.string.error_try_again),
) {
    presentFailure(AppText.Resource(titleRes), operationCode, throwable, detail)
}

fun WhiteNoiseAppState.presentFailure(
    title: AppText,
    operationCode: String,
    throwable: Throwable,
    detail: AppText = AppText.Resource(R.string.error_try_again),
) {
    val presentation = privacySafeErrorPresentation(operationCode, throwable, detail)
    presentText(
        title = title,
        detail = presentation.message,
        copyable = true,
        diagnosticReport = presentation.report,
    )
}
