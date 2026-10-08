package dev.ipf.whitenoise.android.maestro

import dev.ipf.whitenoise.android.state.RetainedComposerExpansionMode
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/** A real resize must change the owning UI geometry while preserving the genuine native draft. */
internal suspend fun verifyMaestroComposer(
    state: WhiteNoiseAppState,
    group: String,
    postcondition: String,
) {
    val owner = checkNotNull(state.activeAccountRef)
    val expected = if (postcondition == "composer-expanded") RetainedComposerExpansionMode.FullScreen else null
    withTimeout(15_000L) {
        while (true) {
            val actual =
                withContext(Dispatchers.Main.immediate) {
                    state.composerExpansionStateRetention.preferenceFor(owner, group)?.mode
                }
            if (actual == expected && state.draftStore.get(owner, group) == "Maestro expanded draft") {
                return@withTimeout
            }
            delay(100L)
        }
    }
}
