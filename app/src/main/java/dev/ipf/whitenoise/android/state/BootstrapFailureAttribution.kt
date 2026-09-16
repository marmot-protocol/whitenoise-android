package dev.ipf.whitenoise.android.state

import dev.ipf.whitenoise.android.core.DiagnosticErrorMetadata
import dev.ipf.whitenoise.android.core.DiagnosticFormatter
import kotlinx.coroutines.CancellationException

/** Stable native-start stages that may be included in privacy-safe support reports. */
internal enum class BootstrapStage {
    RUNTIME_START,
}

/** Preserves the native failure category while identifying the bounded startup boundary that failed. */
internal class BootstrapStageFailure(
    stage: BootstrapStage,
    cause: Throwable,
) : RuntimeException("Startup failed at ${stage.name}", cause),
    DiagnosticErrorMetadata {
    override val diagnosticErrorCode: String = DiagnosticFormatter.errorCode(cause)
    override val diagnosticTechnicalDetail: String = "stage=${stage.name}"
}

/** Adds safe stage attribution without turning cancellation into an ordinary bootstrap failure. */
internal fun <T> Result<T>.getOrThrowAtStartupStage(stage: BootstrapStage): T =
    fold(
        onSuccess = { it },
        onFailure = { failure ->
            if (failure is CancellationException) throw failure
            throw BootstrapStageFailure(stage, failure)
        },
    )
