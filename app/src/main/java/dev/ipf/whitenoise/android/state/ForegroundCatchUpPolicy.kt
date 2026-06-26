package dev.ipf.whitenoise.android.state

/**
 * Gates best-effort [WhiteNoiseAppState.catchUpAfterForegroundActivation] so a
 * foreground resume does not fan out overlapping catch-up work.
 */
internal object ForegroundCatchUpPolicy {
    fun shouldCatchUp(
        appPhase: AppPhase,
        isCatchUpRunning: Boolean,
        appInForeground: Boolean,
    ): Boolean =
        appPhase == AppPhase.Ready &&
            !isCatchUpRunning &&
            appInForeground
}
