@file:Suppress("FunctionNaming")

package dev.ipf.whitenoise.android.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState

/** The actual startup sheet owner remains mounted while its close writes finish. */
@Composable
internal fun StartupUsageDiagnosticsPrompt(
    appState: WhiteNoiseAppState,
    owner: StartupDiagnosticsCloseState =
        remember(appState, appState.runtimeGeneration) { StartupDiagnosticsCloseState() },
) {
    val generation = appState.runtimeGeneration
    LaunchedEffect(
        generation,
        appState.diagnostics.snapshot,
        appState.diagnostics.failed,
        appState.auditUploadConsentRequired,
    ) {
        owner.prompt =
            owner.prompt.refreshed(
                decisionLoaded = appState.diagnostics.snapshot != null || appState.diagnostics.failed,
                choiceRequired = appState.diagnostics.requiresChoice || appState.auditUploadConsentRequired,
                readFailed = appState.diagnostics.failed,
            )
    }

    fun save(attempt: Long) {
        appState.launchMutation {
            owner.save(
                attempt,
                current = { appState.runtimeGeneration == generation },
                audit = { appState.setAuditLogsEnabled(false) },
                usage = { appState.setTelemetryEnabled(false) },
            )
        }
    }
    if (owner.prompt.visible) {
        key(owner.prompt == StartupDiagnosticsPromptState.CLOSE_ERROR) {
            UsageDiagnosticsPrompt(
                appState = appState,
                onChoiceStarted = owner::choiceStarted,
                onCloseStarted = {
                    owner.begin(appState.auditUploadConsentRequired, appState.diagnostics.requiresChoice)?.let(::save)
                },
                closeFailed = owner.failed || owner.prompt == StartupDiagnosticsPromptState.CLOSE_ERROR,
                closeBusy = owner.busy,
                onRetryClose = { owner.retry()?.let(::save) },
                onDismissFailedClose = owner::dismissFailed,
                onDone = owner::hidden,
            )
        }
    }
}
