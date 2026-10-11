@file:Suppress("TooGenericExceptionCaught") // Platform export failures become fixed safe UI outcomes.

package dev.ipf.whitenoise.android.diagnostics

import android.content.Context
import android.content.Intent
import android.widget.Toast
import dev.ipf.whitenoise.android.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean

private const val STARTUP_SHARE_WAIT_MS = 5_000L

/** Closed outcomes from one explicit platform export request. */
internal sealed interface StartupDiagnosticExportResult {
    data class Ready(
        val intent: Intent,
    ) : StartupDiagnosticExportResult

    data object Busy : StartupDiagnosticExportResult

    data object Failed : StartupDiagnosticExportResult
}

/** Keeps one disk operation alive independently while bounding how long the screen waits for it. */
internal class StartupDiagnosticExportCoordinator(
    private val scope: CoroutineScope,
    private val waitMs: Long = STARTUP_SHARE_WAIT_MS,
) {
    private val busy = AtomicBoolean()

    /** Double taps cannot create concurrent writers; a timeout never frees a still-running writer. */
    suspend fun export(create: suspend () -> Intent): StartupDiagnosticExportResult {
        if (!busy.compareAndSet(false, true)) return StartupDiagnosticExportResult.Busy
        val export = scope.async { create() }
        export.invokeOnCompletion { busy.set(false) }
        return try {
            withTimeoutOrNull(waitMs) { StartupDiagnosticExportResult.Ready(export.await()) }
                ?: StartupDiagnosticExportResult.Failed
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Exception) {
            StartupDiagnosticExportResult.Failed
        }
    }
}

private val startupExport = StartupDiagnosticExportCoordinator(CoroutineScope(SupervisorJob() + Dispatchers.IO))

/** Opens only the system chooser after a tap; late completion cannot present through lock or navigation. */
internal suspend fun shareStartupDiagnostics(
    context: Context,
    canPresent: () -> Boolean,
) {
    val snapshot = StartupDiagnostics.snapshot() ?: return
    val result = startupExport.export { createStartupDiagnosticShareIntent(context.applicationContext, snapshot) }
    if (!canPresent()) return
    when (result) {
        is StartupDiagnosticExportResult.Ready -> {
            try {
                val chooser = Intent.createChooser(result.intent, context.getString(R.string.startup_diagnostics_share))
                context.startActivity(chooser)
            } catch (_: Exception) {
                startupShareFailure(context)
            }
        }
        StartupDiagnosticExportResult.Failed -> startupShareFailure(context)
        StartupDiagnosticExportResult.Busy -> startupShareFailure(context)
    }
}

/** Displays an actionable platform export error without exposing filesystem or exception details. */
private fun startupShareFailure(context: Context) {
    Toast.makeText(context, R.string.startup_diagnostics_share_failed, Toast.LENGTH_LONG).show()
}
