package dev.ipf.whitenoise.android.state

import android.content.Context
import dev.ipf.marmotkit.MarmotInterface
import dev.ipf.whitenoise.android.audio.DictationDiagnostics
import dev.ipf.whitenoise.android.diagnostics.DmCreationDiagnostics
import dev.ipf.whitenoise.android.diagnostics.NATIVE_PERFORMANCE_SNAPSHOT_FILE_NAME
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
    nativePerformanceLogBytes: ByteArray? = null,
): File? {
    val marmotRoot = File(context.filesDir, "Marmot")
    val localSendTraceEntries = nativeLocalSendTraceExport(marmotRoot)
    val dmEntries = DmCreationDiagnostics.snapshot()
    val hasDmRecords = dmEntries.keys.any { it.endsWith(".jsonl") } || diagnosticDroppedCount(dmEntries) > 0
    val diagnosticEntries =
        DictationDiagnostics.snapshot() +
            (if (hasDmRecords) dmEntries else emptyMap()) +
            (nativePerformanceLogBytes?.let { mapOf(NATIVE_PERFORMANCE_SNAPSHOT_FILE_NAME to it) } ?: emptyMap())
    val dropped = diagnosticDroppedCount(diagnosticEntries)
    val hasDictationRecords = diagnosticEntries.keys.any { it.endsWith(".jsonl") } || dropped != 0L
    val hasPerformanceRecords = performanceLogBytes != null || nativePerformanceLogBytes != null
    if (sourcePaths.isEmpty() && !hasDictationRecords && !hasPerformanceRecords && localSendTraceEntries.isEmpty()) {
        return null
    }
    return prepareAuditLogArchive(
        context.cacheDir,
        marmotRoot,
        sourcePaths,
        diagnosticEntries + localSendTraceEntries,
        performanceLogBytes,
    )
}

/** Attempts every app-owned diagnostic store; one deletion cannot hide another store's failure. */
internal fun clearAuditAndDictationLogShares(
    cacheDir: File,
    marmotRoot: File? = null,
): Boolean {
    val dictation = runCatchingCancellable { DictationDiagnostics.clear() }
    val dmCreation = runCatchingCancellable { DmCreationDiagnostics.clear() }
    val performance = runCatchingCancellable { PerformanceDiagnostics.clearStoredLog() }
    val localSendTrace = runCatchingCancellable { marmotRoot?.let(::clearNativeLocalSendTrace) ?: false }
    val prepared = runCatchingCancellable { clearPreparedAuditLogShares(cacheDir) }
    val results = listOf(dictation, dmCreation, performance, localSendTrace, prepared)
    val failures = results.mapNotNull { it.exceptionOrNull() }
    failures.firstOrNull()?.let { failure ->
        failures.drop(1).filter { it !== failure }.forEach(failure::addSuppressed)
        throw failure
    }
    return results.any { it.getOrThrow() }
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

/** Reads only closed export manifests; JSONL records never masquerade as metadata or extend retention. */
private fun diagnosticDroppedCount(entries: Map<String, ByteArray>): Long =
    entries.filterKeys { it.endsWith("-manifest.json") }.values.sumOf {
        val manifest = JSONObject(it.decodeToString())
        if (manifest.optString("coverage") == "snapshot_unavailable") {
            1L
        } else {
            manifest.optLong("dropped_in_process") + manifest.optLong("invalid_files_in_process")
        }
    }
