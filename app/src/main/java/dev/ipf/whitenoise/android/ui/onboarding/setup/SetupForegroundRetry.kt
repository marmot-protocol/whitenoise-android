package dev.ipf.whitenoise.android.ui.onboarding.setup

/** Delivers one wake per observed background transition of this retained controller. */
internal class SetupForegroundRetry(
    private val retry: () -> Unit,
) {
    private var backgrounded = false

    /** Records a warm return even if the Activity is recreated in the same process. */
    fun onBackgrounded() {
        backgrounded = true
    }

    /** Delivers a wake after an observed stop. A newly mounted controller starts without one. */
    fun onForegrounded() {
        if (!backgrounded) return
        backgrounded = false
        retry()
    }
}
