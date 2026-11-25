package dev.ipf.whitenoise.android.ui.onboarding.setup

/** Delivers one wake per background transition, including returns after Activity recreation. */
internal class SetupForegroundRetry(
    private val retry: () -> Unit,
) {
    private var backgrounded = false

    fun onBackgrounded() {
        backgrounded = true
    }

    fun onForegrounded() {
        if (!backgrounded) return
        backgrounded = false
        retry()
    }
}
