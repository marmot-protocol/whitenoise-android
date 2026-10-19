package dev.ipf.whitenoise.android.ui.settings

/** Why startup owns a diagnostics sheet; only a read-error-only sheet can resolve itself. */
internal enum class StartupDiagnosticsPromptState {
    AWAITING_RECEIPT,
    CLOSED,
    CHOICE,
    READ_ERROR,
    ;

    /** Whether startup currently owns a sheet. */
    val visible: Boolean get() = this == CHOICE || this == READ_ERROR

    /** A real renewal stays open for both choices even after the first choice is persisted. */
    fun refreshed(
        decisionLoaded: Boolean,
        choiceRequired: Boolean,
        readFailed: Boolean,
    ): StartupDiagnosticsPromptState =
        when (this) {
            AWAITING_RECEIPT ->
                when {
                    !decisionLoaded -> this
                    choiceRequired -> CHOICE
                    readFailed -> READ_ERROR
                    else -> CLOSED
                }

            READ_ERROR ->
                when {
                    choiceRequired -> CHOICE
                    decisionLoaded && !readFailed -> CLOSED
                    else -> this
                }

            CHOICE, CLOSED -> this
        }
}
