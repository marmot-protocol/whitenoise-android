package dev.ipf.whitenoise.android.maestro

import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/** Confirm the owning folder preference state on the UI dispatcher, separately from visible labels. */
internal suspend fun verifyMaestroFolder(
    state: WhiteNoiseAppState,
    postcondition: String,
) {
    require(postcondition == "folder-saved" || postcondition == "folder-absent")
    val owner = checkNotNull(state.activeAccountRef)
    withTimeout(15_000L) {
        while (true) {
            val matches =
                withContext(Dispatchers.Main.immediate) {
                    val custom = state.chatFolderPreferences.foldersFor(owner).filter { it.systemKind == null }
                    if (postcondition == "folder-saved") {
                        custom.size == 1 && custom.single().name == "Maestro saved folder"
                    } else {
                        custom.isEmpty()
                    }
                }
            if (matches) return@withTimeout
            delay(100L)
        }
    }
}
