package dev.ipf.whitenoise.android.ui.settings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

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
        if (attempt == revision && current()) {
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
    }

    fun retry(): Long? {
        if (!failed || busy) return null
        failed = false
        busy = true
        return revision
    }
}

/** Retains presentation ownership across Activities and replaces it at a native runtime boundary. */
internal class StartupDiagnosticsCloseOwner {
    private var generation: Int? = null
    private var owner = StartupDiagnosticsCloseState()

    operator fun invoke(runtimeGeneration: Int): StartupDiagnosticsCloseState {
        if (generation != runtimeGeneration) {
            generation = runtimeGeneration
            owner = StartupDiagnosticsCloseState()
        }
        return owner
    }
}
