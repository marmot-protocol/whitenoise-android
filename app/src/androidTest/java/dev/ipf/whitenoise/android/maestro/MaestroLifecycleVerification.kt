package dev.ipf.whitenoise.android.maestro

import dev.ipf.whitenoise.android.MainActivity
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState

/** Locale recreation must replace the real activity while retaining its owning native draft and geometry. */
internal suspend fun verifyMaestroComposerRecreated(
    state: WhiteNoiseAppState,
    group: String,
    activity: MaestroActivityOwner,
    originalActivity: MainActivity?,
    postcondition: String?,
): Boolean {
    if (postcondition != "composer-recreated") return false
    val original = checkNotNull(originalActivity)
    var recreated = false
    activity.onActivity { recreated = it !== original }
    check(recreated) { "Locale change did not recreate the shipping activity" }
    verifyMaestroComposer(state, group, "composer-expanded")
    verifyMaestroPreferences(state, "language-system")
    return true
}
