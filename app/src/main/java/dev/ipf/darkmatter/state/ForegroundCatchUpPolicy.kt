package dev.ipf.darkmatter.state

/**
 * Gates best-effort [DarkMatterAppState.catchUpAfterForegroundActivation] so a
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
