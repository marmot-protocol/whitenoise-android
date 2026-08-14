package dev.ipf.whitenoise.android.ui.conversation

import dev.ipf.marmotkit.AppMessageRecordFfi
import dev.ipf.whitenoise.android.state.rethrowIfCancellation
import java.util.Locale

internal fun batchDeleteDiagnosticReport(state: BatchDeleteRetryState): String =
    buildString {
        appendLine("White Noise batch delete report")
        appendLine("operation=MESSAGE_BATCH_DELETE")
        appendLine("attempted=${state.attempted}")
        appendLine("succeeded=${state.succeeded}")
        appendLine("failed=${state.failures.size}")
        BatchDeleteOperationKind.entries.forEach { operation ->
            val code = operation.name.toBatchDeleteDiagnosticCode()
            val attempted = state.originalAttempts.count { it.operation == operation }
            val failed = state.failures.count { it.attempt.operation == operation }
            appendLine("$code.attempted=$attempted")
            appendLine("$code.failed=$failed")
        }
        state.failures
            .groupingBy { it.attempt.operation to requireNotNull(it.failure) }
            .eachCount()
            .toSortedMap(compareBy({ it.first.name }, { it.second.name }))
            .forEach { (key, count) ->
                appendLine(
                    "${key.first.name.toBatchDeleteDiagnosticCode()}." +
                        "${key.second.name.toBatchDeleteDiagnosticCode()}=$count",
                )
            }
    }.trimEnd().take(MAX_BATCH_DELETE_REPORT_LENGTH)

private const val MAX_BATCH_DELETE_REPORT_LENGTH = 600
private val DIAGNOSTIC_CODE_BOUNDARY = Regex("([a-z0-9])([A-Z])")

private fun String.toBatchDeleteDiagnosticCode() = replace(DIAGNOSTIC_CODE_BOUNDARY, "\$1_\$2").uppercase(Locale.ROOT)

internal fun batchDeleteBreakdown(items: List<BatchMessageActionItem>): BatchDeleteBreakdown =
    BatchDeleteBreakdown(
        deleteForEveryone = items.count(BatchMessageActionItem::canDeleteForEveryone),
        hideLocally = items.count { !it.canDeleteForEveryone },
    )

internal fun batchDeleteAttempts(
    selections: List<BatchMessageSelection>,
    scope: BatchDeleteScope,
): List<BatchDeleteAttempt> =
    selections.map { selection ->
        BatchDeleteAttempt(
            selection = selection,
            operation =
                if (scope == BatchDeleteScope.EVERYONE && selection.action.canDeleteForEveryone) {
                    BatchDeleteOperationKind.DeleteForEveryone
                } else {
                    BatchDeleteOperationKind.HideLocally
                },
        )
    }

/** Executes the frozen operation kind for each selection and retains a safe per-message outcome. */
@Suppress("TooGenericExceptionCaught") // Each callback is an isolation boundary; cancellation is rethrown below.
internal suspend fun executeBatchDelete(
    attempts: List<BatchDeleteAttempt>,
    deleteForEveryone: suspend (AppMessageRecordFfi) -> Result<Unit>,
    hideLocally: suspend (String) -> Result<Unit>,
    onOutcome: (BatchDeleteOperationOutcome) -> Unit = {},
): BatchDeleteResult {
    val outcomes =
        attempts.map { attempt ->
            val result =
                try {
                    when (attempt.operation) {
                        BatchDeleteOperationKind.DeleteForEveryone ->
                            deleteForEveryone(attempt.selection.record)
                        BatchDeleteOperationKind.HideLocally ->
                            hideLocally(attempt.selection.action.messageId)
                    }
                } catch (throwable: Throwable) {
                    rethrowIfCancellation(throwable)
                    Result.failure(throwable)
                }
            result.exceptionOrNull()?.let(::rethrowIfCancellation)
            BatchDeleteOperationOutcome(
                attempt = attempt,
                failure = result.exceptionOrNull()?.let(BatchDeleteFailureCategory::from),
            ).also(onOutcome)
        }
    return BatchDeleteResult(outcomes)
}
