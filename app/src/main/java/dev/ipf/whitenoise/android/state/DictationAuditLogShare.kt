package dev.ipf.whitenoise.android.state

import android.content.Context
import dev.ipf.marmotkit.MarmotInterface
import dev.ipf.whitenoise.android.audio.DictationDiagnostics
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.io.File

/** Runs on the export IO dispatcher; a manifest alone is useful when records were dropped. */
internal fun prepareAuditAndDictationLogArchive(
    context: Context,
    sourcePaths: List<String>,
): File? {
    val entries = DictationDiagnostics.snapshot()
    val dropped =
        entries["dictation-manifest.json"]?.let {
            val manifest = JSONObject(it.decodeToString())
            if (manifest.optString("coverage") == "snapshot_unavailable") 1L else manifest.optLong("dropped_in_process")
        } ?: 0L
    if (sourcePaths.isEmpty() && entries.keys.none { it.endsWith(".jsonl") } && dropped == 0L) return null
    return prepareAuditLogArchive(context.cacheDir, File(context.filesDir, "Marmot"), sourcePaths, entries)
}

/** Both local stores are attempted; their deletion cannot hide a failure in either store. */
internal fun clearAuditAndDictationLogShares(cacheDir: File): Boolean {
    val dictation = runCatchingCancellable { DictationDiagnostics.clear() }
    val prepared = runCatchingCancellable { clearPreparedAuditLogShares(cacheDir) }
    val failure = dictation.exceptionOrNull() ?: prepared.exceptionOrNull()
    if (failure != null) {
        prepared.exceptionOrNull()?.takeIf { it !== failure }?.let(failure::addSuppressed)
        throw failure
    }
    return dictation.getOrThrow() || prepared.getOrThrow()
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
        DictationDiagnostics.setEnabled(false)
        consent.prepare(runtime)
        DictationDiagnostics.setEnabled(runtime.auditLogSettings().enabled && consent.granted)
    }
    runtime.setProductAnalyticsRuntimeConfig(androidProductAnalyticsRuntimeConfig())
}
