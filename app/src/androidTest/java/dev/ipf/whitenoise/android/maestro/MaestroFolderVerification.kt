package dev.ipf.whitenoise.android.maestro

import dev.ipf.whitenoise.android.state.WhiteNoiseAppState

/** Confirm folder mutation in the account's normal preference store, separately from visible labels. */
internal fun verifyMaestroFolder(
    state: WhiteNoiseAppState,
    postcondition: String,
) {
    val folders = state.chatFolderPreferences.foldersFor(checkNotNull(state.activeAccountRef))
    val custom = folders.filter { it.systemKind == null }
    if (postcondition == "folder-saved") {
        check(custom.size == 1 && custom.single().name == "Maestro saved folder")
    } else {
        check(custom.isEmpty()) { "Canceled or deleted custom folder remains in the store" }
    }
}
