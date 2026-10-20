package dev.ipf.whitenoise.android.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState

/** Runtime-local ownership of a disappearing sheet, not a persisted consent decision. */
internal class StartupDiagnosticsCloseState {
    var prompt by mutableStateOf(StartupDiagnosticsPromptState.AWAITING_RECEIPT)
    var failed by mutableStateOf(false)
        private set
    var busy by mutableStateOf(false)
        private set
    private var revision = 0L
    private var closing = false
    private var hideFinished = false
    private var pendingAudit = false
    private var pendingUsage = false

    fun choiceStarted() {
        revision += 1
        closing = false
        failed = false
        busy = false
        prompt = StartupDiagnosticsPromptState.CHOICE
    }

    fun begin(
        auditRequired: Boolean,
        usageRequired: Boolean,
    ): Long? {
        if (busy || failed) return null
        revision += 1
        closing = true
        hideFinished = false
        pendingAudit = auditRequired
        pendingUsage = usageRequired
        busy = true
        return revision
    }

    fun hidden() {
        if (!closing) return
        hideFinished = true
        prompt = if (failed) StartupDiagnosticsPromptState.CLOSE_ERROR else StartupDiagnosticsPromptState.CLOSED
    }

    suspend fun save(
        attempt: Long,
        current: () -> Boolean,
        audit: suspend () -> Boolean,
        usage: suspend () -> Boolean,
    ) {
        if (attempt != revision || !current()) return
        if (pendingAudit) {
            val saved = audit()
            if (attempt != revision || !current()) return
            pendingAudit = !saved
        }
        if (pendingUsage && !pendingAudit) {
            val saved = usage()
            if (attempt != revision || !current()) return
            pendingUsage = !saved
        }
        failed = pendingAudit || pendingUsage
        busy = false
        if (hideFinished) {
            prompt = if (failed) StartupDiagnosticsPromptState.CLOSE_ERROR else StartupDiagnosticsPromptState.CLOSED
        }
    }

    fun retry(): Long? {
        if (!failed || busy) return null
        failed = false
        busy = true
        return revision
    }
}

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
                onDone = owner::hidden,
            )
        }
    }
}
