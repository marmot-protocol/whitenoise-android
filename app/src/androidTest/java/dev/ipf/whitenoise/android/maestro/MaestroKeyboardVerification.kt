package dev.ipf.whitenoise.android.maestro

import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout

/** A dismissed reactor popup must retain its real native draft and leave the shipping activity IME visible. */
internal suspend fun verifyMaestroReactionKeyboard(
    state: WhiteNoiseAppState,
    group: String,
    activity: MaestroActivityOwner,
    postcondition: String?,
) {
    if (postcondition != "reactions-draft-retained") return
    verifyMaestroComposer(state, group, "composer-automatic")
    withTimeout(10_000L) {
        while (true) {
            var keyboardVisible = false
            activity.onActivity {
                val insets = ViewCompat.getRootWindowInsets(it.window.decorView)
                keyboardVisible = insets?.isVisible(WindowInsetsCompat.Type.ime()) == true
            }
            if (keyboardVisible) return@withTimeout
            delay(100L)
        }
    }
}
