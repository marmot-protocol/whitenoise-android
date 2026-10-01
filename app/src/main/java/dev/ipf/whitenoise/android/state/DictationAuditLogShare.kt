package dev.ipf.whitenoise.android.state

import android.content.Context
import dev.ipf.marmotkit.MarmotInterface
import dev.ipf.whitenoise.android.audio.DictationDiagnostics
import dev.ipf.whitenoise.android.diagnostics.PerformanceDiagnostics
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.io.File

/** Runs on the export IO dispatcher; a manifest alone is useful when records were dropped. */
internal fun prepareAuditAndDictationLogArchive(
    context: Context,
    sourcePaths: List<String>,
    performanceLogBytes: ByteArray? = PerformanceDiagnostics.storedLogForExport(),
): File? {
    val entries = DictationDiagnostics.snapshot()
    val dropped =
        entries["dictation-manifest.json"]?.let {
            val manifest = JSONObject(it.decodeToString())
            if (manifest.optString("coverage") == "snapshot_unavailable") {
                1L
            } else {
                manifest.optLong("dropped_in_process") + manifest.optLong("invalid_files_in_process")
            }
        } ?: 0L
    val hasDictationRecords = entries.keys.any { it.endsWith(".jsonl") } || dropped != 0L
    if (sourcePaths.isEmpty() && !hasDictationRecords && performanceLogBytes == null) return null
    return prepareAuditLogArchive(
        context.cacheDir,
        File(context.filesDir, "Marmot"),
        sourcePaths,
        entries,
        performanceLogBytes,
    )
}

/** Attempts every app-owned diagnostic store; one deletion cannot hide another store's failure. */
internal fun clearAuditAndDictationLogShares(cacheDir: File): Boolean {
    val dictation = runCatchingCancellable { DictationDiagnostics.clear() }
    val performance = runCatchingCancellable { PerformanceDiagnostics.clearStoredLog() }
    val prepared = runCatchingCancellable { clearPreparedAuditLogShares(cacheDir) }
    val failure = dictation.exceptionOrNull() ?: performance.exceptionOrNull() ?: prepared.exceptionOrNull()
    if (failure != null) {
        performance.exceptionOrNull()?.takeIf { it !== failure }?.let(failure::addSuppressed)
        prepared.exceptionOrNull()?.takeIf { it !== failure }?.let(failure::addSuppressed)
        throw failure
    }
    return dictation.getOrThrow() || performance.getOrThrow() || prepared.getOrThrow()
}

/** Installs independent destinations before startup; collection stops before any native consent update. */
internal suspend fun configureAndroidPrivacyRuntime(
    runtime: MarmotInterface,
    consent: AuditUploadConsent,
    mutex: Mutex,
    configureTelemetry: suspend () -> Unit,
) {
    configureTelemetry()
    mutex.withLock {
        consent.prepare(runtime)
    }
    runtime.setProductAnalyticsRuntimeConfig(androidProductAnalyticsRuntimeConfig())
}
