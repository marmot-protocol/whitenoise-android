package dev.ipf.whitenoise.android.amber

import android.util.Log

/** Privacy-safe grouped signer state; deliberately carries no account, request, event, or package data. */
internal enum class AmberGroupedSessionState {
    BOOTSTRAP,
    WAITING_FOR_MERGE,
    MERGE_READY,
    ADMISSION_WAIT,
}

/** Coarse signer terminal category without signer-controlled result data. */
internal enum class AmberApprovalTerminal {
    PENDING,
    COMPLETED,
    REJECTED,
    UNAVAILABLE,
    TIMED_OUT,
}

internal data class AmberApprovalDiagnostic(
    val operationType: String,
    val sessionState: AmberGroupedSessionState,
    val launchCount: Int,
    val terminal: AmberApprovalTerminal,
)

/** Emits only bounded categorical Amber approval diagnostics. */
internal object AmberApprovalDiagnostics {
    private const val TAG = "AmberSigner"

    @Volatile
    private var testObserver: ((AmberApprovalDiagnostic) -> Unit)? = null

    fun record(
        operationType: String?,
        sessionState: AmberGroupedSessionState,
        launchCount: Int,
        terminal: AmberApprovalTerminal,
    ) {
        val safeOperation =
            SignerOp.entries
                .firstOrNull { it.intentType == operationType }
                ?.intentType
                ?: "unknown"
        val diagnostic =
            AmberApprovalDiagnostic(
                operationType = safeOperation,
                sessionState = sessionState,
                launchCount = launchCount.coerceAtLeast(0),
                terminal = terminal,
            )
        Log.d(
            TAG,
            "operation=${diagnostic.operationType} grouped_session=${diagnostic.sessionState.name.lowercase()} " +
                "launch_count=${diagnostic.launchCount} terminal=${diagnostic.terminal.name.lowercase()}",
        )
        testObserver?.invoke(diagnostic)
    }

    internal fun observeForTest(observer: ((AmberApprovalDiagnostic) -> Unit)?) {
        testObserver = observer
    }

    internal fun resetForTest() {
        testObserver = null
    }
}
