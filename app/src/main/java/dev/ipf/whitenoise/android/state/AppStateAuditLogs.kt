package dev.ipf.whitenoise.android.state

import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.diagnostics.PerformanceDiagnostics
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Archives audit, dictation, and performance data; empty or partial exports return null. */
@Suppress("ReturnCount") // Each engine/cache failure is a distinct fail-closed export outcome.
suspend fun WhiteNoiseAppState.prepareAuditLogArchiveForExport(): java.io.File? {
    val sourcePaths =
        runCatching { marmotIo { auditLogFiles().map { it.path } } }
            .getOrElse {
                if (it is CancellationException) throw it
                present(R.string.toast_couldnt_export_audit_logs)
                return null
            }
    return runCatchingCancellable {
        withContext(Dispatchers.IO) {
            val nativePerformance =
                if (PerformanceDiagnostics.isActive()) {
                    runCatchingCancellable {
                        marmotIo {
                            dev.ipf.whitenoise.android.diagnostics
                                .formatNativePerformanceSnapshot(appPerformanceSnapshot())
                        }.toByteArray(Charsets.UTF_8)
                    }.getOrNull()
                } else {
                    null
                }
            prepareAuditAndDictationLogArchive(
                appContext,
                sourcePaths,
                nativePerformanceLogBytes = nativePerformance,
            )
        }.also { if (it == null) presentTransient(R.string.toast_no_audit_logs_to_export) }
    }.getOrElse {
        present(R.string.toast_couldnt_export_audit_logs)
        return null
    }
}

/**
 * Clears audit, dictation, and performance files while surfacing native deletion failures. Every
 * listed file is attempted, but any failed native deletion makes the result a failure, so a
 * deleted sibling never reports logs that are still on disk as cleared (#3705).
 */
@Suppress("CyclomaticComplexMethod", "ReturnCount")
suspend fun WhiteNoiseAppState.deleteAuditLogs(): Boolean {
    var engineFailure: Throwable? = null
    var cacheFailure: Throwable? = null
    val preparedDeleted =
        runCatchingCancellable {
            withContext(Dispatchers.IO) { clearAuditAndDictationLogShares(appContext.cacheDir) }
        }.onFailure { cacheFailure = it }.getOrDefault(false)
    val files =
        runCatching { marmotIo { auditLogFiles() } }
            .getOrElse {
                if (it is CancellationException) throw it
                presentFailure(R.string.toast_couldnt_delete_audit_logs, "AUDIT_LOG_DELETE", it)
                return false
            }
    if (files.isEmpty()) {
        cacheFailure?.let {
            presentFailure(R.string.toast_couldnt_delete_audit_logs, "AUDIT_LOG_DELETE", it)
            return false
        }
        if (preparedDeleted) {
            presentTransient(R.string.toast_audit_logs_deleted)
            return true
        }
        presentTransient(R.string.toast_no_audit_logs_to_delete)
        return false
    }
    for (file in files) {
        val outcome =
            runCatchingCancellable { marmotIo { deleteAuditLogFile(file.path) } }
                .onFailure {
                    if (engineFailure == null) engineFailure = it
                    appStateDebug { "deleteAuditLogFile failed: ${it.readableMessage()}" }
                }.getOrNull() ?: continue
        appStateDebug { "audit log deleted still_recording=${outcome.stillRecording}" }
    }
    cacheFailure?.let {
        presentFailure(R.string.toast_couldnt_delete_audit_logs, "AUDIT_LOG_DELETE", it)
        return false
    }
    // Files were listed, so every one was deleted exactly when no native deletion failed.
    val failure = engineFailure
    if (failure == null) {
        presentTransient(R.string.toast_audit_logs_deleted)
    } else {
        presentFailure(R.string.toast_couldnt_delete_audit_logs, "AUDIT_LOG_DELETE", failure)
    }
    return failure == null
}
