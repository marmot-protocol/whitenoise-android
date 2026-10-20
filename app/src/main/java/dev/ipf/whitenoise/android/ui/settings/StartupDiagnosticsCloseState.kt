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

    /** An explicit choice supersedes any close in flight and pins the sheet open as a choice. */
    fun choiceStarted() {
        revision += 1
        closing = false
        failed = false
        busy = false
        prompt = StartupDiagnosticsPromptState.CHOICE
    }

    /** Starts one close transaction for the declines still pending, or returns null while one runs or failed. */
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

    /** Records that the hide animation finished, publishing the close's result if it has settled. */
    fun hidden() {
        if (!closing) return
        hideFinished = true
        prompt = if (failed) StartupDiagnosticsPromptState.CLOSE_ERROR else StartupDiagnosticsPromptState.CLOSED
    }

    /** Writes the pending audit decline before the usage decline, ignoring a superseded attempt. */
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

    /**
     * Leaves a failed close unsaved for this runtime once no retry is running.
     *
     * Nothing is written and nothing is granted: MDK still reports the unsaved usage receipt as
     * requiring a choice, and an unsaved audit acknowledgement keeps uploads off, so collection
     * stays off and the next launch asks again. A newer close/choice/retry ignores this.
     */
    fun dismissFailed() {
        if (!failed || busy) return
        revision += 1
        closing = false
        failed = false
        prompt = StartupDiagnosticsPromptState.CLOSED
    }

    /** Re-arms the failed close's pending writes for one more attempt, or returns null while busy. */
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

    /** Returns the close state for [runtimeGeneration], replacing it when the native runtime changes. */
    operator fun invoke(runtimeGeneration: Int): StartupDiagnosticsCloseState {
        if (generation != runtimeGeneration) {
            generation = runtimeGeneration
            owner = StartupDiagnosticsCloseState()
        }
        return owner
    }
}
